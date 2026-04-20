package com.xremail.app.backend.service

import com.xremail.app.data.Attachment
import com.xremail.app.data.Contact
import com.xremail.app.data.Email
import com.xremail.app.data.EmailDraft

/**
 * Repository interface for all email data operations.
 *
 * The [EmailViewModel] depends only on this interface — it never touches
 * the network or mock layer directly. This makes it trivial to swap
 * implementations (mock ↔ real) or inject test doubles.
 *
 * Implementations:
 *  - [com.xremail.app.backend.mock.MockEmailRepository]  — Phase 1 / offline
 *  - [GmailRepository]                                   — Phase 2 / production
 */
interface EmailRepository {

    /**
     * Fetches the current inbox email list.
     * Returns a [Result] so the ViewModel can handle errors gracefully without
     * catching exceptions itself.
     */
    suspend fun listEmails(
        query: String = "",
        maxResults: Int = 20,
        pageToken: String? = null,
        labels: List<String> = listOf("INBOX"),
    ): Result<List<Email>>

    /**
     * Fetches a single email with its full body content.
     * Called when the user opens an email in the Focus layer.
     */
    suspend fun getEmail(messageId: String): Result<Email>

    /**
     * Sends a new email or a threaded reply.
     */
    suspend fun sendEmail(draft: EmailDraft): Result<Unit>

    /**
     * Persist an in-progress draft to the Drafts folder. If [existingId]
     * is non-null, updates that draft in place; otherwise creates a new
     * one. Returns the draft id so the caller can update or delete the
     * same draft later.
     *
     * In mock mode this materializes as an [Email] tagged
     * [com.xremail.app.data.Mailbox.DRAFTS] — the Drafts tab shows it
     * immediately. In Gmail mode this is a stub returning a synthetic
     * id (real Gmail drafts API wiring is a follow-up).
     */
    suspend fun saveDraft(draft: EmailDraft, existingId: String? = null): Result<String>

    /**
     * Remove a persisted draft (used after a successful send, or when
     * the user explicitly discards the in-progress draft).
     */
    suspend fun deleteDraft(draftId: String): Result<Unit>

    /**
     * Marks an email as read.
     */
    suspend fun markAsRead(messageId: String): Result<Unit>

    /**
     * Archives an email (removes INBOX label).
     */
    suspend fun archive(messageId: String): Result<Unit>

    /**
     * Stars or un-stars an email.
     */
    suspend fun setStarred(messageId: String, starred: Boolean): Result<Unit>

    /**
     * Moves an email to trash.
     */
    suspend fun trash(messageId: String): Result<Unit>

    /**
     * Returns the contact card for an email's sender, if available.
     */
    suspend fun getContact(email: String): Contact?

    /**
     * Fetches a Gemini-generated summary for the given email.
     */
    suspend fun getSummary(messageId: String): Result<String>

    /**
     * Fetches Gemini reply suggestions for the given email.
     */
    suspend fun getReplySuggestions(messageId: String): Result<List<String>>

    /**
     * Converts a voice transcript into a structured email draft using Gemini.
     */
    suspend fun composeFromVoice(
        transcript: String,
        replyToMessageId: String? = null,
    ): Result<EmailDraft>
}
