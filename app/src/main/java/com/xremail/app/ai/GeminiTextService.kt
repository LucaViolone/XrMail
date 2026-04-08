package com.xremail.app.ai

import com.google.firebase.FirebaseApp
import com.google.firebase.ai.Firebase
import com.google.firebase.ai.GenerativeBackend
import com.google.firebase.ai.type.content
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Non-Live Gemini calls for summarization and lightweight generation (batch / text).
 */
class GeminiTextService {

    private val model by lazy {
        Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
            modelName = "gemini-2.0-flash",
        )
    }

    suspend fun summarizeEmail(subject: String, body: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            if (FirebaseApp.getApps().isEmpty()) {
                throw IllegalStateException("Firebase not initialized")
            }
            val prompt = content {
                text(
                    """
                    Summarize this email in 3 short bullet points for a busy executive.
                    Subject: $subject
                    Body:
                    $body
                    """.trimIndent(),
                )
            }
            val response = model.generateContent(prompt)
            response.text ?: throw IllegalStateException("Empty model response")
        }
    }

    suspend fun draftReply(
        subject: String,
        body: String,
        tone: String?,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            if (FirebaseApp.getApps().isEmpty()) {
                throw IllegalStateException("Firebase not initialized")
            }
            val toneLine = tone?.let { "Tone: $it." } ?: "Tone: professional and brief."
            val prompt = content {
                text(
                    """
                    $toneLine
                    Write a reply email body only (no subject line). Be concise.
                    Original subject: $subject
                    Original message:
                    $body
                    """.trimIndent(),
                )
            }
            val response = model.generateContent(prompt)
            response.text ?: throw IllegalStateException("Empty model response")
        }
    }
}
