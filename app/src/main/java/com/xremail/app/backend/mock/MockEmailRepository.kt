package com.xremail.app.backend.mock

import com.xremail.app.backend.service.EmailRepository
import com.xremail.app.data.*
import kotlinx.coroutines.delay

/**
 * Mock [EmailRepository] for Phase 1 / offline development.
 *
 * Backed by [MockData] — no network calls are made. Simulated async delays
 * mimic real network latency so the UI loading states are testable.
 *
 * Switch to [com.xremail.app.backend.service.GmailRepository] in production by
 * changing the binding in [com.xremail.app.viewmodel.EmailViewModel].
 */
class MockEmailRepository : EmailRepository {

    // In-memory mutable copy so mutations (archive, star, etc.) are reflected
    // immediately in the UI without re-fetching from the server.
    private val emails = MockData.emails.toMutableList()

    // ---------------------------------------------------------------------------
    // Email list
    // ---------------------------------------------------------------------------

    override suspend fun listEmails(
        query: String,
        maxResults: Int,
        pageToken: String?,
        labels: List<String>,
    ): Result<List<Email>> {
        delay(SIMULATED_DELAY_MS)
        val filtered = if (query.isBlank()) {
            emails.toList()
        } else {
            val q = query.lowercase()
            emails.filter {
                it.subject.lowercase().contains(q) ||
                it.sender.lowercase().contains(q) ||
                it.body.lowercase().contains(q)
            }
        }
        return Result.success(filtered.take(maxResults))
    }

    override suspend fun getEmail(messageId: String): Result<Email> {
        delay(SIMULATED_DELAY_MS)
        val email = emails.find { it.id == messageId }
            ?: return Result.failure(NoSuchElementException("Email $messageId not found"))
        return Result.success(email)
    }

    // ---------------------------------------------------------------------------
    // Send / mutations
    // ---------------------------------------------------------------------------

    override suspend fun sendEmail(draft: EmailDraft): Result<Unit> {
        delay(SIMULATED_DELAY_MS)
        // Phase 1: no-op (draft is discarded)
        return Result.success(Unit)
    }

    override suspend fun markAsRead(messageId: String): Result<Unit> {
        mutate(messageId) { it.copy(isRead = true) }
        return Result.success(Unit)
    }

    override suspend fun archive(messageId: String): Result<Unit> {
        emails.removeIf { it.id == messageId }
        return Result.success(Unit)
    }

    override suspend fun setStarred(messageId: String, starred: Boolean): Result<Unit> {
        mutate(messageId) { it.copy(isStarred = starred) }
        return Result.success(Unit)
    }

    override suspend fun trash(messageId: String): Result<Unit> {
        emails.removeIf { it.id == messageId }
        return Result.success(Unit)
    }

    // ---------------------------------------------------------------------------
    // Contact lookup
    // ---------------------------------------------------------------------------

    override suspend fun getContact(email: String): Contact? {
        return MockData.getContactForEmail(
            emails.find { it.senderEmail == email } ?: return null
        )
    }

    // ---------------------------------------------------------------------------
    // AI (returns pre-baked mock responses)
    // ---------------------------------------------------------------------------

    override suspend fun getSummary(messageId: String): Result<String> {
        delay(SIMULATED_AI_DELAY_MS)
        val email = emails.find { it.id == messageId }
            ?: return Result.failure(NoSuchElementException("Email $messageId not found"))
        return Result.success(email.aiSummary)
    }

    override suspend fun getReplySuggestions(messageId: String): Result<List<String>> {
        delay(SIMULATED_AI_DELAY_MS)
        val email = emails.find { it.id == messageId }
            ?: return Result.failure(NoSuchElementException("Email $messageId not found"))

        // Return the pre-baked suggestion from MockData, plus two generic extras
        val primary = email.suggestedReply ?: "Thanks, I'll look into it."
        return Result.success(
            listOf(
                primary,
                "Got it, thanks!",
                "I'll follow up on this soon.",
            )
        )
    }

    override suspend fun composeFromVoice(
        transcript: String,
        replyToMessageId: String?,
    ): Result<EmailDraft> {
        delay(SIMULATED_AI_DELAY_MS)

        // Phase 1: echo the transcript as the body; infer reply context if available
        val replyTo = replyToMessageId?.let { id -> emails.find { it.id == id } }
        return Result.success(
            EmailDraft(
                to = listOfNotNull(replyTo?.senderEmail),
                subject = replyTo?.subject?.let { "Re: $it" } ?: "",
                body = transcript,
                threadId = null,
                inReplyTo = null,
            )
        )
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private fun mutate(messageId: String, transform: (Email) -> Email) {
        val idx = emails.indexOfFirst { it.id == messageId }
        if (idx >= 0) emails[idx] = transform(emails[idx])
    }

    companion object {
        private const val SIMULATED_DELAY_MS = 300L
        private const val SIMULATED_AI_DELAY_MS = 800L
    }
}
