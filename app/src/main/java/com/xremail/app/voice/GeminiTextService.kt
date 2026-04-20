package com.xremail.app.voice

import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.content
import com.xremail.app.util.XrLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Non-Live Gemini calls for batch text tasks (summarize, draft reply).
 *
 * Complementary to [GeminiLiveManager] which owns the always-on bidi voice
 * session. Use this for:
 *   * one-shot text-in, text-out prompts (AI summary card, suggested reply)
 *   * pre-warming reply drafts before the user even hits "Voice"
 *   * anything the user doesn't need low-latency spoken audio for
 *
 * Uses the same `firebase-ai` SDK + Google AI backend as GeminiLiveManager
 * so auth/config is shared. Safe to call on a cold Firebase: we return a
 * Result and surface init errors instead of crashing the host coroutine.
 */
class GeminiTextService(
    private val modelName: String = "gemini-2.0-flash",
) {

    private val model by lazy {
        Firebase.ai(backend = GenerativeBackend.googleAI())
            .generativeModel(modelName = modelName)
    }

    suspend fun summarizeEmail(
        subject: String,
        body: String,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val prompt = content {
                text(
                    """
                    Summarize this email in 3 short bullet points for a busy executive.
                    Skip greeting / sign-off / boilerplate.
                    Subject: $subject
                    Body:
                    $body
                    """.trimIndent(),
                )
            }
            val response = model.generateContent(prompt)
            response.text?.trim() ?: error("Empty model response")
        }.onFailure { XrLog.w(TAG, "summarizeEmail failed: ${it.message}") }
    }

    suspend fun draftReply(
        subject: String,
        body: String,
        tone: String? = null,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val toneLine = tone?.let { "Tone: $it." } ?: "Tone: professional and brief."
            val prompt = content {
                text(
                    """
                    $toneLine
                    Write a reply email body only (no subject line, no greeting, no sign-off).
                    Keep it under 5 sentences.
                    Original subject: $subject
                    Original message:
                    $body
                    """.trimIndent(),
                )
            }
            val response = model.generateContent(prompt)
            response.text?.trim() ?: error("Empty model response")
        }.onFailure { XrLog.w(TAG, "draftReply failed: ${it.message}") }
    }

    private companion object {
        const val TAG = "GeminiTextService"
    }
}
