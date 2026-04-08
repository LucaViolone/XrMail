package com.xremail.app.voice

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Text-to-speech manager with attention-aware pause.
 *
 * Wraps Android [TextToSpeech] for reading email bodies and summaries aloud.
 * Integrates with FaceAttentionTracker via [onAttentionChanged] to auto-pause
 * when the user looks away (detected via ARCore face blendshapes).
 */
class TTSManager(context: Context) : TextToSpeech.OnInitListener {

    enum class PlaybackState { IDLE, PLAYING, PAUSED }

    private val tts = TextToSpeech(context.applicationContext, this)
    private var isReady = false

    private val _playbackState = MutableStateFlow(PlaybackState.IDLE)
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress.asStateFlow()

    private var fullText: String = ""
    private var segments: List<String> = emptyList()
    private var currentSegmentIndex = 0

    companion object {
        private const val TAG = "TTSManager"
        private const val UTTERANCE_PREFIX = "xrmail_tts_"
        private const val SEGMENT_MAX_CHARS = 3500
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.US
            tts.setSpeechRate(1.1f)
            tts.setOnUtteranceProgressListener(ProgressListener())
            isReady = true
            Log.i(TAG, "TTS initialized")
        } else {
            Log.e(TAG, "TTS init failed with status: $status")
        }
    }

    fun speak(text: String) {
        if (!isReady) {
            Log.w(TAG, "TTS not ready, queuing speak")
            fullText = text
            return
        }

        stop()
        fullText = text
        segments = splitIntoSegments(text)
        currentSegmentIndex = 0
        _playbackState.value = PlaybackState.PLAYING
        _progress.value = 0f
        speakCurrentSegment()
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
            speakCurrentSegment()
        }
    }

    fun stop() {
        tts.stop()
        _playbackState.value = PlaybackState.IDLE
        _progress.value = 0f
        fullText = ""
        segments = emptyList()
        currentSegmentIndex = 0
    }

    /**
     * Called by FaceAttentionTracker integration to auto-pause/resume.
     */
    fun onAttentionChanged(isAttentive: Boolean) {
        if (!isAttentive && _playbackState.value == PlaybackState.PLAYING) {
            pause()
        } else if (isAttentive && _playbackState.value == PlaybackState.PAUSED) {
            resume()
        }
    }

    fun destroy() {
        tts.stop()
        tts.shutdown()
    }

    // -- Internal -----------------------------------------------------------------

    private fun speakCurrentSegment() {
        if (currentSegmentIndex >= segments.size) {
            _playbackState.value = PlaybackState.IDLE
            _progress.value = 1f
            return
        }

        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        }
        tts.speak(
            segments[currentSegmentIndex],
            TextToSpeech.QUEUE_FLUSH,
            params,
            "$UTTERANCE_PREFIX$currentSegmentIndex",
        )
    }

    private fun splitIntoSegments(text: String): List<String> {
        if (text.length <= SEGMENT_MAX_CHARS) return listOf(text)

        val result = mutableListOf<String>()
        val sentences = text.split(Regex("(?<=[.!?])\\s+"))
        val current = StringBuilder()

        for (sentence in sentences) {
            if (current.length + sentence.length > SEGMENT_MAX_CHARS && current.isNotEmpty()) {
                result.add(current.toString())
                current.clear()
            }
            if (current.isNotEmpty()) current.append(" ")
            current.append(sentence)
        }
        if (current.isNotEmpty()) result.add(current.toString())

        return result
    }

    private inner class ProgressListener : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            // progress based on segment index
            if (segments.isNotEmpty()) {
                _progress.value = currentSegmentIndex.toFloat() / segments.size
            }
        }

        override fun onDone(utteranceId: String?) {
            currentSegmentIndex++
            if (currentSegmentIndex < segments.size && _playbackState.value == PlaybackState.PLAYING) {
                speakCurrentSegment()
            } else {
                _playbackState.value = PlaybackState.IDLE
                _progress.value = 1f
            }
        }

        @Deprecated("Deprecated in API")
        override fun onError(utteranceId: String?) {
            Log.e(TAG, "TTS error for utterance: $utteranceId")
            _playbackState.value = PlaybackState.IDLE
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            Log.e(TAG, "TTS error $errorCode for utterance: $utteranceId")
            _playbackState.value = PlaybackState.IDLE
        }

        override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
            if (segments.isNotEmpty() && fullText.isNotEmpty()) {
                val segmentStart = segments.take(currentSegmentIndex).sumOf { it.length + 1 }
                val globalPos = segmentStart + start
                _progress.value = globalPos.toFloat() / fullText.length.coerceAtLeast(1)
            }
        }
    }
}
