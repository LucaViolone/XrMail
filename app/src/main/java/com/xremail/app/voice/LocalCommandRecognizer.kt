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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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
        STARTING,
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
     * Components we've already tried and seen fail in this app session.
     * Lets [ensureRecognizer] iterate through the candidate services
     * without infinitely rebuilding on the same broken one.
     */
    private val brokenComponents = mutableSetOf<String>()
    private var usingComponentLabel: String = "uninitialized"

    /**
     * Wall-clock millis at which we last called startListening. Used by
     * the health watchdog to detect "we started a round but the recognizer
     * never bound" — on Galaxy XR the broken on-device service silently
     * eats startListening with no onReadyForSpeech and no onError, so
     * we have to detect by absence.
     */
    @Volatile private var lastStartListeningAtMs: Long = 0L
    @Volatile private var roundReady: Boolean = false
    private var watchdogJob: Job? = null
    private val watchdogScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Suppress duplicate dispatches: SpeechRecognizer will sometimes
     * emit a partial transcript that matches a command pattern, then
     * immediately emit the same text in onResults. Without dedup we
     * fire the command twice (e.g. archive two emails).
     */
    private var lastDispatchedHash: Int = 0
    private var lastDispatchedAtMs: Long = 0L

    fun start() {
        if (_state.value == State.LISTENING || _state.value == State.STARTING) return
        _lastError.value = null
        runOnMainThread {
            try {
                ensureRecognizer()
                _state.value = State.STARTING
                startListeningRound()
                XrLog.i(TAG, "local-command loop starting (recognizer=$usingComponentLabel)")
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
                _state.value = State.STARTING
                startListeningRound()
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
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context) &&
            !ON_DEVICE_KNOWN_BROKEN

    private val systemRecognizerAvailable: Boolean
        get() = SpeechRecognizer.isRecognitionAvailable(context)

    /**
     * Build a recognizer, preferring known-working pinned services over
     * the platform on-device path.
     *
     * Why we don't trust [SpeechRecognizer.isOnDeviceRecognitionAvailable]:
     * on Galaxy XR the platform reports on-device available because
     * Android System Intelligence (com.google.android.as) advertises a
     * service component, but the component is NOT a real
     * [RecognitionService] — bind fails synchronously with error 10
     * inside the recognizer, never reaching our listener. The recognizer
     * sits silently dead, "LISTENING" is reported but no audio ever
     * reaches us. So we skip on-device entirely on this hardware (see
     * [ON_DEVICE_KNOWN_BROKEN]) and pin to a working RecognitionService
     * component instead.
     *
     * Iteration: components we've already tried and seen fail are
     * recorded in [brokenComponents] so a watchdog-triggered rebuild
     * tries the next candidate instead of ping-ponging on the broken one.
     */
    private fun ensureRecognizer() {
        if (recognizer != null) return

        val candidates = candidateComponents(context.packageManager)
            .filter { it.flattenToString() !in brokenComponents }

        val (built, label) = when {
            candidates.isNotEmpty() -> {
                val pinned = candidates.first()
                XrLog.d(TAG, "Creating SpeechRecognizer pinned to ${pinned.flattenToString()}")
                SpeechRecognizer.createSpeechRecognizer(context, pinned) to
                    "pinned:${pinned.packageName}"
            }
            onDeviceAvailable -> {
                XrLog.d(TAG, "Creating on-device SpeechRecognizer (low-latency, private)")
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context) to "on-device"
            }
            systemRecognizerAvailable -> {
                XrLog.d(TAG, "Creating default SpeechRecognizer")
                SpeechRecognizer.createSpeechRecognizer(context) to "default"
            }
            else -> throw IllegalStateException(
                "No usable SpeechRecognizer — local voice commands disabled. " +
                    "User can still summon Gemini Live by tapping the mic.",
            )
        }
        recognizer = built
        usingComponentLabel = label
        recognizer?.setRecognitionListener(listener)
    }

    private fun candidateComponents(pm: PackageManager): List<ComponentName> {
        val intent = Intent(RecognitionService.SERVICE_INTERFACE)
        val services = pm.queryIntentServices(intent, 0)
        // Preferred order: services that are known to actually bind on
        // Galaxy XR / Pixel / Samsung devices. com.google.android.tts
        // surprisingly hosts a RecognitionService that works when AiAi
        // doesn't, so it's the top pick on Galaxy XR.
        val preferred = listOf(
            "com.google.android.tts",
            "com.google.android.googlequicksearchbox",
            "com.samsung.android.bixby.agent",
            "com.samsung.android.bixby.service",
        )
        val ordered = mutableListOf<ComponentName>()
        for (pkg in preferred) {
            services.firstOrNull { it.serviceInfo.packageName == pkg }?.let {
                ordered += ComponentName(it.serviceInfo.packageName, it.serviceInfo.name)
            }
        }
        // Append any other discovered services as last resorts.
        services.forEach {
            val cn = ComponentName(it.serviceInfo.packageName, it.serviceInfo.name)
            if (cn !in ordered) ordered += cn
        }
        return ordered
    }

    private fun startListeningRound() {
        // Only the first round (cold-start STARTING) needs the bind
        // health-check watchdog. Once we've seen a successful
        // onReadyForSpeech and promoted to LISTENING, future rounds
        // restart instantly and a slow callback from a re-arm round is
        // a false positive that we don't want to act on.
        val firstColdStart = _state.value == State.STARTING && !roundReady
        roundReady = false
        lastStartListeningAtMs = System.currentTimeMillis()
        if (firstColdStart) scheduleHealthWatchdog() else watchdogJob?.cancel()
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
        override fun onReadyForSpeech(params: Bundle?) {
            roundReady = true
            watchdogJob?.cancel()
            if (_state.value == State.STARTING) {
                _state.value = State.LISTENING
                XrLog.i(TAG, "recognizer onReadyForSpeech -> LISTENING (component=$usingComponentLabel)")
            } else {
                XrLog.d(TAG, "recognizer onReadyForSpeech (round restart, state=${_state.value})")
            }
        }
        override fun onBeginningOfSpeech() {
            XrLog.d(TAG, "onBeginningOfSpeech (user is talking)")
        }
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {
            XrLog.d(TAG, "onEndOfSpeech (recognizer detected silence)")
        }
        override fun onEvent(eventType: Int, params: Bundle?) {
            XrLog.v(TAG, "onEvent type=$eventType")
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val transcript = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.takeIf { it.isNotBlank() }
            if (transcript != null) {
                XrLog.d(TAG, "onPartialResults: \"$transcript\"")
            }
            handleTranscript(partialResults, partial = true)
        }

        override fun onResults(results: Bundle?) {
            val transcript = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.takeIf { it.isNotBlank() }
            if (transcript != null) {
                XrLog.i(TAG, "onResults FINAL: \"$transcript\"")
            } else {
                XrLog.d(TAG, "onResults FINAL with empty transcript")
            }
            handleTranscript(results, partial = false)
            consecutiveErrors = 0
            if (_state.value == State.LISTENING || _state.value == State.STARTING) {
                startListeningRound()
            }
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
                XrLog.w(TAG, "recognizer onError=$name (consec=$consecutiveErrors component=$usingComponentLabel)")
                consecutiveErrors++
                _lastError.value = name
            } else {
                XrLog.v(TAG, "recognizer onError=$name (benign — re-arming)")
            }
            // If the component never bound (no onReadyForSpeech ever
            // fired before erroring out), it's broken on this device.
            // Mark it broken and rebuild with the next candidate.
            val didBind = roundReady
            if (!didBind && (error == SpeechRecognizer.ERROR_CLIENT ||
                    error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)) {
                markCurrentComponentBrokenAndRebuild("error=$name before bind")
                return
            }
            if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                XrLog.e(TAG, "local-command loop giving up after $consecutiveErrors errors ($name)")
                _state.value = State.ERROR
                return
            }
            if (_state.value == State.LISTENING || _state.value == State.STARTING) {
                startListeningRound()
            }
        }
    }

    /**
     * Schedules a one-shot watchdog that fires [HEALTH_TIMEOUT_MS] after
     * [startListeningRound]. If by then we still haven't seen
     * [onReadyForSpeech] for this round, the bound recognition service is
     * silently dead (the Galaxy XR AiAi failure mode) — we mark the
     * component broken and rebuild with the next candidate.
     */
    private fun scheduleHealthWatchdog() {
        watchdogJob?.cancel()
        val roundStart = lastStartListeningAtMs
        watchdogJob = watchdogScope.launch {
            delay(HEALTH_TIMEOUT_MS)
            // Skip if a newer round superseded us (state changed) or the
            // round bound successfully.
            if (lastStartListeningAtMs != roundStart) return@launch
            if (roundReady) return@launch
            if (_state.value != State.STARTING) return@launch
            runOnMainThread {
                markCurrentComponentBrokenAndRebuild(
                    "no onReadyForSpeech within ${HEALTH_TIMEOUT_MS}ms",
                )
            }
        }
    }

    private fun markCurrentComponentBrokenAndRebuild(reason: String) {
        val brokenLabel = usingComponentLabel
        XrLog.w(TAG, "marking $brokenLabel BROKEN ($reason); trying next recognizer")
        if (brokenLabel.startsWith("pinned:")) {
            // Find the actual ComponentName flatten string we used so
            // candidateComponents() filters it out next time.
            val pkg = brokenLabel.removePrefix("pinned:")
            val pm = context.packageManager
            val intent = Intent(RecognitionService.SERVICE_INTERFACE)
            pm.queryIntentServices(intent, 0).firstOrNull {
                it.serviceInfo.packageName == pkg
            }?.let {
                brokenComponents += ComponentName(
                    it.serviceInfo.packageName, it.serviceInfo.name,
                ).flattenToString()
            }
        }
        try {
            recognizer?.cancel()
            recognizer?.destroy()
        } catch (_: Throwable) { /* best effort */ }
        recognizer = null
        usingComponentLabel = "uninitialized"
        roundReady = false
        try {
            ensureRecognizer()
            startListeningRound()
            // Stay in STARTING; onReadyForSpeech will promote to LISTENING.
        } catch (t: Throwable) {
            XrLog.e(TAG, "no working recognizer left after rebuild — local voice disabled", t)
            _lastError.value = "no working recognizer (${t.message ?: "unknown"})"
            _state.value = State.ERROR
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

    companion object {
        private const val TAG = "LocalCmdRecog"
        private const val MAX_CONSECUTIVE_ERRORS = 6
        // Galaxy XR's on-device recognition advertised by Android System
        // Intelligence (com.google.android.as) is registered but not a
        // real RecognitionService — bind fails inside the framework with
        // error 10 and never surfaces to onError. Skipping the on-device
        // path entirely and pinning to com.google.android.tts works.
        // Flip this to false once Samsung ships a working on-device
        // recognizer.
        private const val ON_DEVICE_KNOWN_BROKEN = true

        // Maximum time to wait for the FIRST recognizer round to call
        // onReadyForSpeech. On Galaxy XR the Google TTS recognizer cold-
        // starts in ~1.9 s (loads the SODA on-device language pack), so
        // 1500 ms was racing against legit successful starts. 4000 ms
        // gives the slow service room while still pivoting before the
        // user reads the "Voice starting…" label twice.
        private const val HEALTH_TIMEOUT_MS = 4_000L

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
