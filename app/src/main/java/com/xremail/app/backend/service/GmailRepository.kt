package com.xremail.app.backend.service

import com.xremail.app.backend.api.*
import com.xremail.app.data.*
import retrofit2.Response
import java.text.SimpleDateFormat
import java.util.*

/**
 * Production [EmailRepository] implementation — communicates with the
 * XrMail Ktor backend over HTTP using Retrofit.
 *
 * All network calls are wrapped in [safeCall] which converts HTTP errors
 * and exceptions into typed [Result.failure] values so the ViewModel can
 * react without try/catch clutter.
 *
 * DTO-to-domain mapping happens here, keeping the ViewModel and UI layers
 * completely decoupled from the network shape.
 */
class GmailRepository(
    private val api: XrMailApiService,
) : EmailRepository {

    // ---------------------------------------------------------------------------
    // Email list
    // ---------------------------------------------------------------------------

    override suspend fun listEmails(
        query: String,
        maxResults: Int,
        pageToken: String?,
        labels: List<String>,
    ): Result<List<Email>> = safeCall {
        api.listEmails(
            query = query,
            maxResults = maxResults,
            pageToken = pageToken,
            labels = labels.joinToString(","),
        ).unwrap().emails.map { it.toDomain() }
    }

    override suspend fun getEmail(messageId: String): Result<Email> = safeCall {
        api.getEmail(messageId).unwrap().toDomain()
    }

    // ---------------------------------------------------------------------------
    // Send / label mutations
    // ---------------------------------------------------------------------------

    override suspend fun sendEmail(draft: EmailDraft): Result<Unit> = safeCall {
        api.sendEmail(
            SendEmailRequest(
                to = draft.to,
                cc = draft.cc,
                subject = draft.subject,
                body = draft.body,
                threadId = draft.threadId,
                inReplyTo = draft.inReplyTo,
            )
        ).unwrap()
        Unit
    }

    override suspend fun markAsRead(messageId: String): Result<Unit> = safeCall {
        api.modifyLabels(
            messageId,
            ModifyLabelsRequest(removeLabelIds = listOf("UNREAD"))
        ).unwrap()
        Unit
    }

    override suspend fun archive(messageId: String): Result<Unit> = safeCall {
        api.modifyLabels(
            messageId,
            ModifyLabelsRequest(removeLabelIds = listOf("INBOX"))
        ).unwrap()
        Unit
    }

    override suspend fun setStarred(messageId: String, starred: Boolean): Result<Unit> = safeCall {
        val request = if (starred) {
            ModifyLabelsRequest(addLabelIds = listOf("STARRED"))
        } else {
            ModifyLabelsRequest(removeLabelIds = listOf("STARRED"))
        }
        api.modifyLabels(messageId, request).unwrap()
        Unit
    }

    override suspend fun trash(messageId: String): Result<Unit> = safeCall {
        api.trashEmail(messageId).unwrap()
        Unit
    }

    // ---------------------------------------------------------------------------
    // Contact lookup (Phase 1: derived from sender field; Phase 2: People API)
    // ---------------------------------------------------------------------------

    override suspend fun getContact(email: String): Contact? {
        // Phase 1: construct a basic contact from the email address itself.
        // Phase 2: query the backend's /contacts/{email} endpoint (Google People API).
        val name = email.substringBefore("@")
            .replace(".", " ")
            .split(" ")
            .joinToString(" ") { it.replaceFirstChar(Char::uppercaseChar) }
        val initials = name.split(" ").take(2).map { it.firstOrNull() ?: ' ' }
            .joinToString("").uppercase()
        return Contact(
            name = name,
            email = email,
            title = "",
            organization = email.substringAfter("@"),
            avatarInitials = initials,
        )
    }

    // ---------------------------------------------------------------------------
    // AI routes
    // ---------------------------------------------------------------------------

    override suspend fun getSummary(messageId: String): Result<String> = safeCall {
        api.summarizeEmail(SummarizeRequest(messageId)).unwrap().summary
    }

    override suspend fun getReplySuggestions(messageId: String): Result<List<String>> = safeCall {
        api.getReplySuggestions(ReplySuggestionsRequest(messageId)).unwrap().suggestions
    }

    override suspend fun composeFromVoice(
        transcript: String,
        replyToMessageId: String?,
    ): Result<EmailDraft> = safeCall {
        val dto = api.composeFromVoice(
            ComposeFromVoiceRequest(
                transcript = transcript,
                replyToMessageId = replyToMessageId,
            )
        ).unwrap()

        EmailDraft(
            to = dto.to,
            cc = dto.cc,
            subject = dto.subject,
            body = dto.body,
        )
    }

    // ---------------------------------------------------------------------------
    // DTO → Domain model mappers
    // ---------------------------------------------------------------------------

    private fun EmailDto.toDomain(): Email {
        val dateStr = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
            .format(Date(date))

        // Infer Priority + Category + Action from Gmail labels and content heuristics.
        // In production these come from the EmailClassifier (Gemini batch classify).
        val priority = when {
            labels.contains("IMPORTANT") -> Priority.HIGH
            labels.contains("CATEGORY_PERSONAL") -> Priority.MEDIUM
            labels.contains("CATEGORY_PROMOTIONS") -> Priority.LOW
            else -> Priority.MEDIUM
        }
        val category = when {
            labels.contains("CATEGORY_PROMOTIONS") -> EmailCategory.PROMOTIONS
            labels.contains("CATEGORY_UPDATES") -> EmailCategory.UPDATES
            labels.contains("CATEGORY_SOCIAL") -> EmailCategory.NEWSLETTERS
            else -> EmailCategory.PEOPLE
        }
        val action = when {
            !isRead -> EmailAction.READ_SUMMARY
            priority == Priority.HIGH -> EmailAction.NEEDS_REPLY
            else -> EmailAction.READ_FULL
        }

        return Email(
            id = id,
            sender = from.name.ifBlank { from.email },
            senderEmail = from.email,
            subject = subject,
            body = bodyText,
            timestamp = dateStr,
            priority = priority,
            category = category,
            action = action,
            isRead = isRead,
            isStarred = isStarred,
            aiSummary = aiSummary ?: snippet,
            urgencyScore = if (priority == Priority.HIGH) 0.85f else 0.4f,
            suggestedReply = null,  // Populated separately via /ai/replies
            replyConfidence = 0f,
            attachments = attachments.map { a ->
                Attachment(
                    name = a.filename,
                    type = a.mimeType,
                    size = formatSize(a.sizeBytes),
                )
            },
            threadCount = 1,
        )
    }

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "${bytes}B"
        bytes < 1024 * 1024 -> "${bytes / 1024}KB"
        else -> "${"%.1f".format(bytes / (1024.0 * 1024.0))}MB"
    }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/**
 * Executes a suspending [block] and wraps its result in a [Result].
 * Converts Retrofit [Response] errors into exceptions automatically.
 */
private suspend fun <T> safeCall(block: suspend () -> T): Result<T> =
    runCatching { block() }

/**
 * Extracts the data from an [ApiResponse], throwing a descriptive exception
 * if the response indicates failure or the data is null.
 */
private fun <T> Response<ApiResponse<T>>.unwrap(): T {
    val body = body()
    if (!isSuccessful || body == null) {
        throw RuntimeException("API error ${code()}: ${errorBody()?.string()}")
    }
    if (!body.success || body.data == null) {
        val err = body.error
        throw RuntimeException("[${err?.code}] ${err?.message ?: "Unknown error"}")
    }
    return body.data
}
