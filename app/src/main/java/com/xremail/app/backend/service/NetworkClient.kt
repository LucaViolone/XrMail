package com.xremail.app.backend.service

import com.xremail.app.backend.api.XrMailApiService
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Builds and holds the singleton [XrMailApiService] Retrofit instance.
 *
 * Two interceptors are attached:
 *  - [AuthInterceptor]: injects the stored JWT as a Bearer token on every request
 *  - [HttpLoggingInterceptor]: logs request/response bodies in DEBUG builds
 *
 * Call [NetworkClient.create] once at app startup (e.g. in your Application class
 * or a dependency injection module) and reuse the resulting [XrMailApiService].
 */
object NetworkClient {

    /**
     * Creates a [XrMailApiService] pointed at [baseUrl].
     *
     * @param baseUrl     The XrMail backend URL, e.g. "http://10.0.2.2:8080/" (emulator)
     *                    or your production URL. Must end with "/".
     * @param tokenManager The [TokenManager] used to read the stored JWT.
     * @param debug       When true, full request/response bodies are logged to Logcat.
     */
    fun create(
        baseUrl: String,
        tokenManager: TokenManager,
        debug: Boolean = false,
    ): XrMailApiService {
        val logging = HttpLoggingInterceptor().apply {
            level = if (debug) {
                HttpLoggingInterceptor.Level.BODY
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }

        val okhttp = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(tokenManager))
            .addInterceptor(logging)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)   // Whisper can be slow on long audio
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()

        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okhttp)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(XrMailApiService::class.java)
    }
}

/**
 * OkHttp interceptor that automatically attaches the stored JWT as a
 * Bearer token to every outgoing request.
 *
 * Requests to /auth/login do not need a token (no-op — the header is simply
 * absent, which the backend permits for public routes).
 */
class AuthInterceptor(private val tokenManager: TokenManager) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()

        val token = tokenManager.getToken()
            ?: return chain.proceed(original) // Not logged in — send unauthenticated

        val authenticated = original.newBuilder()
            .header("Authorization", "Bearer $token")
            .build()

        return chain.proceed(authenticated)
    }
}
