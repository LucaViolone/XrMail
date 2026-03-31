package com.xremail.app.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Text-to-speech manager with attention-aware pause.
 * Integrates with FaceAttentionTracker — pauses when user's eyes close
 * (detected via ARCore face blendshapes, not gaze hit-testing).
 *
 * Production: wraps Android TextToSpeech and subscribes to
 * FaceAttentionTracker.isAttentive to auto-pause/resume.
 */
class TTSManager {

    enum class PlaybackState { IDLE, PLAYING, PAUSED }

    private val _playbackState = MutableStateFlow(PlaybackState.IDLE)
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress.asStateFlow()

    fun speak(text: String) {
        _playbackState.value = PlaybackState.PLAYING
        _progress.value = 0f
    }

    fun pause() {
        if (_playbackState.value == PlaybackState.PLAYING) {
            _playbackState.value = PlaybackState.PAUSED
        }
    }

    fun resume() {
        if (_playbackState.value == PlaybackState.PAUSED) {
            _playbackState.value = PlaybackState.PLAYING
        }
    }

    fun stop() {
        _playbackState.value = PlaybackState.IDLE
        _progress.value = 0f
    }

    /**
     * Called by FaceAttentionTracker integration:
     * ```
     * faceTracker.isAttentive.collect { attentive ->
     *     if (!attentive) ttsManager.pause()
     *     else ttsManager.resume()
     * }
     * ```
     */
    fun onAttentionChanged(isAttentive: Boolean) {
        if (!isAttentive && _playbackState.value == PlaybackState.PLAYING) {
            pause()
        } else if (isAttentive && _playbackState.value == PlaybackState.PAUSED) {
            resume()
        }
    }
}
