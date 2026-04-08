package com.xremail.backend.services

import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeTokenRequest
import com.google.api.client.googleapis.auth.oauth2.GoogleCredential
import com.google.api.client.googleapis.auth.oauth2.GoogleRefreshTokenRequest
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.client.googleapis.auth.oauth2.GoogleTokenResponse
import com.google.api.services.gmail.Gmail
import com.google.api.services.gmail.model.Message
import com.xremail.backend.config.GoogleConfig
import com.xremail.backend.db.OAuthTokensTable
import com.xremail.backend.models.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import org.apache.commons.codec.binary.Base64
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.Properties
import javax.mail.Message as JavaMailMessage
import javax.mail.Session
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage

/**
 * Core Gmail integration service.
 *
 * Responsibilities:
 *  - OAuth code → token exchange
 *  - Token refresh (transparent, called automatically before each API request)
 *  - List, fetch, send, search, and label-modify emails
 *  - Token revocation on sign-out
 *
 * All Gmail API calls run through a per-user [Gmail] client instance created
 * with that user's current access token. If the access token is expired,
 * [getGmailClient] refreshes it before building the client.
 */
class GmailService(private val config: GoogleConfig) {

    private val httpTransport: NetHttpTransport = GoogleNetHttpTransport.newTrustedTransport()
    private val jsonFactory = GsonFactory.getDefaultInstance()

    // Internal HTTP client used for token operations not covered by the Google SDK
    private val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) { json() }
    }

    // ---------------------------------------------------------------------------
    // OAuth token exchange
    // ---------------------------------------------------------------------------

    /**
     * Exchanges a one-time authorization code for Google OAuth tokens.
     *
     * Returns a [Pair] of (userEmail, userId) after:
     *  1. Exchanging the code at the Google token endpoint
     *  2. Fetching the authenticated user's profile for the stable userId ('sub')
     *  3. Persisting the tokens in the local database
     *
     * @param code The authorization code received at /auth/callback
     * @return Pair(userEmail, userId)
     * @throws Exception if the token exchange or profile fetch fails
     */
    suspend fun exchangeCodeForTokens(code: String): Pair<String, String> {
        // Exchange authorization code for access + refresh tokens
        val tokenResponse = GoogleAuthorizationCodeTokenRequest(
            httpTransport,
            jsonFactory,
            "https://oauth2.googleapis.com/token",
            config.clientId,
            config.clientSecret,
            code,
            config.redirectUri
        ).execute()

        val accessToken = tokenResponse.accessToken
            ?: throw IllegalStateException("Google did not return an access token")
        val refreshToken = tokenResponse.refreshToken
            ?: throw IllegalStateException(
                "Google did not return a refresh token. " +
                "Make sure prompt=consent and access_type=offline are set in the auth URL."
            )
        val expiresInSeconds = tokenResponse.expiresInSeconds ?: 3600L
        val expiresAt = Instant.now().plusSeconds(expiresInSeconds)

        // Fetch the user profile to get a stable userId
        val profile = fetchUserProfile(accessToken)
        val userId = profile.id
        val userEmail = profile.email

        // Persist tokens — TokenService handles upsert
        tokenServiceInstance.saveTokens(
            userId = userId,
            email = userEmail,
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresAt = expiresAt,
        )

        return Pair(userEmail, userId)
    }

    /**
     * Verifies that the stored refresh token for [userId] can be used to obtain
     * a new access token. Called before issuing a new JWT during /auth/refresh.
     */
    suspend fun validateRefreshToken(userId: String): Boolean {
        return runCatching {
            refreshAccessTokenIfNeeded(userId)
            true
        }.getOrDefault(false)
    }

    /**
     * Revokes all stored OAuth tokens for the user and removes them from the DB.
     * Called on /auth/logout.
     */
    suspend fun revokeTokens(userId: String) {
        val row = tokenServiceInstance.getTokenRow(userId) ?: return

        // Revoke with Google
        runCatching {
            httpClient.post("https://oauth2.googleapis.com/revoke") {
                parameter("token", row.refreshToken)
            }
        }

        // Remove from local DB
        tokenServiceInstance.deleteTokens(userId)
    }

    // ---------------------------------------------------------------------------
    // Email operations
    // ---------------------------------------------------------------------------

    /**
     * Lists emails in the user's mailbox.
     *
     * @param userId The authenticated user's ID
     * @param query Gmail search query string (e.g. "is:unread", "from:boss@co.com")
     * @param maxResults Maximum number of emails to return (1–100)
     * @param pageToken Pagination token from a previous call
     * @param labelIds Filter by label IDs (e.g. ["INBOX", "UNREAD"])
     */
    fun listEmails(
        userId: String,
        query: String = "",
        maxResults: Long = 20,
        pageToken: String? = null,
        labelIds: List<String> = listOf("INBOX"),
    ): EmailListResponse {
        val gmail = getGmailClient(userId)

        val listRequest = gmail.users().messages().list("me").apply {
            q = query.ifBlank { null }
            this.maxResults = maxResults
            this.pageToken = pageToken
            if (labelIds.isNotEmpty()) this.labelIds = labelIds
        }

        val listResult = listRequest.execute()
        val messageStubs = listResult.messages ?: emptyList()

        // Fetch full message details for each stub in parallel
        val emails = messageStubs.mapNotNull { stub ->
            runCatching {
                val msg = gmail.users().messages().get("me", stub.id)
                    .setFormat("full")
                    .execute()
                msg.toEmail()
            }.getOrNull()
        }

        return EmailListResponse(
            emails = emails,
            nextPageToken = listResult.nextPageToken,
            totalCount = emails.size,
        )
    }

    /**
     * Fetches a single email by ID with full body content.
     */
    fun getEmail(userId: String, messageId: String): Email {
        val gmail = getGmailClient(userId)
        val message = gmail.users().messages().get("me", messageId)
            .setFormat("full")
            .execute()
        return message.toEmail()
    }

    /**
     * Sends an email on behalf of the authenticated user.
     * Supports plain text, HTML, and threaded replies.
     */
    fun sendEmail(userId: String, request: SendEmailRequest) {
        val gmail = getGmailClient(userId)

        val mimeMessage = buildMimeMessage(request)
        val encodedEmail = encodeMessage(mimeMessage)

        val message = Message().apply {
            raw = encodedEmail
            if (request.threadId != null) threadId = request.threadId
        }

        gmail.users().messages().send("me", message).execute()
    }

    /**
     * Modifies the labels on a message (e.g. mark as read, star, archive).
     *
     * Common label operations:
     *   Archive:     removeLabelIds=["INBOX"]
     *   Mark read:   removeLabelIds=["UNREAD"]
     *   Star:        addLabelIds=["STARRED"]
     *   Trash:       addLabelIds=["TRASH"], removeLabelIds=["INBOX"]
     */
    fun modifyLabels(userId: String, messageId: String, request: ModifyLabelsRequest) {
        val gmail = getGmailClient(userId)
        val modifyRequest = com.google.api.services.gmail.model.ModifyMessageRequest().apply {
            addLabelIds = request.addLabelIds.ifEmpty { null }
            removeLabelIds = request.removeLabelIds.ifEmpty { null }
        }
        gmail.users().messages().modify("me", messageId, modifyRequest).execute()
    }

    /**
     * Moves a message to trash.
     */
    fun trashEmail(userId: String, messageId: String) {
        val gmail = getGmailClient(userId)
        gmail.users().messages().trash("me", messageId).execute()
    }

    // ---------------------------------------------------------------------------
    // Private helpers
    // ---------------------------------------------------------------------------

    /**
     * Builds a Gmail API client for the given user, refreshing the access token
     * if it is expired or close to expiry (< 60 seconds remaining).
     */
    private fun getGmailClient(userId: String): Gmail {
        val tokenRow = tokenServiceInstance.getTokenRow(userId)
            ?: throw IllegalStateException("No stored tokens for user $userId. Re-authentication required.")

        val accessToken = if (Instant.now().isAfter(tokenRow.expiresAt.minusSeconds(60))) {
            refreshAccessTokenSync(userId, tokenRow.refreshToken)
        } else {
            tokenRow.accessToken
        }

        val credential = GoogleCredential().setAccessToken(accessToken)
        return Gmail.Builder(httpTransport, jsonFactory, credential)
            .setApplicationName("XrMail")
            .build()
    }

    /**
     * Refreshes the access token synchronously using the stored refresh token.
     * Persists the new token via TokenService and returns the new access token.
     */
    private fun refreshAccessTokenSync(userId: String, refreshToken: String): String {
        val tokenResponse = GoogleRefreshTokenRequest(
            httpTransport,
            jsonFactory,
            refreshToken,
            config.clientId,
            config.clientSecret,
        ).execute()

        val newAccessToken = tokenResponse.accessToken
            ?: throw IllegalStateException("Failed to refresh access token for user $userId")
        val expiresAt = Instant.now().plusSeconds(tokenResponse.expiresInSeconds ?: 3600L)

        tokenServiceInstance.updateAccessToken(
            userId = userId,
            newAccessToken = newAccessToken,
            newExpiresAt = expiresAt,
        )

        return newAccessToken
    }

    /** Suspend-friendly wrapper around [refreshAccessTokenSync]. */
    private suspend fun refreshAccessTokenIfNeeded(userId: String) {
        val tokenRow = tokenServiceInstance.getTokenRow(userId)
            ?: throw IllegalStateException("No tokens for user $userId")
        if (Instant.now().isAfter(tokenRow.expiresAt.minusSeconds(60))) {
            refreshAccessTokenSync(userId, tokenRow.refreshToken)
        }
    }

    /**
     * Fetches the authenticated user's Google profile (email + stable userId).
     */
    private suspend fun fetchUserProfile(accessToken: String): GoogleProfile {
        val response: GoogleProfile = httpClient.get("https://www.googleapis.com/oauth2/v3/userinfo") {
            header("Authorization", "Bearer $accessToken")
        }.body()
        return response
    }

    /** Builds a JavaMail [MimeMessage] from a [SendEmailRequest]. */
    private fun buildMimeMessage(request: SendEmailRequest): MimeMessage {
        val props = Properties()
        val session = Session.getDefaultInstance(props, null)

        return MimeMessage(session).apply {
            setFrom(InternetAddress("me"))
            request.to.forEach { addRecipient(JavaMailMessage.RecipientType.TO, InternetAddress(it)) }
            request.cc.forEach { addRecipient(JavaMailMessage.RecipientType.CC, InternetAddress(it)) }
            request.bcc.forEach { addRecipient(JavaMailMessage.RecipientType.BCC, InternetAddress(it)) }
            subject = request.subject
            if (request.isHtml) {
                setContent(request.body, "text/html; charset=utf-8")
            } else {
                setText(request.body, "utf-8")
            }
            if (request.inReplyTo != null) setHeader("In-Reply-To", request.inReplyTo)
        }
    }

    /** Base64url-encodes a [MimeMessage] into the format Gmail's API expects. */
    private fun encodeMessage(message: MimeMessage): String {
        val buffer = ByteArrayOutputStream()
        message.writeTo(buffer)
        return Base64.encodeBase64URLSafeString(buffer.toByteArray())
    }

    // ---------------------------------------------------------------------------
    // Gmail Message → Email model conversion
    // ---------------------------------------------------------------------------

    private fun Message.toEmail(): Email {
        val headers = payload?.headers?.associateBy({ it.name }, { it.value }) ?: emptyMap()
        val labelIds = this.labelIds ?: emptyList()

        val (textBody, htmlBody) = extractBodies(payload)

        return Email(
            id = id ?: "",
            threadId = threadId ?: "",
            subject = headers["Subject"] ?: "(no subject)",
            from = EmailAddress.parse(headers["From"] ?: ""),
            to = EmailAddress.parseList(headers["To"] ?: ""),
            cc = headers["Cc"]?.let { EmailAddress.parseList(it) } ?: emptyList(),
            snippet = snippet ?: "",
            bodyText = textBody,
            bodyHtml = htmlBody,
            date = internalDate ?: 0L,
            isRead = !labelIds.contains("UNREAD"),
            isStarred = labelIds.contains("STARRED"),
            labels = labelIds,
            attachments = extractAttachments(payload),
        )
    }

    private fun extractBodies(
        part: com.google.api.services.gmail.model.MessagePart?,
    ): Pair<String, String> {
        if (part == null) return Pair("", "")

        val mimeType = part.mimeType ?: ""

        // Leaf node: plain text or HTML
        if (mimeType == "text/plain" || mimeType == "text/html") {
            val decoded = part.body?.data?.let {
                String(Base64.decodeBase64(it.replace('-', '+').replace('_', '/')))
            } ?: ""
            return if (mimeType == "text/plain") Pair(decoded, "") else Pair("", decoded)
        }

        // Multipart: recursively gather from all parts
        var plainText = ""
        var htmlText = ""
        part.parts?.forEach { child ->
            val (childPlain, childHtml) = extractBodies(child)
            if (plainText.isEmpty() && childPlain.isNotEmpty()) plainText = childPlain
            if (htmlText.isEmpty() && childHtml.isNotEmpty()) htmlText = childHtml
        }
        return Pair(plainText, htmlText)
    }

    private fun extractAttachments(
        part: com.google.api.services.gmail.model.MessagePart?,
    ): List<EmailAttachment> {
        if (part == null) return emptyList()
        val attachments = mutableListOf<EmailAttachment>()

        val filename = part.filename
        if (!filename.isNullOrBlank() && part.body?.attachmentId != null) {
            attachments.add(
                EmailAttachment(
                    id = part.body.attachmentId,
                    filename = filename,
                    mimeType = part.mimeType ?: "application/octet-stream",
                    sizeBytes = part.body.size?.toLong() ?: 0L,
                )
            )
        }
        part.parts?.forEach { attachments.addAll(extractAttachments(it)) }
        return attachments
    }

    // Lazy reference to TokenService — avoids circular injection.
    // In production, replace with proper DI (e.g. Koin).
    companion object {
        internal lateinit var tokenServiceInstance: TokenService

        fun init(tokenService: TokenService) {
            tokenServiceInstance = tokenService
        }
    }
}

// ---------------------------------------------------------------------------
// Internal DTOs for Google API responses
// ---------------------------------------------------------------------------

@Serializable
private data class GoogleProfile(
    val sub: String = "",
    val email: String = "",
    val name: String = "",
    val picture: String = "",
) {
    val id: String get() = sub
}
