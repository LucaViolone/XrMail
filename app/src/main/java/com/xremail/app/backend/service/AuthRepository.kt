package com.xremail.app.backend.service

import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import com.xremail.app.backend.api.XrMailApiService

/**
 * Manages the Gmail OAuth 2.0 sign-in flow from the Android side.
 *
 * Full flow:
 *  1. [startSignIn]    — calls GET /auth/login, opens the returned URL
 *                        in a Chrome Custom Tab (stays in-app)
 *  2. Google auth      — user grants permission in the Custom Tab
 *  3. Deep link        — Google → Ktor backend → xrmail://auth/success?token=...
 *  4. [handleCallback] — called from MainActivity with the incoming deep-link URI,
 *                        extracts and saves the JWT via [TokenManager]
 *  5. [refreshToken]   — called proactively when the JWT nears expiry
 *  6. [signOut]        — calls POST /auth/logout, clears local storage
 */
class AuthRepository(
    private val api: XrMailApiService,
    private val tokenManager: TokenManager,
) {

    /** Whether the user currently has a stored JWT (does not validate expiry). */
    val isLoggedIn: Boolean get() = tokenManager.isLoggedIn()

    /** The authenticated Gmail address, or null if not logged in. */
    val userEmail: String? get() = tokenManager.getUserEmail()

    /**
     * Fetches the Google OAuth authorization URL from the backend and opens
     * it in a Chrome Custom Tab so the user can sign in without leaving XrMail.
     *
     * @return `null` if the browser was opened successfully; otherwise a short error message.
     */
    suspend fun startSignIn(context: Context, state: String = ""): String? {
        val response = runCatching { api.getLoginUrl(state = state) }.getOrNull()
            ?: return "Could not reach server. Start the XrMail backend and check the URL."

        if (!response.isSuccessful) {
            val err = response.errorBody()?.string().orEmpty().take(120)
            return "Server error ${response.code()}: ${err.ifBlank { response.message() }}"
        }

        val envelope = response.body()
            ?: return "Empty response from server."

        if (!envelope.success || envelope.data == null) {
            return envelope.error?.message ?: "Could not get sign-in URL."
        }

        val authUrl = envelope.data.authorizationUrl
        val customTab = CustomTabsIntent.Builder()
            .setShowTitle(true)
            .build()

        customTab.launchUrl(context, Uri.parse(authUrl))
        return null
    }

    /**
     * Processes the deep-link URI sent to the app after successful OAuth.
     *
     * The Ktor backend redirects to:
     *   xrmail://auth/success?token=<jwt>&email=<email>&state=<state>
     *
     * Saves the JWT and email, then returns the state string so the caller
     * can navigate to the correct screen.
     *
     * @param uri  The full deep-link URI from the incoming Intent
     * @return     The state string from the deep link, or null on failure
     */
    fun handleCallback(uri: Uri): String? {
        if (uri.scheme != "xrmail" || uri.host != "auth") return null
        if (uri.path != "/success") return null

        val token = uri.getQueryParameter("token") ?: return null
        val email = uri.getQueryParameter("email") ?: return null
        val state = uri.getQueryParameter("state") ?: ""

        tokenManager.saveToken(jwt = token, email = email)
        return state
    }

    /**
     * Requests a new JWT from the backend before the current one expires.
     * The backend validates the stored Google refresh token before issuing a new JWT.
     *
     * Returns true on success. If this fails, the user must sign in again.
     */
    suspend fun refreshToken(): Boolean {
        val response = runCatching { api.refreshToken() }.getOrNull() ?: return false
        val newToken = response.body()?.data?.token ?: return false

        val email = tokenManager.getUserEmail() ?: return false
        tokenManager.saveToken(jwt = newToken, email = email)
        return true
    }

    /**
     * Signs the user out: revokes Google OAuth tokens on the backend and
     * clears the local JWT from [TokenManager].
     */
    suspend fun signOut() {
        runCatching { api.logout() } // Best-effort — clear locally regardless
        tokenManager.clear()
    }
}
