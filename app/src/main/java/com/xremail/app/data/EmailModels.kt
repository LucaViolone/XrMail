package com.xremail.app.data

enum class Priority { HIGH, MEDIUM, LOW, IGNORE }

enum class EmailCategory { PEOPLE, UPDATES, PROMOTIONS, NEWSLETTERS, TRANSACTIONAL }

enum class EmailAction { READ_FULL, READ_SUMMARY, AUTO_ARCHIVE, NEEDS_REPLY }

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
