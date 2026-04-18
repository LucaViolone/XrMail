package com.xremail.app.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Wraps Android's platform TextToSpeech for local narration (email summaries,
 * notification previews, offline fallback). Gemini Live handles the conversational
 * audio — this is for deterministic reads the app originates.
 *
 * Auto-pauses when FaceAttentionTracker reports the user's eyes closed.
 */
class TTSManager(context: Context) {

    enum class PlaybackState { IDLE, PLAYING, PAUSED }

    private val appContext = context.applicationContext
    private var tts: TextToSpeech? = null
    private var ready = false
    private var currentText: String? = null

    private val _playbackState = MutableStateFlow(PlaybackState.IDLE)
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress.asStateFlow()

    private val _finished = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    val finished: SharedFlow<Unit> = _finished.asSharedFlow()

    init {
        tts = TextToSpeech(appContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                tts?.setSpeechRate(1.0f)
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        _playbackState.value = PlaybackState.PLAYING
                        _progress.value = 0f
                    }

                    override fun onDone(utteranceId: String?) {
                        _playbackState.value = PlaybackState.IDLE
                        _progress.value = 1f
                        _finished.tryEmit(Unit)
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        _playbackState.value = PlaybackState.IDLE
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        _playbackState.value = PlaybackState.IDLE
                    }
                })
                ready = true
                Log.i(TAG, "TTS ready")
            } else {
                Log.w(TAG, "TTS init failed: status=$status")
            }
        }
    }

    fun speak(text: String) {
        if (!ready || text.isBlank()) return
        currentText = text
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
    }

    fun pause() {
        if (_playbackState.value == PlaybackState.PLAYING) {
            tts?.stop()
            _playbackState.value = PlaybackState.PAUSED
        }
    }

    fun resume() {
        val text = currentText
        if (_playbackState.value == PlaybackState.PAUSED && text != null) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
        }
    }

    fun stop() {
        tts?.stop()
        _playbackState.value = PlaybackState.IDLE
        _progress.value = 0f
        currentText = null
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
    }

    fun onAttentionChanged(isAttentive: Boolean) {
        if (!isAttentive && _playbackState.value == PlaybackState.PLAYING) {
            pause()
        } else if (isAttentive && _playbackState.value == PlaybackState.PAUSED) {
            resume()
        }
    }

    companion object {
        private const val TAG = "TTSManager"
        private const val UTTERANCE_ID = "xrmail-tts"
    }
}
