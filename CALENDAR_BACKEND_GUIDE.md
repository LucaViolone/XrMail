# Google Calendar Backend Integration Guide

This document describes exactly what the Ktor backend needs to implement so that the XrMail Android app can display real Google Calendar data. The Android client is already wired up and waiting — only the backend changes below are needed.

---

## 1. Add the Calendar OAuth Scope

In your Ktor server's Google OAuth configuration, add the Calendar read-only scope alongside the existing Gmail scopes.

**Find where you define your OAuth scopes** (likely in `Application.kt` or an auth plugin setup file) and add:

```kotlin
// Before (existing Gmail scopes)
val scopes = listOf(
    "https://www.googleapis.com/auth/gmail.readonly",
    "https://www.googleapis.com/auth/gmail.modify",
    "https://www.googleapis.com/auth/gmail.send",
)

// After — add the Calendar scope
val scopes = listOf(
    "https://www.googleapis.com/auth/gmail.readonly",
    "https://www.googleapis.com/auth/gmail.modify",
    "https://www.googleapis.com/auth/gmail.send",
    "https://www.googleapis.com/auth/calendar.readonly",  // <-- ADD THIS
)
```

> **Note:** Existing users who have already authorized will need to re-authorize (sign out and sign back in) to grant the new scope.

---

## 2. Add the Google Calendar API Dependency

Add the Google Calendar client library to your backend's `build.gradle.kts`:

```kotlin
// backend/build.gradle.kts
dependencies {
    // ... existing deps ...
    implementation("com.google.apis:google-api-services-calendar:v3-rev20231123-2.0.0")
    implementation("com.google.api-client:google-api-client:2.2.0")
}
```

---

## 3. Implement the Three Calendar Routes

Add a `CalendarRoutes.kt` (or equivalent) to your Ktor server. The Android app calls these three endpoints:

### Route 1: `GET /calendar/events`

Lists events in a date range.

**Query parameters:**
| Param | Type | Required | Description |
|---|---|---|---|
| `start` | ISO-8601 string | ✅ | e.g. `2025-04-19T00:00:00` |
| `end` | ISO-8601 string | ✅ | e.g. `2025-04-26T23:59:59` |
| `maxResults` | Int | ❌ | Default `50` |

**Response JSON:**
```json
{
  "success": true,
  "data": {
    "events": [
      {
        "id": "abc123",
        "title": "Team Standup",
        "start": "2025-04-19T10:00:00",
        "end": "2025-04-19T10:30:00",
        "busy": true,
        "location": "Zoom",
        "attendees": ["alice@example.com", "bob@example.com"]
      }
    ],
    "nextPageToken": null
  },
  "error": null
}
```

**Example Ktor implementation:**
```kotlin
get("/calendar/events") {
    val principal = call.principal<JWTPrincipal>() ?: return@get call.respond(HttpStatusCode.Unauthorized)
    val userId = principal.subject ?: return@get call.respond(HttpStatusCode.Unauthorized)

    val start = call.request.queryParameters["start"] ?: return@get call.respond(HttpStatusCode.BadRequest)
    val end   = call.request.queryParameters["end"]   ?: return@get call.respond(HttpStatusCode.BadRequest)
    val maxResults = call.request.queryParameters["maxResults"]?.toIntOrNull() ?: 50

    val credential = getGoogleCredentialForUser(userId) // your existing OAuth credential lookup
    val calendarService = Calendar.Builder(httpTransport, jsonFactory, credential)
        .setApplicationName("XrMail")
        .build()

    val startDateTime = DateTime(start)
    val endDateTime   = DateTime(end)

    val events = calendarService.events().list("primary")
        .setTimeMin(startDateTime)
        .setTimeMax(endDateTime)
        .setMaxResults(maxResults)
        .setOrderBy("startTime")
        .setSingleEvents(true)
        .execute()
        .items ?: emptyList()

    val dtos = events.map { event ->
        CalendarEventDto(
            id       = event.id,
            title    = event.summary ?: "(No title)",
            start    = event.start.dateTime?.toStringRfc3339()?.removeSuffix("Z") ?: start,
            end      = event.end.dateTime?.toStringRfc3339()?.removeSuffix("Z") ?: end,
            busy     = event.transparency != "transparent",
            location = event.location,
            attendees = event.attendees?.map { it.email } ?: emptyList(),
        )
    }

    call.respond(ApiResponse(success = true, data = CalendarEventsResponse(events = dtos)))
}
```

---

### Route 2: `GET /calendar/available`

Finds the next free time slot of a given duration, starting from now.

**Query parameters:**
| Param | Type | Required | Description |
|---|---|---|---|
| `duration` | Int (minutes) | ❌ | Default `30` |

**Response JSON (slot found):**
```json
{
  "success": true,
  "data": {
    "found": true,
    "start": "2025-04-19T14:00:00",
    "end": "2025-04-19T14:30:00"
  },
  "error": null
}
```

**Response JSON (fully booked):**
```json
{
  "success": true,
  "data": { "found": false, "start": null, "end": null },
  "error": null
}
```

**Example Ktor implementation:**
```kotlin
get("/calendar/available") {
    val principal = call.principal<JWTPrincipal>() ?: return@get call.respond(HttpStatusCode.Unauthorized)
    val userId = principal.subject ?: return@get call.respond(HttpStatusCode.Unauthorized)
    val durationMinutes = call.request.queryParameters["duration"]?.toIntOrNull() ?: 30

    val now = LocalDateTime.now()
    val endOfDay = now.withHour(18).withMinute(0).withSecond(0) // search until 6pm

    val credential = getGoogleCredentialForUser(userId)
    val calendarService = Calendar.Builder(httpTransport, jsonFactory, credential)
        .setApplicationName("XrMail")
        .build()

    val events = calendarService.events().list("primary")
        .setTimeMin(DateTime(now.toString()))
        .setTimeMax(DateTime(endOfDay.toString()))
        .setSingleEvents(true)
        .setOrderBy("startTime")
        .execute()
        .items ?: emptyList()

    // Walk the event list to find a gap
    var slotStart = now
    var found = false
    var slotEnd = slotStart.plusMinutes(durationMinutes.toLong())

    for (event in events) {
        val eventStart = LocalDateTime.parse(event.start.dateTime.toStringRfc3339().removeSuffix("Z"))
        if (slotStart.plusMinutes(durationMinutes.toLong()) <= eventStart) {
            found = true
            slotEnd = slotStart.plusMinutes(durationMinutes.toLong())
            break
        }
        val eventEnd = LocalDateTime.parse(event.end.dateTime.toStringRfc3339().removeSuffix("Z"))
        if (slotStart < eventEnd) slotStart = eventEnd
    }
    if (!found && slotStart.plusMinutes(durationMinutes.toLong()) <= endOfDay) {
        found = true
        slotEnd = slotStart.plusMinutes(durationMinutes.toLong())
    }

    call.respond(ApiResponse(
        success = true,
        data = AvailableSlotResponse(
            found = found,
            start = if (found) slotStart.toString() else null,
            end   = if (found) slotEnd.toString() else null,
        )
    ))
}
```

---

### Route 3: `GET /calendar/busy`

Returns all busy blocks for today. Used by the voice assistant to answer "am I free at 2pm?" style questions.

**No query parameters.**

**Response JSON:** Same shape as `/calendar/events` but filtered to today only:
```json
{
  "success": true,
  "data": {
    "events": [
      {
        "id": "xyz789",
        "title": "Busy",
        "start": "2025-04-19T13:00:00",
        "end": "2025-04-19T14:00:00",
        "busy": true,
        "location": null,
        "attendees": []
      }
    ],
    "nextPageToken": null
  },
  "error": null
}
```

**Example Ktor implementation:**
```kotlin
get("/calendar/busy") {
    val principal = call.principal<JWTPrincipal>() ?: return@get call.respond(HttpStatusCode.Unauthorized)
    val userId = principal.subject ?: return@get call.respond(HttpStatusCode.Unauthorized)

    val today = LocalDate.now()
    val startOfDay = today.atStartOfDay()
    val endOfDay = today.atTime(23, 59, 59)

    val credential = getGoogleCredentialForUser(userId)
    val calendarService = Calendar.Builder(httpTransport, jsonFactory, credential)
        .setApplicationName("XrMail")
        .build()

    val events = calendarService.events().list("primary")
        .setTimeMin(DateTime(startOfDay.toString()))
        .setTimeMax(DateTime(endOfDay.toString()))
        .setSingleEvents(true)
        .setOrderBy("startTime")
        .execute()
        .items ?: emptyList()

    val busyEvents = events
        .filter { it.transparency != "transparent" } // "free" events excluded
        .map { event ->
            CalendarEventDto(
                id       = event.id,
                title    = event.summary ?: "Busy",
                start    = event.start.dateTime.toStringRfc3339().removeSuffix("Z"),
                end      = event.end.dateTime.toStringRfc3339().removeSuffix("Z"),
                busy     = true,
            )
        }

    call.respond(ApiResponse(success = true, data = CalendarEventsResponse(events = busyEvents)))
}
```

---

## 4. Backend Response Data Classes

Add these Kotlin data classes to your backend's DTO/model layer:

```kotlin
@Serializable
data class CalendarEventDto(
    val id: String,
    val title: String,
    val start: String,         // ISO-8601 local datetime, e.g. "2025-04-19T10:00:00"
    val end: String,           // ISO-8601 local datetime
    val busy: Boolean = true,
    val location: String? = null,
    val attendees: List<String> = emptyList(),
)

@Serializable
data class CalendarEventsResponse(
    val events: List<CalendarEventDto>,
    val nextPageToken: String? = null,
)

@Serializable
data class AvailableSlotResponse(
    val found: Boolean,
    val start: String?,        // null if found == false
    val end: String?,          // null if found == false
)
```

---

## 5. Authentication — JWT Protection

All three routes must be **protected behind the existing JWT `authenticate` block**, just like the `/emails/*` routes. Example:

```kotlin
authenticate("jwt") {
    route("/calendar") {
        get("/events")    { /* ... */ }
        get("/available") { /* ... */ }
        get("/busy")      { /* ... */ }
    }
}
```

---

## 6. Testing the Integration

Once deployed, you can test the endpoints directly with curl:

```bash
# Replace <JWT> with a real token obtained from the sign-in flow
TOKEN="<JWT>"
BASE="http://localhost:8080"

# List events for the next 7 days
curl -H "Authorization: Bearer $TOKEN" \
  "$BASE/calendar/events?start=2025-04-19T00:00:00&end=2025-04-26T23:59:59"

# Find a 30-minute free slot
curl -H "Authorization: Bearer $TOKEN" \
  "$BASE/calendar/available?duration=30"

# Get today's busy blocks
curl -H "Authorization: Bearer $TOKEN" \
  "$BASE/calendar/busy"
```

Once those return correct JSON, flip `USE_REAL_BACKEND = true` in `MainActivity.kt` on the Android side and the XR calendar view will automatically populate with live data.
