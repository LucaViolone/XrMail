package com.xremail.app.data

import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

data class CalendarEvent(
    val id: String,
    val title: String,
    val startTime: LocalDateTime,
    val endTime: LocalDateTime,
    val isBusy: Boolean = true
)

class CalendarRepository {

    // Set to true to use real Google Calendar API when OAuth is configured
    private val useMockData = true

    /**
     * Fetches events for the specified date range.
     * Uses mock data for XR UI prototyping before OAuth is fully wired up.
     */
    suspend fun getEvents(start: LocalDateTime, end: LocalDateTime): List<CalendarEvent> {
        if (useMockData) {
            // Simulate network latency
            delay(800)
            return generateMockEvents(start)
        }

        // TODO: Real Google Calendar API integration
        // val credential = GoogleAccountCredential.usingOAuth2(...)
        // val service = Calendar.Builder(HTTP_TRANSPORT, JSON_FACTORY, credential).build()
        // val events = service.events().list("primary").setTimeMin(start).setTimeMax(end).execute()
        return emptyList()
    }

    /**
     * Finds the next available time slot of the given duration (in minutes) 
     * within the specified time window.
     */
    suspend fun findNextAvailableSlot(durationMinutes: Int, start: LocalDateTime, end: LocalDateTime): LocalDateTime? {
        val events = getEvents(start, end).sortedBy { it.startTime }
        
        var currentSlotStart = start
        for (event in events) {
            if (currentSlotStart.plusMinutes(durationMinutes.toLong()) <= event.startTime) {
                // Found a gap before the next event
                return currentSlotStart
            }
            // Move start time to the end of the current event
            if (currentSlotStart < event.endTime) {
                currentSlotStart = event.endTime
            }
        }
        
        // Check gap after the last event
        if (currentSlotStart.plusMinutes(durationMinutes.toLong()) <= end) {
            return currentSlotStart
        }
        
        return null
    }

    private fun generateMockEvents(baseDate: LocalDateTime): List<CalendarEvent> {
        val today = baseDate.withHour(0).withMinute(0).withSecond(0)
        return listOf(
            CalendarEvent(
                id = "m1",
                title = "Team Standup",
                startTime = today.plusHours(10),
                endTime = today.plusHours(10).plusMinutes(30)
            ),
            CalendarEvent(
                id = "m2",
                title = "1:1 with Alex",
                startTime = today.plusHours(13),
                endTime = today.plusHours(14)
            ),
            CalendarEvent(
                id = "m3",
                title = "Research Sync",
                startTime = today.plusHours(15),
                endTime = today.plusHours(16)
            ),
            CalendarEvent(
                id = "m4",
                title = "Design Review",
                startTime = today.plusDays(1).plusHours(11),
                endTime = today.plusDays(1).plusHours(12).plusMinutes(30)
            )
        )
    }
}
