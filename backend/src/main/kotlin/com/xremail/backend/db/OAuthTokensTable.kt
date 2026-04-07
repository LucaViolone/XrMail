package com.xremail.backend.db

import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.javatime.timestamp
import java.time.Instant

/**
 * Stores Google OAuth tokens per user.
 *
 * Each row represents one authenticated Gmail account.
 * The userId is a stable Google account identifier (the 'sub' field from
 * the Google ID token / People API).
 */
object OAuthTokensTable : IntIdTable("oauth_tokens") {
    val userId: Column<String> = varchar("user_id", 128).uniqueIndex()
    val email: Column<String> = varchar("email", 320)
    val accessToken: Column<String> = text("access_token")
    val refreshToken: Column<String> = text("refresh_token")
    val tokenType: Column<String> = varchar("token_type", 64).default("Bearer")
    val expiresAt: Column<Instant> = timestamp("expires_at")
    val createdAt: Column<Instant> = timestamp("created_at").default(Instant.now())
    val updatedAt: Column<Instant> = timestamp("updated_at").default(Instant.now())
}
