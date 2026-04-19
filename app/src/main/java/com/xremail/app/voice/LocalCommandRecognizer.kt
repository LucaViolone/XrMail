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
 * Always-on, on-device speech recognizer that:
 *
 *   1. Listens continuously (no wake word UX needed — the user can
 *      address the inbox and have it react in <500 ms).
 *   2. Strips the wake prefix, parses the rest with [CommandGrammar],
 *      and routes:
 *        - matched commands  → [onLocalCommand]  (instant, no cloud)
 *        - addressed but unmatched → [onEscalateToGemini]  (text remainder
 *          of the utterance is forwarded so Gemini doesn't re-listen)
 *        - utterances NOT addressed to us → silently dropped, NEVER
 *          shipped to the cloud (privacy: a coworker saying "open it"
 *          should not control the inbox or hit any server).
 *
 * This replaces the previous [WakeWordDetector] + Gemini-Live audio
 * round-trip for common commands. The user's perceived latency for
 * "Hey Gemini, archive" goes from ~3-5 s (wake → SDK warmup → re-speak
 * → cloud → function call → confirm) to ~300-500 ms (wake + command
 * captured in one round, parsed locally, dispatched immediately).
 *
 * ### Why we re-do all the recognizer plumbing here
 *
 * [WakeWordDetector] only gives you a "wake fired" event — by the time
 * we want to handle the command, the recognizer has already discarded
 * the rest of the utterance to start a fresh round. To capture the
 * whole "hey gemini ARCHIVE" in one transcript we need our own
 * recognizer instance with slightly relaxed silence timeouts so the
 * round survives a brief pause between the wake phrase and the
 * command.
 */
class LocalCommandRecognizer(
    private val context: Context,
    private val onLocalCommand: (EmailCommandTool.Command) -> Unit,
    private val onEscalateToGemini: (remainder: String) -> Unit,
) {

    enum class State {
        IDLE,
        LISTENING,
        PAUSED,
        ERROR,
    }

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    /**
     * Most recent (final) transcript captured by the recognizer, for
     * UI overlays (caption track, debugging banner). Updated on every
     * onResults regardless of whether the parse matched.
     */
    private val _lastTranscript = MutableStateFlow("")
    val lastTranscript: StateFlow<String> = _lastTranscript.asStateFlow()

    private var recognizer: SpeechRecognizer? = null
    private var consecutiveErrors = 0

    /**
     * Suppress duplicate dispatches: SpeechRecognizer will sometimes
     * emit a partial transcript that matches a command pattern, then
     * immediately emit the same text in onResults. Without dedup we
     * fire the command twice (e.g. archive two emails).
     */
    private var lastDispatchedHash: Int = 0
    private var lastDispatchedAtMs: Long = 0L

    fun start() {
        if (_state.value == State.LISTENING) return
        _lastError.value = null
        runOnMainThread {
            try {
                ensureRecognizer()
                startListeningRound()
                _state.value = State.LISTENING
                XrLog.i(TAG, "local-command loop started (on-device=$onDeviceAvailable)")
            } catch (t: Throwable) {
                XrLog.e(TAG, "Could not start local-command recognizer", t)
                _lastError.value = t.message ?: t::class.simpleName
                _state.value = State.ERROR
            }
        }
    }

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
            XrLog.i(TAG, "local-command loop PAUSED")
        }
    }

    fun resume() {
        if (_state.value != State.PAUSED) return
        runOnMainThread {
            try {
                startListeningRound()
                _state.value = State.LISTENING
                XrLog.i(TAG, "local-command loop RESUMED")
            } catch (t: Throwable) {
                XrLog.e(TAG, "Could not resume local-command recognizer", t)
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
            XrLog.i(TAG, "local-command loop stopped")
        }
    }

    // ---------------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------------

    private val onDeviceAvailable: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    private val systemRecognizerAvailable: Boolean
        get() = SpeechRecognizer.isRecognitionAvailable(context)

    private fun ensureRecognizer() {
        if (recognizer != null) return
        if (!systemRecognizerAvailable && !onDeviceAvailable) {
            val anyService = context.packageManager
                .queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
            if (anyService.isEmpty()) {
                throw IllegalStateException(
                    "No SpeechRecognizer service installed — local voice commands disabled. " +
                        "User can still summon Gemini Live via gesture.",
                )
            }
        }
        recognizer = if (onDeviceAvailable) {
            XrLog.d(TAG, "Creating on-device SpeechRecognizer (low-latency, private)")
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
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
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // Slightly more generous than [WakeWordDetector] because we
            // need to capture the WHOLE utterance ("hey gemini archive
            // this email") in one round, not just the wake phrase. The
            // user often pauses between the wake and the command, and
            // we don't want that pause to end the round prematurely.
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                COMPLETE_SILENCE_MS,
            )
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                POSSIBLY_COMPLETE_SILENCE_MS,
            )
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS,
                MINIMUM_LENGTH_MS,
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
            // We try to dispatch on partials so the user perceives an
            // "instant" reaction — for short phrases like "next" or
            // "archive" the partial fires within ~150-200 ms of speech
            // onset, well before onResults arrives ~500 ms later. We
            // only act on partials that look complete (pattern matches
            // the WHOLE remainder, not just a prefix); see
            // [CommandGrammar] for the regex anchors.
            handleTranscript(partialResults, partial = true)
        }

        override fun onResults(results: Bundle?) {
            handleTranscript(results, partial = false)
            consecutiveErrors = 0
            if (_state.value == State.LISTENING) startListeningRound()
        }

        override fun onError(error: Int) {
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
            val benign = error == SpeechRecognizer.ERROR_NO_MATCH ||
                error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
            if (!benign) {
                XrLog.w(TAG, "recognizer onError=$name (consec=$consecutiveErrors)")
                consecutiveErrors++
                _lastError.value = name
            }
            if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                XrLog.e(TAG, "local-command loop giving up after $consecutiveErrors errors ($name)")
                _state.value = State.ERROR
                return
            }
            if (_state.value == State.LISTENING) startListeningRound()
        }
    }

    private fun handleTranscript(bundle: Bundle?, partial: Boolean) {
        val list = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return
        val transcript = list.firstOrNull()?.takeIf { it.isNotBlank() } ?: return
        if (!partial) _lastTranscript.value = transcript

        val result = CommandGrammar.parse(transcript)
        when (result) {
            CommandGrammar.Result.NotAddressed -> {
                if (!partial) {
                    XrLog.v(TAG, "not addressed (no wake prefix): \"$transcript\"")
                }
            }
            is CommandGrammar.Result.Dispatch -> {
                if (shouldDispatch(result.command, transcript)) {
                    XrLog.i(
                        TAG,
                        "dispatch (${if (partial) "partial" else "final"}) " +
                            "\"$transcript\" -> ${result.command}",
                    )
                    onLocalCommand(result.command)
                }
            }
            is CommandGrammar.Result.Escalate -> {
                // Only escalate to Gemini on the FINAL transcript —
                // partials are usually mid-utterance fragments that
                // would mislead the model ("hey gemini" with no
                // command yet). Waiting for the final ~500 ms more
                // costs basically nothing here because the slow path
                // (Gemini round-trip) is already 1-2 s.
                if (!partial) {
                    XrLog.i(TAG, "escalate to Gemini: remainder=\"${result.remainder}\"")
                    onEscalateToGemini(result.remainder)
                }
            }
        }
    }

    /**
     * Suppress same-command repeats inside a tight window so we don't
     * fire twice from "partial then final" deliveries of the same
     * transcript. Different commands within the same window are
     * allowed (e.g. user says "archive" then immediately "next" — both
     * should fire).
     */
    private fun shouldDispatch(command: EmailCommandTool.Command, transcript: String): Boolean {
        val now = System.currentTimeMillis()
        val hash = command.hashCode() xor transcript.hashCode()
        val sinceLast = now - lastDispatchedAtMs
        if (hash == lastDispatchedHash && sinceLast < DUPLICATE_DISPATCH_WINDOW_MS) {
            XrLog.v(TAG, "  dedup $command within ${sinceLast}ms — skipping")
            return false
        }
        lastDispatchedHash = hash
        lastDispatchedAtMs = now
        return true
    }

    private fun runOnMainThread(action: () -> Unit) {
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
        private const val TAG = "LocalCmdRecog"
        private const val MAX_CONSECUTIVE_ERRORS = 6

        // Silence-window tuning. Wider than [WakeWordDetector] because
        // we need the WHOLE utterance ("hey gemini archive this") in
        // one round; the user often pauses ~300 ms between the wake
        // phrase and the command. Tighter than the Android defaults
        // (~1.5 s) because long silences feel sluggish.
        private const val COMPLETE_SILENCE_MS = 1_200L
        private const val POSSIBLY_COMPLETE_SILENCE_MS = 800L
        private const val MINIMUM_LENGTH_MS = 400L

        // Same-command repeats inside this window are dropped. Long
        // enough to swallow the partial→final double-fire (~500 ms),
        // short enough that the user can deliberately say "archive,
        // archive" to chain two commands.
        private const val DUPLICATE_DISPATCH_WINDOW_MS = 1_200L
    }
}
