package com.xremail.backend.services

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.xremail.backend.config.JwtConfig
import com.xremail.backend.db.OAuthTokensTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant
import java.util.Date

/**
 * Manages JWT generation for the Android client and OAuth token persistence.
 *
 * The JWT issued here is what the Android app stores and sends as the
 * Bearer token on every API request. It encodes the userId and email as claims,
 * so downstream services can look up the stored Google OAuth tokens without
 * another database round-trip on every request.
 */
class TokenService(private val config: JwtConfig) {

    private val algorithm = Algorithm.HMAC256(config.secret)

    // ---------------------------------------------------------------------------
    // JWT
    // ---------------------------------------------------------------------------

    /**
     * Issues a signed JWT for the authenticated user.
     * The Android XR app stores this and attaches it as:
     *   Authorization: Bearer <token>
     */
    fun generateJwt(userId: String, email: String): String {
        val now = System.currentTimeMillis()
        return JWT.create()
            .withIssuer(config.issuer)
            .withAudience(config.audience)
            .withSubject(userId)
            .withClaim("email", email)
            .withIssuedAt(Date(now))
            .withExpiresAt(Date(now + config.expirationMs))
            .sign(algorithm)
    }

    // ---------------------------------------------------------------------------
    // OAuth token persistence (H2 via Exposed)
    // ---------------------------------------------------------------------------

    /**
     * Persists or updates the Google OAuth tokens for a user.
     * Uses an "upsert" pattern: insert on first login, update on re-authentication.
     */
    fun saveTokens(
        userId: String,
        email: String,
        accessToken: String,
        refreshToken: String,
        expiresAt: Instant,
    ) = transaction {
        val existing = OAuthTokensTable.select { OAuthTokensTable.userId eq userId }
            .singleOrNull()

        if (existing == null) {
            OAuthTokensTable.insert {
                it[OAuthTokensTable.userId] = userId
                it[OAuthTokensTable.email] = email
                it[OAuthTokensTable.accessToken] = accessToken
                it[OAuthTokensTable.refreshToken] = refreshToken
                it[OAuthTokensTable.expiresAt] = expiresAt
                it[createdAt] = Instant.now()
                it[updatedAt] = Instant.now()
            }
        } else {
            OAuthTokensTable.update({ OAuthTokensTable.userId eq userId }) {
                it[OAuthTokensTable.accessToken] = accessToken
                it[OAuthTokensTable.refreshToken] = refreshToken
                it[OAuthTokensTable.expiresAt] = expiresAt
                it[updatedAt] = Instant.now()
            }
        }
    }

    /**
     * Updates the access token after a successful refresh.
     * Called by GmailService when the current access token has expired.
     */
    fun updateAccessToken(
        userId: String,
        newAccessToken: String,
        newExpiresAt: Instant,
    ) = transaction {
        OAuthTokensTable.update({ OAuthTokensTable.userId eq userId }) {
            it[accessToken] = newAccessToken
            it[expiresAt] = newExpiresAt
            it[updatedAt] = Instant.now()
        }
    }

    /**
     * Returns the stored token row for a user, or null if not found.
     */
    fun getTokenRow(userId: String): TokenRow? = transaction {
        OAuthTokensTable.select { OAuthTokensTable.userId eq userId }
            .singleOrNull()
            ?.let {
                TokenRow(
                    userId = it[OAuthTokensTable.userId],
                    email = it[OAuthTokensTable.email],
                    accessToken = it[OAuthTokensTable.accessToken],
                    refreshToken = it[OAuthTokensTable.refreshToken],
                    expiresAt = it[OAuthTokensTable.expiresAt],
                )
            }
    }

    /**
     * Deletes the stored tokens for a user (called on logout / revocation).
     */
    fun deleteTokens(userId: String) = transaction {
        OAuthTokensTable.deleteWhere { OAuthTokensTable.userId eq userId }
    }
}

data class TokenRow(
    val userId: String,
    val email: String,
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Instant,
)
