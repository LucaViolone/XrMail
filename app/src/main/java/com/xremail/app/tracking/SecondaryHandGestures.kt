package com.xremail.app.tracking

import android.content.ContentResolver
import android.util.Log
import androidx.xr.arcore.Hand
import androidx.xr.arcore.HandJointType
import androidx.xr.runtime.HandTrackingMode
import androidx.xr.runtime.Session
import androidx.xr.runtime.SessionConfigureSuccess
import androidx.xr.runtime.math.Vector3
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

private const val TAG = "SecondaryHandGestures"

private const val PINCH_DISTANCE_THRESHOLD = 0.04f
private const val PINCH_HOLD_DURATION_MS = 600L
private const val SWIPE_DISTANCE_THRESHOLD = 0.08f
private const val SWIPE_VELOCITY_THRESHOLD = 0.15f
private const val DEDUP_WINDOW_MS = 250L

/**
 * Tracks pinch + swipe gestures on BOTH hands. Early versions only watched the
 * non-dominant hand because the OS-level gaze-pinch on the dominant hand was
 * expected to drive panel clicks directly. In practice users pinch with either
 * hand interchangeably, so we observe both and de-dupe identical gestures
 * fired within 250 ms.
 */
class SecondaryHandGestures {

    enum class Gesture {
        PINCH_SELECT,
        PINCH_HOLD_EXPAND,
        SWIPE_LEFT_ARCHIVE,
        SWIPE_RIGHT_SNOOZE,
        SWIPE_DOWN_DISMISS,
        SWIPE_UP_STAR,
    }

    private val _gestures = MutableSharedFlow<Gesture>(extraBufferCapacity = 16)
    val gestures: SharedFlow<Gesture> = _gestures.asSharedFlow()

    private var leftJob: Job? = null
    private var rightJob: Job? = null

    private val leftHand = HandState("L")
    private val rightHand = HandState("R")

    @Volatile private var lastEmitType: Gesture? = null
    @Volatile private var lastEmitMs: Long = 0L

    fun startTracking(
        session: Session,
        contentResolver: ContentResolver,
        scope: CoroutineScope,
    ) {
        stopTracking()

        val configResult = session.configure(
            session.config.copy(handTracking = HandTrackingMode.BOTH)
        )
        if (configResult is SessionConfigureSuccess) {
            Log.d(TAG, "Hand tracking configured (both hands)")
        } else {
            Log.w(TAG, "Hand tracking config result: $configResult")
        }

        val left = Hand.left(session)
        val right = Hand.right(session)

        if (left == null && right == null) {
            Log.w(TAG, "Neither hand available")
            return
        }

        if (left != null) {
            leftJob = scope.launch {
                left.state.collect { handState -> processHandState(handState, leftHand) }
            }
        } else {
            Log.w(TAG, "Left hand unavailable")
        }

        if (right != null) {
            rightJob = scope.launch {
                right.state.collect { handState -> processHandState(handState, rightHand) }
            }
        } else {
            Log.w(TAG, "Right hand unavailable")
        }
    }

    fun stopTracking() {
        leftJob?.cancel()
        rightJob?.cancel()
        leftJob = null
        rightJob = null
        leftHand.reset()
        rightHand.reset()
    }

    private fun processHandState(handState: Hand.State, s: HandState) {
        val thumbTip = handState.handJoints[HandJointType.HAND_JOINT_TYPE_THUMB_TIP] ?: return
        val indexTip = handState.handJoints[HandJointType.HAND_JOINT_TYPE_INDEX_TIP] ?: return
        val palm = handState.handJoints[HandJointType.HAND_JOINT_TYPE_PALM] ?: return

        val pinchDistance = Vector3.distance(thumbTip.translation, indexTip.translation)
        val now = System.currentTimeMillis()

        detectPinch(pinchDistance, now, s)
        trackPalmForSwipe(palm.translation, now, s)
    }

    private fun detectPinch(distance: Float, now: Long, s: HandState) {
        val wasPinching = s.isPinching
        s.isPinching = distance < PINCH_DISTANCE_THRESHOLD

        if (s.isPinching && !wasPinching) {
            s.pinchStartTimeMs = now
            s.pinchEmitted = false
        }

        if (s.isPinching && !s.pinchEmitted) {
            val holdDuration = now - s.pinchStartTimeMs
            if (holdDuration >= PINCH_HOLD_DURATION_MS) {
                emit(Gesture.PINCH_HOLD_EXPAND, s)
                s.pinchEmitted = true
            }
        }

        if (!s.isPinching && wasPinching && !s.pinchEmitted) {
            emit(Gesture.PINCH_SELECT, s)
        }
    }

    private fun trackPalmForSwipe(palmPos: Vector3, now: Long, s: HandState) {
        s.palmPositionHistory.add(now to palmPos)
        if (s.palmPositionHistory.size > POSITION_HISTORY_MAX) {
            s.palmPositionHistory.removeAt(0)
        }

        if (s.palmPositionHistory.size < 4) return

        val oldest = s.palmPositionHistory.first()
        val newest = s.palmPositionHistory.last()
        val dt = (newest.first - oldest.first) / 1000f
        if (dt < 0.05f) return

        val dx = newest.second.x - oldest.second.x
        val dy = newest.second.y - oldest.second.y

        val vx = dx / dt
        val vy = dy / dt

        val absDx = kotlin.math.abs(dx)
        val absDy = kotlin.math.abs(dy)

        if (absDx > SWIPE_DISTANCE_THRESHOLD && absDx > absDy * 1.5f) {
            if (kotlin.math.abs(vx) > SWIPE_VELOCITY_THRESHOLD) {
                if (vx > 0) emit(Gesture.SWIPE_RIGHT_SNOOZE, s)
                else emit(Gesture.SWIPE_LEFT_ARCHIVE, s)
                s.palmPositionHistory.clear()
            }
        } else if (absDy > SWIPE_DISTANCE_THRESHOLD && absDy > absDx * 1.5f) {
            if (kotlin.math.abs(vy) > SWIPE_VELOCITY_THRESHOLD) {
                if (vy > 0) emit(Gesture.SWIPE_UP_STAR, s)
                else emit(Gesture.SWIPE_DOWN_DISMISS, s)
                s.palmPositionHistory.clear()
            }
        }
    }

    private fun emit(gesture: Gesture, s: HandState) {
        val now = System.currentTimeMillis()
        if (lastEmitType == gesture && now - lastEmitMs < DEDUP_WINDOW_MS) {
            Log.d(TAG, "dedup ${s.label} $gesture (within $DEDUP_WINDOW_MS ms)")
            return
        }
        lastEmitType = gesture
        lastEmitMs = now
        Log.d(TAG, "emit ${s.label} $gesture")
        _gestures.tryEmit(gesture)
    }

    fun onGestureDetected(gesture: Gesture) {
        _gestures.tryEmit(gesture)
    }

    fun simulateGesture(gesture: Gesture) {
        _gestures.tryEmit(gesture)
    }

    private class HandState(val label: String) {
        var isPinching = false
        var pinchStartTimeMs = 0L
        var pinchEmitted = false
        val palmPositionHistory = mutableListOf<Pair<Long, Vector3>>()

        fun reset() {
            isPinching = false
            pinchStartTimeMs = 0L
            pinchEmitted = false
            palmPositionHistory.clear()
        }
    }

    companion object {
        private const val POSITION_HISTORY_MAX = 10
    }
}
