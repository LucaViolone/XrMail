package com.xremail.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.xremail.app.backend.mock.MockEmailRepository
import com.xremail.app.backend.service.EmailRepository
import com.xremail.app.data.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class AppMode { READING, COMPOSING }

enum class InteractionTier { AMBIENT_HUD, NOTIFICATION_CARDS, TRIAGE, FOCUS }

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
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = error.message ?: "Failed to load emails",
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
        _uiState.update {
            it.copy(
                tier = InteractionTier.NOTIFICATION_CARDS,
                isGazingAtNotifications = true,
            )
        }
    }

    fun collapseFromNotificationCards() {
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

    fun openFromNotification(email: Email) {
        selectEmail(email)
        _uiState.update {
            it.copy(
                tier = InteractionTier.TRIAGE,
                highlightedNotificationId = null,
                isGazingAtNotifications = false,
            )
        }
    }

    fun expandToTriage() {
        _uiState.update {
            it.copy(
                tier = InteractionTier.TRIAGE,
                highlightedNotificationId = null,
                isGazingAtNotifications = false,
            )
        }
    }

    fun collapseToHud() {
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
        _uiState.update { it.copy(tier = InteractionTier.FOCUS) }
    }

    fun collapseToNotificationCards() {
        _uiState.update {
            it.copy(
                tier = InteractionTier.NOTIFICATION_CARDS,
                isGazingAtNotifications = true,
            )
        }
    }

    fun collapseToTriage() {
        _uiState.update { it.copy(tier = InteractionTier.TRIAGE) }
    }

    fun setGazingAtNotifications(gazing: Boolean) {
        _uiState.update { it.copy(isGazingAtNotifications = gazing) }
    }

    fun toggleEmulatorHelp() {
        _uiState.update { it.copy(showEmulatorHelp = !it.showEmulatorHelp) }
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

    fun voiceReply(briefInstruction: String) {
        val email = _uiState.value.selectedEmail ?: return
        val draft = email.suggestedReply ?: "Thank you for your email. $briefInstruction"
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

    fun confirmSend() {
        _uiState.update {
            it.copy(
                mode = AppMode.READING,
                isVoiceComposing = false,
                voiceDraft = null,
                toastMessage = ToastMessage(
                    "Sent to ${it.selectedEmail?.sender ?: "recipient"}"
                ),
            )
        }
    }

    fun dismissToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }

    // ---------------------------------------------------------------------------
    // Triage actions
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
            repository.archive(selected.id)
        }
    }

    fun snoozeSelected() {
        val selected = _uiState.value.selectedEmail ?: return
        snoozeEmail(selected)
    }

    fun forwardSelected() {
        _uiState.update { it.copy(mode = AppMode.COMPOSING) }
    }

    fun sendDraft() {
        confirmSend()
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
            repository.setStarred(messageId, starred)
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
                    _uiState.update {
                        it.copy(errorMessage = error.message ?: "Could not compose email")
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
