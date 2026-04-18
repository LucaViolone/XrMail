package com.xremail.app.voice

import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.FunctionCallPart
import com.google.firebase.ai.type.FunctionResponsePart
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.LiveGenerationConfig
import com.google.firebase.ai.type.LiveSession
import com.google.firebase.ai.type.PublicPreviewAPI
import com.google.firebase.ai.type.ResponseModality
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.liveGenerationConfig
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
 * - Mic + speaker are managed by the SDK via startAudioConversation.
 * - Function calls from the model are routed through [EmailCommandTool.parse]
 *   and emitted on [commands] for [VoiceCommandDispatcher] to execute.
 *
 * Lifecycle:
 *   connect(scope)   → establishes WebSocket, registers tools + system prompt
 *   startListening() → unmutes mic (model starts hearing the user)
 *   stopListening()  → mutes mic
 *   disconnect()     → closes the session
 */
@OptIn(PublicPreviewAPI::class)
class GeminiLiveManager {

    enum class SessionState { DISCONNECTED, CONNECTING, CONNECTED, LISTENING, ERROR }

    private val _state = MutableStateFlow(SessionState.DISCONNECTED)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val _commands = MutableSharedFlow<EmailCommandTool.Command>(extraBufferCapacity = 16)
    val commands: SharedFlow<EmailCommandTool.Command> = _commands.asSharedFlow()

    /** Text transcripts of model-spoken responses, for captions / logging. */
    private val _spokenResponses = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val spokenResponses: SharedFlow<String> = _spokenResponses.asSharedFlow()

    private var session: LiveSession? = null
    private var sessionJob: Job? = null
    private var managedScope: CoroutineScope? = null
    private var contextProvider: () -> String = { "" }

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

    suspend fun connect(scope: CoroutineScope) {
        if (_state.value == SessionState.CONNECTING ||
            _state.value == SessionState.CONNECTED ||
            _state.value == SessionState.LISTENING
        ) {
            Log.i(TAG, "connect() skipped — already in state=${_state.value}")
            return
        }
        _state.value = SessionState.CONNECTING
        _lastError.value = null
        managedScope = scope
        Log.i(TAG, "Gemini Live connecting (model=$MODEL)…")

        try {
            val model = Firebase.ai(backend = GenerativeBackend.googleAI()).liveModel(
                modelName = MODEL,
                generationConfig = liveGenerationConfig {
                    responseModality = ResponseModality.AUDIO
                },
                systemInstruction = content {
                    text(SYSTEM_PROMPT)
                },
                tools = listOf(EmailCommandTool.tool),
            )

            val live = model.connect()
            session = live

            sessionJob = scope.launch(Dispatchers.Default) {
                try {
                    live.startAudioConversation { call: FunctionCallPart ->
                        Log.i(TAG, "function call: ${call.name} args=${call.args}")
                        handleFunctionCall(call)
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "Audio conversation failed", t)
                    _lastError.value = t.message ?: t::class.simpleName ?: "audio error"
                    _state.value = SessionState.ERROR
                }
            }

            _state.value = SessionState.LISTENING
            Log.i(TAG, "Gemini Live connected and listening (model=$MODEL)")
        } catch (t: Throwable) {
            Log.e(TAG, "Gemini Live connect failed", t)
            _lastError.value = t.message ?: t::class.simpleName ?: "connect error"
            _state.value = SessionState.ERROR
            session = null
        }
    }

    fun startListening() {
        if (_state.value == SessionState.CONNECTED) {
            _state.value = SessionState.LISTENING
        }
    }

    fun stopListening() {
        if (_state.value == SessionState.LISTENING) {
            _state.value = SessionState.CONNECTED
        }
    }

    fun disconnect() {
        val live = session
        session = null
        val scope = managedScope
        sessionJob?.cancel()
        sessionJob = null
        _state.value = SessionState.DISCONNECTED
        if (live != null && scope != null) {
            scope.launch(Dispatchers.Default) {
                try {
                    live.stopAudioConversation()
                    live.close()
                } catch (t: Throwable) {
                    Log.w(TAG, "Error closing session: ${t.message}")
                }
            }
        }
    }

    /**
     * Inject a short transcript describing what the user is looking at.
     * Called on email selection / tier change so the model knows the referent
     * of "this email" without round-tripping.
     */
    suspend fun sendContextUpdate(text: String) {
        val live = session ?: return
        try {
            live.send(content(role = "user") { text(text) })
        } catch (t: Throwable) {
            Log.w(TAG, "sendContextUpdate failed: ${t.message}")
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
            Log.w(TAG, "Unknown function call: ${call.name}")
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

        private val SYSTEM_PROMPT = """
            You are XrMail's voice operator. The user is wearing a headset and cannot type.
            Your job is to run the app FOR them, conversationally and with initiative.

            Behavior rules:
            - When anything the user says maps to a function, CALL IT IMMEDIATELY — do not
              narrate first, do not ask permission. Speak a short confirmation after the call.
            - You can chain calls. "Archive everything from promotions" → loop calls.
              "Reply saying I'll be there" → call reply with body filled.
            - When a command needs an email and one is already selected, USE THE SELECTED
              ONE. Only ask for clarification if nothing is selected AND the referent is
              truly ambiguous (multiple candidates in the context).
            - Use the context block to answer inbox questions directly without a round trip.
              "What's urgent?" → read priority from context, don't call search.
              "Anything from Sarah?" → scan top-unread in context first.
            - Reference emails by sender or subject, never by id.
            - Use read_aloud for full body, summarize for a one-sentence gist.
            - After a destructive action (archive, send), speak a warm one-clause
              confirmation: "Archived." "Sent to Sarah." "Snoozed till tomorrow."
            - If you are unsure WHICH function fits, prefer speak to clarify rather than
              guessing. But lean toward action — the user is hands-free.
            - Keep conversational turns under ~15 words unless the user asks for detail.
            - You are warm and direct. Not chatty. Not robotic.
        """.trimIndent()
    }
}
