package com.xremail.backend.plugins

import com.xremail.backend.config.AppConfig
import com.xremail.backend.routes.authRoutes
import com.xremail.backend.routes.emailRoutes
import com.xremail.backend.routes.voiceRoutes
import com.xremail.backend.routes.aiRoutes
import com.xremail.backend.services.GeminiService
import com.xremail.backend.services.GmailService
import com.xremail.backend.services.TokenService
import com.xremail.backend.services.WhisperService
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Application.configureRouting(config: AppConfig) {
    // Instantiate services (simple DI — swap for Koin if the project grows)
    val tokenService = TokenService(config.jwt)
    val gmailService = GmailService(config.google).also {
        // Give GmailService a reference to TokenService for token persistence
        GmailService.init(tokenService)
    }
    val whisperService = WhisperService(config.whisper)
    val geminiService = GeminiService(config.gemini)

    routing {
        // Health check — used by the Android app to verify connectivity
        get("/health") {
            call.respond(mapOf("status" to "ok", "version" to "0.1.0"))
        }

        // Public routes — no JWT required
        authRoutes(config.google, tokenService, gmailService)

        // Protected routes — JWT bearer token required
        authenticate("jwt-auth") {
            emailRoutes(gmailService, tokenService)
            voiceRoutes(whisperService)
            aiRoutes(geminiService, gmailService, tokenService)
        }
    }
}
