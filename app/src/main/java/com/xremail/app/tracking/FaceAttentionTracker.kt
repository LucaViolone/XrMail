package com.xremail.app.tracking

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tracks user attention via ARCore Face Tracking blendshapes.
 *
 * Eye tracking on Android XR is system-rendered only — raw gaze data is not
 * exposed to apps. For attention-awareness (e.g. pause TTS when user looks
 * away), we use face blendshapes instead of gaze hit-testing.
 *
 * Requires: session.configure(config.copy(faceTracking = FaceTrackingMode.BLEND_SHAPES))
 *
 * Production implementation collects from Face.getUserFace(session)?.state
 * and reads FACE_BLEND_SHAPE_TYPE_EYES_CLOSED_L / _R values.
 */
class FaceAttentionTracker {

    private val _isAttentive = MutableStateFlow(true)
    val isAttentive: StateFlow<Boolean> = _isAttentive.asStateFlow()

    private val _eyeClosedAmount = MutableStateFlow(0f)
    val eyeClosedAmount: StateFlow<Float> = _eyeClosedAmount.asStateFlow()

    private var eyeClosedThreshold = 0.8f

    /**
     * Call from a coroutine scope that collects face blendshape state:
     *
     * ```
     * session.configure(session.config.copy(faceTracking = FaceTrackingMode.BLEND_SHAPES))
     * Face.getUserFace(session)?.state?.collect { state ->
     *     val leftClosed = state.blendShapes[FACE_BLEND_SHAPE_TYPE_EYES_CLOSED_L] ?: 0f
     *     val rightClosed = state.blendShapes[FACE_BLEND_SHAPE_TYPE_EYES_CLOSED_R] ?: 0f
     *     tracker.updateBlendShapes(leftClosed, rightClosed)
     * }
     * ```
     */
    fun updateBlendShapes(eyesClosedLeft: Float, eyesClosedRight: Float) {
        val avg = (eyesClosedLeft + eyesClosedRight) / 2f
        _eyeClosedAmount.value = avg
        _isAttentive.value = avg < eyeClosedThreshold
    }

    fun setThreshold(threshold: Float) {
        eyeClosedThreshold = threshold.coerceIn(0f, 1f)
    }

    // Phase 1 stub: always attentive
    fun simulateAttentive() {
        _isAttentive.value = true
        _eyeClosedAmount.value = 0f
    }
}
