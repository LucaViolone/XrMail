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
    // AI state
    val replySuggestions: List<String> = emptyList(),
    val isLoadingSuggestions: Boolean = false,
    val voiceDraft: EmailDraft? = null,
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
    // Email selection
    // ---------------------------------------------------------------------------

    fun selectEmail(email: Email) {
        viewModelScope.launch {
            // Optimistically mark as read in local state
            _uiState.update { state ->
                val updatedEmails = state.emails.map {
                    if (it.id == email.id) it.copy(isRead = true) else it
                }
                state.copy(
                    emails = updatedEmails,
                    selectedEmail = email.copy(isRead = true),
                    mode = AppMode.READING,
                    isAiSummaryExpanded = true,
                    replySuggestions = emptyList(),
                    unreadCount = updatedEmails.count { !it.isRead },
                )
            }
            loadContactFor(email)

            // Persist read status to backend (fire-and-forget)
            if (!email.isRead) repository.markAsRead(email.id)

            // Pre-fetch reply suggestions in the background
            loadReplySuggestions(email.id)
        }
    }

    // ---------------------------------------------------------------------------
    // Category filtering
    // ---------------------------------------------------------------------------

    fun filterByCategory(category: EmailCategory?) {
        loadEmails(category = category)
    }

    // ---------------------------------------------------------------------------
    // AI summary
    // ---------------------------------------------------------------------------

    fun toggleAiSummary() {
        _uiState.update { it.copy(isAiSummaryExpanded = !it.isAiSummaryExpanded) }
    }

    // ---------------------------------------------------------------------------
    // Actions
    // ---------------------------------------------------------------------------

    fun startCompose() {
        _uiState.update { it.copy(mode = AppMode.COMPOSING, voiceDraft = null) }
    }

    fun cancelCompose() {
        _uiState.update { it.copy(mode = AppMode.READING, voiceDraft = null) }
    }

    fun archiveSelected() {
        val selected = _uiState.value.selectedEmail ?: return
        viewModelScope.launch {
            // Optimistic removal
            _uiState.update { state ->
                val remaining = state.emails.filter { it.id != selected.id }
                state.copy(
                    emails = remaining,
                    selectedEmail = remaining.firstOrNull(),
                    unreadCount = remaining.count { !it.isRead },
                )
            }
            _uiState.value.selectedEmail?.let { loadContactFor(it) }
            repository.archive(selected.id)
        }
    }

    fun snoozeSelected() {
        // Phase 1 stub: removes from list (production schedules a WorkManager reminder)
        archiveSelected()
    }

    fun forwardSelected() {
        _uiState.update { it.copy(mode = AppMode.COMPOSING) }
    }

    fun sendDraft() {
        val draft = _uiState.value.voiceDraft ?: run {
            _uiState.update { it.copy(mode = AppMode.READING) }
            return
        }
        viewModelScope.launch {
            repository.sendEmail(draft)
            _uiState.update { it.copy(mode = AppMode.READING, voiceDraft = null) }
        }
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
                        it.copy(mode = AppMode.COMPOSING, voiceDraft = draft)
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
