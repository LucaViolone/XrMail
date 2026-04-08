package com.xremail.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.xremail.app.ai.GeminiTextService
import com.xremail.app.backend.mock.MockEmailRepository
import com.xremail.app.backend.service.EmailRepository
import com.xremail.app.data.ActionItem
import com.xremail.app.data.Attachment
import com.xremail.app.data.Contact
import com.xremail.app.data.Email
import com.xremail.app.data.EmailCategory
import com.xremail.app.data.EmailDraft
import com.xremail.app.voice.EmailCommandTool
import com.xremail.app.voice.TTSManager
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
    val replySuggestions: List<String> = emptyList(),
    val isLoadingSuggestions: Boolean = false,
    val voiceDraft: EmailDraft? = null,
    val draftBody: String = "",
    val assistantStatus: String? = null,
)

class EmailViewModel(
    application: Application,
    private val repository: EmailRepository = MockEmailRepository(),
) : AndroidViewModel(application) {

    private val textService = GeminiTextService()
    private val tts = TTSManager(application.applicationContext)

    private var voiceSendArmedUntil: Long = 0L

    private val _uiState = MutableStateFlow(EmailUiState(isLoading = true))
    val uiState: StateFlow<EmailUiState> = _uiState.asStateFlow()

    init {
        loadEmails()
    }

    fun isVoiceSendArmed(): Boolean = System.currentTimeMillis() < voiceSendArmedUntil

    private fun armVoiceSend() {
        voiceSendArmedUntil = System.currentTimeMillis() + 45_000L
    }

    fun updateDraftBody(text: String) {
        _uiState.update { it.copy(draftBody = text) }
    }

    fun dismissAssistantStatus() {
        _uiState.update { it.copy(assistantStatus = null) }
    }

    fun loadEmails(query: String = "", category: EmailCategory? = null) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }

            val result = repository.listEmails(query = query)

            result.fold(
                onSuccess = { emails ->
                    val filtered = if (category != null) {
                        emails.filter { it.category == category }
                    } else {
                        emails
                    }

                    _uiState.update { state ->
                        val first = filtered.firstOrNull()
                        state.copy(
                            emails = filtered,
                            selectedEmail = first?.also { loadContactFor(it) },
                            activeCategory = category,
                            unreadCount = filtered.count { !it.isRead },
                            isLoading = false,
                            draftBody = first?.suggestedReply.orEmpty(),
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
                },
            )
        }
    }

    fun selectEmail(email: Email) {
        viewModelScope.launch {
            _uiState.update { state ->
                val updatedEmails = state.emails.map {
                    if (it.id == email.id) it.copy(isRead = true) else it
                }
                val read = email.copy(isRead = true)
                state.copy(
                    emails = updatedEmails,
                    selectedEmail = read,
                    mode = AppMode.READING,
                    isAiSummaryExpanded = true,
                    replySuggestions = emptyList(),
                    unreadCount = updatedEmails.count { !it.isRead },
                    draftBody = read.suggestedReply.orEmpty(),
                    assistantStatus = null,
                )
            }
            loadContactFor(email.copy(isRead = true))

            if (!email.isRead) repository.markAsRead(email.id)

            loadReplySuggestions(email.id)
        }
    }

    fun filterByCategory(category: EmailCategory?) {
        loadEmails(category = category)
    }

    fun toggleAiSummary() {
        _uiState.update { it.copy(isAiSummaryExpanded = !it.isAiSummaryExpanded) }
    }

    fun startCompose() {
        val sel = _uiState.value.selectedEmail
        _uiState.update {
            it.copy(
                mode = AppMode.COMPOSING,
                voiceDraft = null,
                draftBody = sel?.suggestedReply.orEmpty(),
            )
        }
    }

    fun cancelCompose() {
        val sel = _uiState.value.selectedEmail
        _uiState.update {
            it.copy(
                mode = AppMode.READING,
                voiceDraft = null,
                draftBody = sel?.suggestedReply.orEmpty(),
                assistantStatus = null,
            )
        }
    }

    fun archiveSelected() {
        val selected = _uiState.value.selectedEmail ?: return
        viewModelScope.launch {
            _uiState.update { state ->
                val remaining = state.emails.filter { it.id != selected.id }
                val newSel = remaining.firstOrNull()
                state.copy(
                    emails = remaining,
                    selectedEmail = newSel,
                    unreadCount = remaining.count { !it.isRead },
                    draftBody = newSel?.suggestedReply.orEmpty(),
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
        _uiState.update {
            it.copy(mode = AppMode.COMPOSING, voiceDraft = null, draftBody = "")
        }
    }

    fun sendDraft(isFromVoice: Boolean = false) {
        val state = _uiState.value
        val fromVoiceDraft = state.voiceDraft
        if (fromVoiceDraft != null) {
            if (isFromVoice && !isVoiceSendArmed()) {
                tts.speak("Say you want to send, then confirm clearly.")
                _uiState.update { it.copy(assistantStatus = "Voice send not confirmed") }
                return
            }
            voiceSendArmedUntil = 0L
            viewModelScope.launch {
                repository.sendEmail(fromVoiceDraft).fold(
                    onSuccess = {
                        _uiState.update {
                            it.copy(
                                mode = AppMode.READING,
                                voiceDraft = null,
                                draftBody = it.selectedEmail?.suggestedReply.orEmpty(),
                                assistantStatus = if (isFromVoice) "Email sent" else null,
                            )
                        }
                        if (isFromVoice) tts.speak("Sent.")
                    },
                    onFailure = { e ->
                        val msg = e.message ?: "Send failed"
                        _uiState.update { it.copy(errorMessage = msg, assistantStatus = msg) }
                        if (isFromVoice) tts.speak(msg)
                    },
                )
            }
            return
        }

        val body = state.draftBody.trim()
        if (body.isBlank()) {
            if (isFromVoice) tts.speak("Draft is empty.")
            _uiState.update { it.copy(assistantStatus = "Draft is empty") }
            return
        }
        if (isFromVoice && !isVoiceSendArmed()) {
            tts.speak("Say you want to send, then confirm clearly.")
            _uiState.update { it.copy(assistantStatus = "Voice send not confirmed") }
            return
        }
        val draft = buildDraftFromComposeState(state) ?: return
        voiceSendArmedUntil = 0L
        viewModelScope.launch {
            repository.sendEmail(draft).fold(
                onSuccess = {
                    _uiState.update {
                        it.copy(
                            mode = AppMode.READING,
                            draftBody = it.selectedEmail?.suggestedReply.orEmpty(),
                            assistantStatus = if (isFromVoice) "Email sent" else null,
                        )
                    }
                    if (isFromVoice) tts.speak("Sent.")
                },
                onFailure = { e ->
                    val msg = e.message ?: "Send failed"
                    _uiState.update { it.copy(errorMessage = msg, assistantStatus = msg) }
                    if (isFromVoice) tts.speak(msg)
                },
            )
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
                    },
                )
            }
            repository.setStarred(messageId, starred)
        }
    }

    fun composeFromVoice(transcript: String) {
        val replyToId = _uiState.value.selectedEmail?.id
        viewModelScope.launch {
            repository.composeFromVoice(
                transcript = transcript,
                replyToMessageId = replyToId,
            ).fold(
                onSuccess = { draft ->
                    _uiState.update {
                        it.copy(
                            mode = AppMode.COMPOSING,
                            voiceDraft = draft,
                            draftBody = draft.body,
                        )
                    }
                },
                onFailure = { error ->
                    val msg = error.message ?: "Could not compose email"
                    _uiState.update {
                        it.copy(errorMessage = msg, assistantStatus = msg)
                    }
                },
            )
        }
    }

    fun handleVoiceCommand(command: EmailCommandTool.Command) {
        when (command) {
            is EmailCommandTool.Command.ArmSendForVoice -> {
                armVoiceSend()
                tts.speak("Okay. Confirm when you are ready to send.")
            }
            is EmailCommandTool.Command.SendDraft -> sendDraft(isFromVoice = true)
            is EmailCommandTool.Command.SelectEmail ->
                findEmail(command.emailId)?.let { selectEmail(it) }
            is EmailCommandTool.Command.ArchiveEmail ->
                findEmail(command.emailId)?.let { selectEmail(it); archiveSelected() }
            is EmailCommandTool.Command.SnoozeEmail ->
                findEmail(command.emailId)?.let { selectEmail(it); snoozeSelected() }
            is EmailCommandTool.Command.ForwardEmail -> {
                findEmail(command.emailId)?.let { selectEmail(it) }
                forwardSelected()
                _uiState.update { it.copy(draftBody = "Forwarding — To: ${command.to}\n\n") }
            }
            is EmailCommandTool.Command.Reply -> {
                findEmail(command.emailId)?.let { selectEmail(it) }
                _uiState.update { s ->
                    s.copy(
                        mode = AppMode.COMPOSING,
                        draftBody = command.body ?: s.draftBody,
                    )
                }
            }
            is EmailCommandTool.Command.Search -> voiceSearch(command.query)
            is EmailCommandTool.Command.ReadAloud -> {
                val email = findEmail(command.emailId)
                if (email != null) {
                    selectEmail(email)
                    tts.speak("${email.subject}. ${email.body}")
                } else {
                    tts.speak("Email not found.")
                }
            }
            is EmailCommandTool.Command.Summarize -> voiceSummarize(command.emailId)
            is EmailCommandTool.Command.DraftReply -> voiceDraftReply(command.emailId, command.tone)
            is EmailCommandTool.Command.FilterCategory -> voiceFilterCategory(command.category)
            is EmailCommandTool.Command.SetComposeBody -> {
                _uiState.update { it.copy(mode = AppMode.COMPOSING, draftBody = command.body) }
            }
            EmailCommandTool.Command.ShowInbox -> filterByCategory(null)
            EmailCommandTool.Command.GoBack -> cancelCompose()
        }
    }

    val selectedAttachments: List<Attachment>
        get() = _uiState.value.selectedEmail?.attachments.orEmpty()

    val selectedActionItems: List<ActionItem>
        get() = _uiState.value.selectedEmail?.actionItems.orEmpty()

    private fun voiceSearch(query: String) {
        val q = query.trim()
        if (q.isBlank()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val category = _uiState.value.activeCategory
            repository.listEmails(query = q).fold(
                onSuccess = { emails ->
                    val filtered = if (category != null) {
                        emails.filter { it.category == category }
                    } else {
                        emails
                    }
                    _uiState.update { state ->
                        val first = filtered.firstOrNull()
                        state.copy(
                            emails = filtered,
                            selectedEmail = first,
                            unreadCount = filtered.count { !it.isRead },
                            isLoading = false,
                            assistantStatus = "Search: ${filtered.size} results",
                            draftBody = first?.suggestedReply.orEmpty(),
                        )
                    }
                    filtered.firstOrNull()?.let { loadContactFor(it) }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = error.message,
                            assistantStatus = error.message,
                        )
                    }
                },
            )
        }
    }

    private fun voiceFilterCategory(categoryToken: String) {
        val cat = categoryToken.uppercase()
        if (cat == "ALL") {
            filterByCategory(null)
            return
        }
        val category = when (cat) {
            "PEOPLE" -> EmailCategory.PEOPLE
            "UPDATES" -> EmailCategory.UPDATES
            "PROMOTIONS" -> EmailCategory.PROMOTIONS
            "NEWSLETTERS" -> EmailCategory.NEWSLETTERS
            "TRANSACTIONAL" -> EmailCategory.TRANSACTIONAL
            else -> null
        }
        if (category != null) {
            filterByCategory(category)
        } else {
            _uiState.update { it.copy(assistantStatus = "Unknown category") }
        }
    }

    private fun voiceSummarize(emailId: String) {
        val email = findEmail(emailId) ?: run {
            tts.speak("Email not found.")
            return
        }
        selectEmail(email)
        viewModelScope.launch {
            textService.summarizeEmail(email.subject, email.body)
                .onSuccess { summary ->
                    updateEmailInState(email.id) { it.copy(aiSummary = summary) }
                    _uiState.update { it.copy(isAiSummaryExpanded = true, assistantStatus = "Summary ready") }
                    tts.speak(summary)
                }
                .onFailure {
                    val fallback = email.aiSummary
                    tts.speak(fallback)
                    _uiState.update { it.copy(assistantStatus = "Used cached summary") }
                }
        }
    }

    private fun voiceDraftReply(emailId: String, tone: String?) {
        val email = findEmail(emailId) ?: run {
            tts.speak("Email not found.")
            return
        }
        selectEmail(email)
        viewModelScope.launch {
            textService.draftReply(email.subject, email.body, tone)
                .onSuccess { draft ->
                    _uiState.update {
                        it.copy(
                            mode = AppMode.COMPOSING,
                            draftBody = draft,
                            assistantStatus = "Draft ready",
                        )
                    }
                    tts.speak("Draft is on screen.")
                }
                .onFailure {
                    val fallback = email.suggestedReply.orEmpty()
                    if (fallback.isNotBlank()) {
                        _uiState.update {
                            it.copy(mode = AppMode.COMPOSING, draftBody = fallback)
                        }
                        tts.speak("Using suggested reply.")
                    } else {
                        tts.speak("Could not draft a reply.")
                    }
                }
        }
    }

    private fun updateEmailInState(id: String, transform: (Email) -> Email) {
        _uiState.update { state ->
            val emails = state.emails.map { if (it.id == id) transform(it) else it }
            val selected = state.selectedEmail?.let { if (it.id == id) transform(it) else it }
            state.copy(
                emails = emails,
                selectedEmail = selected,
                selectedContact = state.selectedContact,
            )
        }
    }

    private fun findEmail(id: String): Email? =
        _uiState.value.emails.find { it.id == id }

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
                },
            )
        }
    }

    private fun buildDraftFromComposeState(state: EmailUiState): EmailDraft? {
        val body = state.draftBody.trim()
        if (body.isBlank()) return null
        val replyTo = state.selectedEmail
        return EmailDraft(
            to = replyTo?.senderEmail?.let(::listOf) ?: listOf(""),
            subject = replyTo?.let { "Re: ${it.subject}" } ?: "(no subject)",
            body = body,
            inReplyTo = replyTo?.id,
        )
    }

    class Factory(
        private val application: Application,
        private val repository: EmailRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return EmailViewModel(application, repository) as T
        }
    }

    override fun onCleared() {
        super.onCleared()
        tts.shutdown()
    }
}
