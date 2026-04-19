package com.xremail.app.ai

import com.xremail.app.data.Email
import com.xremail.app.data.EmailAction
import com.xremail.app.data.EmailCategory
import com.xremail.app.data.Priority

/**
 * Batch email classification via Firebase AI Logic.
 * Classifies ~20 emails per API call for 95% cost reduction vs per-email.
 *
 * Uses com.google.firebase:firebase-ai (NOT the old generativeai SDK).
 * Supports on-device Gemini Nano fallback for zero-latency, zero-cost,
 * offline classification.
 *
 * Production:
 * ```
 * val model = Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
 *     modelName = "gemini-2.5-flash",
 *     onDeviceConfig = onDeviceConfig { mode = InferenceMode.PREFER_ON_DEVICE }
 * )
 * val response = model.generateContent(batchPrompt)
 * // response.inferenceSource == "ON_DEVICE" or "CLOUD"
 * ```
 */
class EmailClassifier {

    data class ClassificationResult(
        val emailId: String,
        val priority: Priority,
        val category: EmailCategory,
        val action: EmailAction,
        val summary: String,
        val urgencyScore: Float,
        val suggestedReply: String?,
        val replyConfidence: Float,
        val hasSchedulingIntent: Boolean,
        val inferenceSource: InferenceSource,
    )

    enum class InferenceSource { ON_DEVICE, CLOUD, MOCK }

    /**
     * Classify a batch of emails in a single API call.
     * Phase 1: returns mock classifications based on existing mock data.
     */
    fun batchClassify(emails: List<Email>): List<ClassificationResult> {
        return emails.map { email ->
            ClassificationResult(
                emailId = email.id,
                priority = email.priority,
                category = email.category,
                action = email.action,
                summary = email.aiSummary,
                urgencyScore = email.urgencyScore,
                suggestedReply = email.suggestedReply,
                replyConfidence = email.replyConfidence,
                hasSchedulingIntent = email.hasSchedulingIntent,
                inferenceSource = InferenceSource.MOCK,
            )
        }
    }

    /**
     * Build the batch prompt for Gemini. Sends all emails in one request.
     */
    fun buildBatchPrompt(emails: List<Email>): String {
        return buildString {
            appendLine("Classify these ${emails.size} emails. Return a JSON array.")
            appendLine("Each element: {priority, category, action, summary, urgencyScore, suggestedReply, replyConfidence, hasSchedulingIntent}")
            appendLine()
            emails.forEachIndexed { i, email ->
                appendLine("--- EMAIL $i ---")
                appendLine("From: ${email.sender} <${email.senderEmail}>")
                appendLine("Subject: ${email.subject}")
                appendLine("Body: ${email.body.take(500)}")
                appendLine()
            }
        }
    }
}
