package im.vector.app.core.utils

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewGroup
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.children
import androidx.core.view.isVisible
import androidx.customview.widget.ViewDragHelper
import com.google.android.material.appbar.AppBarLayout
import java.lang.ref.WeakReference
import kotlin.math.max
import kotlin.math.min

class ExpandingBottomSheetBehavior<V : View> : CoordinatorLayout.Behavior<V> {

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun <V : View> from(view: V): ExpandingBottomSheetBehavior<V>? {
            val params = view.layoutParams as? CoordinatorLayout.LayoutParams ?: return null
            return params.behavior as? ExpandingBottomSheetBehavior<V>
        }
    }

    enum class State(val value: Int) {
        Collapsed(0),
        Dragging(1),
        Settling(2),
        Expanded(3)
    }

    var state: State = State.Collapsed
        private set
    var isDraggable = true
    private var ignoreEvents = false
    private var touchingScrollingChild = false

    private var lastY: Int = -1
    private var collapsedOffset = -1
    private var expandedOffset = -1
    private var parentHeight = -1

    private var activePointerId = -1

    private var lastNestedScrollDy = -1
    private var isNestedScrolled = false

    private var viewRef: WeakReference<V>? = null
    private var nestedScrollingChildRef: WeakReference<View>? = null
    private var velocityTracker: VelocityTracker? = null

    private var dragHelper: ViewDragHelper? = null
    private var scrimView: View? = null

    private val stateSettlingTracker = StateSettlingTracker()

    var callback: Callback? = null
        set(value) {
            field = value
            value?.onStateChanged(state)
        }
    var topOffset = 0
        set(value) {
            field = value
            expandedOffset = -1
        }
    var drawBelowAppBar = false
        set(value) {
            field = value
            expandedOffset = -1
        }
    var useScrimView = false
    var offsetContentViewBottom = false
        set(value) {
            field = value
            needsContentViewOffsetUpdate = true
        }
    private var needsContentViewOffsetUpdate = true

    var bottomSheetContentId: Int? = null

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
    constructor() : super()

    override fun onLayoutChild(parent: CoordinatorLayout, child: V, layoutDirection: Int): Boolean {
        parentHeight = parent.height

        if (viewRef == null) {
            viewRef = WeakReference(child)
            setWindowInsetsListener(child)
            child.isClickable = true
        }

        ensureViewDragHelper(parent)

        val savedTop = child.top

        parent.onLayoutChild(child, layoutDirection)

        if (state != State.Dragging && state != State.Settling) {
            calculateCollapsedOffset(child, parent.width)
        }
        calculateExpandedOffset(parent)

        if (needsContentViewOffsetUpdate) {
            needsContentViewOffsetUpdate = false

            val appBar = findAppBarLayout(parent)
            val contentView = parent.children.find { it !== appBar && it !== child && it !== scrimView }
            val offset = if (offsetContentViewBottom) parentHeight - collapsedOffset else 0
            if (contentView != null) {
                val params = contentView.layoutParams as CoordinatorLayout.LayoutParams
                params.bottomMargin = offset
                contentView.layoutParams = params
            }
        }

        if (useScrimView && scrimView == null) {
            val scrimView = View(parent.context)
            scrimView.setBackgroundColor(0x60000000)
            scrimView.isVisible = false
            val params = CoordinatorLayout.LayoutParams(
                    CoordinatorLayout.LayoutParams.MATCH_PARENT,
                    CoordinatorLayout.LayoutParams.MATCH_PARENT
            )
            scrimView.layoutParams = params
            val currentIndex = parent.children.indexOf(child)
            parent.addView(scrimView, currentIndex)
            this.scrimView = scrimView
        } else if (!useScrimView && scrimView != null) {
            parent.removeView(scrimView)
            scrimView = null
        }

        when (state) {
            State.Collapsed -> {
                println("CollapsedOffsetTopAndBottom | Offset: $collapsedOffset")
                val params = child.layoutParams
                params.height = parentHeight - collapsedOffset
                child.layoutParams = params
                ViewCompat.offsetTopAndBottom(child, collapsedOffset - insetTop)
            }
            State.Dragging, State.Settling -> {
                val newOffset = savedTop - child.top
                println("DraggingSettlingOffsetTopAndBottom | Offset: $newOffset")
                val params = child.layoutParams
                params.height = parentHeight - savedTop
                child.layoutParams = params
                ViewCompat.offsetTopAndBottom(child, newOffset)
            }
            State.Expanded -> {
                val params = child.layoutParams
                params.height = parentHeight - expandedOffset - insetTop
                child.layoutParams = params
                ViewCompat.offsetTopAndBottom(child, expandedOffset)
            }
        }

        nestedScrollingChildRef = findScrollingChild(child)?.let { WeakReference(it) }

        return true
    }

    private fun findScrollingChild(view: View): View? {
        return when {
            !view.isVisible -> null
            ViewCompat.isNestedScrollingEnabled(view) -> view
            view is ViewGroup -> {
                view.children.firstNotNullOfOrNull { findScrollingChild(it) }
            }
            else -> null
        }
    }

    override fun onInterceptTouchEvent(
            parent: CoordinatorLayout,
            child: V,
            ev: MotionEvent
    ): Boolean {
        if (bottomSheetContentId != null && child.id != bottomSheetContentId) {
            return true
        }
        val action = ev.actionMasked

        if (action == MotionEvent.ACTION_DOWN) {
            resetTouchEventTracking()
        }
        if (velocityTracker == null) {
            velocityTracker = VelocityTracker.obtain()
        }
        velocityTracker?.addMovement(ev)

        when (action) {
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                touchingScrollingChild = false
                activePointerId = MotionEvent.INVALID_POINTER_ID
                if (ignoreEvents) {
                    ignoreEvents = false
                    return false
                }
            }
            MotionEvent.ACTION_DOWN -> {
                val x = ev.x.toInt()
                lastY = ev.y.toInt()

                // Only intercept nested scrolling events here if the view not being moved by the
                // ViewDragHelper.
                val scroll = nestedScrollingChildRef?.get()
                if (state != State.Settling) {
                    if (scroll != null && parent.isPointInChildBounds(scroll, x, lastY)) {
                        activePointerId = ev.getPointerId(ev.actionIndex)
                        touchingScrollingChild = true
                    }
                }
                ignoreEvents = (activePointerId == MotionEvent.INVALID_POINTER_ID &&
                        !parent.isPointInChildBounds(child, x, lastY))
            }
            else -> Unit
        }

        if (!ignoreEvents && isDraggable && dragHelper?.shouldInterceptTouchEvent(ev) == true) {
            return true
        }

        // If using scrim view, a click on it should collapse the bottom sheet
        if (useScrimView && state == State.Expanded && action == MotionEvent.ACTION_DOWN) {
            val y = ev.y.toInt()
            if (y <= expandedOffset) {
                setState(State.Collapsed)
                return true
            }
        }

        // We have to handle cases that the ViewDragHelper does not capture the bottom sheet because
        // it is not the top most view of its parent. This is not necessary when the touch event is
        // happening over the scrolling content as nested scrolling logic handles that case.
        val scroll = nestedScrollingChildRef?.get()
        return (action == MotionEvent.ACTION_MOVE &&
                scroll != null &&
                !ignoreEvents &&
                state != State.Dragging &&
                !parent.isPointInChildBounds(scroll, ev.x.toInt(), ev.y.toInt()) &&
                dragHelper != null &&
                Math.abs(lastY - ev.y.toInt()) > (dragHelper?.touchSlop ?: 0))
    }

    override fun onTouchEvent(parent: CoordinatorLayout, child: V, ev: MotionEvent): Boolean {
        val action = ev.actionMasked
        if (state == State.Dragging && action == MotionEvent.ACTION_DOWN) {
            return true
        }
        if (shouldHandleDraggingWithHelper()) {
            dragHelper?.processTouchEvent(ev)
        }

        // Record the velocity
        if (action == MotionEvent.ACTION_DOWN) {
            resetTouchEventTracking()
        }
        if (velocityTracker == null) {
            velocityTracker = VelocityTracker.obtain()
        }
        velocityTracker?.addMovement(ev)

        if (shouldHandleDraggingWithHelper() && action == MotionEvent.ACTION_MOVE && !ignoreEvents) {
            if (Math.abs(lastY - ev.y.toInt()) > (dragHelper?.touchSlop ?: 0)) {
                dragHelper?.captureChildView(child, ev.getPointerId(ev.actionIndex))
            }
        }

        return !ignoreEvents
    }

    override fun onAttachedToLayoutParams(params: CoordinatorLayout.LayoutParams) {
        super.onAttachedToLayoutParams(params)

        viewRef = null
        dragHelper = null
    }

    override fun onDetachedFromLayoutParams() {
        super.onDetachedFromLayoutParams()

        viewRef = null
        dragHelper = null
    }

    private fun calculateCollapsedOffset(child: View, parentWidth: Int) {
//        val measuredWidth = child.measuredWidth
        val measuredHeight = child.measuredHeight
        child.measure(
                MeasureSpec.makeMeasureSpec(parentWidth, MeasureSpec.EXACTLY),
                MeasureSpec.UNSPECIFIED,
        )
        collapsedOffset = parentHeight - child.measuredHeight
//        if (measuredWidth >= 0 && measuredHeight >= 0) {
//            child.measure(
//                    MeasureSpec.makeMeasureSpec(measuredWidth, MeasureSpec.EXACTLY),
//                    MeasureSpec.makeMeasureSpec(measuredHeight, MeasureSpec.EXACTLY),
//            )
//        }
    }

    private fun calculateExpandedOffset(parent: CoordinatorLayout): Int {
        expandedOffset = if (drawBelowAppBar) {
            findAppBarLayout(parent)?.measuredHeight ?: 0
        } else {
            0
        } + topOffset
        return expandedOffset
    }

    private fun ensureViewDragHelper(parent: CoordinatorLayout): ViewDragHelper {
        return dragHelper ?: run {
            val helper = ViewDragHelper.create(parent, dragHelperCallback)
            this.dragHelper = helper
            helper
        }
    }

    private fun findAppBarLayout(view: View): AppBarLayout? {
        return when (view) {
            is AppBarLayout -> view
            is ViewGroup -> view.children.firstNotNullOfOrNull { findAppBarLayout(it) }
            else -> null
        }
    }

    private fun shouldHandleDraggingWithHelper(): Boolean {
        return dragHelper != null && (isDraggable || state == State.Dragging)
    }

    private fun startSettling(child: View, state: State, isReleasingView: Boolean) {
        val top = getTopOffsetForState(state)
        println("New Offset: $top | CollapsedOffset: $collapsedOffset")
        val isSettling = dragHelper?.let {
            if (isReleasingView) {
                it.settleCapturedViewAt(child.left, top)
            } else {
                it.smoothSlideViewTo(child, child.left, top)
            }
        } ?: false
        setInternalState(if (isSettling) State.Settling else state)

        if (isSettling) {
            stateSettlingTracker.continueSettlingToState(state)
        }
    }

    fun setState(state: State) {
        if (viewRef?.get() == null) {
            setInternalState(state)
        } else {
            viewRef?.get()?.let { child ->
                runAfterLayout(child) { startSettling(child, state, false) }
            }
        }
    }

    private fun setInternalState(state: State) {
        this.state = state

        viewRef?.get()?.requestLayout()

        callback?.onStateChanged(state)
    }

    private fun runAfterLayout(child: V, runnable: Runnable) {
        if (isLayouting(child)) {
            child.post(runnable)
        } else {
            runnable.run()
        }
    }

    private fun isLayouting(child: V): Boolean {
        return child.parent != null && child.parent.isLayoutRequested && ViewCompat.isAttachedToWindow(child)
    }

    private fun getTopOffsetForState(state: State): Int {
        return when (state) {
            State.Collapsed -> collapsedOffset
            State.Expanded -> expandedOffset + insetTop
            else -> error("Cannot get offset for state $state")
        }
    }

    private fun resetTouchEventTracking() {
        activePointerId = ViewDragHelper.INVALID_POINTER
        velocityTracker?.recycle()
        velocityTracker = null
    }

    override fun onStartNestedScroll(
            coordinatorLayout: CoordinatorLayout,
            child: V,
            directTargetChild: View,
            target: View,
            axes: Int,
            type: Int
    ): Boolean {
        lastNestedScrollDy = 0
        isNestedScrolled = false
        return (axes and ViewCompat.SCROLL_AXIS_VERTICAL) != 0
    }

    override fun onNestedPreScroll(
            coordinatorLayout: CoordinatorLayout,
            child: V,
            target: View,
            dx: Int,
            dy: Int,
            consumed: IntArray,
            type: Int
    ) {
        if (type == ViewCompat.TYPE_NON_TOUCH) return
        val scrollingChild = nestedScrollingChildRef?.get()
        if (target != scrollingChild) return

        val currentTop = child.top
        val newTop = currentTop - dy
        if (dy > 0) {
            // Upward scroll
            if (newTop < expandedOffset) {
                consumed[1] = currentTop - expandedOffset
                ViewCompat.offsetTopAndBottom(child, -consumed[1])
                setInternalState(State.Expanded)
            } else {
                if (!isDraggable) return

                consumed[1] = dy
                ViewCompat.offsetTopAndBottom(child, -dy)
                setInternalState(State.Dragging)
            }
        } else if (dy < 0) {
            // Scroll downward
            if (!target.canScrollVertically(-1)) {
                if (newTop <= collapsedOffset) {
                    if (!isDraggable) return

                    consumed[1] = dy
                    ViewCompat.offsetTopAndBottom(child, -dy)
                    setInternalState(State.Dragging)
                } else {
                    consumed[1] = currentTop - collapsedOffset
                    ViewCompat.offsetTopAndBottom(child, -consumed[1])
                    setInternalState(State.Collapsed)
                }
            }
        }
        lastNestedScrollDy = dy
        isNestedScrolled = true
    }

    override fun onNestedScroll(
            coordinatorLayout: CoordinatorLayout,
            child: V,
            target: View,
            dxConsumed: Int,
            dyConsumed: Int,
            dxUnconsumed: Int,
            dyUnconsumed: Int,
            type: Int,
            consumed: IntArray
    ) {
        // Empty to avoid default behaviour
    }

    override fun onNestedPreFling(
            coordinatorLayout: CoordinatorLayout,
            child: V,
            target: View,
            velocityX: Float,
            velocityY: Float
    ): Boolean {
        return target == nestedScrollingChildRef?.get() &&
                (state != State.Expanded || super.onNestedPreFling(coordinatorLayout, child, target, velocityX, velocityY))
    }

    private var insetBottom = 0
    private var insetTop = 0
    @Suppress("DEPRECATION")
    private fun setWindowInsetsListener(view: View) {
        // Create a snapshot of the view's padding state.
        val initialPadding = RelativePadding(
                ViewCompat.getPaddingStart(view),
                view.paddingTop,
                ViewCompat.getPaddingEnd(view),
                view.paddingBottom
        )

        ViewCompat.setOnApplyWindowInsetsListener(view) { _: View, insets: WindowInsetsCompat ->
            val leftPadding = view.paddingLeft
            val rightPadding = view.paddingRight
            val insetsType = WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            val imeInsets = insets.getInsets(insetsType)
            insetTop = imeInsets.top
            insetBottom = imeInsets.bottom
            val bottomPadding = initialPadding.bottom + insetBottom
            view.setPadding(leftPadding, initialPadding.top, rightPadding, bottomPadding)
            if (state == State.Collapsed) {
                val params = view.layoutParams
                params.height = CoordinatorLayout.LayoutParams.WRAP_CONTENT
                view.layoutParams = params
            }
            insets.consumeStableInsets()
        }

        if (ViewCompat.isAttachedToWindow(view)) {
            ViewCompat.requestApplyInsets(view)
        } else {
            view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    v.removeOnAttachStateChangeListener(this)
                    ViewCompat.requestApplyInsets(v)
                }

                override fun onViewDetachedFromWindow(v: View) = Unit
            })
        }
    }

    private val dragHelperCallback = object : ViewDragHelper.Callback() {

        override fun tryCaptureView(child: View, pointerId: Int): Boolean {
            if (state == State.Dragging) {
                return false
            }

            if (touchingScrollingChild) {
                return false
            }

            if (state == State.Expanded && activePointerId == pointerId) {
                val scroll = nestedScrollingChildRef?.get()
                if (scroll?.canScrollVertically(-1) == true) {
                    return false
                }
            }

            return viewRef?.get() == child
        }

        override fun onViewDragStateChanged(state: Int) {
            if (state == ViewDragHelper.STATE_DRAGGING && isDraggable) {
                setInternalState(State.Dragging)
            }
        }

        override fun onViewPositionChanged(
                changedView: View,
                left: Int,
                top: Int,
                dx: Int,
                dy: Int
        ) {
            super.onViewPositionChanged(changedView, left, top, dx, dy)

            val params = changedView.layoutParams
            params.height = parentHeight - top + insetBottom
            changedView.layoutParams = params

            val percentage = 1 - top.toFloat() / collapsedOffset.toFloat()

            callback?.onSlidePositionChanged(changedView, percentage)

            scrimView?.let {
                if (percentage == 0f) {
                    it.isVisible = false
                } else {
                    it.alpha = percentage
                    it.isVisible = true
                }
            }
        }

        override fun onViewReleased(releasedChild: View, xvel: Float, yvel: Float) {
            val targetState = if (yvel < 0) {
                // Moving up
                val currentTop = releasedChild.top

                val yPositionPercentage = currentTop * 100f / collapsedOffset
                if (yPositionPercentage >= 0.5f) {
                    State.Expanded
                } else {
                    State.Collapsed
                }
            } else if (yvel == 0f || Math.abs(xvel) > Math.abs(yvel)) {
                // If the Y velocity is 0 or the swipe was mostly horizontal indicated by the X velocity
                // being greater than the Y velocity, settle to the nearest correct height.

                val currentTop = releasedChild.top
                if (currentTop < collapsedOffset / 2) {
                    State.Expanded
                } else {
                    State.Collapsed
                }
            } else {
                // Moving down
                val currentTop = releasedChild.top

                val yPositionPercentage = currentTop * 100f / collapsedOffset
                if (yPositionPercentage >= 0.5f) {
                    State.Collapsed
                } else {
                    State.Expanded
                }
            }
            startSettling(releasedChild, targetState, true)
        }

        override fun clampViewPositionHorizontal(child: View, left: Int, dx: Int): Int {
            return child.left
        }

        override fun clampViewPositionVertical(child: View, top: Int, dy: Int): Int {
            return min(max(top, expandedOffset), collapsedOffset)
        }

        override fun getViewVerticalDragRange(child: View): Int {
            return collapsedOffset
        }
    }

    interface Callback {
        fun onStateChanged(state: State) {}
        fun onSlidePositionChanged(view: View, yPosition: Float) {}
    }

    private inner class StateSettlingTracker {
        private lateinit var targetState: State
        private var isContinueSettlingRunnablePosted = false

        private val continueSettlingRunnable: Runnable = Runnable {
            isContinueSettlingRunnablePosted = false
            if (dragHelper?.continueSettling(true) == true) {
                continueSettlingToState(targetState)
            } else {
                setInternalState(targetState)
            }
        }

        fun continueSettlingToState(state: State) {
            val view = viewRef?.get() ?: return

            this.targetState = state
            if (!isContinueSettlingRunnablePosted) {
                ViewCompat.postOnAnimation(view, continueSettlingRunnable)
                isContinueSettlingRunnablePosted = true
            }
        }
    }
}

private data class RelativePadding(
        val start: Int,
        val top: Int,
        val end: Int,
        val bottom: Int,
)
