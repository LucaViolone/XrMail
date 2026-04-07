package com.xremail.backend.config

import io.ktor.server.config.*

/**
 * Central configuration for the XrMail backend.
 * All values are read from application.yaml (or environment variables in production).
 */
data class AppConfig(
    val server: ServerConfig,
    val jwt: JwtConfig,
    val google: GoogleConfig,
    val whisper: WhisperConfig,
    val gemini: GeminiConfig,
    val database: DatabaseConfig,
) {
    companion object {
        fun load(config: ApplicationConfig): AppConfig = AppConfig(
            server = ServerConfig(
                host = config.propertyOrNull("ktor.deployment.host")?.getString() ?: "0.0.0.0",
                port = config.propertyOrNull("ktor.deployment.port")?.getString()?.toInt() ?: 8080,
                baseUrl = config.property("xrmail.server.baseUrl").getString(),
            ),
            jwt = JwtConfig(
                secret = config.property("xrmail.jwt.secret").getString(),
                issuer = config.property("xrmail.jwt.issuer").getString(),
                audience = config.property("xrmail.jwt.audience").getString(),
                expirationMs = config.property("xrmail.jwt.expirationMs").getString().toLong(),
            ),
            google = GoogleConfig(
                clientId = config.property("xrmail.google.clientId").getString(),
                clientSecret = config.property("xrmail.google.clientSecret").getString(),
                redirectUri = config.property("xrmail.google.redirectUri").getString(),
                scopes = config.property("xrmail.google.scopes").getString().split(","),
            ),
            whisper = WhisperConfig(
                apiKey = config.property("xrmail.whisper.apiKey").getString(),
                apiUrl = config.propertyOrNull("xrmail.whisper.apiUrl")?.getString()
                    ?: "https://api.openai.com/v1/audio/transcriptions",
                model = config.propertyOrNull("xrmail.whisper.model")?.getString() ?: "whisper-1",
            ),
            gemini = GeminiConfig(
                apiKey = config.property("xrmail.gemini.apiKey").getString(),
                model = config.propertyOrNull("xrmail.gemini.model")?.getString()
                    ?: "gemini-1.5-flash",
            ),
            database = DatabaseConfig(
                url = config.propertyOrNull("xrmail.database.url")?.getString()
                    ?: "jdbc:h2:file:./data/xrmail;DB_CLOSE_DELAY=-1",
                driver = config.propertyOrNull("xrmail.database.driver")?.getString()
                    ?: "org.h2.Driver",
            ),
        )
    }
}

data class ServerConfig(
    val host: String,
    val port: Int,
    val baseUrl: String,
)

data class JwtConfig(
    val secret: String,
    val issuer: String,
    val audience: String,
    val expirationMs: Long,
)

data class GoogleConfig(
    val clientId: String,
    val clientSecret: String,
    val redirectUri: String,
    val scopes: List<String>,
)

data class WhisperConfig(
    val apiKey: String,
    val apiUrl: String,
    val model: String,
)

data class GeminiConfig(
    val apiKey: String,
    val model: String,
)

data class DatabaseConfig(
    val url: String,
    val driver: String,
)
