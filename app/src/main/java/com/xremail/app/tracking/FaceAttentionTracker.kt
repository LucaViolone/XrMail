package com.xremail.app.tracking

import android.util.Log
import androidx.xr.arcore.Face
import androidx.xr.arcore.FaceBlendShapeType
import androidx.xr.runtime.FaceTrackingMode
import androidx.xr.runtime.Session
import androidx.xr.runtime.SessionConfigureSuccess
import androidx.xr.runtime.TrackingState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TAG = "FaceAttentionTracker"

/**
 * Tracks user attention via ARCore face blendshapes and provides
 * gaze-zone dwell detection for the notification stack.
 *
 * The gaze zone system uses head orientation blendshapes to approximate
 * whether the user is looking toward the notification area (upper-right
 * peripheral zone). On XR devices with eye tracking, this would use
 * actual gaze rays; here we use head-turn blendshapes as a proxy that
 * works reliably while walking.
 *
 * Dwell timing:
 * - 500ms gaze on notification zone → [isGazingAtNotificationZone] = true
 * - 2000ms gaze away → [isGazingAtNotificationZone] = false
 */
class FaceAttentionTracker {

    private val _isAttentive = MutableStateFlow(true)
    val isAttentive: StateFlow<Boolean> = _isAttentive.asStateFlow()

    private val _eyeClosedAmount = MutableStateFlow(0f)
    val eyeClosedAmount: StateFlow<Float> = _eyeClosedAmount.asStateFlow()

    private val _isGazingAtNotificationZone = MutableStateFlow(false)
    val isGazingAtNotificationZone: StateFlow<Boolean> = _isGazingAtNotificationZone.asStateFlow()

    private val _gazeDirection = MutableStateFlow(GazeDirection.CENTER)
    val gazeDirection: StateFlow<GazeDirection> = _gazeDirection.asStateFlow()

    private var eyeClosedThreshold = 0.7f
    private var trackingJob: Job? = null
    private var gazeDwellJob: Job? = null

    private var gazeOnNotifStartMs = 0L
    private var gazeOffNotifStartMs = 0L

    enum class GazeDirection {
        CENTER,
        UPPER_RIGHT,
        RIGHT,
        LEFT,
        DOWN,
    }

    fun startTracking(session: Session, scope: CoroutineScope) {
        trackingJob?.cancel()

        val configResult = session.configure(
            session.config.copy(faceTracking = FaceTrackingMode.BLEND_SHAPES)
        )
        if (configResult is SessionConfigureSuccess) {
            Log.d(TAG, "Face tracking configured with gaze zone detection")
        } else {
            Log.w(TAG, "Face tracking config result: $configResult")
        }

        val face = Face.getUserFace(session)
        if (face == null) {
            Log.w(TAG, "User face not available, defaulting to attentive")
            return
        }

        trackingJob = scope.launch {
            face.state.collect { state ->
                if (state.trackingState != TrackingState.TRACKING) return@collect

                val leftClosed = state.blendShapes[
                    FaceBlendShapeType.FACE_BLEND_SHAPE_TYPE_EYES_CLOSED_LEFT
                ] ?: 0f
                val rightClosed = state.blendShapes[
                    FaceBlendShapeType.FACE_BLEND_SHAPE_TYPE_EYES_CLOSED_RIGHT
                ] ?: 0f

                updateBlendShapes(leftClosed, rightClosed)

                // Use eye look direction blendshapes for gaze zone approximation.
                // These indicate where the eyes are pointing relative to the head.
                val lookRightL = state.blendShapes[
                    FaceBlendShapeType.FACE_BLEND_SHAPE_TYPE_EYES_LOOK_RIGHT_LEFT
                ] ?: 0f
                val lookRightR = state.blendShapes[
                    FaceBlendShapeType.FACE_BLEND_SHAPE_TYPE_EYES_LOOK_RIGHT_RIGHT
                ] ?: 0f
                val lookUpL = state.blendShapes[
                    FaceBlendShapeType.FACE_BLEND_SHAPE_TYPE_EYES_LOOK_UP_LEFT
                ] ?: 0f
                val lookUpR = state.blendShapes[
                    FaceBlendShapeType.FACE_BLEND_SHAPE_TYPE_EYES_LOOK_UP_RIGHT
                ] ?: 0f
                val lookDownL = state.blendShapes[
                    FaceBlendShapeType.FACE_BLEND_SHAPE_TYPE_EYES_LOOK_DOWN_LEFT
                ] ?: 0f
                val lookDownR = state.blendShapes[
                    FaceBlendShapeType.FACE_BLEND_SHAPE_TYPE_EYES_LOOK_DOWN_RIGHT
                ] ?: 0f

                updateGazeDirection(
                    lookRight = (lookRightL + lookRightR) / 2f,
                    lookUp = (lookUpL + lookUpR) / 2f,
                    lookDown = (lookDownL + lookDownR) / 2f,
                )
            }
        }

        gazeDwellJob = scope.launch {
            while (true) {
                delay(100)
                processGazeDwell()
            }
        }
    }

    fun stopTracking() {
        trackingJob?.cancel()
        trackingJob = null
        gazeDwellJob?.cancel()
        gazeDwellJob = null
        _isAttentive.value = true
        _eyeClosedAmount.value = 0f
        _isGazingAtNotificationZone.value = false
        _gazeDirection.value = GazeDirection.CENTER
    }

    fun updateBlendShapes(eyesClosedLeft: Float, eyesClosedRight: Float) {
        val avg = (eyesClosedLeft + eyesClosedRight) / 2f
        _eyeClosedAmount.value = avg
        _isAttentive.value = avg < eyeClosedThreshold
    }

    private fun updateGazeDirection(lookRight: Float, lookUp: Float, lookDown: Float) {
        val isLookingRight = lookRight > 0.3f
        val isLookingUp = lookUp > 0.25f
        val isLookingDown = lookDown > 0.4f

        _gazeDirection.value = when {
            isLookingRight && isLookingUp -> GazeDirection.UPPER_RIGHT
            isLookingRight -> GazeDirection.RIGHT
            isLookingDown -> GazeDirection.DOWN
            else -> GazeDirection.CENTER
        }
    }

    /**
     * Processes gaze dwell timing for notification zone expansion.
     * 500ms dwell on upper-right = expand, 2000ms away = collapse.
     * These timings are tuned for walking use — long enough to prevent
     * accidental triggers from head movement, short enough to feel responsive.
     */
    private fun processGazeDwell() {
        val now = System.currentTimeMillis()
        val isInNotifZone = _gazeDirection.value == GazeDirection.UPPER_RIGHT

        if (isInNotifZone) {
            gazeOffNotifStartMs = 0L
            if (gazeOnNotifStartMs == 0L) {
                gazeOnNotifStartMs = now
            } else if (now - gazeOnNotifStartMs >= GAZE_DWELL_EXPAND_MS) {
                _isGazingAtNotificationZone.value = true
            }
        } else {
            gazeOnNotifStartMs = 0L
            if (_isGazingAtNotificationZone.value) {
                if (gazeOffNotifStartMs == 0L) {
                    gazeOffNotifStartMs = now
                } else if (now - gazeOffNotifStartMs >= GAZE_AWAY_COLLAPSE_MS) {
                    _isGazingAtNotificationZone.value = false
                    gazeOffNotifStartMs = 0L
                }
            }
        }
    }

    fun setThreshold(threshold: Float) {
        eyeClosedThreshold = threshold.coerceIn(0f, 1f)
    }

    fun simulateAttentive() {
        _isAttentive.value = true
        _eyeClosedAmount.value = 0f
    }

    fun simulateGazeAtNotifications(gazing: Boolean) {
        _isGazingAtNotificationZone.value = gazing
    }

    companion object {
        const val GAZE_DWELL_EXPAND_MS = 500L
        const val GAZE_AWAY_COLLAPSE_MS = 2000L
    }
}
