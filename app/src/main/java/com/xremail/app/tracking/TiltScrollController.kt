package com.xremail.app.tracking

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Uses the TiltGesture API (alpha10+, no permission required) to scroll
 * email lists by tilting the device.
 *
 * Production implementation:
 * ```
 * TiltGesture.observe(session).collect { tilt ->
 *     when (tilt.state) {
 *         TiltState.DOWN -> controller.onTilt(tilt.progress)
 *         TiltState.UP -> controller.onTilt(-tilt.progress)
 *         TiltState.NEUTRAL -> controller.onTilt(0f)
 *     }
 * }
 * ```
 *
 * The scroll delta is exposed as a StateFlow that UI can collect to call
 * LazyListState.scrollBy().
 */
class TiltScrollController {

    private val _scrollDelta = MutableStateFlow(0f)
    val scrollDelta: StateFlow<Float> = _scrollDelta.asStateFlow()

    private var sensitivity = 100f

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
