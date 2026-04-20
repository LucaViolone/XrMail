package com.xremail.app.backend.mock

import com.xremail.app.backend.service.EmailRepository
import com.xremail.app.data.*
import com.xremail.app.util.XrLog
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
        // Label-aware filtering so Inbox/Sent tabs return the right set.
        // Gmail uses "INBOX" / "SENT" string labels; we map those onto the
        // [Email.mailbox] enum. Unknown/empty labels default to INBOX so
        // existing call sites that don't care about folders keep working.
        val mailbox = when {
            labels.any { it.equals("SENT", ignoreCase = true) } -> Mailbox.SENT
            labels.any {
                it.equals("DRAFT", ignoreCase = true) ||
                it.equals("DRAFTS", ignoreCase = true)
            } -> Mailbox.DRAFTS
            else -> Mailbox.INBOX
        }
        val inFolder = emails.filter { it.mailbox == mailbox }
        val filtered = if (query.isBlank()) {
            inFolder
        } else {
            val q = query.lowercase()
            inFolder.filter {
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
        // Mock mode is the only place we can actually "send" — we don't
        // have an SMTP pipe, so instead we materialize the outgoing
        // message as a synthetic Email tagged [Mailbox.SENT] and drop it
        // at the top of the list. That way the Sent tab shows exactly
        // what the user just dictated, making the voice-compose flow
        // end-to-end verifiable without a real inbox.
        val to = draft.to.firstOrNull() ?: "unknown@recipient"
        val recipientName = emails.firstOrNull { it.senderEmail == to }?.sender
            ?: to.substringBefore('@').replaceFirstChar { it.titlecase(Locale.US) }
        val now = Date()
        val timestamp = SimpleDateFormat("h:mm a", Locale.US).format(now)
        val sent = Email(
            id = "sent-${now.time}",
            sender = "To: $recipientName",
            senderEmail = to,
            subject = draft.subject.ifBlank { "(no subject)" },
            body = draft.body,
            timestamp = timestamp,
            priority = Priority.LOW,
            category = EmailCategory.PEOPLE,
            action = EmailAction.READ_FULL,
            isRead = true,
            aiSummary = "Sent message — ${draft.body.take(80)}",
            urgencyScore = 0f,
            mailbox = Mailbox.SENT,
        )
        emails.add(0, sent)

        XrLog.i(
            "MockRepo",
            "sendEmail: appended to SENT id=${sent.id} to=${draft.to} " +
                "subject=\"${sent.subject}\" bodyLen=${draft.body.length}",
        )
        XrLog.d("MockRepo", "sendEmail body: ${draft.body}")
        return Result.success(Unit)
    }

    override suspend fun saveDraft(draft: EmailDraft, existingId: String?): Result<String> {
        delay(SIMULATED_DELAY_MS)
        val to = draft.to.firstOrNull() ?: "unknown@recipient"
        val recipientName = emails.firstOrNull { it.senderEmail == to }?.sender
            ?: to.substringBefore('@').replaceFirstChar { it.titlecase(Locale.US) }
        val now = Date()
        val timestamp = SimpleDateFormat("h:mm a", Locale.US).format(now)
        val id = existingId ?: "draft-${now.time}"
        val drafted = Email(
            id = id,
            sender = "Draft: to $recipientName",
            senderEmail = to,
            subject = draft.subject.ifBlank { "(no subject)" },
            body = draft.body,
            timestamp = timestamp,
            priority = Priority.LOW,
            category = EmailCategory.PEOPLE,
            action = EmailAction.NEEDS_REPLY,
            isRead = true,
            aiSummary = "Draft — ${draft.body.take(80)}",
            urgencyScore = 0f,
            mailbox = Mailbox.DRAFTS,
        )
        val existingIdx = emails.indexOfFirst { it.id == id }
        if (existingIdx >= 0) {
            emails[existingIdx] = drafted
            XrLog.i(
                "MockRepo",
                "saveDraft: UPDATED id=$id to=${draft.to} " +
                    "subject=\"${drafted.subject}\" bodyLen=${draft.body.length}",
            )
        } else {
            emails.add(0, drafted)
            XrLog.i(
                "MockRepo",
                "saveDraft: APPENDED id=$id to=${draft.to} " +
                    "subject=\"${drafted.subject}\" bodyLen=${draft.body.length}",
            )
        }
        return Result.success(id)
    }

    override suspend fun deleteDraft(draftId: String): Result<Unit> {
        val removed = emails.removeIf { it.id == draftId && it.mailbox == Mailbox.DRAFTS }
        XrLog.i("MockRepo", "deleteDraft id=$draftId removed=$removed")
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
