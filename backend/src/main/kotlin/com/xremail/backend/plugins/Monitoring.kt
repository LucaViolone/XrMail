package com.xremail.backend.plugins

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import kotlinx.serialization.Serializable
import org.slf4j.event.Level

fun Application.configureMonitoring() {
    install(CallLogging) {
        level = Level.INFO
        filter { call -> call.request.path().startsWith("/") }
        format { call ->
            val status = call.response.status()
            val httpMethod = call.request.httpMethod.value
            val path = call.request.path()
            val duration = call.processingTimeMillis()
            "$httpMethod $path → $status (${duration}ms)"
        }
    }

    install(StatusPages) {
        // Handle unhandled exceptions with a structured error body
        exception<Throwable> { call, cause ->
            application.log.error("Unhandled exception on ${call.request.path()}", cause)
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse(
                    error = "internal_server_error",
                    message = cause.localizedMessage ?: "An unexpected error occurred",
                )
            )
        }

        // 401 Unauthorized
        status(HttpStatusCode.Unauthorized) { call, status ->
            call.respond(
                status,
                ErrorResponse(
                    error = "unauthorized",
                    message = "Authentication required. Provide a valid Bearer token.",
                )
            )
        }

        // 404 Not Found
        status(HttpStatusCode.NotFound) { call, status ->
            call.respond(
                status,
                ErrorResponse(
                    error = "not_found",
                    message = "The requested resource does not exist.",
                )
            )
        }
    }
}

@Serializable
data class ErrorResponse(
    val error: String,
    val message: String,
)
