package com.xremail.app.backend.service

import com.xremail.app.backend.api.XrMailApiService
import com.xremail.app.data.CalendarEvent
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import retrofit2.Response
import com.xremail.app.backend.api.ApiResponse

/**
 * Production [CalendarRepository] that fetches real Google Calendar data by
 * calling the XrMail Ktor backend's `/calendar/...` endpoints.
 *
 * The backend proxies requests to the Google Calendar API using the OAuth
 * token it obtained during the Gmail sign-in flow. The Android client never
 * holds a Google OAuth token directly.
 *
 * Requires the backend to request the `calendar.readonly` scope in addition
 * to the Gmail scopes.
 */
class GoogleCalendarRepository(
    private val api: XrMailApiService,
) {
    private val isoFormat = DateTimeFormatter.ISO_LOCAL_DATE_TIME

    /**
     * Fetches calendar events for the given date range from the backend.
     * Falls back to an empty list on any network error so the UI degrades gracefully.
     */
    suspend fun getEvents(start: LocalDateTime, end: LocalDateTime): List<CalendarEvent> =
        safeCalendarCall {
            val response = api.listCalendarEvents(
                start = start.format(isoFormat),
                end = end.format(isoFormat),
            )
            response.unwrapOrNull()?.events?.mapNotNull { dto ->
                runCatching {
                    CalendarEvent(
                        id = dto.id,
                        title = dto.title,
                        startTime = LocalDateTime.parse(dto.start, isoFormat),
                        endTime = LocalDateTime.parse(dto.end, isoFormat),
                        isBusy = dto.busy,
                    )
                }.getOrNull()
            }
        } ?: emptyList()

    /**
     * Finds the next available free slot of [durationMinutes] minutes.
     * Returns null if no free slot is found today, or on any error.
     */
    suspend fun findNextAvailableSlot(durationMinutes: Int): LocalDateTime? =
        safeCalendarCall {
            val response = api.findAvailableSlot(durationMinutes = durationMinutes)
            val slot = response.unwrapOrNull() ?: return@safeCalendarCall null
            if (!slot.found || slot.start == null) return@safeCalendarCall null
            runCatching { LocalDateTime.parse(slot.start, isoFormat) }.getOrNull()
        }

    /**
     * Returns all busy blocks for today — used by the Gemini voice assistant
     * to answer "am I free at 2pm?" style questions.
     */
    suspend fun getTodayBusyBlocks(): List<CalendarEvent> =
        safeCalendarCall {
            val response = api.getTodayBusy()
            response.unwrapOrNull()?.events?.mapNotNull { dto ->
                runCatching {
                    CalendarEvent(
                        id = dto.id,
                        title = dto.title,
                        startTime = LocalDateTime.parse(dto.start, isoFormat),
                        endTime = LocalDateTime.parse(dto.end, isoFormat),
                        isBusy = dto.busy,
                    )
                }.getOrNull()
            }
        } ?: emptyList()

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    /**
     * Executes [block] and swallows any exception, returning null on failure.
     * Keeps the ViewModel free of calendar-specific error handling.
     */
    private suspend fun <T> safeCalendarCall(block: suspend () -> T?): T? =
        runCatching { block() }.getOrNull()

    private fun <T> Response<ApiResponse<T>>.unwrapOrNull(): T? {
        val body = body() ?: return null
        if (!isSuccessful || !body.success) return null
        return body.data
    }
}
