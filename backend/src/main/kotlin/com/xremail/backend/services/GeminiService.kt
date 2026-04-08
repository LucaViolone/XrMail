package com.xremail.backend.services

import com.xremail.backend.config.GeminiConfig
import com.xremail.backend.models.Email
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable

/**
 * AI processing service powered by the Gemini API.
 *
 * Provides three capabilities used by the XR Interaction layer:
 *
 *  1. [summarize]       — Short 1-2 sentence summary of an email (shown in the
 *                         Ambient and Interaction layers without opening the email)
 *  2. [suggestReplies]  — 2-3 short, context-aware reply options the user can
 *                         select by voice or pinch gesture
 *  3. [composeFromVoice] — Takes a Whisper transcript of a voice command and
 *                         structures it into a ready-to-send email draft
 */
class GeminiService(private val config: GeminiConfig) {

    private val baseUrl = "https://generativelanguage.googleapis.com/v1beta/models"
    private val endpoint get() = "$baseUrl/${config.model}:generateContent?key=${config.apiKey}"

    private val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) { json() }
        engine {
            requestTimeout = 30_000
        }
    }

    /**
     * Generates a concise summary of an email for the XR Interaction layer.
     *
     * The summary is optimized for:
     *  - Ambient overlay display (≤ 150 chars)
     *  - Text-to-speech narration (natural sentence structure)
     *
     * @param email The full [Email] to summarize
     * @return A 1-2 sentence plain-text summary
     */
    suspend fun summarize(email: Email): String {
        val prompt = buildSummaryPrompt(email)
        return generateText(prompt).trim()
    }

    /**
     * Generates 2-3 short reply suggestions for an email.
     *
     * Each suggestion is designed to be:
     *  - Voice-readable (< 10 words)
     *  - Gesture-selectable in the XR Interaction layer
     *
     * @param email The email being replied to
     * @return List of 2-3 reply suggestion strings
     */
    suspend fun suggestReplies(email: Email): List<String> {
        val prompt = buildReplySuggestionsPrompt(email)
        val raw = generateText(prompt)

        // Parse numbered list: "1. ...\n2. ...\n3. ..."
        return raw.lines()
            .filter { it.matches(Regex("""^\d+\.\s+.+""")) }
            .map { it.replace(Regex("""^\d+\.\s+"""), "").trim() }
            .take(3)
            .ifEmpty { listOf(raw.trim()) } // fallback: return raw if parsing fails
    }

    /**
     * Converts a voice transcript into a structured email draft.
     *
     * The user dictates something like:
     *  "Reply to John's email and tell him I'll be late to the meeting tomorrow,
     *   maybe by 15 minutes, and ask him to push the start time."
     *
     * Gemini interprets this and returns a structured [EmailDraft] with subject,
     * body, and inferred recipients — ready to be reviewed and sent.
     *
     * @param transcript    The raw Whisper transcript of the voice command
     * @param replyToEmail  Optional email being replied to (provides context)
     * @return An [EmailDraft] ready for the Android app to display for confirmation
     */
    suspend fun composeFromVoice(
        transcript: String,
        replyToEmail: Email? = null,
    ): EmailDraft {
        val prompt = buildVoiceComposePrompt(transcript, replyToEmail)
        val raw = generateText(prompt)

        // Parse structured output — Gemini is instructed to return JSON
        return runCatching {
            kotlinx.serialization.json.Json {
                ignoreUnknownKeys = true
            }.decodeFromString(EmailDraft.serializer(), raw.extractJson())
        }.getOrElse {
            // Graceful fallback: treat entire response as body
            EmailDraft(
                to = replyToEmail?.from?.email?.let { listOf(it) } ?: emptyList(),
                subject = replyToEmail?.subject?.let { "Re: $it" } ?: "",
                body = raw.trim(),
            )
        }
    }

    // ---------------------------------------------------------------------------
    // Prompt builders
    // ---------------------------------------------------------------------------

    private fun buildSummaryPrompt(email: Email): String = """
        You are an AI assistant for an XR email client. Summarize the following email in
        1-2 sentences. The summary must be under 150 characters, use natural language
        suitable for text-to-speech, and focus on the key action or information.

        From: ${email.from.name} <${email.from.email}>
        Subject: ${email.subject}
        Date: ${java.util.Date(email.date)}

        Body:
        ${email.bodyText.take(2000)}

        Summary:
    """.trimIndent()

    private fun buildReplySuggestionsPrompt(email: Email): String = """
        You are an AI assistant for an XR email client. Generate exactly 3 short reply
        options for the email below. Each reply must be:
        - Under 10 words
        - A complete, natural-sounding response
        - Voice-friendly (no markdown, no punctuation except periods)

        Format your response as a numbered list:
        1. [reply option]
        2. [reply option]
        3. [reply option]

        From: ${email.from.name} <${email.from.email}>
        Subject: ${email.subject}
        Body:
        ${email.bodyText.take(1500)}

        Reply suggestions:
    """.trimIndent()

    private fun buildVoiceComposePrompt(transcript: String, replyToEmail: Email?): String {
        val context = if (replyToEmail != null) {
            """
            Context — the user is replying to this email:
            From: ${replyToEmail.from.name} <${replyToEmail.from.email}>
            Subject: ${replyToEmail.subject}
            Body: ${replyToEmail.bodyText.take(1000)}
            """.trimIndent()
        } else ""

        return """
            You are an AI email composer for an XR device. The user dictated the following
            voice command. Convert it into a structured email draft.

            $context

            Voice command transcript:
            "$transcript"

            Return ONLY a valid JSON object with this structure (no markdown, no explanation):
            {
              "to": ["email@example.com"],
              "cc": [],
              "subject": "Email subject here",
              "body": "Full email body here"
            }

            If the user did not specify recipients, infer from context or use an empty array.
        """.trimIndent()
    }

    // ---------------------------------------------------------------------------
    // HTTP helper — calls the Gemini generateContent endpoint
    // ---------------------------------------------------------------------------

    private suspend fun generateText(prompt: String): String {
        val requestBody = GeminiRequest(
            contents = listOf(
                GeminiContent(
                    parts = listOf(GeminiPart(text = prompt))
                )
            ),
            generationConfig = GeminiGenerationConfig(
                temperature = 0.3f,
                maxOutputTokens = 512,
            )
        )

        val response: GeminiResponse = httpClient.post(endpoint) {
            contentType(ContentType.Application.Json)
            setBody(requestBody)
        }.body()

        return response.candidates
            .firstOrNull()
            ?.content
            ?.parts
            ?.firstOrNull()
            ?.text
            ?: throw IllegalStateException("Gemini returned an empty response")
    }

    /** Extracts the first JSON object found in a string. */
    private fun String.extractJson(): String {
        val start = indexOf('{')
        val end = lastIndexOf('}')
        return if (start >= 0 && end > start) substring(start, end + 1) else this
    }
}

// ---------------------------------------------------------------------------
// DTOs
// ---------------------------------------------------------------------------

@Serializable
data class EmailDraft(
    val to: List<String>,
    val cc: List<String> = emptyList(),
    val subject: String,
    val body: String,
)

@Serializable
private data class GeminiRequest(
    val contents: List<GeminiContent>,
    val generationConfig: GeminiGenerationConfig? = null,
)

@Serializable
private data class GeminiContent(
    val role: String = "user",
    val parts: List<GeminiPart>,
)

@Serializable
private data class GeminiPart(val text: String)

@Serializable
private data class GeminiGenerationConfig(
    val temperature: Float = 0.3f,
    val maxOutputTokens: Int = 512,
)

@Serializable
private data class GeminiResponse(
    val candidates: List<GeminiCandidate> = emptyList(),
)

@Serializable
private data class GeminiCandidate(
    val content: GeminiContent,
)
