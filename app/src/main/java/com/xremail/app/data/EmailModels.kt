package com.xremail.app.data

enum class Priority { HIGH, MEDIUM, LOW, IGNORE }

enum class EmailCategory { PEOPLE, UPDATES, PROMOTIONS, NEWSLETTERS, TRANSACTIONAL }

enum class EmailAction { READ_FULL, READ_SUMMARY, AUTO_ARCHIVE, NEEDS_REPLY }

/**
 * Which Gmail-style folder an email lives in. Mapped onto Gmail's
 * INBOX / SENT / DRAFT labels on the backend; for mock mode it's the
 * source of truth for the Inbox / Sent / Drafts tab switcher.
 *
 * DRAFTS holds voice-composed messages that haven't been sent yet —
 * they're persisted through the repository so the user can revisit
 * them from the Drafts tab even after dismissing the compose UI
 * (matching Gmail behavior).
 */
enum class Mailbox { INBOX, SENT, DRAFTS }

data class Email(
    val id: String,
    val sender: String,
    val senderEmail: String,
    val subject: String,
    val body: String,
    val timestamp: String,
    val priority: Priority,
    val category: EmailCategory,
    val action: EmailAction,
    val isRead: Boolean,
    val isStarred: Boolean = false,
    val aiSummary: String,
    val urgencyScore: Float,
    val suggestedReply: String? = null,
    val replyConfidence: Float = 0f,
    val actionItems: List<ActionItem> = emptyList(),
    val attachments: List<Attachment> = emptyList(),
    val threadCount: Int = 1,
    val mailbox: Mailbox = Mailbox.INBOX,
)

data class Contact(
    val name: String,
    val email: String,
    val title: String,
    val organization: String,
    val avatarInitials: String,
)

data class ActionItem(
    val id: String,
    val description: String,
    val isCompleted: Boolean,
)

data class Attachment(
    val name: String,
    val type: String,
    val size: String,
)

/**
 * A composed or AI-generated email draft pending user review and send.
 * Produced by [com.xremail.app.backend.service.EmailRepository.composeFromVoice]
 * and displayed in the Focus layer's compose screen.
 */
data class EmailDraft(
    val to: List<String>,
    val cc: List<String> = emptyList(),
    val subject: String,
    val body: String,
    val threadId: String? = null,
    val inReplyTo: String? = null,
)
