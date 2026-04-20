package com.xremail.app.backend.service

import android.util.Base64
import org.json.JSONObject

/**
 * Minimal JWT payload decode (exp claim only) — no signature verification.
 * Used client-side to decide when to call [AuthRepository.refreshToken].
 */
object JwtPayload {

    fun expiresAtEpochSeconds(token: String): Long? {
        val parts = token.split('.')
        if (parts.size != 3) return null
        val payload = try {
            Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        } catch (_: IllegalArgumentException) {
            return null
        }
        val json = JSONObject(String(payload, Charsets.UTF_8))
        val exp = json.optLong("exp", 0L)
        return exp.takeIf { it > 0 }
    }
}
