package com.xremail.app.tracking

import android.content.ContentResolver
import android.util.Log
import androidx.xr.arcore.Hand
import androidx.xr.arcore.HandJointType
import androidx.xr.runtime.HandTrackingMode
import androidx.xr.runtime.Session
import androidx.xr.runtime.SessionConfigureSuccess
import androidx.xr.runtime.math.Pose
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

    private var trackingJob: Job? = null

    private var isPinching = false
    private var pinchStartTimeMs = 0L
    private var pinchEmitted = false

    private var palmPositionHistory = mutableListOf<Pair<Long, Vector3>>()
    private val positionHistoryMaxSize = 10

    fun startTracking(
        session: Session,
        contentResolver: ContentResolver,
        scope: CoroutineScope,
    ) {
        trackingJob?.cancel()

        val configResult = session.configure(
            session.config.copy(handTracking = HandTrackingMode.BOTH)
        )
        if (configResult is SessionConfigureSuccess) {
            Log.d(TAG, "Hand tracking configured")
        } else {
            Log.w(TAG, "Hand tracking config result: $configResult")
        }

        val primarySide = Hand.getPrimaryHandSide(contentResolver)
        val secondaryHand = if (primarySide == Hand.HandSide.LEFT) {
            Hand.right(session)
        } else {
            Hand.left(session)
        }

        if (secondaryHand == null) {
            Log.w(TAG, "Secondary hand not available")
            return
        }

        trackingJob = scope.launch {
            secondaryHand.state.collect { handState ->
                processHandState(handState)
            }
        }
    }

    fun stopTracking() {
        trackingJob?.cancel()
        trackingJob = null
        palmPositionHistory.clear()
        isPinching = false
    }

    private fun processHandState(handState: Hand.State) {
        val thumbTip = handState.handJoints[HandJointType.HAND_JOINT_TYPE_THUMB_TIP] ?: return
        val indexTip = handState.handJoints[HandJointType.HAND_JOINT_TYPE_INDEX_TIP] ?: return
        val palm = handState.handJoints[HandJointType.HAND_JOINT_TYPE_PALM] ?: return

        val pinchDistance = Vector3.distance(thumbTip.translation, indexTip.translation)
        val now = System.currentTimeMillis()

        detectPinch(pinchDistance, now)
        trackPalmForSwipe(palm.translation, now)
    }

    private fun detectPinch(distance: Float, now: Long) {
        val wasPinching = isPinching
        isPinching = distance < PINCH_DISTANCE_THRESHOLD

        if (isPinching && !wasPinching) {
            pinchStartTimeMs = now
            pinchEmitted = false
        }

        if (isPinching && !pinchEmitted) {
            val holdDuration = now - pinchStartTimeMs
            if (holdDuration >= PINCH_HOLD_DURATION_MS) {
                _gestures.tryEmit(Gesture.PINCH_HOLD_EXPAND)
                pinchEmitted = true
            }
        }

        if (!isPinching && wasPinching && !pinchEmitted) {
            _gestures.tryEmit(Gesture.PINCH_SELECT)
        }
    }

    private fun trackPalmForSwipe(palmPos: Vector3, now: Long) {
        palmPositionHistory.add(now to palmPos)
        if (palmPositionHistory.size > positionHistoryMaxSize) {
            palmPositionHistory.removeAt(0)
        }

        if (palmPositionHistory.size < 4) return

        val oldest = palmPositionHistory.first()
        val newest = palmPositionHistory.last()
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
                if (vx > 0) {
                    _gestures.tryEmit(Gesture.SWIPE_RIGHT_SNOOZE)
                } else {
                    _gestures.tryEmit(Gesture.SWIPE_LEFT_ARCHIVE)
                }
                palmPositionHistory.clear()
            }
        } else if (absDy > SWIPE_DISTANCE_THRESHOLD && absDy > absDx * 1.5f) {
            if (kotlin.math.abs(vy) > SWIPE_VELOCITY_THRESHOLD) {
                if (vy > 0) {
                    _gestures.tryEmit(Gesture.SWIPE_UP_STAR)
                } else {
                    _gestures.tryEmit(Gesture.SWIPE_DOWN_DISMISS)
                }
                palmPositionHistory.clear()
            }
        }
    }

    fun onGestureDetected(gesture: Gesture) {
        _gestures.tryEmit(gesture)
    }

    fun simulateGesture(gesture: Gesture) {
        _gestures.tryEmit(gesture)
    }
}
