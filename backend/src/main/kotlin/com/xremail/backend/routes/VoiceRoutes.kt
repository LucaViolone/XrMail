package com.xremail.backend.routes

import com.xremail.backend.models.ok
import com.xremail.backend.models.err
import com.xremail.backend.services.TranscriptionResult
import com.xremail.backend.services.WhisperService
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

/**
 * Voice transcription route.
 *
 * The Android XR app records audio using the headset's 6-mic beamforming array
 * and posts it here as raw bytes. Whisper returns a clean transcript that the
 * app can pass to the AI routes or display directly.
 *
 * Base path: /voice
 */
fun Route.voiceRoutes(whisperService: WhisperService) {
    route("/voice") {

        /**
         * POST /voice/transcribe
         *
         * Transcribes an audio recording to text using OpenAI Whisper.
         *
         * Request:
         *   Content-Type: audio/wav  (or audio/mpeg, audio/mp4, audio/ogg)
         *   Body: raw audio bytes
         *
         * Optional headers:
         *   X-Audio-Language  — BCP-47 language code (e.g. "en", "id")
         *                       Omit for auto-detection
         *   X-Whisper-Prompt  — Hint text (e.g. names or jargon) to improve accuracy
         *   X-Audio-Filename  — Original filename (used for MIME type detection)
         *
         * Response: TranscriptionResponse
         */
        post("/transcribe") {
            val language = call.request.headers["X-Audio-Language"]
            val prompt = call.request.headers["X-Whisper-Prompt"]
            val filename = call.request.headers["X-Audio-Filename"] ?: "audio.wav"
            val contentType = call.request.contentType().toString()

            if (!contentType.startsWith("audio/")) {
                call.respond(
                    HttpStatusCode.UnsupportedMediaType,
                    err<Unit>(
                        "invalid_content_type",
                        "Content-Type must be audio/* (e.g. audio/wav). Got: $contentType"
                    )
                )
                return@post
            }

            val audioBytes = call.receive<ByteArray>()

            if (audioBytes.isEmpty()) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    err<Unit>("empty_audio", "Request body must contain audio data")
                )
                return@post
            }

            val result = runCatching {
                whisperService.transcribe(
                    audioBytes = audioBytes,
                    filename = filename,
                    language = language,
                    prompt = prompt,
                )
            }

            if (result.isFailure) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    err<Unit>(
                        "transcription_failed",
                        result.exceptionOrNull()?.message ?: "Whisper transcription failed"
                    )
                )
                return@post
            }

            val transcription = result.getOrThrow()
            call.respond(
                HttpStatusCode.OK,
                ok(
                    TranscriptionResponse(
                        text = transcription.text,
                        language = transcription.language,
                        durationSeconds = transcription.durationSeconds,
                    )
                )
            )
        }
    }
}

@Serializable
data class TranscriptionResponse(
    val text: String,
    val language: String,
    val durationSeconds: Double,
)
