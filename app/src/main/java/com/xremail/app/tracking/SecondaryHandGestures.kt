package com.xremail.app.tracking

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Custom gesture detection bound to the secondary (non-primary) hand only.
 *
 * System navigation owns the primary hand. All custom gestures must use
 * Hand.getPrimaryHandSide() and bind to the opposite hand.
 *
 * Production implementation:
 * ```
 * val primarySide = Hand.getPrimaryHandSide(session)
 * val gestureHand = if (primarySide == HandSide.RIGHT)
 *     Hand.left(session) else Hand.right(session)
 * gestureHand?.state?.collect { handState ->
 *     detectGestures(handState)
 * }
 * ```
 */
class SecondaryHandGestures {

    enum class Gesture {
        PINCH_SELECT,
        SWIPE_LEFT_ARCHIVE,
        SWIPE_RIGHT_SNOOZE,
        SWIPE_DOWN_DISMISS,
    }

    private val _gestures = MutableSharedFlow<Gesture>(extraBufferCapacity = 16)
    val gestures: SharedFlow<Gesture> = _gestures.asSharedFlow()

    /**
     * Called from the hand tracking collection loop when a gesture is
     * recognized on the secondary hand.
     */
    fun onGestureDetected(gesture: Gesture) {
        _gestures.tryEmit(gesture)
    }

    // Phase 1 stub: simulate a gesture for testing
    fun simulateGesture(gesture: Gesture) {
        _gestures.tryEmit(gesture)
    }
}
