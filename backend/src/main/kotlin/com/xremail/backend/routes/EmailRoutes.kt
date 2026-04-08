package com.xremail.backend.routes

import com.xremail.backend.models.*
import com.xremail.backend.services.GmailService
import com.xremail.backend.services.TokenService
import io.ktor.http.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.auth.*
import io.ktor.server.routing.*

/**
 * Gmail email CRUD routes — all require a valid JWT (enforced by the
 * authenticate("jwt-auth") block in Routing.kt).
 *
 * Base path: /emails
 */
fun Route.emailRoutes(
    gmailService: GmailService,
    tokenService: TokenService,
) {
    route("/emails") {

        /**
         * GET /emails
         *
         * Lists emails in the user's inbox (or any label/query combination).
         *
         * Query params:
         *   q           — Gmail search query (default: "")
         *   maxResults  — Max messages to return (default: 20, max: 100)
         *   pageToken   — Pagination token from a previous response
         *   labels      — Comma-separated label IDs (default: INBOX)
         *
         * Response: EmailListResponse
         */
        get {
            val userId = call.principal<JWTPrincipal>()?.subject
                ?: return@get call.respond(HttpStatusCode.Unauthorized)

            val query = call.request.queryParameters["q"] ?: ""
            val maxResults = call.request.queryParameters["maxResults"]?.toLongOrNull() ?: 20L
            val pageToken = call.request.queryParameters["pageToken"]
            val labels = call.request.queryParameters["labels"]
                ?.split(",")?.map { it.trim() }
                ?: listOf("INBOX")

            val result = runCatching {
                gmailService.listEmails(
                    userId = userId,
                    query = query,
                    maxResults = maxResults.coerceIn(1, 100),
                    pageToken = pageToken,
                    labelIds = labels,
                )
            }

            if (result.isFailure) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    err<EmailListResponse>(
                        "gmail_list_failed",
                        result.exceptionOrNull()?.message ?: "Failed to list emails"
                    )
                )
                return@get
            }

            call.respond(HttpStatusCode.OK, ok(result.getOrThrow()))
        }

        /**
         * GET /emails/{id}
         *
         * Fetches a single email with full body content.
         * Use this when the user opens an email in the Focus layer.
         */
        get("/{id}") {
            val userId = call.principal<JWTPrincipal>()?.subject
                ?: return@get call.respond(HttpStatusCode.Unauthorized)
            val messageId = call.parameters["id"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing message id")

            val result = runCatching { gmailService.getEmail(userId, messageId) }

            if (result.isFailure) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    err<Email>(
                        "gmail_get_failed",
                        result.exceptionOrNull()?.message ?: "Failed to fetch email"
                    )
                )
                return@get
            }

            call.respond(HttpStatusCode.OK, ok(result.getOrThrow()))
        }

        /**
         * POST /emails/send
         *
         * Sends a new email or a reply on behalf of the authenticated user.
         *
         * Body: SendEmailRequest
         *
         * To reply in-thread, include threadId and inReplyTo (Message-ID header
         * of the email being replied to) in the request body.
         */
        post("/send") {
            val userId = call.principal<JWTPrincipal>()?.subject
                ?: return@post call.respond(HttpStatusCode.Unauthorized)

            val request = runCatching { call.receive<SendEmailRequest>() }.getOrElse {
                call.respond(
                    HttpStatusCode.BadRequest,
                    err<Unit>("invalid_body", "Request body must be a valid SendEmailRequest JSON")
                )
                return@post
            }

            if (request.to.isEmpty()) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    err<Unit>("missing_recipient", "At least one recipient is required")
                )
                return@post
            }

            val result = runCatching { gmailService.sendEmail(userId, request) }

            if (result.isFailure) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    err<Unit>(
                        "gmail_send_failed",
                        result.exceptionOrNull()?.message ?: "Failed to send email"
                    )
                )
                return@post
            }

            call.respond(HttpStatusCode.OK, ok(mapOf("sent" to true)))
        }

        /**
         * PATCH /emails/{id}/labels
         *
         * Modifies labels on a message. Use this for:
         *  - Mark as read:  removeLabelIds=["UNREAD"]
         *  - Archive:       removeLabelIds=["INBOX"]
         *  - Star:          addLabelIds=["STARRED"]
         *  - Move to trash: addLabelIds=["TRASH"], removeLabelIds=["INBOX"]
         *
         * Body: ModifyLabelsRequest
         */
        patch("/{id}/labels") {
            val userId = call.principal<JWTPrincipal>()?.subject
                ?: return@patch call.respond(HttpStatusCode.Unauthorized)
            val messageId = call.parameters["id"]
                ?: return@patch call.respond(HttpStatusCode.BadRequest, "Missing message id")

            val request = runCatching { call.receive<ModifyLabelsRequest>() }.getOrElse {
                call.respond(
                    HttpStatusCode.BadRequest,
                    err<Unit>("invalid_body", "Request body must be a valid ModifyLabelsRequest JSON")
                )
                return@patch
            }

            val result = runCatching { gmailService.modifyLabels(userId, messageId, request) }

            if (result.isFailure) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    err<Unit>(
                        "gmail_label_failed",
                        result.exceptionOrNull()?.message ?: "Failed to modify labels"
                    )
                )
                return@patch
            }

            call.respond(HttpStatusCode.OK, ok(mapOf("modified" to true)))
        }

        /**
         * DELETE /emails/{id}
         *
         * Moves a message to the Trash. (Permanent deletion is intentionally not
         * exposed — use the Gmail UI for that.)
         */
        delete("/{id}") {
            val userId = call.principal<JWTPrincipal>()?.subject
                ?: return@delete call.respond(HttpStatusCode.Unauthorized)
            val messageId = call.parameters["id"]
                ?: return@delete call.respond(HttpStatusCode.BadRequest, "Missing message id")

            val result = runCatching { gmailService.trashEmail(userId, messageId) }

            if (result.isFailure) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    err<Unit>(
                        "gmail_trash_failed",
                        result.exceptionOrNull()?.message ?: "Failed to trash email"
                    )
                )
                return@delete
            }

            call.respond(HttpStatusCode.OK, ok(mapOf("trashed" to true)))
        }
    }
}
