package com.xremail.app.voice

import com.xremail.app.viewmodel.VoiceDraft
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Orchestrates the voice-first compose flow:
 * 1. User gives brief instruction ("Reply saying I'll revise by Friday")
 * 2. GeminiLiveManager generates a full draft
 * 3. TTSManager reads it back
 * 4. User confirms, edits by voice, or cancels
 *
 * Production: pipes GeminiLiveManager.commands for edit/confirm/cancel,
 * and GeminiLiveManager.spokenResponses for draft text.
 */
class VoiceComposeManager(
    private val geminiLive: GeminiLiveManager,
    private val ttsManager: TTSManager,
) {

    enum class ComposeState { IDLE, LISTENING, GENERATING, READING_BACK, AWAITING_CONFIRM }

    private val _state = MutableStateFlow(ComposeState.IDLE)
    val state: StateFlow<ComposeState> = _state.asStateFlow()

    private val _draft = MutableStateFlow<VoiceDraft?>(null)
    val draft: StateFlow<VoiceDraft?> = _draft.asStateFlow()

    fun startCompose(recipientName: String, subject: String) {
        _state.value = ComposeState.LISTENING
        _draft.value = VoiceDraft(
            recipientName = recipientName,
            subject = subject,
        )
        geminiLive.startListening()
    }

    fun onDraftGenerated(draftText: String, confidence: Float = 0.85f) {
        _state.value = ComposeState.READING_BACK
        _draft.value = _draft.value?.copy(
            draftText = draftText,
            isGenerating = false,
            confidence = confidence,
        )
        ttsManager.speak(draftText)
    }

    fun onTtsFinished() {
        if (_state.value == ComposeState.READING_BACK) {
            _state.value = ComposeState.AWAITING_CONFIRM
        }
    }

    fun editDraft(instruction: String) {
        _state.value = ComposeState.GENERATING
        _draft.value = _draft.value?.copy(isGenerating = true)
    }

    fun confirmSend(): VoiceDraft? {
        val sent = _draft.value
        reset()
        return sent
    }

    fun cancel() {
        ttsManager.stop()
        reset()
    }

    private fun reset() {
        _state.value = ComposeState.IDLE
        _draft.value = null
        geminiLive.stopListening()
    }

    fun simulateDraft(recipientName: String, subject: String, draftText: String) {
        _draft.value = VoiceDraft(
            recipientName = recipientName,
            subject = subject,
            draftText = draftText,
            isGenerating = false,
            confidence = 0.85f,
        )
        _state.value = ComposeState.AWAITING_CONFIRM
    }
}
