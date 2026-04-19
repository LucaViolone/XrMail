package com.xremail.app.backend.api

import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.*

/**
 * Retrofit interface for the XrMail Ktor backend.
 *
 * All endpoints mirror the routes defined in the backend module:
 *   GET  /health
 *   GET  /auth/login          → returns Google authorization URL
 *   POST /auth/refresh        → extends JWT session (requires Bearer token)
 *   POST /auth/logout         → revokes Google tokens (requires Bearer token)
 *   GET  /emails              → list inbox emails
 *   GET  /emails/{id}         → fetch single email with full body
 *   POST /emails/send         → send a new email
 *   PATCH /emails/{id}/labels → modify Gmail labels
 *   DELETE /emails/{id}       → move to trash
 *   POST /voice/transcribe    → Whisper transcription
 *   POST /ai/summarize        → Gemini email summary
 *   POST /ai/replies          → Gemini reply suggestions
 *   POST /ai/compose          → Gemini voice-to-email-draft
 *   GET  /calendar/events     → list events in a date range
 *   GET  /calendar/available  → find next free slot of given duration
 *   GET  /calendar/busy       → list today's busy blocks (for check_availability)
 *
 * Authentication is handled by [com.xremail.app.backend.service.AuthInterceptor],
 * which automatically injects the stored JWT into every request that needs it.
 */
interface XrMailApiService {

    // -------------------------------------------------------------------------
    // Health
    // -------------------------------------------------------------------------

    @GET("health")
    suspend fun health(): Response<Map<String, String>>

    // -------------------------------------------------------------------------
    // Auth — no Bearer token needed on login; token required for refresh/logout
    // -------------------------------------------------------------------------

    @GET("auth/login")
    suspend fun getLoginUrl(
        @Query("state") state: String = "",
    ): Response<ApiResponse<AuthLoginDto>>

    @POST("auth/refresh")
    suspend fun refreshToken(): Response<ApiResponse<TokenRefreshDto>>

    @POST("auth/logout")
    suspend fun logout(): Response<ApiResponse<Map<String, Boolean>>>

    // -------------------------------------------------------------------------
    // Emails
    // -------------------------------------------------------------------------

    @GET("emails")
    suspend fun listEmails(
        @Query("q") query: String = "",
        @Query("maxResults") maxResults: Int = 20,
        @Query("pageToken") pageToken: String? = null,
        @Query("labels") labels: String = "INBOX",
    ): Response<ApiResponse<EmailListDto>>

    @GET("emails/{id}")
    suspend fun getEmail(
        @Path("id") messageId: String,
    ): Response<ApiResponse<EmailDto>>

    @POST("emails/send")
    suspend fun sendEmail(
        @Body request: SendEmailRequest,
    ): Response<ApiResponse<Map<String, Boolean>>>

    @PATCH("emails/{id}/labels")
    suspend fun modifyLabels(
        @Path("id") messageId: String,
        @Body request: ModifyLabelsRequest,
    ): Response<ApiResponse<Map<String, Boolean>>>

    @DELETE("emails/{id}")
    suspend fun trashEmail(
        @Path("id") messageId: String,
    ): Response<ApiResponse<Map<String, Boolean>>>

    // -------------------------------------------------------------------------
    // Voice transcription
    // -------------------------------------------------------------------------

    /**
     * POST /voice/transcribe
     *
     * Send raw audio bytes as the request body.
     * Set Content-Type header to match the audio format (e.g. audio/wav).
     * Optional headers: X-Audio-Language, X-Whisper-Prompt, X-Audio-Filename
     */
    @POST("voice/transcribe")
    suspend fun transcribeAudio(
        @Body audioBody: RequestBody,
        @Header("X-Audio-Language") language: String? = null,
        @Header("X-Whisper-Prompt") prompt: String? = null,
        @Header("X-Audio-Filename") filename: String = "audio.wav",
    ): Response<ApiResponse<TranscriptionDto>>

    // -------------------------------------------------------------------------
    // AI — Gemini powered
    // -------------------------------------------------------------------------

    @POST("ai/summarize")
    suspend fun summarizeEmail(
        @Body request: SummarizeRequest,
    ): Response<ApiResponse<SummarizeDto>>

    @POST("ai/replies")
    suspend fun getReplySuggestions(
        @Body request: ReplySuggestionsRequest,
    ): Response<ApiResponse<ReplySuggestionsDto>>

    @POST("ai/compose")
    suspend fun composeFromVoice(
        @Body request: ComposeFromVoiceRequest,
    ): Response<ApiResponse<EmailDraftDto>>

    // -------------------------------------------------------------------------
    // Calendar — proxied Google Calendar API (requires calendar.readonly scope)
    // -------------------------------------------------------------------------

    /**
     * GET /calendar/events?start=...&end=...
     *
     * Returns events between [start] and [end] (both ISO-8601 datetime strings).
     * The backend calls the Google Calendar API using the stored OAuth token.
     */
    @GET("calendar/events")
    suspend fun listCalendarEvents(
        @Query("start") start: String,
        @Query("end")   end: String,
        @Query("maxResults") maxResults: Int = 50,
    ): Response<ApiResponse<CalendarEventsDto>>

    /**
     * GET /calendar/available?duration=30
     *
     * Finds the next free slot of [durationMinutes] minutes starting from now.
     * Returns a [AvailableSlotDto] with [found]=false if the day is fully booked.
     */
    @GET("calendar/available")
    suspend fun findAvailableSlot(
        @Query("duration") durationMinutes: Int = 30,
    ): Response<ApiResponse<AvailableSlotDto>>

    /**
     * GET /calendar/busy
     *
     * Returns all busy blocks for today so the voice assistant can answer
     * availability questions without fetching the full event list.
     */
    @GET("calendar/busy")
    suspend fun getTodayBusy(): Response<ApiResponse<CalendarEventsDto>>
}
