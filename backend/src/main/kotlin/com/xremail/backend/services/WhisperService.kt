package com.xremail.backend.services

import com.xremail.backend.config.WhisperConfig
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable

/**
 * Handles voice transcription via the OpenAI Whisper API.
 *
 * The XR headset records audio using its 6-mic beamforming array.
 * The Android app sends the raw PCM/WAV bytes to POST /voice/transcribe.
 * This service forwards the audio to Whisper and returns the transcript.
 *
 * Language is auto-detected by default (Whisper is multilingual).
 * Pass an explicit language code (e.g. "en", "id") to improve accuracy.
 */
class WhisperService(private val config: WhisperConfig) {

    private val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) { json() }
        engine {
            requestTimeout = 60_000 // Whisper can take a while on large audio
        }
    }

    /**
     * Transcribes an audio file using OpenAI Whisper.
     *
     * @param audioBytes  Raw audio bytes (WAV, MP3, M4A, or OGG supported)
     * @param filename    Filename hint for MIME type detection (e.g. "audio.wav")
     * @param language    BCP-47 language code, or null for auto-detection
     * @param prompt      Optional text to guide Whisper (e.g. email address names
     *                    the user is likely to dictate — improves accuracy)
     * @return [TranscriptionResult] with the full transcript and detected language
     */
    suspend fun transcribe(
        audioBytes: ByteArray,
        filename: String = "audio.wav",
        language: String? = null,
        prompt: String? = null,
    ): TranscriptionResult {
        val response: WhisperResponse = httpClient.post(config.apiUrl) {
            header(HttpHeaders.Authorization, "Bearer ${config.apiKey}")
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append("model", config.model)
                        append("response_format", "verbose_json") // includes language + segments
                        if (language != null) append("language", language)
                        if (prompt != null) append("prompt", prompt)
                        append(
                            "file",
                            audioBytes,
                            Headers.build {
                                append(HttpHeaders.ContentType, detectMimeType(filename))
                                append(HttpHeaders.ContentDisposition, "filename=\"$filename\"")
                            }
                        )
                    }
                )
            )
        }.body()

        return TranscriptionResult(
            text = response.text.trim(),
            language = response.language ?: "unknown",
            durationSeconds = response.duration ?: 0.0,
        )
    }

    private fun detectMimeType(filename: String): String = when {
        filename.endsWith(".mp3") -> "audio/mpeg"
        filename.endsWith(".m4a") -> "audio/mp4"
        filename.endsWith(".ogg") -> "audio/ogg"
        filename.endsWith(".flac") -> "audio/flac"
        filename.endsWith(".webm") -> "audio/webm"
        else -> "audio/wav"
    }
}

// ---------------------------------------------------------------------------
// DTOs
// ---------------------------------------------------------------------------

data class TranscriptionResult(
    val text: String,
    val language: String,
    val durationSeconds: Double,
)

@Serializable
private data class WhisperResponse(
    val text: String,
    val language: String? = null,
    val duration: Double? = null,
)
