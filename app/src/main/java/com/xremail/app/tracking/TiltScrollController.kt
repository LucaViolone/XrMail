package com.xremail.app.tracking

import android.util.Log
import androidx.xr.arcore.ExperimentalGesturesApi
import androidx.xr.arcore.Tilt
import androidx.xr.arcore.TiltGesture
import androidx.xr.runtime.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TAG = "TiltScrollController"

class TiltScrollController {

    private val _scrollDelta = MutableStateFlow(0f)
    val scrollDelta: StateFlow<Float> = _scrollDelta.asStateFlow()

    private var sensitivity = 100f
    private var trackingJob: Job? = null

    @OptIn(ExperimentalGesturesApi::class)
    fun startTracking(session: Session, scope: CoroutineScope) {
        trackingJob?.cancel()

        trackingJob = scope.launch {
            try {
                TiltGesture.detect(session).collect { state ->
                    when (state.tilt) {
                        Tilt.DOWN -> onTilt(state.progress)
                        Tilt.UP -> onTilt(-state.progress)
                        else -> onTilt(0f)
                    }
                }
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Tilt detection failed (device tracking may be disabled): ${e.message}")
            }
        }
    }

    fun stopTracking() {
        trackingJob?.cancel()
        trackingJob = null
        _scrollDelta.value = 0f
    }

    fun onTilt(progress: Float) {
        _scrollDelta.value = progress * sensitivity
    }

    fun setSensitivity(value: Float) {
        sensitivity = value.coerceIn(10f, 500f)
    }

    fun reset() {
        _scrollDelta.value = 0f
    }
}
