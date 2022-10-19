/*
 * Copyright 2020 The Matrix.org Foundation C.I.C.
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

package org.matrix.android.sdk.internal.database.model

import org.matrix.android.sdk.internal.database.model.livelocation.LiveLocationShareAggregatedSummaryEntity
import org.matrix.android.sdk.internal.database.model.presence.UserPresenceEntity
import org.matrix.android.sdk.internal.database.model.threads.ThreadSummaryEntity

/**
 * Realm module for Session.
 * Please respect the order (alphabetically) to avoid mistake during merge conflict, and avoid duplication.
 */
internal val SESSION_REALM_SCHEMA = setOf(
        BreadcrumbsEntity::class,
        ChunkEntity::class,
        CurrentStateEventEntity::class,
        DraftEntity::class,
        EditAggregatedSummaryEntity::class,
        EditionOfEvent::class,
        EventAnnotationsSummaryEntity::class,
        EventEntity::class,
        EventInsertEntity::class,
        FilterEntity::class,
        HomeServerCapabilitiesEntity::class,
        IgnoredUserEntity::class,
        LiveLocationShareAggregatedSummaryEntity::class,
        LocalRoomSummaryEntity::class,
        PendingThreePidEntity::class,
        PollResponseAggregatedSummaryEntity::class,
        PreviewUrlCacheEntity::class,
        PushConditionEntity::class,
        PushRuleEntity::class,
        PushRulesEntity::class,
        PusherDataEntity::class,
        PusherEntity::class,
        ReactionAggregatedSummaryEntity::class,
        ReadMarkerEntity::class,
        ReadReceiptEntity::class,
        ReadReceiptsSummaryEntity::class,
        ReferencesAggregatedSummaryEntity::class,
        RoomAccountDataEntity::class,
        RoomEntity::class,
        RoomMemberSummaryEntity::class,
        RoomSummaryEntity::class,
        RoomTagEntity::class,
        ScalarTokenEntity::class,
        SpaceChildSummaryEntity::class,
        SpaceParentSummaryEntity::class,
        SyncEntity::class,
        ThreadSummaryEntity::class,
        TimelineEventEntity::class,
        UserAccountDataEntity::class,
        UserDraftsEntity::class,
        UserEntity::class,
        UserPresenceEntity::class,
        UserThreePidEntity::class,
        WellknownIntegrationManagerConfigEntity::class,
)
