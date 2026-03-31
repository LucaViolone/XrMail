package com.xremail.app.voice

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages a Gemini Live API session for bidirectional audio voice commands.
 * Replaces the old SpeechRecognizer + manual intent parser approach.
 *
 * Production implementation:
 * ```
 * val liveModel = Firebase.ai().liveModel(
 *     modelName = "gemini-2.5-flash-native-audio-preview-12-2025",
 *     tools = listOf(emailCommandTool),
 *     systemInstruction = content {
 *         text("Email assistant. Keep spoken responses under 10 words.")
 *     }
 * )
 * val session = liveModel.connect()
 * session.startAudioConversation(::handleCommand)
 * ```
 */
class GeminiLiveManager {

    enum class SessionState { DISCONNECTED, CONNECTING, CONNECTED, LISTENING, ERROR }

    private val _state = MutableStateFlow(SessionState.DISCONNECTED)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val _commands = MutableSharedFlow<EmailCommandTool.Command>(extraBufferCapacity = 16)
    val commands: SharedFlow<EmailCommandTool.Command> = _commands.asSharedFlow()

    private val _spokenResponses = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val spokenResponses: SharedFlow<String> = _spokenResponses.asSharedFlow()

    fun connect() {
        _state.value = SessionState.CONNECTING
        // Phase 1 stub: immediately mark connected
        _state.value = SessionState.CONNECTED
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
        _state.value = SessionState.DISCONNECTED
    }

    // Phase 1: simulate a voice command for testing
    fun simulateCommand(command: EmailCommandTool.Command) {
        _commands.tryEmit(command)
    }

    fun simulateResponse(text: String) {
        _spokenResponses.tryEmit(text)
    }
}
