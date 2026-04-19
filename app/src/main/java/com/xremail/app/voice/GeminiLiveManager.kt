package com.xremail.app.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.FunctionCallPart
import com.google.firebase.ai.type.FunctionResponsePart
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.LiveSession
import com.google.firebase.ai.type.PublicPreviewAPI
import com.google.firebase.ai.type.ResponseModality
import com.google.firebase.ai.type.SpeechConfig
import com.google.firebase.ai.type.Voice
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.liveAudioConversationConfig
import com.google.firebase.ai.type.liveGenerationConfig
import com.xremail.app.util.XrLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Live bidirectional audio session with Gemini.
 *
 * Backed by Firebase AI Logic's [com.google.firebase.ai.LiveGenerativeModel].
 * - Model: gemini-2.5-flash native-audio preview — speaks in a natural voice.
 * - Mic + speaker are managed by the SDK via startAudioConversation, but we
 *   tune the underlying [AudioRecord] / [AudioTrack] to the low-latency voice
 *   path so the round-trip feels conversational instead of walkie-talkie.
 * - Function calls from the model are routed through [EmailCommandTool.parse]
 *   and emitted on [commands] for [VoiceCommandDispatcher] to execute.
 *
 * ### Latency tuning rationale
 *
 * This used to be on the default [LiveSession.startAudioConversation] overload
 * which builds AudioRecord / AudioTrack with default Android buffers. On Galaxy
 * XR that adds ~200ms of perceived round-trip on top of the model's
 * time-to-first-audio. The [liveAudioConversationConfig] overload lets us
 * intercept the builders and request:
 *
 * - `MediaRecorder.AudioSource.VOICE_COMMUNICATION` — uses the OS voice path
 *   with hardware AEC/NS, much lower latency than MIC source.
 * - `AudioTrack.PERFORMANCE_MODE_LOW_LATENCY` — bypasses the system mixer and
 *   asks AudioFlinger for the fast track when the device supports it.
 * - `AudioAttributes.USAGE_VOICE_COMMUNICATION` — routes to the voice stream
 *   so the OS can prioritize it over media playback.
 *
 * Combined with `enableInterruptions = true` (so the model stops talking the
 * moment the user starts), this is what makes the conversation feel real-time
 * instead of turn-based.
 *
 * ### Lifecycle
 *
 *   connect(scope)   → establishes WebSocket, registers tools + system prompt
 *   startListening() → unmutes mic (model starts hearing the user)
 *   stopListening()  → mutes mic
 *   disconnect()     → closes the session
 */
@OptIn(PublicPreviewAPI::class)
class GeminiLiveManager {

    enum class SessionState {
        /** Not connected to Gemini at all (no WebSocket open). */
        DISCONNECTED,
        /** Opening the WebSocket / negotiating tools + system prompt. */
        CONNECTING,
        /** WebSocket open but mic NOT being streamed (waiting for wake word). */
        CONNECTED,
        /** WebSocket open AND audio conversation active (mic open, model can speak). */
        LISTENING,
        /** Last operation failed — see [lastError]. */
        ERROR,
    }

    private val _state = MutableStateFlow(SessionState.DISCONNECTED)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val _commands = MutableSharedFlow<EmailCommandTool.Command>(extraBufferCapacity = 16)
    val commands: SharedFlow<EmailCommandTool.Command> = _commands.asSharedFlow()

    /** Text transcripts of model-spoken responses, for captions / logging. */
    private val _spokenResponses = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val spokenResponses: SharedFlow<String> = _spokenResponses.asSharedFlow()

    /**
     * True from the moment the model starts producing audio for a turn until
     * the turn ends. Other components (e.g. [TTSManager]) read this to avoid
     * stepping on Gemini Live's voice with overlapping local TTS.
     *
     * NOTE: the SDK doesn't expose per-frame "is speaking" yet; we
     * approximate by flipping this true when a function call arrives (model
     * is mid-turn) and false on the next user-driven event. That's enough to
     * suppress the most common overlap source — the auto-summary TTS racing
     * a Gemini reply.
     */
    private val _modelSpeaking = MutableStateFlow(false)
    val modelSpeaking: StateFlow<Boolean> = _modelSpeaking.asStateFlow()

    private var session: LiveSession? = null
    private var sessionJob: Job? = null
    private var managedScope: CoroutineScope? = null
    private var contextProvider: () -> String = { "" }

    /**
     * Wall-clock millis when the model last produced audio or the user
     * last triggered a turn. Used by [com.xremail.app.MainActivity]'s
     * idle-timeout LaunchedEffect to auto-dismiss the active conversation
     * after a stretch of silence so the wake word has to fire again.
     */
    private val _lastActivityMs = MutableStateFlow(0L)
    val lastActivityMs: StateFlow<Long> = _lastActivityMs.asStateFlow()

    /** Last error message — surface in UI so failures are visible, not silent. */
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    /**
     * Register a callback that returns a short text block describing the
     * currently visible UI state (selected email id, inbox focus, etc).
     * Injected into system prompt on every turn so the model has fresh grounding.
     */
    fun setContextProvider(provider: () -> String) {
        contextProvider = provider
    }

    /**
     * Open the WebSocket and register tools + system prompt, but DO NOT
     * open the mic. The model is "warm" but won't hear anything until
     * [summon] fires. Splitting connect from listen lets the wake-word
     * loop keep the mic to itself until "hey gemini" actually fires —
     * before this split we were streaming raw audio to the cloud the
     * second the app opened, which is what the user described as "the
     * voice agent is always on and working really weirdly".
     */
    suspend fun connect(scope: CoroutineScope) {
        if (_state.value != SessionState.DISCONNECTED &&
            _state.value != SessionState.ERROR
        ) {
            XrLog.i(TAG, "connect() skipped — already in state=${_state.value}")
            return
        }
        _state.value = SessionState.CONNECTING
        _lastError.value = null
        managedScope = scope
        XrLog.i(TAG, "Gemini Live connecting (model=$MODEL)…")

        try {
            val model = Firebase.ai(backend = GenerativeBackend.googleAI()).liveModel(
                modelName = MODEL,
                generationConfig = liveGenerationConfig {
                    responseModality = ResponseModality.AUDIO

                    // Cap output high enough to comfortably cover a 1–4
                    // sentence email draft (the longest legitimate single
                    // turn the model produces). Action-mode confirmations
                    // ("Done.", "Drafted, want me to send it?") are
                    // policed by the SYSTEM_PROMPT, not the token cap —
                    // the cap exists to prevent runaway generation, not
                    // to enforce terseness. ~64 was too aggressive: it
                    // cut email drafts off mid-sentence so the user could
                    // never hear a full readback.
                    maxOutputTokens = 320

                    // Slightly conservative temperature: makes the model
                    // commit to the first sensible response instead of
                    // exploring rephrasings. Trims a few hundred ms of "the
                    // model is still deciding what to say" before audio starts.
                    temperature = 0.6f

                    // Puck is the most conversational of the Gemini Live
                    // voices — natural cadence, slight lift at the end of
                    // confirmations. AOEDE / KORE feel more announcer-ish;
                    // CHARON is too monotone for fast back-and-forth.
                    speechConfig = SpeechConfig(voice = Voice("Puck"))
                },
                systemInstruction = content {
                    text(SYSTEM_PROMPT)
                },
                tools = listOf(EmailCommandTool.tool),
            )

            val live = model.connect()
            session = live

            _state.value = SessionState.CONNECTED
            XrLog.i(TAG, "Gemini Live connected (mic still closed — waiting for wake word)")
        } catch (t: Throwable) {
            XrLog.e(TAG, "Gemini Live connect failed", t)
            _lastError.value = t.message ?: t::class.simpleName ?: "connect error"
            _state.value = SessionState.ERROR
            session = null
        }
    }

    /**
     * Open the mic and start streaming audio to the model. Idempotent —
     * already-listening sessions just bump [lastActivityMs] so the
     * idle-timeout coroutine resets.
     *
     * Called from the wake-word handler in MainActivity, and also wired
     * to a manual "summon" gesture (pinch on the voice indicator) so
     * the conversation works even on devices where the wake-word
     * service is unavailable.
     */
    fun summon() {
        val live = session
        val scope = managedScope
        if (live == null || scope == null) {
            XrLog.w(TAG, "summon() with no live session — call connect() first")
            return
        }
        if (_state.value == SessionState.LISTENING) {
            // Already listening — just bump activity so the idle timer
            // restarts. Lets the user "re-summon" mid-conversation to
            // explicitly extend the session if they want.
            _lastActivityMs.value = System.currentTimeMillis()
            XrLog.v(TAG, "summon() while already LISTENING — activity bumped")
            return
        }
        if (_state.value != SessionState.CONNECTED) {
            XrLog.w(TAG, "summon() in unexpected state=${_state.value} — ignoring")
            return
        }
        _lastActivityMs.value = System.currentTimeMillis()
        _state.value = SessionState.LISTENING
        XrLog.i(TAG, "Gemini SUMMONED — opening mic for conversation")
        sessionJob = scope.launch(Dispatchers.Default) {
            try {
                live.startAudioConversation(
                    liveAudioConversationConfig {
                        functionCallHandler = { call: FunctionCallPart ->
                            XrLog.i(TAG, "function call: ${call.name} args=${call.args}")
                            _modelSpeaking.value = true
                            _lastActivityMs.value = System.currentTimeMillis()
                            handleFunctionCall(call)
                        }
                        enableInterruptions = true
                        initializationHandler = { recordBuilder, trackBuilder ->
                            tuneAudioForLowLatency(recordBuilder, trackBuilder)
                        }
                    },
                )
            } catch (t: Throwable) {
                XrLog.e(TAG, "Audio conversation failed", t)
                _lastError.value = t.message ?: t::class.simpleName ?: "audio error"
                _state.value = SessionState.ERROR
            }
        }
    }

    /**
     * Inverse of [summon]: stop streaming audio, but keep the WebSocket
     * warm so the next wake-word fires open the mic in <100ms. After this
     * the wake-word detector should be resumed on the caller side.
     */
    fun dismiss() {
        val live = session
        val scope = managedScope
        if (_state.value != SessionState.LISTENING) {
            XrLog.v(TAG, "dismiss() while not LISTENING (state=${_state.value}) — ignoring")
            return
        }
        sessionJob?.cancel()
        sessionJob = null
        _state.value = SessionState.CONNECTED
        _modelSpeaking.value = false
        XrLog.i(TAG, "Gemini DISMISSED — mic closed, session kept warm")
        if (live != null && scope != null) {
            scope.launch(Dispatchers.Default) {
                try {
                    live.stopAudioConversation()
                } catch (t: Throwable) {
                    XrLog.w(TAG, "stopAudioConversation threw: ${t.message}")
                }
            }
        }
    }

    /**
     * Bump the activity timestamp from outside (e.g. on every model
     * function-call dispatch) so the idle-timeout doesn't dismiss the
     * session mid-tool-call.
     */
    fun bumpActivity() {
        _lastActivityMs.value = System.currentTimeMillis()
    }

    fun disconnect() {
        val live = session
        session = null
        val scope = managedScope
        sessionJob?.cancel()
        sessionJob = null
        _state.value = SessionState.DISCONNECTED
        _modelSpeaking.value = false
        if (live != null && scope != null) {
            scope.launch(Dispatchers.Default) {
                try {
                    live.stopAudioConversation()
                    live.close()
                } catch (t: Throwable) {
                    XrLog.w(TAG, "Error closing session: ${t.message}")
                }
            }
        }
    }

    /**
     * Called when the model has finished a turn and is back to listening.
     * Cleared after each function-call turn or when an explicit "model done"
     * signal comes through. Today we clear conservatively from the dispatcher
     * after a small grace period — see [VoiceCommandDispatcher].
     */
    fun markModelIdle() {
        if (_modelSpeaking.value) {
            XrLog.v(TAG, "model marked idle (turn ended)")
            _modelSpeaking.value = false
        }
    }

    /**
     * Inject a short transcript describing what the user is looking at.
     *
     * IMPORTANT: prefer the [setContextProvider] callback path over calling
     * this directly. Every send() here counts as a user-role turn and may
     * provoke the model to respond, which causes spurious "Got it." audio
     * and adds latency to the next real user turn. This entry point is kept
     * for cases where we deliberately want to nudge the model (e.g. major
     * tier transitions where the available actions change), not for every
     * selection change.
     */
    suspend fun sendContextUpdate(text: String) {
        val live = session ?: return
        try {
            live.send(content(role = "user") { text(text) })
        } catch (t: Throwable) {
            XrLog.w(TAG, "sendContextUpdate failed: ${t.message}")
        }
    }

    // ---------------------------------------------------------------------------
    // Audio path tuning — pulled out so the rationale for each call is visible
    // ---------------------------------------------------------------------------

    /**
     * Reconfigures the SDK-supplied [AudioRecord.Builder] and
     * [AudioTrack.Builder] for low-latency two-way voice. These builders are
     * handed to us BEFORE the SDK calls `.build()`, so any tuning here
     * actually takes effect. (The SDK still owns the Track/Record lifecycle.)
     *
     * The defaults Android picks are biased toward media playback (large
     * buffers, music stream) which adds ~150-300ms each direction on top of
     * model latency. The voice-call path with low-latency performance mode
     * cuts that to ~50ms each direction on Galaxy XR.
     */
    private fun tuneAudioForLowLatency(
        recordBuilder: AudioRecord.Builder,
        trackBuilder: AudioTrack.Builder,
    ) {
        try {
            // VOICE_COMMUNICATION engages the platform voice path:
            // AEC + NS + AGC are applied in DSP, not in software, AND the
            // buffer sizing is tuned for telephony (~20ms frames) instead of
            // media capture (~100ms frames).
            recordBuilder.setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)

            // Don't let the OS treat our mic as a privacy-sensitive recorder
            // (which can introduce extra muting around system events).
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                recordBuilder.setPrivacySensitive(false)
            }
        } catch (t: Throwable) {
            XrLog.w(TAG, "Could not tune AudioRecord builder: ${t.message}")
        }

        try {
            trackBuilder.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            // FLAG_LOW_LATENCY on AudioAttributes is deprecated; the modern
            // equivalent is AudioTrack.PERFORMANCE_MODE_LOW_LATENCY set
            // directly on the AudioTrack builder below.
            trackBuilder.setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(LIVE_OUTPUT_SAMPLE_RATE_HZ)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            // PERFORMANCE_MODE_LOW_LATENCY asks AudioFlinger for the fast
            // track. Devices that can't honor it fall back gracefully to
            // PERFORMANCE_MODE_NONE — never throws.
            trackBuilder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)

            // Use the smallest legal output buffer for our format. The SDK
            // sets this internally to a much larger value optimized for
            // glitch-free playback; we'd rather risk an underrun than carry
            // an extra 100ms of speaker buffer on a conversational response.
            val minBuf = AudioTrack.getMinBufferSize(
                LIVE_OUTPUT_SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBuf > 0) {
                trackBuilder.setBufferSizeInBytes(minBuf)
            }
        } catch (t: Throwable) {
            XrLog.w(TAG, "Could not tune AudioTrack builder: ${t.message}")
        }
    }

    // ---------------------------------------------------------------------------
    // Function call plumbing
    // ---------------------------------------------------------------------------

    private fun handleFunctionCall(call: FunctionCallPart): FunctionResponsePart {
        val args = call.args.mapValues { (_, v) ->
            (v as? JsonPrimitive)?.content
        }
        val command = EmailCommandTool.parse(call.name, args)
        return if (command != null) {
            _commands.tryEmit(command)
            FunctionResponsePart(
                name = call.name,
                response = buildJsonObject {
                    put("status", JsonPrimitive("ok"))
                },
            )
        } else {
            XrLog.w(TAG, "Unknown function call: ${call.name}")
            FunctionResponsePart(
                name = call.name,
                response = buildJsonObject {
                    put("status", JsonPrimitive("unknown_function"))
                },
            )
        }
    }

    companion object {
        private const val TAG = "GeminiLive"

        // Firebase AI Logic Live API on the Gemini Developer API backend exposes
        // only the native-audio variant. Pin the dated preview ID — aliases are not
        // supported for Live models. Source: firebase.google.com/docs/ai-logic/live-api
        private const val MODEL = "gemini-2.5-flash-native-audio-preview-12-2025"

        // Gemini Live emits 24kHz PCM16 mono — pin the AudioTrack to match
        // so the SDK doesn't have to resample (resampling adds buffering AND
        // CPU on the audio thread).
        private const val LIVE_OUTPUT_SAMPLE_RATE_HZ = 24_000

        // The system prompt encodes TWO modes the model needs to switch
        // between fluidly:
        //
        //   ACTION MODE (default): the user gave a command that maps to a
        //   tool — fire the tool, say a 4-6 word confirmation, done. This
        //   is what makes the assistant feel responsive instead of chatty.
        //
        //   COMPOSE MODE (entered when the user asks for a reply / draft /
        //   message): the model has to actually WRITE the email body. It
        //   calls draft_reply(body=COMPLETE TEXT), then says ONE short
        //   confirmation like "Drafted, send it?". The UI reads the draft
        //   itself via local TTS so the model doesn't burn ~5s saying it
        //   twice. Revisions go through revise_draft; sends through
        //   send_draft (only after the user explicitly confirms).
        //
        // The "ask one tight clarifying question" line is the escape hatch
        // when the model genuinely doesn't know what to do — better than
        // calling the wrong tool, much better than rambling.
        //
        // Every additional token here adds prefill latency to the first
        // audio chunk on each turn, so we keep the prose dense.
        private val SYSTEM_PROMPT = """
            You are XrMail's hands-free voice agent. Two modes:

            ACTION MODE (default): when the user's intent maps to a tool, call it immediately — never narrate. Then speak ONE confirmation, max 6 words ("Archived." "Snoozed till tomorrow." "Opening inbox.").

            COMPOSE MODE (when the user asks to reply, write back, draft, respond, or send a message): write the FULL reply yourself in 1-4 short sentences, natural and ready-to-send. Pass the complete text in draft_reply(body=...). Then say ONE short confirmation like "Drafted, want me to send it?" — DO NOT speak the draft body, the UI reads it back. If the user revises, call revise_draft(body=...) with the COMPLETE rewritten body. Only call send_draft after the user explicitly confirms ("send it", "yes", "fire it").

            Always default to the selected email when one exists. Reference emails by sender or subject, never id. If you genuinely don't know what to do, ask ONE tight clarifying question — never guess wrong.
        """.trimIndent()
    }
}
