package com.xremail.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.xremail.app.backend.mock.MockEmailRepository
import com.xremail.app.backend.service.EmailRepository
import com.xremail.app.data.*
import com.xremail.app.util.XrLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class AppMode { READING, COMPOSING }

enum class InteractionTier { AMBIENT_HUD, NOTIFICATION_CARDS, INBOX, FOCUS }

data class VoiceDraft(
    val recipientName: String = "",
    val subject: String = "",
    val draftText: String = "",
    val isGenerating: Boolean = false,
    val confidence: Float = 0f,
)

data class ToastMessage(
    val text: String,
    val id: Long = System.currentTimeMillis(),
)

data class EmailUiState(
    val emails: List<Email> = emptyList(),
    val selectedEmail: Email? = null,
    val selectedContact: Contact? = null,
    val mode: AppMode = AppMode.READING,
    val activeCategory: EmailCategory? = null,
    val isAiSummaryExpanded: Boolean = true,
    val unreadCount: Int = 0,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val replySuggestions: List<String> = emptyList(),
    val isLoadingSuggestions: Boolean = false,
    val tier: InteractionTier = InteractionTier.AMBIENT_HUD,
    val voiceDraft: VoiceDraft? = null,
    val toastMessage: ToastMessage? = null,
    val isVoiceComposing: Boolean = false,
    val highlightedNotificationId: String? = null,
    val isGazingAtNotifications: Boolean = false,
    val showEmulatorHelp: Boolean = true,
)

class EmailViewModel(
    private val repository: EmailRepository = MockEmailRepository(),
) : ViewModel() {

    private val _uiState = MutableStateFlow(EmailUiState(isLoading = true))
    val uiState: StateFlow<EmailUiState> = _uiState.asStateFlow()

    init {
        loadEmails()
    }

    // ---------------------------------------------------------------------------
    // Email loading
    // ---------------------------------------------------------------------------

    fun loadEmails(query: String = "", category: EmailCategory? = null) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }

            val result = repository.listEmails(query = query)

            result.fold(
                onSuccess = { emails ->
                    val filtered = if (category != null) {
                        emails.filter { it.category == category }
                    } else emails

                    _uiState.update { state ->
                        state.copy(
                            emails = filtered,
                            selectedEmail = filtered.firstOrNull()
                                ?.also { loadContactFor(it) },
                            activeCategory = category,
                            unreadCount = filtered.count { !it.isRead },
                            isLoading = false,
                        )
                    }
                },
                onFailure = { error ->
                    val msg = error.message ?: "Failed to load emails"
                    XrLog.e("EmailVM", "loadEmails FAILED", error)
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = msg,
                            // errorMessage is not rendered anywhere — wire
                            // the same text into the toast channel so the
                            // user actually sees that the inbox load failed
                            // (otherwise the UI sits on stale or empty data
                            // with no explanation).
                            toastMessage = ToastMessage("Inbox: $msg"),
                        )
                    }
                }
            )
        }
    }

    // ---------------------------------------------------------------------------
    // Tier navigation
    // ---------------------------------------------------------------------------

    fun expandToNotificationCards() {
        logTier("expandToNotificationCards", InteractionTier.NOTIFICATION_CARDS)
        _uiState.update {
            it.copy(
                tier = InteractionTier.NOTIFICATION_CARDS,
                isGazingAtNotifications = true,
            )
        }
    }

    fun collapseFromNotificationCards() {
        logTier("collapseFromNotificationCards", InteractionTier.AMBIENT_HUD)
        _uiState.update {
            it.copy(
                tier = InteractionTier.AMBIENT_HUD,
                highlightedNotificationId = null,
                isGazingAtNotifications = false,
            )
        }
    }

    fun highlightNotification(emailId: String?) {
        _uiState.update { it.copy(highlightedNotificationId = emailId) }
    }

    /**
     * User pinched directly on a notification card. Open that email in the
     * full spatial reader (FOCUS) — that's the "fuller view" the user
     * asked for: dedicated panels for the email body, contact card, and
     * actions instead of the email-list-with-preview INBOX layout.
     *
     * Going straight to FOCUS instead of stopping at INBOX is the whole
     * point of the pinch gesture: the user already picked which email
     * they care about, so there's no value in dropping them on a list
     * view that has the email selected on one side.
     */
    fun openFromNotification(email: Email) {
        logTier("openFromNotification(${email.id})", InteractionTier.FOCUS)
        selectEmail(email)
        _uiState.update {
            it.copy(
                tier = InteractionTier.FOCUS,
                highlightedNotificationId = null,
                isGazingAtNotifications = false,
            )
        }
    }

    fun expandToInbox() {
        logTier("expandToInbox", InteractionTier.INBOX)
        _uiState.update {
            it.copy(
                tier = InteractionTier.INBOX,
                highlightedNotificationId = null,
                isGazingAtNotifications = false,
            )
        }
    }

    fun collapseToHud() {
        logTier("collapseToHud", InteractionTier.AMBIENT_HUD)
        _uiState.update {
            it.copy(
                tier = InteractionTier.AMBIENT_HUD,
                isVoiceComposing = false,
                voiceDraft = null,
                highlightedNotificationId = null,
                isGazingAtNotifications = false,
            )
        }
    }

    fun expandToFocus() {
        logTier("expandToFocus", InteractionTier.FOCUS)
        _uiState.update { it.copy(tier = InteractionTier.FOCUS) }
    }

    fun collapseToNotificationCards() {
        logTier("collapseToNotificationCards", InteractionTier.NOTIFICATION_CARDS)
        _uiState.update {
            it.copy(
                tier = InteractionTier.NOTIFICATION_CARDS,
                isGazingAtNotifications = true,
            )
        }
    }

    fun collapseToInbox() {
        logTier("collapseToInbox", InteractionTier.INBOX)
        _uiState.update { it.copy(tier = InteractionTier.INBOX) }
    }

    /**
     * Single-source-of-truth logger for tier transitions. Captures a
     * stack trace so when the user reports "the screen switched and I
     * didn't do anything," logcat shows the exact call path that fired
     * the transition (gesture → mapper, voice → dispatcher, OS click →
     * compose modifier, periodic refresh, etc.). The stack stays in WARN
     * level so it's grep-able from a noisy log.
     */
    private fun logTier(reason: String, target: InteractionTier) {
        val from = _uiState.value.tier.name
        val sameTier = from == target.name
        if (sameTier) {
            XrLog.d("Tier", "$reason: already at $from (no-op transition request)")
            return
        }
        // Prune the trace to the first 6 app frames so the line is
        // scannable. Skips Throwable.fillInStackTrace + this method.
        val appFrames = Throwable().stackTrace
            .asSequence()
            .drop(1)
            .filter { it.className.startsWith("com.xremail.app") }
            .take(6)
            .joinToString(" <- ") { "${it.fileName}:${it.lineNumber}" }
        XrLog.tier(from, target.name, reason)
        XrLog.w("Tier", "  via: $appFrames")
    }

    fun setGazingAtNotifications(gazing: Boolean) {
        _uiState.update { it.copy(isGazingAtNotifications = gazing) }
    }

    fun toggleEmulatorHelp() {
        _uiState.update { it.copy(showEmulatorHelp = !it.showEmulatorHelp) }
    }

    /**
     * Emergency-recovery reset. Collapses back to AMBIENT_HUD, clears any
     * half-baked compose / selection / toast state, and reloads the inbox.
     * Called by the voice agent when the user says "refresh" / "reset" /
     * "start over", and wired to a visible escape-hatch button in the HUD.
     *
     * Safe to call from any tier — always lands you on a known-good state.
     */
    fun refreshUi() {
        logTier("refreshUi", InteractionTier.AMBIENT_HUD)
        _uiState.update {
            it.copy(
                tier = InteractionTier.AMBIENT_HUD,
                mode = AppMode.READING,
                isVoiceComposing = false,
                voiceDraft = null,
                highlightedNotificationId = null,
                isGazingAtNotifications = false,
                errorMessage = null,
                toastMessage = ToastMessage("Refreshed"),
            )
        }
        loadEmails()
    }

    // ---------------------------------------------------------------------------
    // Email selection
    // ---------------------------------------------------------------------------

    fun selectEmail(email: Email) {
        viewModelScope.launch {
            _uiState.update { state ->
                val updatedEmails = state.emails.map {
                    if (it.id == email.id) it.copy(isRead = true) else it
                }
                state.copy(
                    emails = updatedEmails,
                    selectedEmail = email.copy(isRead = true),
                    selectedContact = MockData.getContactForEmail(email.copy(isRead = true)),
                    mode = AppMode.READING,
                    isAiSummaryExpanded = true,
                    replySuggestions = emptyList(),
                    unreadCount = updatedEmails.count { !it.isRead },
                )
            }
            loadContactFor(email)

            if (!email.isRead) repository.markAsRead(email.id)

            loadReplySuggestions(email.id)
        }
    }

    fun navigateNextUnread() {
        _uiState.update { state ->
            val nextUnread = state.emails.firstOrNull { !it.isRead }
            if (nextUnread != null) {
                val updatedEmails = state.emails.map {
                    if (it.id == nextUnread.id) it.copy(isRead = true) else it
                }
                val readEmail = nextUnread.copy(isRead = true)
                state.copy(
                    emails = updatedEmails,
                    selectedEmail = readEmail,
                    selectedContact = MockData.getContactForEmail(readEmail),
                    unreadCount = updatedEmails.count { !it.isRead },
                )
            } else {
                state
            }
        }
    }

    fun toggleStar(email: Email) {
        _uiState.update { state ->
            val updated = state.emails.map {
                if (it.id == email.id) it.copy(isStarred = !it.isStarred) else it
            }
            val updatedSelected = if (state.selectedEmail?.id == email.id) {
                state.selectedEmail.copy(isStarred = !state.selectedEmail.isStarred)
            } else {
                state.selectedEmail
            }
            state.copy(emails = updated, selectedEmail = updatedSelected)
        }
    }

    // ---------------------------------------------------------------------------
    // Category filtering
    // ---------------------------------------------------------------------------

    fun filterByCategory(category: EmailCategory?) {
        loadEmails(category = category)
    }

    fun prioritySortedEmails(): List<Email> {
        val priorityOrder = mapOf(
            Priority.HIGH to 0,
            Priority.MEDIUM to 1,
            Priority.LOW to 2,
            Priority.IGNORE to 3,
        )
        return _uiState.value.emails.sortedWith(
            compareBy<Email> { it.isRead }
                .thenBy { priorityOrder[it.priority] ?: 3 }
                .thenByDescending { it.urgencyScore }
        )
    }

    // ---------------------------------------------------------------------------
    // AI summary
    // ---------------------------------------------------------------------------

    fun toggleAiSummary() {
        _uiState.update { it.copy(isAiSummaryExpanded = !it.isAiSummaryExpanded) }
    }

    // ---------------------------------------------------------------------------
    // Compose
    // ---------------------------------------------------------------------------

    fun startCompose() {
        _uiState.update { it.copy(mode = AppMode.COMPOSING, voiceDraft = null) }
    }

    fun cancelCompose() {
        _uiState.update {
            it.copy(mode = AppMode.READING, isVoiceComposing = false, voiceDraft = null)
        }
    }

    fun startVoiceCompose() {
        val email = _uiState.value.selectedEmail ?: return
        _uiState.update {
            it.copy(
                isVoiceComposing = true,
                voiceDraft = VoiceDraft(
                    recipientName = email.sender,
                    subject = "Re: ${email.subject}",
                ),
            )
        }
    }

    /**
     * Stamp a complete, model-generated draft body into the voice compose
     * UI. Used by the voice dispatcher when Gemini calls draft_reply with
     * a full body — the user reviews the draft (TTS reads it back), then
     * confirms or revises.
     *
     * If [body] is blank we fall back to the email's [Email.suggestedReply]
     * boilerplate so the user still sees something to react to instead of
     * an empty card. That happens when the user says "reply" without any
     * actual content ("hey gemini reply") — better to show the suggested
     * reply than to silently fail.
     */
    fun voiceReply(body: String) {
        val email = _uiState.value.selectedEmail ?: return
        val draft = body.takeIf { it.isNotBlank() }
            ?: email.suggestedReply
            ?: "Thank you for your email."
        _uiState.update {
            it.copy(
                isVoiceComposing = true,
                voiceDraft = VoiceDraft(
                    recipientName = email.sender,
                    subject = "Re: ${email.subject}",
                    draftText = draft,
                    isGenerating = false,
                    confidence = email.replyConfidence,
                ),
            )
        }
    }

    /**
     * Replace the in-progress draft body with [newBody] without resetting
     * recipient / subject. Triggered by Gemini's revise_draft tool when
     * the user says "change it to ..." / "make it shorter" / etc.
     *
     * If no draft is in progress this is a no-op (logged) so a stray
     * model call after a send/cancel doesn't resurrect the compose UI.
     */
    fun reviseVoiceDraft(newBody: String) {
        val current = _uiState.value.voiceDraft
        if (current == null || !_uiState.value.isVoiceComposing) {
            return
        }
        _uiState.update {
            it.copy(
                voiceDraft = current.copy(
                    draftText = newBody,
                    isGenerating = false,
                ),
            )
        }
    }

    /**
     * Actually send the in-progress voice draft via the email repository.
     *
     * BIG BUG-FIX: this used to ONLY update local state and pop a "Sent
     * to X" toast. The email never left the device — `repository.sendEmail`
     * was never called. The user reported it as "I can't send emails
     * either" because the success toast was lying. We now construct an
     * `EmailDraft` from the selected message + voice draft and POST it
     * through the repository, only flipping the UI to "Sent" on a
     * genuine network success. On failure we keep the draft visible
     * with an error toast so the user can retry instead of believing
     * the message was sent when it wasn't.
     */
    fun confirmSend() {
        val state = _uiState.value
        val selected = state.selectedEmail
        val draft = state.voiceDraft

        if (selected == null || draft == null || draft.draftText.isBlank()) {
            XrLog.w("EmailVM", "confirmSend: missing selected or draft (selected=$selected, draft=$draft)")
            _uiState.update {
                it.copy(
                    toastMessage = ToastMessage("Nothing to send."),
                )
            }
            return
        }

        // Optimistically clear the compose UI before the network round-trip
        // — the user already heard "Drafted, want me to send it?" and said
        // yes, so leaving the draft visible while we wait for HTTP feels
        // unresponsive. We restore the draft below if the send fails.
        _uiState.update {
            it.copy(
                mode = AppMode.READING,
                isVoiceComposing = false,
                voiceDraft = null,
                toastMessage = ToastMessage(
                    "Sending to ${selected.sender}…",
                ),
            )
        }

        viewModelScope.launch {
            val emailDraft = EmailDraft(
                to = listOf(selected.senderEmail),
                subject = if (draft.subject.isNotBlank()) draft.subject
                          else "Re: ${selected.subject}",
                body = draft.draftText,
                inReplyTo = selected.id,
            )
            XrLog.i(
                "EmailVM",
                "sendEmail -> to=${emailDraft.to} subject=\"${emailDraft.subject}\" " +
                    "bodyLen=${emailDraft.body.length}",
            )
            repository.sendEmail(emailDraft).fold(
                onSuccess = {
                    XrLog.i("EmailVM", "sendEmail SUCCESS")
                    _uiState.update {
                        it.copy(
                            toastMessage = ToastMessage(
                                "Sent to ${selected.sender}",
                            ),
                        )
                    }
                },
                onFailure = { err ->
                    XrLog.e("EmailVM", "sendEmail FAILED", err)
                    // Restore the draft so the user can retry — no
                    // silent loss of the body they just dictated.
                    _uiState.update {
                        it.copy(
                            mode = AppMode.COMPOSING,
                            isVoiceComposing = true,
                            voiceDraft = draft,
                            toastMessage = ToastMessage(
                                "Send failed: ${err.message ?: "network error"} — try again",
                            ),
                        )
                    }
                },
            )
        }
    }

    fun dismissToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }

    /**
     * Surface a failure to the user as a toast — the central "nothing
     * fails silently" channel. Anything that throws / fails / no-ops
     * unexpectedly should land here so the user sees concrete feedback
     * instead of pressing buttons that quietly do nothing.
     *
     * Call this from MainActivity when observing GeminiLive.lastError,
     * LocalCommandRecognizer.lastError, repository failures the
     * ViewModel doesn't already toast itself, etc. Always also writes
     * an XrLog at WARN level so the same failure shows up in adb logcat.
     *
     * [source] is a short subsystem tag like "Gemini" / "Voice" / "Send"
     * that gets prefixed onto the toast so the user can tell where the
     * problem is coming from at a glance.
     */
    fun showError(source: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            XrLog.w("EmailVM", "showError [$source]: $message", throwable)
        } else {
            XrLog.w("EmailVM", "showError [$source]: $message")
        }
        _uiState.update {
            it.copy(toastMessage = ToastMessage("$source: $message"))
        }
    }

    // ---------------------------------------------------------------------------
    // Inbox actions
    // ---------------------------------------------------------------------------

    fun archiveEmail(email: Email) {
        _uiState.update { state ->
            val remaining = state.emails.filter { it.id != email.id }
            val newSelected = if (state.selectedEmail?.id == email.id) {
                remaining.firstOrNull()
            } else {
                state.selectedEmail
            }
            state.copy(
                emails = remaining,
                selectedEmail = newSelected,
                selectedContact = newSelected?.let { MockData.getContactForEmail(it) },
                unreadCount = remaining.count { !it.isRead },
                toastMessage = ToastMessage("Archived: ${email.sender}"),
            )
        }
    }

    fun snoozeEmail(email: Email) {
        _uiState.update { state ->
            val remaining = state.emails.filter { it.id != email.id }
            val newSelected = if (state.selectedEmail?.id == email.id) {
                remaining.firstOrNull()
            } else {
                state.selectedEmail
            }
            state.copy(
                emails = remaining,
                selectedEmail = newSelected,
                selectedContact = newSelected?.let { MockData.getContactForEmail(it) },
                unreadCount = remaining.count { !it.isRead },
                toastMessage = ToastMessage("Snoozed: ${email.sender}"),
            )
        }
    }

    fun archiveSelected() {
        val selected = _uiState.value.selectedEmail ?: return
        archiveEmail(selected)
        viewModelScope.launch {
            // Repository.archive returns Result — fold so a network
            // failure becomes a visible toast instead of an apparently-
            // successful local archive that silently re-appears on next
            // refresh.
            repository.archive(selected.id).fold(
                onSuccess = { XrLog.i("EmailVM", "archive ok: ${selected.id}") },
                onFailure = { err ->
                    showError(
                        "Archive",
                        "couldn't archive ${selected.sender}: ${err.message ?: "network error"}",
                        err,
                    )
                },
            )
        }
    }

    fun snoozeSelected() {
        val selected = _uiState.value.selectedEmail ?: return
        snoozeEmail(selected)
    }

    fun forwardSelected() {
        _uiState.update { it.copy(mode = AppMode.COMPOSING) }
    }

    /**
     * Voice-driven "send it". Only fires the actual send if the user
     * actually has a draft in progress — without this guard, a stray
     * Gemini send_draft call (or a misheard "send" while idle) would
     * fire confirmSend() with no draft and pop a misleading "Sent to ..."
     * toast for an email the user never reviewed. Returns true if a send
     * actually fired so the caller can speak the right confirmation.
     */
    fun sendDraft(): Boolean {
        val state = _uiState.value
        if (!state.isVoiceComposing || state.voiceDraft?.draftText.isNullOrBlank()) {
            return false
        }
        confirmSend()
        return true
    }

    fun setStarred(messageId: String, starred: Boolean) {
        viewModelScope.launch {
            _uiState.update { state ->
                state.copy(
                    emails = state.emails.map {
                        if (it.id == messageId) it.copy(isStarred = starred) else it
                    },
                    selectedEmail = state.selectedEmail?.let {
                        if (it.id == messageId) it.copy(isStarred = starred) else it
                    }
                )
            }
            repository.setStarred(messageId, starred).fold(
                onSuccess = { XrLog.v("EmailVM", "setStarred ok: $messageId=$starred") },
                onFailure = { err ->
                    showError("Star", "couldn't update star: ${err.message ?: "network error"}", err)
                },
            )
        }
    }

    // ---------------------------------------------------------------------------
    // Voice compose — called by GeminiLiveManager command handler
    // ---------------------------------------------------------------------------

    /**
     * Converts a Whisper/Gemini voice transcript into an [EmailDraft] and
     * switches the UI to compose mode showing the draft for review.
     */
    fun composeFromVoice(transcript: String) {
        val replyToId = _uiState.value.selectedEmail?.id
        viewModelScope.launch {
            repository.composeFromVoice(
                transcript = transcript,
                replyToMessageId = replyToId,
            ).fold(
                onSuccess = { draft ->
                    _uiState.update {
                        it.copy(mode = AppMode.COMPOSING)
                    }
                },
                onFailure = { error ->
                    val msg = error.message ?: "Could not compose email"
                    XrLog.e("EmailVM", "composeFromVoice FAILED", error)
                    _uiState.update {
                        it.copy(
                            errorMessage = msg,
                            // Surface to the user — composing silently
                            // failing is the worst UX (they think the
                            // mic didn't pick them up and re-dictate).
                            toastMessage = ToastMessage("Compose: $msg"),
                        )
                    }
                }
            )
        }
    }

    // ---------------------------------------------------------------------------
    // Convenience accessors (used by UI composables)
    // ---------------------------------------------------------------------------

    val selectedAttachments: List<Attachment>
        get() = _uiState.value.selectedEmail?.attachments.orEmpty()

    val selectedActionItems: List<ActionItem>
        get() = _uiState.value.selectedEmail?.actionItems.orEmpty()

    // ---------------------------------------------------------------------------
    // Private helpers
    // ---------------------------------------------------------------------------

    private fun loadContactFor(email: Email) {
        viewModelScope.launch {
            val contact = repository.getContact(email.senderEmail)
            _uiState.update { it.copy(selectedContact = contact) }
        }
    }

    private fun loadReplySuggestions(messageId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingSuggestions = true) }
            repository.getReplySuggestions(messageId).fold(
                onSuccess = { suggestions ->
                    _uiState.update {
                        it.copy(replySuggestions = suggestions, isLoadingSuggestions = false)
                    }
                },
                onFailure = {
                    _uiState.update { it.copy(isLoadingSuggestions = false) }
                }
            )
        }
    }

    // ---------------------------------------------------------------------------
    // Factory — use this when injecting a real GmailRepository
    // ---------------------------------------------------------------------------

    class Factory(private val repository: EmailRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return EmailViewModel(repository) as T
        }
    }
}
