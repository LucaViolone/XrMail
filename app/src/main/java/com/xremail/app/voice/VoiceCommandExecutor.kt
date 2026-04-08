package com.xremail.app.voice

import com.xremail.app.data.EmailCategory
import com.xremail.app.data.Priority
import com.xremail.app.viewmodel.EmailViewModel
import com.xremail.app.viewmodel.InteractionTier

/**
 * Executes Gemini Live API function-call commands against the ViewModel,
 * TTSManager, and VoiceComposeManager. This is the single point where
 * voice commands become interface actions.
 *
 * Every command produces either a state change (via ViewModel), an audio
 * response (via TTS), or both. Gemini acts as the full interface commander —
 * anything you can do with gestures, you can do with voice.
 */
class VoiceCommandExecutor(
    private val viewModel: EmailViewModel,
    private val ttsManager: TTSManager,
    private val voiceCompose: VoiceComposeManager,
    private val geminiLive: GeminiLiveManager,
) {

    /**
     * Execute a command and return a spoken response for Gemini to relay
     * back to the user. Returns null if no spoken feedback is needed.
     */
    fun execute(command: EmailCommandTool.Command): String? {
        return when (command) {
            // -- Tier navigation --
            is EmailCommandTool.Command.ExpandToNotifications -> {
                viewModel.expandToNotificationCards()
                "Showing notifications"
            }
            is EmailCommandTool.Command.ExpandToTriage -> {
                viewModel.expandToTriage()
                "Opening inbox"
            }
            is EmailCommandTool.Command.ExpandToFocus -> {
                viewModel.expandToFocus()
                "Expanding to full view"
            }
            is EmailCommandTool.Command.CollapseToHud -> {
                viewModel.collapseToHud()
                "Minimized"
            }
            is EmailCommandTool.Command.CollapseOneTier -> {
                collapseOneTier()
            }

            // -- Email selection --
            is EmailCommandTool.Command.SelectEmail -> {
                val email = viewModel.uiState.value.emails.find { it.id == command.emailId }
                if (email != null) {
                    viewModel.selectEmail(email)
                    "Selected email from ${email.sender}"
                } else {
                    "Email not found"
                }
            }
            is EmailCommandTool.Command.SelectNextUnread -> {
                viewModel.navigateNextUnread()
                val selected = viewModel.uiState.value.selectedEmail
                if (selected != null) {
                    "Next unread from ${selected.sender}: ${selected.aiSummary}"
                } else {
                    "No more unread emails"
                }
            }

            // -- Email actions --
            is EmailCommandTool.Command.ArchiveSelected -> {
                val sender = viewModel.uiState.value.selectedEmail?.sender
                viewModel.archiveSelected()
                if (sender != null) "Archived email from $sender" else "No email selected"
            }
            is EmailCommandTool.Command.ArchiveEmail -> {
                val email = viewModel.uiState.value.emails.find { it.id == command.emailId }
                if (email != null) {
                    viewModel.archiveEmail(email)
                    "Archived email from ${email.sender}"
                } else {
                    "Email not found"
                }
            }
            is EmailCommandTool.Command.SnoozeSelected -> {
                val sender = viewModel.uiState.value.selectedEmail?.sender
                viewModel.snoozeSelected()
                if (sender != null) "Snoozed email from $sender" else "No email selected"
            }
            is EmailCommandTool.Command.SnoozeEmail -> {
                val email = viewModel.uiState.value.emails.find { it.id == command.emailId }
                if (email != null) {
                    viewModel.snoozeEmail(email)
                    val untilText = command.until?.let { " until $it" } ?: ""
                    "Snoozed email from ${email.sender}$untilText"
                } else {
                    "Email not found"
                }
            }
            is EmailCommandTool.Command.StarSelected -> {
                val email = viewModel.uiState.value.selectedEmail
                if (email != null) {
                    viewModel.toggleStar(email)
                    if (email.isStarred) "Unstarred" else "Starred"
                } else {
                    "No email selected"
                }
            }
            is EmailCommandTool.Command.ForwardSelected -> {
                viewModel.forwardSelected()
                "Opening forward"
            }

            // -- TTS / Reading --
            is EmailCommandTool.Command.ReadTopPriority -> {
                val topEmail = viewModel.prioritySortedEmails().firstOrNull { !it.isRead }
                if (topEmail != null) {
                    viewModel.selectEmail(topEmail)
                    ttsManager.speak(topEmail.aiSummary)
                    null // TTS handles the audio
                } else {
                    "No unread emails"
                }
            }
            is EmailCommandTool.Command.ReadNextUnread -> {
                viewModel.navigateNextUnread()
                val email = viewModel.uiState.value.selectedEmail
                if (email != null) {
                    ttsManager.speak(email.aiSummary)
                    null
                } else {
                    "No more unread emails"
                }
            }
            is EmailCommandTool.Command.ReadEmail -> {
                val email = viewModel.uiState.value.emails.find { it.id == command.emailId }
                if (email != null) {
                    viewModel.selectEmail(email)
                    ttsManager.speak(email.body)
                    null
                } else {
                    "Email not found"
                }
            }
            is EmailCommandTool.Command.ReadSummary -> {
                val email = viewModel.uiState.value.selectedEmail
                if (email != null) {
                    ttsManager.speak(email.aiSummary)
                    null
                } else {
                    "No email selected"
                }
            }
            is EmailCommandTool.Command.StopTts -> {
                ttsManager.stop()
                null
            }
            is EmailCommandTool.Command.PauseTts -> {
                ttsManager.pause()
                null
            }
            is EmailCommandTool.Command.ResumeTts -> {
                ttsManager.resume()
                null
            }

            // -- Compose / Reply --
            is EmailCommandTool.Command.VoiceReply -> {
                val email = viewModel.uiState.value.selectedEmail
                if (email != null) {
                    viewModel.voiceReply(command.briefInstruction)
                    val draft = viewModel.uiState.value.voiceDraft
                    if (draft != null) {
                        ttsManager.speak("Draft: ${draft.draftText}")
                    }
                    null
                } else {
                    "No email selected to reply to"
                }
            }
            is EmailCommandTool.Command.ConfirmSend -> {
                viewModel.confirmSend()
                "Sent"
            }
            is EmailCommandTool.Command.CancelCompose -> {
                viewModel.cancelCompose()
                voiceCompose.cancel()
                "Draft cancelled"
            }
            is EmailCommandTool.Command.EditDraft -> {
                voiceCompose.editDraft(command.instruction)
                "Editing draft"
            }

            // -- Filtering --
            is EmailCommandTool.Command.FilterCategory -> {
                val category = parseCategoryName(command.category)
                viewModel.filterByCategory(category)
                if (category != null) {
                    "Showing ${command.category} emails"
                } else {
                    "Showing all emails"
                }
            }
            is EmailCommandTool.Command.ShowAllEmails -> {
                viewModel.filterByCategory(null)
                "Showing all emails"
            }
            is EmailCommandTool.Command.Search -> {
                // Phase 1: search not yet implemented, acknowledge the intent
                "Search for '${command.query}' — coming soon"
            }

            // -- Utility --
            is EmailCommandTool.Command.WhatIsUrgent -> {
                val urgent = viewModel.prioritySortedEmails()
                    .filter { !it.isRead && it.priority == Priority.HIGH }
                if (urgent.isEmpty()) {
                    "Nothing urgent right now"
                } else {
                    val summaries = urgent.take(3).joinToString(". ") {
                        "${it.sender}: ${it.aiSummary}"
                    }
                    ttsManager.speak(summaries)
                    null
                }
            }
            is EmailCommandTool.Command.HowManyUnread -> {
                val count = viewModel.uiState.value.unreadCount
                "$count unread email${if (count != 1) "s" else ""}"
            }
        }
    }

    private fun collapseOneTier(): String {
        return when (viewModel.uiState.value.tier) {
            InteractionTier.FOCUS -> {
                viewModel.collapseToTriage()
                "Collapsed to triage"
            }
            InteractionTier.TRIAGE -> {
                viewModel.collapseToHud()
                "Minimized"
            }
            InteractionTier.NOTIFICATION_CARDS -> {
                viewModel.collapseFromNotificationCards()
                "Minimized"
            }
            InteractionTier.AMBIENT_HUD -> {
                "Already minimized"
            }
        }
    }

    private fun parseCategoryName(name: String): EmailCategory? {
        return when (name.lowercase().trim()) {
            "people", "personal" -> EmailCategory.PEOPLE
            "updates", "update" -> EmailCategory.UPDATES
            "promotions", "promos", "promo" -> EmailCategory.PROMOTIONS
            "newsletters", "newsletter", "news" -> EmailCategory.NEWSLETTERS
            "transactional", "transactions", "receipts" -> EmailCategory.TRANSACTIONAL
            "all", "" -> null
            else -> null
        }
    }
}
