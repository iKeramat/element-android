/*
 * Copyright (c) 2022 New Vector Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package im.vector.app.features.settings.devices.v2

import com.airbnb.mvrx.MavericksViewModelFactory
import com.airbnb.mvrx.Success
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import im.vector.app.R
import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.core.di.MavericksAssistedViewModelFactory
import im.vector.app.core.di.hiltMavericksViewModelFactory
import im.vector.app.core.resources.StringProvider
import im.vector.app.features.auth.PendingAuthHandler
import im.vector.app.features.settings.devices.v2.filter.DeviceManagerFilterType
import im.vector.app.features.settings.devices.v2.signout.InterceptSignoutFlowResponseUseCase
import im.vector.app.features.settings.devices.v2.signout.SignoutSessionResult
import im.vector.app.features.settings.devices.v2.signout.SignoutSessionsUseCase
import im.vector.app.features.settings.devices.v2.verification.CheckIfCurrentSessionCanBeVerifiedUseCase
import im.vector.app.features.settings.devices.v2.verification.GetCurrentSessionCrossSigningInfoUseCase
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.matrix.android.sdk.api.auth.UIABaseAuth
import org.matrix.android.sdk.api.auth.UserInteractiveAuthInterceptor
import org.matrix.android.sdk.api.auth.registration.RegistrationFlowResponse
import org.matrix.android.sdk.api.extensions.orFalse
import org.matrix.android.sdk.api.failure.Failure
import org.matrix.android.sdk.api.session.uia.DefaultBaseAuth
import timber.log.Timber
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.Continuation

class DevicesViewModel @AssistedInject constructor(
        @Assisted initialState: DevicesViewState,
        activeSessionHolder: ActiveSessionHolder,
        private val stringProvider: StringProvider,
        private val getCurrentSessionCrossSigningInfoUseCase: GetCurrentSessionCrossSigningInfoUseCase,
        private val getDeviceFullInfoListUseCase: GetDeviceFullInfoListUseCase,
        private val refreshDevicesOnCryptoDevicesChangeUseCase: RefreshDevicesOnCryptoDevicesChangeUseCase,
        private val checkIfCurrentSessionCanBeVerifiedUseCase: CheckIfCurrentSessionCanBeVerifiedUseCase,
        private val signoutSessionsUseCase: SignoutSessionsUseCase,
        private val interceptSignoutFlowResponseUseCase: InterceptSignoutFlowResponseUseCase,
        private val pendingAuthHandler: PendingAuthHandler,
        refreshDevicesUseCase: RefreshDevicesUseCase,
) : VectorSessionsListViewModel<DevicesViewState, DevicesAction, DevicesViewEvent>(initialState, activeSessionHolder, refreshDevicesUseCase) {

    @AssistedFactory
    interface Factory : MavericksAssistedViewModelFactory<DevicesViewModel, DevicesViewState> {
        override fun create(initialState: DevicesViewState): DevicesViewModel
    }

    companion object : MavericksViewModelFactory<DevicesViewModel, DevicesViewState> by hiltMavericksViewModelFactory()

    init {
        observeCurrentSessionCrossSigningInfo()
        observeDevices()
        refreshDevicesOnCryptoDevicesChange()
        refreshDeviceList()
    }

    private fun observeCurrentSessionCrossSigningInfo() {
        getCurrentSessionCrossSigningInfoUseCase.execute()
                .onEach { crossSigningInfo ->
                    setState {
                        copy(currentSessionCrossSigningInfo = crossSigningInfo)
                    }
                }
                .launchIn(viewModelScope)
    }

    private fun observeDevices() {
        getDeviceFullInfoListUseCase.execute(
                filterType = DeviceManagerFilterType.ALL_SESSIONS,
                excludeCurrentDevice = false
        )
                .execute { async ->
                    if (async is Success) {
                        val deviceFullInfoList = async.invoke()
                        val unverifiedSessionsCount = deviceFullInfoList.count { !it.cryptoDeviceInfo?.trustLevel?.isCrossSigningVerified().orFalse() }
                        val inactiveSessionsCount = deviceFullInfoList.count { it.isInactive }
                        copy(
                                devices = async,
                                unverifiedSessionsCount = unverifiedSessionsCount,
                                inactiveSessionsCount = inactiveSessionsCount,
                        )
                    } else {
                        copy(
                                devices = async
                        )
                    }
                }
    }

    private fun refreshDevicesOnCryptoDevicesChange() {
        viewModelScope.launch {
            refreshDevicesOnCryptoDevicesChangeUseCase.execute()
        }
    }

    override fun handle(action: DevicesAction) {
        when (action) {
            is DevicesAction.PasswordAuthDone -> handlePasswordAuthDone(action)
            DevicesAction.ReAuthCancelled -> handleReAuthCancelled()
            DevicesAction.SsoAuthDone -> handleSsoAuthDone()
            is DevicesAction.VerifyCurrentSession -> handleVerifyCurrentSessionAction()
            is DevicesAction.MarkAsManuallyVerified -> handleMarkAsManuallyVerifiedAction()
            DevicesAction.MultiSignoutOtherSessions -> handleMultiSignoutOtherSessions()
        }
    }

    private fun handleVerifyCurrentSessionAction() {
        viewModelScope.launch {
            val currentSessionCanBeVerified = checkIfCurrentSessionCanBeVerifiedUseCase.execute()
            if (currentSessionCanBeVerified) {
                _viewEvents.post(DevicesViewEvent.SelfVerification)
            } else {
                _viewEvents.post(DevicesViewEvent.PromptResetSecrets)
            }
        }
    }

    private fun handleMarkAsManuallyVerifiedAction() {
        // TODO implement when needed
    }

    private fun handleMultiSignoutOtherSessions() = withState { state ->
        viewModelScope.launch {
            setLoading(true)
            val deviceIds = getDeviceIdsOfOtherSessions(state)
            if (deviceIds.isEmpty()) {
                return@launch
            }
            val signoutResult = signout(deviceIds)
            setLoading(false)

            if (signoutResult.isSuccess) {
                onSignoutSuccess()
            } else {
                when (val failure = signoutResult.exceptionOrNull()) {
                    null -> onSignoutSuccess()
                    else -> onSignoutFailure(failure)
                }
            }
        }
    }

    private fun getDeviceIdsOfOtherSessions(state: DevicesViewState): List<String> {
        val currentDeviceId = state.currentSessionCrossSigningInfo.deviceId
        return state.devices()
                ?.mapNotNull { fullInfo -> fullInfo.deviceInfo.deviceId.takeUnless { it == currentDeviceId } }
                .orEmpty()
    }

    private suspend fun signout(deviceIds: List<String>) = signoutSessionsUseCase.execute(deviceIds, object : UserInteractiveAuthInterceptor {
        override fun performStage(flowResponse: RegistrationFlowResponse, errCode: String?, promise: Continuation<UIABaseAuth>) {
            when (val result = interceptSignoutFlowResponseUseCase.execute(flowResponse, errCode, promise)) {
                is SignoutSessionResult.ReAuthNeeded -> onReAuthNeeded(result)
                is SignoutSessionResult.Completed -> Unit
            }
        }
    })

    private fun onReAuthNeeded(reAuthNeeded: SignoutSessionResult.ReAuthNeeded) {
        Timber.d("onReAuthNeeded")
        pendingAuthHandler.pendingAuth = DefaultBaseAuth(session = reAuthNeeded.flowResponse.session)
        pendingAuthHandler.uiaContinuation = reAuthNeeded.uiaContinuation
        _viewEvents.post(DevicesViewEvent.RequestReAuth(reAuthNeeded.flowResponse, reAuthNeeded.errCode))
    }

    private fun setLoading(isLoading: Boolean) {
        setState { copy(isLoading = isLoading) }
    }

    private fun onSignoutSuccess() {
        Timber.d("signout success")
        refreshDeviceList()
        _viewEvents.post(DevicesViewEvent.SignoutSuccess)
    }

    private fun onSignoutFailure(failure: Throwable) {
        Timber.e("signout failure", failure)
        val failureMessage = if (failure is Failure.OtherServerError && failure.httpCode == HttpsURLConnection.HTTP_UNAUTHORIZED) {
            stringProvider.getString(R.string.authentication_error)
        } else {
            stringProvider.getString(R.string.matrix_error)
        }
        _viewEvents.post(DevicesViewEvent.SignoutError(Exception(failureMessage)))
    }

    private fun handleSsoAuthDone() {
        pendingAuthHandler.ssoAuthDone()
    }

    private fun handlePasswordAuthDone(action: DevicesAction.PasswordAuthDone) {
        pendingAuthHandler.passwordAuthDone(action.password)
    }

    private fun handleReAuthCancelled() {
        pendingAuthHandler.reAuthCancelled()
    }
}
