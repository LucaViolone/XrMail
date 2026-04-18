package com.xremail.app.voice

import android.util.Log
import com.xremail.app.data.Email
import com.xremail.app.data.EmailCategory
import com.xremail.app.viewmodel.EmailViewModel

/**
 * Executes [EmailCommandTool.Command]s produced by [GeminiLiveManager].
 *
 * Commands are fire-and-forget: the model speaks its own confirmation via Live
 * audio, and UI state updates happen through [EmailViewModel] StateFlows.
 * Local TTS is used only for the deterministic `speak` / `summarize` paths
 * when we want a quick spoken readback without another model round-trip.
 */
class VoiceCommandDispatcher(
    private val viewModel: EmailViewModel,
    private val tts: TTSManager,
) {

    fun dispatch(command: EmailCommandTool.Command) {
        Log.i(TAG, "dispatch: $command")
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
                val body = command.body
                if (body.isNullOrBlank()) {
                    viewModel.startCompose()
                } else {
                    viewModel.voiceReply(body)
                }
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
                tts.speak(target.aiSummary.ifBlank { target.subject })
            }

            is EmailCommandTool.Command.DraftReply -> {
                viewModel.startVoiceCompose()
            }

            is EmailCommandTool.Command.SendDraft -> {
                viewModel.sendDraft()
            }

            is EmailCommandTool.Command.FilterCategory -> {
                viewModel.filterByCategory(parseCategory(command.category))
            }

            EmailCommandTool.Command.ShowInbox -> {
                viewModel.collapseToHud()
            }

            EmailCommandTool.Command.GoBack -> {
                viewModel.collapseToHud()
            }

            is EmailCommandTool.Command.Speak -> {
                tts.speak(command.text)
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
            } else {
                append("\nselected: none")
            }
            if (unreadTop.isNotEmpty()) {
                append("\ntop unread:")
                unreadTop.forEach { e ->
                    append("\n  - id=${e.id}, from=${e.sender}, subject=\"${e.subject}\", priority=${e.priority.name}")
                }
            }
        }
    }

    companion object {
        private const val TAG = "VoiceDispatcher"
    }
}
