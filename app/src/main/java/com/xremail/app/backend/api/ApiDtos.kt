package com.xremail.app.backend.api

import com.google.gson.annotations.SerializedName

/**
 * Network-layer DTOs — these mirror the Ktor backend's JSON shapes exactly.
 *
 * Kept separate from the app's [com.xremail.app.data.Email] domain model so
 * that backend schema changes don't require touching UI code.
 * Mapping to domain models happens in [com.xremail.app.backend.service.GmailRepository].
 */

// ---------------------------------------------------------------------------
// Generic API envelope  (matches backend ApiResponse<T>)
// ---------------------------------------------------------------------------

data class ApiResponse<T>(
    @SerializedName("success") val success: Boolean,
    @SerializedName("data") val data: T?,
    @SerializedName("error") val error: ApiError?,
)

data class ApiError(
    @SerializedName("code") val code: String,
    @SerializedName("message") val message: String,
)

// ---------------------------------------------------------------------------
// Auth
// ---------------------------------------------------------------------------

data class AuthLoginDto(
    @SerializedName("authorizationUrl") val authorizationUrl: String,
    @SerializedName("message") val message: String,
)

data class TokenRefreshDto(
    @SerializedName("token") val token: String,
)

// ---------------------------------------------------------------------------
// Email list
// ---------------------------------------------------------------------------

data class EmailListDto(
    @SerializedName("emails") val emails: List<EmailDto>,
    @SerializedName("nextPageToken") val nextPageToken: String?,
    @SerializedName("totalCount") val totalCount: Int,
)

data class EmailDto(
    @SerializedName("id") val id: String,
    @SerializedName("threadId") val threadId: String,
    @SerializedName("subject") val subject: String,
    @SerializedName("from") val from: EmailAddressDto,
    @SerializedName("to") val to: List<EmailAddressDto>,
    @SerializedName("cc") val cc: List<EmailAddressDto> = emptyList(),
    @SerializedName("snippet") val snippet: String,
    @SerializedName("bodyText") val bodyText: String = "",
    @SerializedName("bodyHtml") val bodyHtml: String = "",
    @SerializedName("date") val date: Long,
    @SerializedName("isRead") val isRead: Boolean,
    @SerializedName("isStarred") val isStarred: Boolean,
    @SerializedName("labels") val labels: List<String> = emptyList(),
    @SerializedName("attachments") val attachments: List<AttachmentDto> = emptyList(),
    @SerializedName("aiSummary") val aiSummary: String? = null,
)

data class EmailAddressDto(
    @SerializedName("name") val name: String,
    @SerializedName("email") val email: String,
)

data class AttachmentDto(
    @SerializedName("id") val id: String,
    @SerializedName("filename") val filename: String,
    @SerializedName("mimeType") val mimeType: String,
    @SerializedName("sizeBytes") val sizeBytes: Long,
)

// ---------------------------------------------------------------------------
// Send email
// ---------------------------------------------------------------------------

data class SendEmailRequest(
    @SerializedName("to") val to: List<String>,
    @SerializedName("cc") val cc: List<String> = emptyList(),
    @SerializedName("bcc") val bcc: List<String> = emptyList(),
    @SerializedName("subject") val subject: String,
    @SerializedName("body") val body: String,
    @SerializedName("isHtml") val isHtml: Boolean = false,
    @SerializedName("threadId") val threadId: String? = null,
    @SerializedName("inReplyTo") val inReplyTo: String? = null,
)

// ---------------------------------------------------------------------------
// Label modification
// ---------------------------------------------------------------------------

data class ModifyLabelsRequest(
    @SerializedName("addLabelIds") val addLabelIds: List<String> = emptyList(),
    @SerializedName("removeLabelIds") val removeLabelIds: List<String> = emptyList(),
)

// ---------------------------------------------------------------------------
// Voice transcription
// ---------------------------------------------------------------------------

data class TranscriptionDto(
    @SerializedName("text") val text: String,
    @SerializedName("language") val language: String,
    @SerializedName("durationSeconds") val durationSeconds: Double,
)

// ---------------------------------------------------------------------------
// AI
// ---------------------------------------------------------------------------

data class SummarizeRequest(
    @SerializedName("messageId") val messageId: String,
)

data class SummarizeDto(
    @SerializedName("summary") val summary: String,
)

data class ReplySuggestionsRequest(
    @SerializedName("messageId") val messageId: String,
)

data class ReplySuggestionsDto(
    @SerializedName("suggestions") val suggestions: List<String>,
)

data class ComposeFromVoiceRequest(
    @SerializedName("transcript") val transcript: String,
    @SerializedName("replyToMessageId") val replyToMessageId: String? = null,
)

data class EmailDraftDto(
    @SerializedName("to") val to: List<String>,
    @SerializedName("cc") val cc: List<String> = emptyList(),
    @SerializedName("subject") val subject: String,
    @SerializedName("body") val body: String,
)
