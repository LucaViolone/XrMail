package com.xremail.app.voice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.xremail.app.util.XrLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Always-on wake-word detector that listens for "hey gemini" / "ok gemini" /
 * a few near-misses, and fires [onWakeWord] when one is heard.
 *
 * Why we own this instead of relying on Gemini Live's continuous mic:
 *
 *   The Live SDK's `startAudioConversation` opens the mic the moment we
 *   call it and keeps streaming until `stopAudioConversation`. That means
 *   anything in the room — coworkers talking, a podcast, the user thinking
 *   out loud — gets shipped to the model and may provoke a response. The
 *   user explicitly asked for a wake-word so the model stays asleep until
 *   it's actually addressed.
 *
 * Implementation notes:
 *
 * - We use the **on-device** SpeechRecognizer when available (API 31+). It
 *   matches partial results within ~200 ms of speech onset and never leaves
 *   the device, so wake-detection is private even when the user hasn't yet
 *   triggered the cloud pipeline.
 * - Recognition rounds last ~3-5 s before the recognizer fires `onResults`
 *   or `onError`. We restart immediately on either, which produces a
 *   continuous listen loop with a tiny gap (~50 ms) between rounds. That
 *   gap is short enough that a wake word straddling the boundary still
 *   gets picked up on the next round.
 * - Some errors are fatal-ish (NO_MATCH, SPEECH_TIMEOUT) and just mean
 *   "nothing said this round" — restart silently. Others (CLIENT,
 *   INSUFFICIENT_PERMISSIONS, RECOGNIZER_BUSY) we log + back off.
 * - We pause the loop while Gemini Live owns the mic (the SDK can't share
 *   the input stream with another AudioRecord client cleanly on Galaxy
 *   XR), then resume once Live shuts back down.
 */
class WakeWordDetector(
    private val context: Context,
    private val onWakeWord: (matched: String) -> Unit,
) {

    enum class State {
        /** Detector hasn't been started yet, or has been explicitly stopped. */
        IDLE,
        /** Listening for the wake phrase. */
        LISTENING,
        /** Paused (e.g. Gemini Live currently owns the mic). */
        PAUSED,
        /** Last attempt to start failed permanently — see [lastError]. */
        ERROR,
    }

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private var recognizer: SpeechRecognizer? = null
    private var consecutiveErrors = 0

    /**
     * Restart the recognition loop. Safe to call from any thread; under
     * the hood we marshal back to the main thread because SpeechRecognizer
     * is annoyingly main-thread only.
     */
    fun start() {
        if (_state.value == State.LISTENING) return
        _lastError.value = null
        runOnMainThread {
            try {
                ensureRecognizer()
                startListeningRound()
                _state.value = State.LISTENING
                XrLog.i(TAG, "wake-word loop started (on-device=$onDeviceAvailable)")
            } catch (t: Throwable) {
                XrLog.e(TAG, "Could not start wake-word recognizer", t)
                _lastError.value = t.message ?: t::class.simpleName
                _state.value = State.ERROR
            }
        }
    }

    /**
     * Pause without tearing down. Use when Gemini Live is about to take the
     * mic — calling [resume] later picks the loop back up without paying
     * the recognizer-construction cost.
     */
    fun pause() {
        if (_state.value != State.LISTENING) return
        runOnMainThread {
            try {
                recognizer?.stopListening()
                recognizer?.cancel()
            } catch (t: Throwable) {
                XrLog.w(TAG, "pause: stopListening threw — ignoring", t)
            }
            _state.value = State.PAUSED
            XrLog.i(TAG, "wake-word loop PAUSED (Gemini owns mic)")
        }
    }

    fun resume() {
        if (_state.value != State.PAUSED) return
        runOnMainThread {
            try {
                startListeningRound()
                _state.value = State.LISTENING
                XrLog.i(TAG, "wake-word loop RESUMED")
            } catch (t: Throwable) {
                XrLog.e(TAG, "Could not resume wake-word recognizer", t)
                _lastError.value = t.message ?: t::class.simpleName
                _state.value = State.ERROR
            }
        }
    }

    fun stop() {
        runOnMainThread {
            try {
                recognizer?.cancel()
                recognizer?.destroy()
            } catch (t: Throwable) {
                XrLog.w(TAG, "stop: destroy threw — ignoring", t)
            }
            recognizer = null
            _state.value = State.IDLE
            XrLog.i(TAG, "wake-word loop stopped")
        }
    }

    // ---------------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------------

    private val onDeviceAvailable: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    /**
     * True when SOME (any) recognition service is installed on this device.
     * On Galaxy XR alpha12 the on-device variant is missing on some images
     * and the system service is missing on others, so we have to probe
     * both. If neither is present the wake-word path silently disables —
     * MainActivity falls back to gesture/voice-toggle activation.
     */
    private val systemRecognizerAvailable: Boolean
        get() = SpeechRecognizer.isRecognitionAvailable(context)

    private fun ensureRecognizer() {
        if (recognizer != null) return
        // Defensive: if neither flavor is available, fail fast with a
        // human-readable error instead of letting the SDK throw a generic
        // ServiceConnectionLeaked / NPE later. The exception message
        // surfaces through `lastError` so it's visible in logcat.
        if (!systemRecognizerAvailable && !onDeviceAvailable) {
            // Probe for a Samsung-shipped recognizer that doesn't advertise
            // through the standard intent — ResolveInfo skips packages with
            // <queries> restrictions on API 30+. If we can't find ANY
            // RecognitionService on the system, give up loudly so the
            // caller can fall back to manual activation.
            val anyService = context.packageManager
                .queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
            if (anyService.isEmpty()) {
                throw IllegalStateException(
                    "No SpeechRecognizer service installed on this device — " +
                        "wake-word disabled (use gesture / pinch on the voice " +
                        "indicator to summon Gemini instead).",
                )
            }
        }
        recognizer = if (onDeviceAvailable) {
            XrLog.d(TAG, "Creating on-device SpeechRecognizer")
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            // Try to pin a specific provider when the manifest <queries>
            // restrictions hide the default. Empirically Galaxy XR ships
            // `com.samsung.android.bixby.agent` on some images and Google
            // Assistant on others — both implement RecognitionService.
            val pinned = pickRecognizerComponent(context.packageManager)
            if (pinned != null) {
                XrLog.d(TAG, "Creating SpeechRecognizer pinned to $pinned")
                SpeechRecognizer.createSpeechRecognizer(context, pinned)
            } else {
                XrLog.d(TAG, "Creating default SpeechRecognizer")
                SpeechRecognizer.createSpeechRecognizer(context)
            }
        }
        recognizer?.setRecognitionListener(listener)
    }

    private fun startListeningRound() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, java.util.Locale.getDefault())
            // Limit to a single result so the recognizer doesn't try to
            // build alternatives — we only need to know whether the wake
            // phrase appeared at all.
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // Tighten timeouts so empty rounds end quickly and the loop
            // restarts. Defaults are ~1.5s of speech-end silence which
            // makes the wake-word feel laggy.
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                700L,
            )
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                500L,
            )
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS,
                250L,
            )
        }
        recognizer?.startListening(intent)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            // Partial results land within ~200ms of speech onset — checking
            // here (rather than waiting for onResults) is what makes the
            // wake word feel instant instead of "say it then wait two
            // seconds". We DO check both partial and final so a wake word
            // recognized only at the end of a longer utterance still fires.
            checkResultsForWakeWord(partialResults, partial = true)
        }

        override fun onResults(results: Bundle?) {
            val matched = checkResultsForWakeWord(results, partial = false)
            // Always restart the loop — wake-word detection is continuous.
            // Even if we matched, the surrounding code will pause us via
            // pause() before opening Gemini's mic, so the immediate
            // restart here is harmless.
            consecutiveErrors = 0
            if (_state.value == State.LISTENING) startListeningRound()
            if (matched != null) {
                XrLog.i(TAG, "WAKE matched=\"$matched\"")
            }
        }

        override fun onError(error: Int) {
            // Map the opaque ints to something we can grep for.
            val name = when (error) {
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "NETWORK_TIMEOUT"
                SpeechRecognizer.ERROR_NETWORK -> "NETWORK"
                SpeechRecognizer.ERROR_AUDIO -> "AUDIO"
                SpeechRecognizer.ERROR_SERVER -> "SERVER"
                SpeechRecognizer.ERROR_CLIENT -> "CLIENT"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "SPEECH_TIMEOUT"
                SpeechRecognizer.ERROR_NO_MATCH -> "NO_MATCH"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "RECOGNIZER_BUSY"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "INSUFFICIENT_PERMISSIONS"
                else -> "UNKNOWN($error)"
            }

            // NO_MATCH and SPEECH_TIMEOUT are the normal "nothing said
            // this round" signals — restart silently. Anything else is
            // worth logging.
            val benign = error == SpeechRecognizer.ERROR_NO_MATCH ||
                error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT

            if (!benign) {
                XrLog.w(TAG, "recognizer onError=$name (consec=$consecutiveErrors)")
                consecutiveErrors++
                _lastError.value = name
            }

            // After enough back-to-back unexplained errors, stop hammering
            // the recognizer — typically means the underlying service
            // crashed. Surface ERROR state so the caller can fall back to
            // a manual activation path.
            if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                XrLog.e(
                    TAG,
                    "wake-word loop giving up after $consecutiveErrors errors " +
                        "($name) — recognizer may be unavailable on this device",
                )
                _state.value = State.ERROR
                return
            }

            if (_state.value == State.LISTENING) startListeningRound()
        }
    }

    private fun checkResultsForWakeWord(bundle: Bundle?, partial: Boolean): String? {
        val list = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return null
        for (raw in list) {
            val phrase = raw.lowercase().trim()
            for (token in WAKE_TOKENS) {
                if (phrase.contains(token)) {
                    if (!partial) {
                        XrLog.i(TAG, "wake-word matched (final): \"$raw\" via \"$token\"")
                    } else {
                        XrLog.d(TAG, "wake-word matched (partial): \"$raw\" via \"$token\"")
                    }
                    onWakeWord(token)
                    return token
                }
            }
        }
        return null
    }

    private fun runOnMainThread(action: () -> Unit) {
        // SpeechRecognizer's APIs all assert main-thread; running from a
        // ViewModel coroutine on Default would crash with `Calling startListening
        // from a non-main thread`.
        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            action()
        } else {
            mainHandler.post(action)
        }
    }

    private fun pickRecognizerComponent(pm: PackageManager): ComponentName? {
        val intent = Intent(RecognitionService.SERVICE_INTERFACE)
        val services = pm.queryIntentServices(intent, 0)
        // Prefer Google's first (lowest-latency partial results), then
        // Samsung's, then anything else. If everything's missing we
        // return null and fall back to the SDK's default lookup.
        val preferred = listOf(
            "com.google.android.googlequicksearchbox",
            "com.google.android.tts",
            "com.samsung.android.bixby.agent",
            "com.samsung.android.bixby.service",
        )
        for (pkg in preferred) {
            val match = services.firstOrNull { it.serviceInfo.packageName == pkg }
            if (match != null) {
                return ComponentName(match.serviceInfo.packageName, match.serviceInfo.name)
            }
        }
        val anyMatch = services.firstOrNull() ?: return null
        return ComponentName(anyMatch.serviceInfo.packageName, anyMatch.serviceInfo.name)
    }

    companion object {
        private const val TAG = "WakeWord"
        private const val MAX_CONSECUTIVE_ERRORS = 6

        /**
         * Lowercased substrings any of which trigger the wake action. We
         * deliberately include common ASR mishears ("hey gem", "hey
         * jiminy") so the user doesn't have to enunciate. The trade-off
         * is more false positives — but a stray activation just opens
         * the mic for a turn, costing one extra Gemini round-trip.
         */
        private val WAKE_TOKENS = listOf(
            "hey gemini",
            "hi gemini",
            "ok gemini",
            "okay gemini",
            "hey jiminy",
            "hey gem",
            "hey gemma",
            "gemini",
        )
    }
}
