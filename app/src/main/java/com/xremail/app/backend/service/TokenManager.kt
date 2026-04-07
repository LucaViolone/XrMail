package com.xremail.app.backend.service

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Stores and retrieves the user's JWT and authenticated email address
 * in Android's [EncryptedSharedPreferences] (AES-256-GCM backed by the
 * Android Keystore).
 *
 * The JWT is obtained at the end of the Gmail OAuth flow (via the
 * xrmail://auth/success deep link) and is attached to every API request
 * by [AuthInterceptor].
 *
 * Usage:
 * ```
 * val manager = TokenManager(context)
 * manager.saveToken(jwt, "user@gmail.com")
 * manager.getToken()      // → "eyJ..."
 * manager.getUserEmail()  // → "user@gmail.com"
 * manager.isLoggedIn()    // → true
 * manager.clear()         // on logout
 * ```
 */
class TokenManager(context: Context) {

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        PREFS_FILENAME,
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    /** Persists the JWT and the authenticated email address. */
    fun saveToken(jwt: String, email: String) {
        prefs.edit()
            .putString(KEY_JWT, jwt)
            .putString(KEY_EMAIL, email)
            .apply()
    }

    /**
     * Returns the stored JWT, or null if not logged in.
     * [AuthInterceptor] calls this on every request.
     */
    fun getToken(): String? = prefs.getString(KEY_JWT, null)

    /** Returns the authenticated Gmail address, or null if not logged in. */
    fun getUserEmail(): String? = prefs.getString(KEY_EMAIL, null)

    /** Returns true if a JWT is stored (does not validate expiry). */
    fun isLoggedIn(): Boolean = getToken() != null

    /** Removes all stored credentials. Called on logout. */
    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val PREFS_FILENAME = "xrmail_secure_prefs"
        private const val KEY_JWT = "jwt_token"
        private const val KEY_EMAIL = "user_email"
    }
}
