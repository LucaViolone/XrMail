package com.xremail.app.voice

import com.xremail.app.viewmodel.VoiceDraft
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Orchestrates the voice-first compose flow:
 * 1. User gives brief instruction ("Reply saying I'll revise by Friday")
 * 2. GeminiLiveManager generates a full draft
 * 3. TTSManager reads it back
 * 4. User confirms, edits by voice, or cancels
 *
 * Production: pipes GeminiLiveManager.commands for edit/confirm/cancel,
 * and GeminiLiveManager.spokenResponses for draft text.
 *
 * NOTE: we intentionally do NOT auto-arm GeminiLiveManager here — the live
 * session is summoned explicitly by the user via the Gemini pill so we don't
 * burn quota / open the mic the moment the user hits "Voice reply" with a
 * canned body. Revise once a real always-on voice pipeline lands.
 */
class VoiceComposeManager(
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

    /**
     * Applies a voice edit instruction to the current draft.
     *
     * In production, the instruction is forwarded to Gemini Live which generates
     * a revised body and calls [onDraftGenerated] with the result. For the
     * prototype we simulate the round-trip with a short delay, then append a
     * note so it's obvious in demos that the edit was received.
     */
    fun editDraft(instruction: String, scope: CoroutineScope = CoroutineScope(Dispatchers.Main)) {
        val current = _draft.value ?: return
        _state.value = ComposeState.GENERATING
        _draft.value = current.copy(isGenerating = true)

        scope.launch {
            delay(900)
            val revised = "${current.draftText}\n\n[Edited: $instruction]"
            onDraftGenerated(revised, confidence = 0.80f)
        }
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
