package com.xremail.app.voice

import com.xremail.app.data.Email
import com.xremail.app.data.EmailCategory
import com.xremail.app.util.XrLog
import com.xremail.app.viewmodel.EmailViewModel
import com.xremail.app.viewmodel.InteractionTier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/**
 * Executes [EmailCommandTool.Command]s produced by [GeminiLiveManager].
 *
 * Commands are fire-and-forget: the model speaks its own confirmation via Live
 * audio, and UI state updates happen through [EmailViewModel] StateFlows.
 * Local TTS is used only for the deterministic `speak` / `summarize` paths
 * when we want a quick spoken readback without another model round-trip.
 *
 * [geminiText] is optional. When provided, `summarize` and empty-body
 * `draft_reply` trigger a one-shot Gemini text call so the spoken summary /
 * draft actually reflects the live email rather than a canned `aiSummary`
 * field. When null we fall back to the canned text path. Keeping it nullable
 * means demo mode (mock repo, no backend sign-in) still works even if
 * Firebase AI credentials aren't wired — the voice pipeline degrades rather
 * than crashes.
 */
class VoiceCommandDispatcher(
    private val viewModel: EmailViewModel,
    private val tts: TTSManager,
    private val geminiText: GeminiTextService? = null,
    private val scope: CoroutineScope = MainScope(),
) {

    fun dispatch(command: EmailCommandTool.Command) {
        XrLog.i(TAG, "dispatch: $command")
        try {
            dispatchInner(command)
        } catch (t: Throwable) {
            // Never let a bad command handler take down the voice pipeline —
            // a thrown exception here would propagate up through the commands
            // SharedFlow collector in MainActivity and kill the session.
            // Visible feedback path: user-facing toast (so the failure
            // isn't a silent no-op) PLUS spoken phrase (so they hear it
            // hands-free) PLUS detailed XrLog (for adb post-mortem).
            val cmdName = command::class.simpleName ?: "command"
            val msg = t.message ?: t::class.simpleName ?: "unknown error"
            XrLog.e(TAG, "dispatch failed for $command: $msg", t)
            viewModel.showError("Voice", "$cmdName failed: $msg", t)
            tts.speak("Sorry, that command failed.")
            viewModel.refreshUi()
        }
    }

    private fun dispatchInner(command: EmailCommandTool.Command) {
        val state = viewModel.uiState.value
        val selected: Email? = state.selectedEmail

        when (command) {
            is EmailCommandTool.Command.SelectEmail -> {
                findEmail(command.emailId)?.let(viewModel::selectEmail)
            }

            is EmailCommandTool.Command.ArchiveEmail -> {
                resolveEmail(command.emailId, selected)?.let(viewModel::archiveEmail)
            }

            is EmailCommandTool.Command.SnoozeEmail -> {
                resolveEmail(command.emailId, selected)?.let(viewModel::snoozeEmail)
            }

            is EmailCommandTool.Command.ForwardEmail -> {
                viewModel.forwardSelected()
            }

            is EmailCommandTool.Command.Reply -> {
                // Treat a model-issued reply(body=...) the same as a
                // draft_reply: NEVER auto-send. Always stamp the body
                // into the voice compose UI for review + readback first,
                // then wait for an explicit "send it" before firing.
                // This is the safety contract we promise the user — no
                // email ever leaves the device without an explicit
                // confirmation, regardless of which tool the model picked.
                handleDraftReply(body = command.body)
            }

            is EmailCommandTool.Command.Search -> {
                viewModel.loadEmails(query = command.query)
            }

            is EmailCommandTool.Command.ReadAloud -> {
                val target = resolveEmail(command.emailId, selected) ?: return
                tts.speak(target.body)
            }

            is EmailCommandTool.Command.Summarize -> {
                val target = resolveEmail(command.emailId, selected) ?: return
                summarizeAndSpeak(target)
            }

            is EmailCommandTool.Command.DraftReply -> {
                handleDraftReply(body = command.body)
            }

            is EmailCommandTool.Command.ReviseDraft -> {
                handleReviseDraft(command.body)
            }

            EmailCommandTool.Command.CancelDraft -> {
                handleCancelDraft()
            }

            is EmailCommandTool.Command.SendDraft -> {
                handleSendDraft()
            }

            EmailCommandTool.Command.ShowSendConfirmation -> {
                handleShowSendConfirmation()
            }

            EmailCommandTool.Command.ReadDraft -> {
                handleReadDraft()
            }

            EmailCommandTool.Command.ArmSendForVoice -> {
                // Consumed upstream in GeminiLiveManager (flips the
                // voiceSendArmed flag and returns the function response
                // directly). We still receive it here so it shows up in
                // dispatch logs for debugging, but no UI-side work is
                // needed — arming is a model-facing contract, not a
                // user-facing state change.
                XrLog.v(TAG, "arm_send_for_voice dispatched (no-op on dispatcher side)")
            }

            is EmailCommandTool.Command.FilterCategory -> {
                viewModel.filterByCategory(parseCategory(command.category))
            }

            EmailCommandTool.Command.ShowInbox -> {
                // Re-point: "show inbox" should land on INBOX tier (the actual
                // list view), not collapse to the ambient banner. The banner
                // is ambient/walk-mode, the inbox tier is the list of emails.
                XrLog.tier(viewModel.uiState.value.tier.name, "INBOX", "voice.show_inbox")
                viewModel.expandToInbox()
            }

            EmailCommandTool.Command.GoBack -> {
                handleCollapseOneTier()
            }

            EmailCommandTool.Command.Refresh -> {
                viewModel.refreshUi()
                tts.speak("Refreshed.")
            }

            is EmailCommandTool.Command.Speak -> {
                tts.speak(command.text)
            }

            is EmailCommandTool.Command.ExpandTier -> {
                handleExpandTier(command.target)
            }

            EmailCommandTool.Command.CollapseOneTier -> {
                handleCollapseOneTier()
            }

            EmailCommandTool.Command.NextUnread -> {
                XrLog.v(TAG, "navigateNextUnread() (voice 'next')")
                viewModel.navigateNextUnread()
            }

            EmailCommandTool.Command.GetInboxState -> {
                // No UI side-effect — the actual response is returned to
                // Gemini through GeminiLiveManager.handleFunctionCall,
                // which calls currentContextSummary() directly.
                XrLog.v(TAG, "get_inbox_state: dispatch no-op (response returned via tool channel)")
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Voice compose flow
    //
    // The contract (updated — NO automatic body readback):
    //   1. draft_reply(body=...) / reply(body=...)  → voiceReply(body)
    //      stamps the draft into the UI. The dispatcher does NOT TTS
    //      the body; Gemini asks the user "read, show, or send?" and
    //      the user's next turn drives one of:
    //        - read_draft   → dispatcher TTS's the body verbatim
    //        - show_send_confirmation → dispatcher pops the preview modal
    //        - arm_send_for_voice + send_draft → actually send
    //   2. revise_draft(body=...)  → reviseVoiceDraft(body); stays silent
    //      (Gemini re-asks "read, show, or send?").
    //   3. cancel_draft  → cancelCompose; TTS "cancelled".
    //   4. send_draft  → only fires if there's a draft; TTS "sent" on
    //      success or "no draft to send" if the model misfired.
    //
    // Rationale: earlier iterations auto-read every draft body aloud,
    // which the user explicitly asked us to stop doing. Now the choice
    // of read vs. show vs. send is the user's — expressed per turn —
    // and falls back to showing the preview when unclear.
    // ---------------------------------------------------------------------------

    private fun handleDraftReply(body: String?) {
        val selected = viewModel.uiState.value.selectedEmail
        if (selected == null) {
            tts.speak("Open an email first.")
            return
        }
        val cleaned = body?.trim().orEmpty()

        if (cleaned.isNotBlank()) {
            viewModel.voiceReply(cleaned)
            return
        }

        // Empty-body draft_reply: try the text service to generate one
        // before giving up. Keeps the "hey, draft a reply" path useful
        // even when the Live model forgets to fill `body`.
        val textService = geminiText
        if (textService == null) {
            viewModel.voiceReply("")
            tts.speak("I couldn't draft that. Try again?")
            return
        }
        // Optimistically flip to voice-compose (isGenerating) so the UI
        // shows a spinner while we wait on the model.
        viewModel.voiceReply("")
        scope.launch {
            textService.draftReply(
                subject = selected.subject,
                body = selected.body,
            ).onSuccess { drafted ->
                viewModel.reviseVoiceDraft(drafted)
            }.onFailure {
                tts.speak("I couldn't draft that. Try again?")
            }
        }
    }

    private fun handleReadDraft() {
        val state = viewModel.uiState.value
        val body = state.voiceDraft?.draftText?.trim().orEmpty()
        if (!state.isVoiceComposing || body.isBlank()) {
            XrLog.w(TAG, "read_draft with no active draft — ignoring")
            tts.speak("No draft to read.")
            return
        }
        tts.speak(body)
    }

    private fun handleShowSendConfirmation() {
        val state = viewModel.uiState.value
        if (!state.isVoiceComposing || state.voiceDraft?.draftText.isNullOrBlank()) {
            XrLog.w(TAG, "show_send_confirmation with no active draft — ignoring")
            tts.speak("No draft to preview.")
            return
        }
        viewModel.showSendConfirmation()
    }

    private fun summarizeAndSpeak(target: Email) {
        val textService = geminiText
        if (textService == null) {
            // Fallback path used by demo mode: canned aiSummary / subject.
            tts.speak(target.aiSummary.ifBlank { target.subject })
            return
        }
        // Speak the canned summary first for immediate feedback, then
        // override with Gemini's live summary when it lands (if different).
        val canned = target.aiSummary.ifBlank { target.subject }
        tts.speak(canned)
        scope.launch {
            textService.summarizeEmail(subject = target.subject, body = target.body)
                .onSuccess { live ->
                    if (live.isNotBlank() && live != canned) {
                        tts.speak(live)
                    }
                }
        }
    }

    private fun handleReviseDraft(newBody: String) {
        val state = viewModel.uiState.value
        if (!state.isVoiceComposing || state.voiceDraft == null) {
            XrLog.w(TAG, "revise_draft with no active draft — ignoring")
            tts.speak("No draft to revise.")
            return
        }
        // Stay silent — Gemini re-asks "read, show, or send?". Auto-
        // speaking the revised body was the exact behaviour the user
        // asked us to kill.
        viewModel.reviseVoiceDraft(newBody.trim())
    }

    private fun handleCancelDraft() {
        val state = viewModel.uiState.value
        if (!state.isVoiceComposing) {
            XrLog.v(TAG, "cancel_draft with no active draft — no-op")
            return
        }
        viewModel.cancelCompose()
        tts.speak("Cancelled.")
    }

    private fun handleSendDraft() {
        val sent = viewModel.sendDraft()
        if (sent) {
            tts.speak("Sent.")
        } else {
            // Common cause: the model called send_draft prematurely
            // (without a prior draft_reply) or the user said "send it"
            // when no draft was up. Tell the user, don't pop a fake
            // success toast.
            XrLog.w(TAG, "send_draft with no draft on screen — refusing")
            tts.speak("No draft to send. Say reply first.")
        }
    }

    private fun handleExpandTier(rawTarget: String) {
        val current = viewModel.uiState.value.tier.name
        when (rawTarget.trim().lowercase()) {
            "notifications", "notification", "notification_cards", "cards" -> {
                XrLog.tier(current, "NOTIFICATION_CARDS", "voice.expand_tier($rawTarget)")
                viewModel.expandToNotificationCards()
            }
            "triage", "inbox", "list" -> {
                XrLog.tier(current, "INBOX", "voice.expand_tier($rawTarget)")
                viewModel.expandToInbox()
            }
            "focus", "reader", "read", "open" -> {
                XrLog.tier(current, "FOCUS", "voice.expand_tier($rawTarget)")
                viewModel.expandToFocus()
            }
            else -> {
                XrLog.w(TAG, "expand_tier got unknown target='$rawTarget' — defaulting to INBOX")
                viewModel.expandToInbox()
            }
        }
    }

    private fun handleCollapseOneTier() {
        val current = viewModel.uiState.value.tier
        when (current) {
            InteractionTier.FOCUS -> {
                XrLog.tier("FOCUS", "INBOX", "voice.collapse_one_tier")
                viewModel.collapseToInbox()
            }
            InteractionTier.INBOX -> {
                XrLog.tier("INBOX", "NOTIFICATION_CARDS", "voice.collapse_one_tier")
                viewModel.collapseToNotificationCards()
            }
            InteractionTier.NOTIFICATION_CARDS -> {
                XrLog.tier("NOTIFICATION_CARDS", "AMBIENT_HUD", "voice.collapse_one_tier")
                viewModel.collapseFromNotificationCards()
            }
            InteractionTier.AMBIENT_HUD -> {
                XrLog.v(TAG, "collapse_one_tier from AMBIENT_HUD: nothing to collapse")
            }
        }
    }

    private fun resolveEmail(id: String?, fallback: Email?): Email? {
        if (id != null) findEmail(id)?.let { return it }
        return fallback
    }

    private fun findEmail(id: String): Email? =
        viewModel.uiState.value.emails.firstOrNull { it.id == id }

    private fun parseCategory(raw: String): EmailCategory? {
        val token = raw.trim().uppercase().replace(' ', '_')
        return EmailCategory.values().firstOrNull { it.name == token }
    }

    /**
     * Generates a context block sent back to Gemini Live so it can reason about
     * the inbox without waiting on another tool call. Includes the top 5 unread
     * emails (sender, subject, priority) so the model can answer questions like
     * "what's urgent?" or "anything from Sarah?" purely from context.
     */
    fun currentContextSummary(): String {
        val state = viewModel.uiState.value
        val sel = state.selectedEmail
        val unreadTop = state.emails
            .asSequence()
            .filter { !it.isRead }
            .sortedByDescending { it.priority.ordinal }
            .take(5)
            .toList()

        return buildString {
            append("tier=${state.tier.name}; ")
            append("total=${state.emails.size}; ")
            append("unread=${state.emails.count { !it.isRead }}")
            if (sel != null) {
                append("\nselected: id=${sel.id}, from=${sel.sender}, subject=\"${sel.subject}\", priority=${sel.priority.name}")
                append("\nselected_body: \"${bodyExcerpt(sel.body, MAX_SELECTED_BODY_CHARS)}\"")
            } else {
                append("\nselected: none")
            }
            if (unreadTop.isNotEmpty()) {
                append("\ntop unread:")
                unreadTop.forEach { e ->
                    append("\n  - id=${e.id}, from=${e.sender}, subject=\"${e.subject}\", priority=${e.priority.name}")
                    append("\n    body: \"${bodyExcerpt(e.body, MAX_LIST_BODY_CHARS)}\"")
                }
            }
        }
    }

    /**
     * Trim an email body for inclusion in the Gemini context. Keeps roughly
     * the first [maxChars] characters and collapses runs of whitespace so
     * one email doesn't blow the context window. Just enough for Gemini to
     * answer "what did Alex say?" or "what's the gist of the Stripe email"
     * without a separate tool call.
     */
    private fun bodyExcerpt(body: String, maxChars: Int): String {
        val collapsed = body.replace(Regex("""\s+"""), " ").trim()
        if (collapsed.length <= maxChars) return collapsed.escapeForContext()
        return (collapsed.take(maxChars).trimEnd() + "…").escapeForContext()
    }

    private fun String.escapeForContext(): String =
        this.replace("\"", "'").replace("\n", " ")

    companion object {
        private const val TAG = "VoiceDispatcher"
        // Body excerpt budget for the SELECTED email (the user is most
        // likely asking about it, so we give it more room).
        private const val MAX_SELECTED_BODY_CHARS = 600
        // Per-email budget for the top-unread list. Five emails × 220 ≈
        // 1.1k chars of body context — well under the model's prefill
        // budget but enough to answer "what's everyone asking about?".
        private const val MAX_LIST_BODY_CHARS = 220
    }
}
