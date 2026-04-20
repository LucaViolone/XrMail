package com.xremail.app.voice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.xremail.app.util.XrLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/**
 * Explicit push-to-talk voice session.
 *
 * Tap the chip: [toggle] opens one [SpeechRecognizer] round. Tap again
 * while listening and [stop] flushes the transcript so anything already
 * spoken still processes. Tap during SPEAKING or THINKING and the
 * in-flight TTS/Gemini call is cancelled and a fresh round begins — the
 * "interrupt via button" replacement for the previous always-on
 * barge-in mic (which produced constant recognizer-startup beeps and
 * false interruptions from TTS echo on Galaxy XR).
 *
 * What happens with the transcript:
 *  1. Feed through [CommandGrammar.parse] for instant local matching.
 *     Common commands ("archive", "next", "collapse") dispatch in
 *     ~50ms with no cloud round-trip.
 *  2. Miss → forward to [GeminiTextService.reply] with the current
 *     [contextProvider] inbox snapshot. Gemini returns spoken text plus
 *     any function calls; we dispatch the calls and speak the text via
 *     [TTSManager].
 *
 * This replaces the flaky [GeminiLiveManager] streaming path — on Galaxy
 * XR the Live API stalls indefinitely in its "Listening" state because
 * its SDK-managed VAD never flips "user done" and its audio output gets
 * routed to a stream the headset doesn't play. One-shot SpeechRecognizer
 * works reliably on the exact same hardware (proven in the user's EVA
 * devfest-2026 project on the same Galaxy XR).
 *
 * ### Recognizer component pinning
 *
 * Galaxy XR's default on-device recognizer (AiAi / com.google.android.as)
 * advertises a [RecognitionService] component that doesn't actually bind.
 * Pinning to `com.google.android.tts` (which also hosts a working
 * recognition service) avoids this failure mode. Same approach as
 * [LocalCommandRecognizer.ensureRecognizer].
 */
class PushToTalkSession(
    private val context: Context,
    private val tts: TTSManager,
    private val gemini: GeminiTextService,
    private val dispatchCommand: (EmailCommandTool.Command) -> Unit,
    private val contextProvider: () -> String,
) {

    enum class State {
        /** Mic closed, waiting for user tap. */
        IDLE,

        /** Recognizer is open, capturing audio. */
        LISTENING,

        /** Transcript received; waiting on Gemini text reply. */
        THINKING,

        /** Reply received; TTS is speaking it. */
        SPEAKING,

        /** Last round ended with an error — tap to retry. */
        ERROR,
    }

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Internal setter that logs every transition so we can scan logcat and
     * reconstruct the turn even when outputs are missing. Keeps the chip
     * state flow and the log line in sync — one source of truth. */
    private fun setState(next: State, reason: String) {
        val prev = _state.value
        if (prev != next) {
            XrLog.i(TAG, "state $prev -> $next ($reason)")
        }
        _state.value = next
    }

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    /** Most recent final transcript — useful for captions / debugging. */
    private val _lastTranscript = MutableStateFlow("")
    val lastTranscript: StateFlow<String> = _lastTranscript.asStateFlow()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var recognizer: SpeechRecognizer? = null
    private var usingComponentLabel: String = "uninitialized"
    private var replyJob: Job? = null
    private var speakWatcherJob: Job? = null

    /** True between [start] and the onResults/onError that ends the round. */
    @Volatile private var roundActive: Boolean = false

    // ---------------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------------

    /**
     * Toggle entry point wired to the VoicePrompt chip. Pure tap-to-talk:
     * every tap begins a new recognizer round, no matter what was going
     * on before.
     *
     * Semantics by current state:
     * - IDLE / ERROR          → start a new round.
     * - LISTENING             → stop the round (flush transcript through
     *                           onResults so anything already spoken
     *                           still gets processed). Second-tap to
     *                           commit, matching the legacy contract.
     * - SPEAKING              → interrupt TTS and start a new round.
     *                           This is the replacement for the old
     *                           always-on barge-in mic: the user taps
     *                           to interrupt instead of speaking over
     *                           the agent, which kills the beeping and
     *                           the false-positive cut-offs.
     * - THINKING              → cancel the in-flight Gemini reply and
     *                           start a new round. Otherwise a slow
     *                           network call would leave the chip
     *                           unresponsive.
     *
     * The "always-on listening" approach (barge-in mic opened during
     * TTS, auto-restarted after every turn) was tried and produced two
     * unusable failure modes on Galaxy XR: (1) the on-device
     * SpeechRecognizer emits a startup beep every time it opens, so the
     * agent audibly beeped on every TTS start and every turn boundary;
     * (2) its VAD fires on TTS echo and ambient noise, triggering
     * spurious interruptions mid-sentence. Explicit tap is the only
     * interaction model that's actually reliable here.
     */
    fun toggle() {
        XrLog.v(TAG, "toggle() state=${_state.value}")
        when (_state.value) {
            State.LISTENING -> stop()
            State.IDLE,
            State.ERROR,
            State.SPEAKING,
            State.THINKING -> start()
        }
    }

    fun start() {
        runOnMainThread {
            if (_state.value == State.LISTENING) {
                XrLog.v(TAG, "start() ignored — already listening")
                return@runOnMainThread
            }
            // Cancel any in-flight reply / speak so the new round starts
            // clean. This is what makes a tap during SPEAKING/THINKING
            // behave as "interrupt and listen" — tts.stop() kills the
            // current utterance, replyJob.cancel() drops the pending
            // Gemini response, and the old recognizer round (if any)
            // is cancelled below.
            replyJob?.cancel()
            speakWatcherJob?.cancel()
            tts.stop()
            try {
                if (roundActive) recognizer?.cancel()
            } catch (t: Throwable) {
                XrLog.w(TAG, "start: recognizer.cancel threw", t)
            }
            _lastError.value = null
            try {
                ensureRecognizer()
                roundActive = true
                setState(State.LISTENING, "start()")
                recognizer?.startListening(buildRecognizeIntent())
                XrLog.i(TAG, "PTT round started (recognizer=$usingComponentLabel)")
            } catch (t: Throwable) {
                XrLog.e(TAG, "PTT start failed", t)
                _lastError.value = t.message ?: t::class.simpleName
                setState(State.ERROR, "start() threw")
                roundActive = false
            }
        }
    }

    fun stop() {
        runOnMainThread {
            if (_state.value != State.LISTENING) {
                XrLog.v(TAG, "stop() ignored — state=${_state.value}")
                return@runOnMainThread
            }
            try {
                // stopListening flushes buffered audio and fires onResults.
                // Unlike cancel(), it does NOT discard the utterance.
                recognizer?.stopListening()
                XrLog.i(TAG, "PTT stop requested — waiting for onResults")
            } catch (t: Throwable) {
                XrLog.w(TAG, "stopListening threw: ${t.message}", t)
                roundActive = false
                setState(State.IDLE, "stop() threw")
            }
        }
    }

    fun shutdown() {
        runOnMainThread {
            replyJob?.cancel()
            speakWatcherJob?.cancel()
            try {
                recognizer?.cancel()
                recognizer?.destroy()
            } catch (t: Throwable) {
                XrLog.w(TAG, "shutdown: destroy threw — ignoring", t)
            }
            recognizer = null
            roundActive = false
            setState(State.IDLE, "shutdown()")
        }
        scope.cancel()
    }

    // ---------------------------------------------------------------------------
    // Recognizer wiring
    // ---------------------------------------------------------------------------

    private fun ensureRecognizer() {
        if (recognizer != null) return
        val pm = context.packageManager
        val candidates = candidateComponents(pm)
        val (built, label) = when {
            candidates.isNotEmpty() -> {
                val pinned = candidates.first()
                SpeechRecognizer.createSpeechRecognizer(context, pinned) to
                    "pinned:${pinned.packageName}"
            }
            SpeechRecognizer.isRecognitionAvailable(context) -> {
                SpeechRecognizer.createSpeechRecognizer(context) to "default"
            }
            else -> throw IllegalStateException(
                "No SpeechRecognizer available on this device.",
            )
        }
        recognizer = built
        usingComponentLabel = label
        recognizer?.setRecognitionListener(listener)
    }

    private fun candidateComponents(pm: PackageManager): List<ComponentName> {
        val services = pm.queryIntentServices(
            Intent(RecognitionService.SERVICE_INTERFACE), 0,
        )
        // Order matters: first candidate that resolves is used. On Galaxy XR
        // com.google.android.tts hosts a working RecognitionService while
        // the "default" on-device one (AiAi) does not. Pixels/Samsung
        // phones usually route through googlequicksearchbox.
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
        services.forEach {
            val cn = ComponentName(it.serviceInfo.packageName, it.serviceInfo.name)
            if (cn !in ordered) ordered += cn
        }
        return ordered
    }

    private fun buildRecognizeIntent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // Generous silence windows so the user has time to think mid
            // utterance without the recognizer ending the round early.
            // They can always tap-to-stop for an immediate end.
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                2500L,
            )
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                1800L,
            )
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS,
                800L,
            )
        }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            XrLog.d(TAG, "onReadyForSpeech ($usingComponentLabel)")
        }

        override fun onBeginningOfSpeech() {
            XrLog.d(TAG, "onBeginningOfSpeech state=${_state.value}")
        }

        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onEndOfSpeech() {
            XrLog.d(TAG, "onEndOfSpeech")
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val transcript = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            if (transcript != null) _lastTranscript.value = transcript
        }

        override fun onResults(results: Bundle?) {
            val transcript = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()
                .orEmpty()
            roundActive = false
            if (transcript.isBlank()) {
                XrLog.w(TAG, "onResults empty transcript")
                _lastError.value = "Didn't catch that — tap and try again."
                setState(State.IDLE, "empty transcript")
                return
            }
            _lastTranscript.value = transcript
            XrLog.i(TAG, "onResults FINAL (len=${transcript.length}): \"$transcript\"")
            handleTranscript(transcript)
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
            roundActive = false
            val benign = error == SpeechRecognizer.ERROR_NO_MATCH ||
                error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
            if (benign) {
                XrLog.i(TAG, "recognizer onError=$name (benign)")
                _lastError.value = "Didn't catch that — tap and try again."
                setState(State.IDLE, "recognizer $name")
            } else {
                XrLog.w(TAG, "recognizer onError=$name")
                _lastError.value = name
                setState(State.ERROR, "recognizer $name")
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Transcript routing
    // ---------------------------------------------------------------------------

    private fun handleTranscript(transcript: String) {
        // CommandGrammar gates on a wake prefix because its other caller
        // (always-on LocalCommandRecognizer) needs to ignore overheard
        // speech. Push-to-talk is explicitly opt-in per tap, so we prepend
        // a synthetic wake token to satisfy the grammar and let every
        // transcript through as a potential command.
        val withWake = "hey gemini $transcript"
        when (val parsed = CommandGrammar.parse(withWake)) {
            is CommandGrammar.Result.Dispatch -> {
                XrLog.i(TAG, "route=LOCAL grammar-match command=${parsed.command}")
                try {
                    dispatchCommand(parsed.command)
                } catch (t: Throwable) {
                    XrLog.e(TAG, "local dispatch threw", t)
                    _lastError.value = t.message ?: t::class.simpleName
                    setState(State.ERROR, "local dispatch threw")
                    return
                }
                // The dispatcher already speaks its own confirmation for
                // most commands (via VoiceCommandDispatcher's TTS hooks),
                // so we don't speak here — just drop back to IDLE.
                setState(State.IDLE, "local dispatch ok")
            }

            is CommandGrammar.Result.Escalate -> {
                XrLog.i(TAG, "route=GEMINI (grammar escalate) utterance=\"$transcript\"")
                escalateToGemini(transcript)
            }
            CommandGrammar.Result.NotAddressed -> {
                XrLog.i(TAG, "route=GEMINI (grammar not-addressed) utterance=\"$transcript\"")
                escalateToGemini(transcript)
            }
        }
    }

    private fun escalateToGemini(utterance: String) {
        setState(State.THINKING, "escalate to gemini")
        replyJob?.cancel()
        val startNanos = System.nanoTime()
        replyJob = scope.launch {
            val snapshot = try {
                contextProvider().trim()
            } catch (t: Throwable) {
                XrLog.w(TAG, "contextProvider threw: ${t.message}", t)
                ""
            }
            XrLog.i(
                TAG,
                "gemini request: utterance=\"$utterance\" snapshot_len=${snapshot.length}",
            )
            // Full snapshot dumped at debug so it can be inspected via
            // `adb logcat -s XrMail/PushToTalk:D` without spamming info.
            XrLog.d(TAG, "gemini request snapshot=\n$snapshot")
            val result = gemini.reply(utterance = utterance, inboxSnapshot = snapshot)
            val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000
            result.onSuccess { reply ->
                XrLog.i(
                    TAG,
                    "gemini reply OK in ${elapsedMs}ms: commands=${reply.commands.size} " +
                        "text_len=${reply.spokenText.length}",
                )
                XrLog.i(TAG, "gemini reply text: \"${reply.spokenText}\"")
                reply.commands.forEachIndexed { i, cmd ->
                    XrLog.i(TAG, "gemini command[$i]: $cmd")
                    try {
                        dispatchCommand(cmd)
                    } catch (t: Throwable) {
                        XrLog.e(TAG, "gemini command dispatch threw for $cmd", t)
                    }
                }
                if (reply.spokenText.isNotBlank()) {
                    speakThenIdle(reply.spokenText)
                } else if (reply.commands.isEmpty()) {
                    // Empty reply AND no commands — tell the user something
                    // went wrong so the chip doesn't silently slip to IDLE.
                    XrLog.w(TAG, "gemini reply empty (no text, no commands)")
                    _lastError.value = "No response from Gemini — tap to retry."
                    setState(State.ERROR, "empty gemini reply")
                } else {
                    // Commands-only reply — Gemini forgot to speak a
                    // confirmation despite the prompt telling it to. Don't
                    // leave the user wondering; synthesize a short audible
                    // acknowledgement based on what we dispatched so the
                    // tap-to-talk loop always gives feedback.
                    val fallback = fallbackConfirmation(reply.commands)
                    if (fallback.isBlank()) {
                        // Dispatcher already spoke (e.g. read_draft) or
                        // the command is intentionally silent — skip the
                        // SPEAKING state so we don't hang waiting on a
                        // TTS round that never fires.
                        XrLog.i(TAG, "commands-only reply (silent by design)")
                        setState(State.IDLE, "silent commands-only")
                    } else {
                        XrLog.i(TAG, "commands-only reply — falling back to: \"$fallback\"")
                        speakThenIdle(fallback)
                    }
                }
            }.onFailure { err ->
                XrLog.w(TAG, "gemini reply FAILED in ${elapsedMs}ms: ${err.message}", err)
                _lastError.value = err.message ?: err::class.simpleName ?: "Gemini error"
                setState(State.ERROR, "gemini failure")
            }
        }
    }

    /**
     * Short spoken acknowledgement when Gemini dispatches one or more
     * commands with no [ToolReply.spokenText]. Keeps the push-to-talk
     * loop audibly closed so the user never sees the chip silently drop
     * from THINKING back to IDLE. Intentionally generic — the UI shows
     * the real state change, this is just "something happened".
     */
    private fun fallbackConfirmation(commands: List<EmailCommandTool.Command>): String {
        if (commands.isEmpty()) return "Done."
        return when (val first = commands.first()) {
            is EmailCommandTool.Command.SelectEmail -> "Opening."
            is EmailCommandTool.Command.ArchiveEmail -> "Archived."
            is EmailCommandTool.Command.SnoozeEmail -> "Snoozed."
            is EmailCommandTool.Command.ForwardEmail -> "Forwarding."
            is EmailCommandTool.Command.Reply -> "Opening reply."
            is EmailCommandTool.Command.Search -> "Searching."
            is EmailCommandTool.Command.ReadAloud -> "Reading."
            is EmailCommandTool.Command.Summarize -> "Summarizing."
            is EmailCommandTool.Command.DraftReply ->
                "Drafted. Read it, show it, or send it?"
            is EmailCommandTool.Command.ReviseDraft ->
                "Updated. Read, show, or send?"
            EmailCommandTool.Command.CancelDraft -> "Discarded."
            is EmailCommandTool.Command.SendDraft -> "Sent."
            EmailCommandTool.Command.ArmSendForVoice -> "Ready."
            EmailCommandTool.Command.ShowSendConfirmation -> "Here's the preview."
            // read_draft TTS's the body itself in the dispatcher — no
            // fallback phrase needed, and adding one would talk over it.
            EmailCommandTool.Command.ReadDraft -> ""
            is EmailCommandTool.Command.FilterCategory -> "Filtering."
            EmailCommandTool.Command.ShowInbox -> "Opening inbox."
            EmailCommandTool.Command.GoBack -> "Going back."
            EmailCommandTool.Command.Refresh -> "Reset."
            is EmailCommandTool.Command.Speak -> first.text.ifBlank { "Done." }
            is EmailCommandTool.Command.ExpandTier -> "Expanding."
            EmailCommandTool.Command.CollapseOneTier -> "Collapsing."
            EmailCommandTool.Command.NextUnread -> "Next."
            EmailCommandTool.Command.GetInboxState -> "Got it."
        }
    }

    private fun speakThenIdle(text: String) {
        setState(State.SPEAKING, "tts start")
        XrLog.i(TAG, "SPEAK: \"$text\"")
        tts.speak(text)
        speakWatcherJob?.cancel()
        speakWatcherJob = scope.launch {
            // Bound the wait so a stuck TTS can't pin the chip at SPEAKING.
            val completed = withTimeoutOrNull(20_000L) { tts.finished.first() }
            if (completed == null) {
                XrLog.w(TAG, "tts.finished timed out after 20s — forcing IDLE")
            }
            // If the user tapped the chip mid-TTS, toggle() → start()
            // will have already flipped state to LISTENING and called
            // tts.stop(). In that case we leave the new round alone.
            if (_state.value == State.SPEAKING) {
                setState(State.IDLE, "tts done")
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Utilities
    // ---------------------------------------------------------------------------

    private fun runOnMainThread(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    private companion object {
        const val TAG = "PushToTalk"
    }
}
