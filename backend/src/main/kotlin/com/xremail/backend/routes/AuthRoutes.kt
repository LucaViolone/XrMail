package com.xremail.backend.routes

import com.xremail.backend.config.GoogleConfig
import com.xremail.backend.models.AuthLoginResponse
import com.xremail.backend.models.TokenRefreshDto
import com.xremail.backend.models.ok
import com.xremail.backend.services.GmailService
import com.xremail.backend.services.TokenService
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * OAuth 2.0 authentication routes for Gmail.
 *
 * Flow overview:
 *  1. Android app calls GET /auth/login  → receives Google authorization URL
 *  2. App opens URL in a custom tab / WebView
 *  3. User authorizes → Google redirects to /auth/callback?code=...
 *  4. Backend exchanges code for tokens, stores them, issues a JWT
 *  5. App uses JWT for all subsequent API calls
 *
 * Token refresh is handled transparently by GmailService, but the app can also
 * call POST /auth/refresh explicitly if needed.
 */
fun Route.authRoutes(
    googleConfig: GoogleConfig,
    tokenService: TokenService,
    gmailService: GmailService,
) {
    route("/auth") {

        /**
         * GET /auth/login
         *
         * Returns the Google OAuth authorization URL. The Android app should open
         * this URL in a Chrome Custom Tab or system browser.
         *
         * Query params:
         *   state (optional) — an opaque value the app wants echoed back on callback
         *                       (useful for deep-link routing after auth completes)
         */
        get("/login") {
            val state = call.request.queryParameters["state"] ?: ""

            val authUrl = buildGoogleAuthUrl(googleConfig, state)

            call.respond(
                HttpStatusCode.OK,
                ok(
                    AuthLoginResponse(
                        authorizationUrl = authUrl,
                        message = "Open this URL in a browser to authorize XrMail",
                    ),
                ),
            )
        }

        /**
         * GET /auth/callback
         *
         * Google redirects here after the user grants (or denies) access.
         * This endpoint:
         *  1. Validates the authorization code
         *  2. Exchanges it for access + refresh tokens via the Google Token endpoint
         *  3. Fetches the user's profile (email address) as a stable identifier
         *  4. Persists the tokens in the local H2 database
         *  5. Issues a signed JWT for the Android client
         *
         * On success:  redirects to xrmail://auth/success?token=<jwt>&email=<email>
         * On failure:  redirects to xrmail://auth/error?reason=<message>
         */
        get("/callback") {
            val code = call.request.queryParameters["code"]
            val error = call.request.queryParameters["error"]
            val state = call.request.queryParameters["state"] ?: ""

            // Handle user-denied authorization
            if (error != null) {
                val reason = URLEncoder.encode(error, StandardCharsets.UTF_8.name())
                call.respondRedirect("xrmail://auth/error?reason=$reason&state=$state")
                return@get
            }

            if (code.isNullOrBlank()) {
                call.respondRedirect("xrmail://auth/error?reason=missing_code&state=$state")
                return@get
            }

            // Exchange authorization code for OAuth tokens
            val tokenResult = runCatching {
                gmailService.exchangeCodeForTokens(code)
            }

            if (tokenResult.isFailure) {
                val reason = URLEncoder.encode(
                    tokenResult.exceptionOrNull()?.message ?: "token_exchange_failed",
                    StandardCharsets.UTF_8.name()
                )
                call.respondRedirect("xrmail://auth/error?reason=$reason&state=$state")
                return@get
            }

            val (userEmail, userId) = tokenResult.getOrThrow()

            // Issue a JWT for the Android client to use on subsequent requests
            val jwt = tokenService.generateJwt(userId = userId, email = userEmail)

            val encodedEmail = URLEncoder.encode(userEmail, StandardCharsets.UTF_8.name())
            val encodedJwt = URLEncoder.encode(jwt, StandardCharsets.UTF_8.name())

            // Deep-link back into the XR app
            call.respondRedirect(
                "xrmail://auth/success?token=$encodedJwt&email=$encodedEmail&state=$state"
            )
        }

        /**
         * POST /auth/refresh
         *
         * Extends the JWT session. The Android app should call this when its JWT
         * is close to expiry (check the 'exp' claim). The backend re-validates that
         * the stored refresh token is still valid before issuing a new JWT.
         *
         * Requires: Authorization: Bearer <current-jwt>
         */
        authenticate("jwt-auth") {
            post("/refresh") {
                val principal = call.principal<JWTPrincipal>()
                    ?: return@post call.respond(HttpStatusCode.Unauthorized)

                val userId = principal.subject
                    ?: return@post call.respond(HttpStatusCode.Unauthorized)

                val email = principal.payload.getClaim("email")?.asString()
                    ?: return@post call.respond(HttpStatusCode.Unauthorized)

                // Verify the stored Google refresh token is still usable
                val refreshValid = runCatching {
                    gmailService.validateRefreshToken(userId)
                }.getOrDefault(false)

                if (!refreshValid) {
                    call.respond(
                        HttpStatusCode.Unauthorized,
                        mapOf(
                            "error" to "refresh_token_invalid",
                            "message" to "Re-authentication required. Call GET /auth/login."
                        )
                    )
                    return@post
                }

                val newJwt = tokenService.generateJwt(userId = userId, email = email)
                call.respond(HttpStatusCode.OK, ok(TokenRefreshDto(token = newJwt)))
            }

            /**
             * POST /auth/logout
             *
             * Revokes the stored Google OAuth tokens and invalidates the session.
             * The Android app should call this when the user explicitly signs out.
             *
             * Requires: Authorization: Bearer <jwt>
             */
            post("/logout") {
                val principal = call.principal<JWTPrincipal>()
                    ?: return@post call.respond(HttpStatusCode.Unauthorized)

                val userId = principal.subject
                    ?: return@post call.respond(HttpStatusCode.Unauthorized)

                runCatching { gmailService.revokeTokens(userId) }

                call.respond(HttpStatusCode.OK, ok(mapOf("success" to true)))
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Private helpers
// ---------------------------------------------------------------------------

private fun buildGoogleAuthUrl(config: GoogleConfig, state: String): String {
    val baseUrl = "https://accounts.google.com/o/oauth2/v2/auth"
    val scopes = config.scopes.joinToString(" ")

    val params = buildMap {
        put("client_id", config.clientId)
        put("redirect_uri", config.redirectUri)
        put("response_type", "code")
        put("scope", scopes)
        put("access_type", "offline")       // Request refresh token
        put("prompt", "consent")            // Force consent screen to get refresh token
        put("include_granted_scopes", "true")
        if (state.isNotBlank()) put("state", state)
    }

    val query = params.entries.joinToString("&") { (k, v) ->
        "${URLEncoder.encode(k, StandardCharsets.UTF_8.name())}=${URLEncoder.encode(v, StandardCharsets.UTF_8.name())}"
    }

    return "$baseUrl?$query"
}
