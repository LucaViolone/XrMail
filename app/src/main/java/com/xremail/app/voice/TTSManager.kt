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
        // `lateinit`-via-self-ref: the listener needs to be able to retry by
        // constructing a fresh TextToSpeech, which means it has to refer to
        // itself. Hold it in a `var` we can read from inside its own body.
        var listener: TextToSpeech.OnInitListener? = null
        listener = TextToSpeech.OnInitListener { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                // 1.15 trims dead space without entering "chipmunk" territory.
                // Conversational humans speak at roughly 150-180 wpm; the
                // platform TTS default sits closer to 130, which feels
                // sluggish next to a real-time voice agent.
                tts?.setSpeechRate(1.15f)
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
                Log.i(
                    TAG,
                    "TTS ready (engine=${tts?.defaultEngine}, " +
                        "voices=${tts?.voices?.size ?: 0})",
                )
            } else {
                Log.w(TAG, "TTS init failed: status=$status — retrying with system default")
                // Retry without forcing an engine. Some OEM builds return
                // ERROR for the engine-pinned constructor even when the
                // engine package is installed (timing race during boot).
                val cb = listener
                if (cb != null) {
                    tts = TextToSpeech(appContext, cb)
                }
            }
        }

        // Prefer Google's TTS engine when present — its synthesizer warms up
        // faster (~50ms vs ~250ms cold-start on Galaxy XR) and the default
        // en-US voice is markedly more natural than the Samsung default.
        tts = try {
            TextToSpeech(appContext, listener, GOOGLE_TTS_PACKAGE)
        } catch (t: Throwable) {
            Log.w(TAG, "Google TTS engine unavailable, falling back to default", t)
            TextToSpeech(appContext, listener)
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
        // Google's TTS engine. Pre-installed on Galaxy XR; on devices that
        // don't have it the constructor throws and we fall back to the
        // system default. Higher quality voices, faster cold start.
        private const val GOOGLE_TTS_PACKAGE = "com.google.android.tts"
    }
}
