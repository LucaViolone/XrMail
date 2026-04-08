package com.xremail.app.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Text-to-speech manager with attention-aware pause.
 * Production: subscribes to FaceAttentionTracker for pause/resume.
 */
class TTSManager(context: Context) {

    enum class PlaybackState { IDLE, PLAYING, PAUSED }

    private val appContext = context.applicationContext

    private val _playbackState = MutableStateFlow(PlaybackState.IDLE)
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress.asStateFlow()

    @Volatile
    private var ready: Boolean = false

    private var languageConfigured: Boolean = false

    private val tts: TextToSpeech = TextToSpeech(appContext) { status ->
        ready = status == TextToSpeech.SUCCESS
    }

    fun speak(text: String) {
        if (!ready || text.isBlank()) return
        if (!languageConfigured) {
            tts.language = Locale.getDefault()
            languageConfigured = true
        }
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "xrmail-${System.currentTimeMillis()}")
        _playbackState.value = PlaybackState.PLAYING
        _progress.value = 0f
    }

    fun pause() {
        if (_playbackState.value == PlaybackState.PLAYING) {
            tts.stop()
            _playbackState.value = PlaybackState.PAUSED
        }
    }

    fun resume() {
        if (_playbackState.value == PlaybackState.PAUSED) {
            _playbackState.value = PlaybackState.PLAYING
        }
    }

    fun stop() {
        tts.stop()
        _playbackState.value = PlaybackState.IDLE
        _progress.value = 0f
    }

    fun shutdown() {
        stop()
        tts.shutdown()
    }

    fun onAttentionChanged(isAttentive: Boolean) {
        if (!isAttentive && _playbackState.value == PlaybackState.PLAYING) {
            pause()
        } else if (isAttentive && _playbackState.value == PlaybackState.PAUSED) {
            resume()
        }
    }
}
