@file:OptIn(com.google.firebase.ai.annotations.PublicPreviewAPI::class)

package com.xremail.app.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.google.firebase.ai.Firebase
import com.google.firebase.ai.GenerativeBackend
import com.google.firebase.ai.LiveGenerativeModel
import com.google.firebase.ai.ResponseModality
import com.google.firebase.ai.SpeechConfig
import com.google.firebase.ai.Voice
import com.google.firebase.ai.liveGenerationConfig
import com.google.firebase.ai.type.FunctionResponsePart
import com.google.firebase.ai.type.LiveSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Manages a Gemini Live API session for bidirectional audio voice commands.
 */
class GeminiLiveManager {

    enum class SessionState { DISCONNECTED, CONNECTING, CONNECTED, LISTENING, ERROR }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var conversationJob: Job? = null

    private var liveModel: LiveGenerativeModel? = null
    private var liveSession: LiveSession? = null

    private var voiceSendArmed: () -> Boolean = { false }

    private val _state = MutableStateFlow(SessionState.DISCONNECTED)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val _commands = MutableSharedFlow<EmailCommandTool.Command>(extraBufferCapacity = 16)
    val commands: SharedFlow<EmailCommandTool.Command> = _commands.asSharedFlow()

    private val _spokenResponses = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val spokenResponses: SharedFlow<String> = _spokenResponses.asSharedFlow()

    private val _lastError = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val lastError: SharedFlow<String> = _lastError.asSharedFlow()

    fun setVoiceSendGate(isArmed: () -> Boolean) {
        voiceSendArmed = isArmed
    }

    private fun buildLiveModel(): LiveGenerativeModel =
        Firebase.ai(backend = GenerativeBackend.googleAI()).liveModel(
            modelName = "gemini-2.5-flash-native-audio-preview-12-2025",
            generationConfig = liveGenerationConfig {
                responseModality = ResponseModality.AUDIO
                speechConfig = SpeechConfig(voice = Voice("FENRIR"))
            },
            systemInstruction = EmailCommandTool.systemInstruction,
            tools = listOf(EmailCommandTool.emailAssistantTool),
        )

    fun startListening(context: Context) {
        if (_state.value == SessionState.LISTENING) return
        if (!hasRecordAudio(context)) {
            scope.launch { _lastError.emit("Microphone permission required") }
            return
        }
        conversationJob = scope.launch {
            try {
                if (liveModel == null) {
                    liveModel = buildLiveModel()
                }
                if (liveSession == null) {
                    _state.value = SessionState.CONNECTING
                    liveSession = withContext(Dispatchers.IO) {
                        liveModel!!.connect()
                    }
                }
                _state.value = SessionState.CONNECTED
                val session = liveSession ?: run {
                    _state.value = SessionState.ERROR
                    _lastError.emit("No live session")
                    return@launch
                }
                _state.value = SessionState.LISTENING
                session.startAudioConversation { functionCall ->
                    val result = VoiceCommandParser.handleFunctionCall(functionCall, voiceSendArmed)
                    result.command?.let { _commands.tryEmit(it) }
                    FunctionResponsePart(
                        name = functionCall.name,
                        response = result.modelResponse,
                        id = functionCall.id,
                    )
                }
                if (_state.value != SessionState.ERROR) {
                    _state.value = SessionState.CONNECTED
                }
            } catch (e: Exception) {
                _state.value = SessionState.ERROR
                _lastError.emit(e.message ?: "listen failed")
            }
        }
    }

    fun toggleListening(context: Context) {
        when (_state.value) {
            SessionState.LISTENING -> stopListening()
            else -> startListening(context)
        }
    }

    fun stopListening() {
        scope.launch {
            try {
                liveSession?.stopAudioConversation()
            } catch (_: Exception) {
            }
            if (_state.value == SessionState.LISTENING) {
                _state.value = SessionState.CONNECTED
            }
        }
    }

    fun disconnect() {
        scope.launch {
            conversationJob?.cancel()
            conversationJob = null
            withContext(Dispatchers.IO) {
                try {
                    liveSession?.close()
                } catch (_: Exception) {
                }
            }
            liveSession = null
            liveModel = null
            if (_state.value != SessionState.ERROR) {
                _state.value = SessionState.DISCONNECTED
            }
        }
    }

    fun simulateCommand(command: EmailCommandTool.Command) {
        _commands.tryEmit(command)
    }

    fun simulateResponse(text: String) {
        _spokenResponses.tryEmit(text)
    }

    private fun hasRecordAudio(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
}
