package com.xremail.backend.models

import kotlinx.serialization.Serializable

/**
 * Standard API envelope used by all XrMail backend endpoints.
 *
 * Success:  ApiResponse(success=true, data=<T>)
 * Failure:  ApiResponse(success=false, error=ApiError(...))
 */
@Serializable
data class ApiResponse<T>(
    val success: Boolean,
    val data: T? = null,
    val error: ApiError? = null,
)

@Serializable
data class ApiError(
    val code: String,
    val message: String,
)

// Convenience constructors
fun <T> ok(data: T): ApiResponse<T> = ApiResponse(success = true, data = data)
fun <T> err(code: String, message: String): ApiResponse<T> =
    ApiResponse(success = false, error = ApiError(code, message))
