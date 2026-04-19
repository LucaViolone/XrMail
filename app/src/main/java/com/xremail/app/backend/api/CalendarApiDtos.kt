package com.xremail.app.backend.api

import com.google.gson.annotations.SerializedName

// ---------------------------------------------------------------------------
// Calendar event DTOs — mirrors the shape returned by the Ktor backend's
// /calendar/events endpoint, which proxies the Google Calendar API.
// ---------------------------------------------------------------------------

data class CalendarEventDto(
    @SerializedName("id")         val id: String,
    @SerializedName("title")      val title: String,
    @SerializedName("start")      val start: String,   // ISO-8601: "2025-04-19T10:00:00"
    @SerializedName("end")        val end: String,     // ISO-8601: "2025-04-19T10:30:00"
    @SerializedName("busy")       val busy: Boolean = true,
    @SerializedName("location")   val location: String? = null,
    @SerializedName("attendees")  val attendees: List<String> = emptyList(),
)

data class CalendarEventsDto(
    @SerializedName("events") val events: List<CalendarEventDto>,
    @SerializedName("nextPageToken") val nextPageToken: String? = null,
)

data class AvailableSlotDto(
    @SerializedName("start") val start: String?,   // null if no slot found
    @SerializedName("end")   val end: String?,
    @SerializedName("found") val found: Boolean,
)
