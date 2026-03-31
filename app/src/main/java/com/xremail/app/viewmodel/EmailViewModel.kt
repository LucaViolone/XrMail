package com.xremail.app.viewmodel

import androidx.lifecycle.ViewModel
import com.xremail.app.data.ActionItem
import com.xremail.app.data.Attachment
import com.xremail.app.data.Contact
import com.xremail.app.data.Email
import com.xremail.app.data.EmailCategory
import com.xremail.app.data.MockData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class AppMode { READING, COMPOSING }

data class EmailUiState(
    val emails: List<Email> = MockData.emails,
    val selectedEmail: Email? = MockData.emails.firstOrNull(),
    val selectedContact: Contact? = null,
    val mode: AppMode = AppMode.READING,
    val activeCategory: EmailCategory? = null,
    val isAiSummaryExpanded: Boolean = true,
    val unreadCount: Int = 0,
)

class EmailViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(
        EmailUiState(
            selectedContact = MockData.emails.firstOrNull()?.let {
                MockData.getContactForEmail(it)
            },
            unreadCount = MockData.emails.count { !it.isRead },
        )
    )
    val uiState: StateFlow<EmailUiState> = _uiState.asStateFlow()

    fun selectEmail(email: Email) {
        _uiState.update { state ->
            val updatedEmails = if (!email.isRead) {
                state.emails.map { if (it.id == email.id) it.copy(isRead = true) else it }
            } else {
                state.emails
            }
            val readEmail = email.copy(isRead = true)
            state.copy(
                emails = updatedEmails,
                selectedEmail = readEmail,
                selectedContact = MockData.getContactForEmail(readEmail),
                mode = AppMode.READING,
                isAiSummaryExpanded = true,
                unreadCount = updatedEmails.count { !it.isRead },
            )
        }
    }

    fun filterByCategory(category: EmailCategory?) {
        _uiState.update { state ->
            val filtered = if (category == null) {
                MockData.emails
            } else {
                MockData.emails.filter { it.category == category }
            }
            state.copy(
                activeCategory = category,
                emails = filtered,
                unreadCount = filtered.count { !it.isRead },
            )
        }
    }

    fun toggleAiSummary() {
        _uiState.update { it.copy(isAiSummaryExpanded = !it.isAiSummaryExpanded) }
    }

    fun startCompose() {
        _uiState.update { it.copy(mode = AppMode.COMPOSING) }
    }

    fun cancelCompose() {
        _uiState.update { it.copy(mode = AppMode.READING) }
    }

    fun archiveSelected() {
        _uiState.update { state ->
            val selected = state.selectedEmail ?: return@update state
            val remaining = state.emails.filter { it.id != selected.id }
            state.copy(
                emails = remaining,
                selectedEmail = remaining.firstOrNull(),
                selectedContact = remaining.firstOrNull()?.let {
                    MockData.getContactForEmail(it)
                },
                unreadCount = remaining.count { !it.isRead },
            )
        }
    }

    fun snoozeSelected() {
        // Phase 1 stub: removes from list (production would schedule a reminder)
        archiveSelected()
    }

    fun forwardSelected() {
        _uiState.update { it.copy(mode = AppMode.COMPOSING) }
    }

    fun sendDraft() {
        _uiState.update { it.copy(mode = AppMode.READING) }
    }

    val selectedAttachments: List<Attachment>
        get() = _uiState.value.selectedEmail?.attachments.orEmpty()

    val selectedActionItems: List<ActionItem>
        get() = _uiState.value.selectedEmail?.actionItems.orEmpty()
}
