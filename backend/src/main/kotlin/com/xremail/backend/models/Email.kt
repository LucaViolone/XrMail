package com.xremail.backend.models

import kotlinx.serialization.Serializable

/**
 * Core email data model returned to the Android XR client.
 * Flattened from the raw Gmail API message format for ease of use.
 */
@Serializable
data class Email(
    val id: String,
    val threadId: String,
    val subject: String,
    val from: EmailAddress,
    val to: List<EmailAddress>,
    val cc: List<EmailAddress> = emptyList(),
    val snippet: String,            // Short preview text (~100 chars)
    val bodyText: String = "",      // Plain-text body (for voice reading)
    val bodyHtml: String = "",      // HTML body (for Focus layer rendering)
    val date: Long,                 // Unix timestamp (ms)
    val isRead: Boolean,
    val isStarred: Boolean,
    val labels: List<String> = emptyList(),
    val attachments: List<EmailAttachment> = emptyList(),
    val aiSummary: String? = null,  // Populated by Gemini after /ai/summarize
)

@Serializable
data class EmailAddress(
    val name: String,
    val email: String,
) {
    companion object {
        /**
         * Parses RFC 5322 address strings like "John Doe <john@example.com>"
         * or bare "john@example.com".
         */
        fun parse(raw: String): EmailAddress {
            val nameEmailRegex = Regex("""^"?(.+?)"?\s*<(.+?)>$""")
            val match = nameEmailRegex.find(raw.trim())
            return if (match != null) {
                EmailAddress(
                    name = match.groupValues[1].trim(),
                    email = match.groupValues[2].trim()
                )
            } else {
                EmailAddress(name = raw.trim(), email = raw.trim())
            }
        }

        fun parseList(raw: String): List<EmailAddress> =
            raw.split(",").map { parse(it) }
    }
}

@Serializable
data class EmailAttachment(
    val id: String,
    val filename: String,
    val mimeType: String,
    val sizeBytes: Long,
)

// ---------------------------------------------------------------------------
// Request / Response models for the email routes
// ---------------------------------------------------------------------------

@Serializable
data class EmailListResponse(
    val emails: List<Email>,
    val nextPageToken: String? = null,
    val totalCount: Int,
)

@Serializable
data class SendEmailRequest(
    val to: List<String>,           // Plain email addresses
    val cc: List<String> = emptyList(),
    val bcc: List<String> = emptyList(),
    val subject: String,
    val body: String,
    val isHtml: Boolean = false,
    val threadId: String? = null,   // Set to reply in-thread
    val inReplyTo: String? = null,  // Message-ID of the email being replied to
)

@Serializable
data class ModifyLabelsRequest(
    val addLabelIds: List<String> = emptyList(),
    val removeLabelIds: List<String> = emptyList(),
)

@Serializable
data class AuthLoginResponse(
    val authorizationUrl: String,
    val message: String,
)

@Serializable
data class AuthCallbackResponse(
    val token: String,      // JWT for the Android client
    val email: String,      // Authenticated Gmail address
)
