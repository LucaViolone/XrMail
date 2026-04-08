package com.xremail.backend.routes

import com.xremail.backend.models.*
import com.xremail.backend.services.EmailDraft
import com.xremail.backend.services.GeminiService
import com.xremail.backend.services.GmailService
import com.xremail.backend.services.TokenService
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

/**
 * AI-powered email processing routes backed by Gemini.
 *
 * These endpoints sit between the Gmail data layer and the XR UI layers:
 *  - Ambient layer: uses /ai/summarize for the peripheral notification bar
 *  - Interaction layer: uses /ai/replies for gesture-selectable reply chips
 *  - Focus layer (voice compose): uses /ai/compose to structure voice input
 *
 * Base path: /ai
 */
fun Route.aiRoutes(
    geminiService: GeminiService,
    gmailService: GmailService,
    tokenService: TokenService,
) {
    route("/ai") {

        /**
         * POST /ai/summarize
         *
         * Generates a concise 1-2 sentence summary of an email.
         * Used in the Ambient layer notification bar and Interaction layer list.
         *
         * Body: SummarizeRequest
         * Response: SummarizeResponse
         */
        post("/summarize") {
            val userId = call.principal<JWTPrincipal>()?.subject
                ?: return@post call.respond(HttpStatusCode.Unauthorized)

            val request = runCatching { call.receive<SummarizeRequest>() }.getOrElse {
                call.respond(HttpStatusCode.BadRequest, err<Unit>("invalid_body", it.message ?: "Bad request"))
                return@post
            }

            val email = runCatching { gmailService.getEmail(userId, request.messageId) }.getOrElse {
                call.respond(HttpStatusCode.NotFound, err<Unit>("email_not_found", it.message ?: "Email not found"))
                return@post
            }

            val summary = runCatching { geminiService.summarize(email) }.getOrElse {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    err<Unit>("summarize_failed", it.message ?: "Summarization failed")
                )
                return@post
            }

            call.respond(HttpStatusCode.OK, ok(SummarizeResponse(summary = summary)))
        }

        /**
         * POST /ai/replies
         *
         * Generates 2-3 short voice-ready reply suggestions for an email.
         * Displayed as gesture-selectable chips in the Interaction layer.
         *
         * Body: ReplySuggestionsRequest
         * Response: ReplySuggestionsResponse
         */
        post("/replies") {
            val userId = call.principal<JWTPrincipal>()?.subject
                ?: return@post call.respond(HttpStatusCode.Unauthorized)

            val request = runCatching { call.receive<ReplySuggestionsRequest>() }.getOrElse {
                call.respond(HttpStatusCode.BadRequest, err<Unit>("invalid_body", it.message ?: "Bad request"))
                return@post
            }

            val email = runCatching { gmailService.getEmail(userId, request.messageId) }.getOrElse {
                call.respond(HttpStatusCode.NotFound, err<Unit>("email_not_found", it.message ?: "Email not found"))
                return@post
            }

            val suggestions = runCatching { geminiService.suggestReplies(email) }.getOrElse {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    err<Unit>("replies_failed", it.message ?: "Reply suggestion failed")
                )
                return@post
            }

            call.respond(
                HttpStatusCode.OK,
                ok(ReplySuggestionsResponse(suggestions = suggestions))
            )
        }

        /**
         * POST /ai/compose
         *
         * Converts a Whisper transcript (voice command) into a structured email draft.
         *
         * The XR app flow:
         *  1. User says "Reply to Sarah and tell her I'll be 10 minutes late"
         *  2. App POSTs the Whisper transcript to /voice/transcribe
         *  3. App POSTs the resulting text here to get a structured EmailDraft
         *  4. App displays the draft in the Focus layer for review
         *  5. User confirms by voice ("Send it") or gesture → POST /emails/send
         *
         * Body: ComposeFromVoiceRequest
         * Response: EmailDraft
         */
        post("/compose") {
            val userId = call.principal<JWTPrincipal>()?.subject
                ?: return@post call.respond(HttpStatusCode.Unauthorized)

            val request = runCatching { call.receive<ComposeFromVoiceRequest>() }.getOrElse {
                call.respond(HttpStatusCode.BadRequest, err<Unit>("invalid_body", it.message ?: "Bad request"))
                return@post
            }

            // If replying to a specific email, fetch it for context
            val replyToEmail = request.replyToMessageId?.let {
                runCatching { gmailService.getEmail(userId, it) }.getOrNull()
            }

            val draft = runCatching {
                geminiService.composeFromVoice(
                    transcript = request.transcript,
                    replyToEmail = replyToEmail,
                )
            }.getOrElse {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    err<Unit>("compose_failed", it.message ?: "Email composition failed")
                )
                return@post
            }

            call.respond(HttpStatusCode.OK, ok(draft))
        }
    }
}

// ---------------------------------------------------------------------------
// Request / Response models specific to AI routes
// ---------------------------------------------------------------------------

@Serializable
data class SummarizeRequest(val messageId: String)

@Serializable
data class SummarizeResponse(val summary: String)

@Serializable
data class ReplySuggestionsRequest(val messageId: String)

@Serializable
data class ReplySuggestionsResponse(val suggestions: List<String>)

@Serializable
data class ComposeFromVoiceRequest(
    val transcript: String,
    val replyToMessageId: String? = null,
)
