package com.xremail.app.voice

import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.FunctionCallPart
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.TextPart
import com.google.firebase.ai.type.content
import com.xremail.app.util.XrLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive

/**
 * Non-Live Gemini calls for batch text tasks (summarize, draft reply,
 * push-to-talk voice command handling).
 *
 * Complementary to [GeminiLiveManager] which owns the always-on bidi voice
 * session. Use this for:
 *   * one-shot text-in, text-out prompts (AI summary card, suggested reply)
 *   * pre-warming reply drafts before the user even hits "Voice"
 *   * [reply] — one-shot voice-command handling for [PushToTalkSession]
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

    /**
     * Tool-calling model used by [reply]. Shares the same model name as
     * [model] but carries the voice system prompt and the full
     * [EmailCommandTool.tool] function list so Gemini can drive the app
     * just like it does over the Live API — only over one-shot HTTP.
     */
    private val commandModel by lazy {
        Firebase.ai(backend = GenerativeBackend.googleAI())
            .generativeModel(
                modelName = modelName,
                systemInstruction = content { text(VOICE_COMMAND_SYSTEM_PROMPT) },
                tools = listOf(EmailCommandTool.tool),
            )
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

    /**
     * Handle a single voice utterance from [PushToTalkSession].
     *
     * The [inboxSnapshot] is inlined in the prompt so the model can answer
     * content questions ("what did Maria say?") on the first turn without
     * an extra tool round-trip — this is the biggest latency win over the
     * Live API's `get_inbox_state` tool dance.
     *
     * Gemini may return spoken text (for ANSWER mode), function calls (for
     * ACTION / COMPOSE modes), or both. Callers must dispatch every command
     * AND speak the text — the text alone is often just a confirmation that
     * accompanies a command, not a substitute for it.
     */
    suspend fun reply(
        utterance: String,
        inboxSnapshot: String,
    ): Result<ToolReply> = withContext(Dispatchers.IO) {
        runCatching {
            val snapshot = inboxSnapshot.ifBlank { "(inbox is empty)" }
            val prompt = content {
                text(
                    """
                    [inbox state]
                    $snapshot

                    [user said]
                    $utterance
                    """.trimIndent(),
                )
            }
            val response = commandModel.generateContent(prompt)
            val parts = response.candidates.firstOrNull()?.content?.parts.orEmpty()
            val textBuilder = StringBuilder()
            val commands = mutableListOf<EmailCommandTool.Command>()
            for (part in parts) {
                when (part) {
                    is TextPart -> {
                        if (part.text.isNotBlank()) {
                            if (textBuilder.isNotEmpty()) textBuilder.append(' ')
                            textBuilder.append(part.text.trim())
                        }
                    }
                    is FunctionCallPart -> {
                        val args = part.args.mapValues { (_, v) ->
                            (v as? JsonPrimitive)?.content
                        }
                        EmailCommandTool.parse(part.name, args)?.let { commands += it }
                    }
                    else -> {
                        // Inline data, executable code, etc. — not used here.
                    }
                }
            }
            ToolReply(spokenText = textBuilder.toString().trim(), commands = commands)
        }.onFailure { XrLog.w(TAG, "reply failed: ${it.message}", it) }
    }

    /**
     * Result of a [reply] call.
     *
     * [spokenText] is what the app should speak via [TTSManager]. May be
     * empty when Gemini answered purely with a function call (e.g. a
     * silent `archive_email`).
     *
     * [commands] are parsed UI actions to dispatch in order via
     * [VoiceCommandDispatcher.dispatch].
     */
    data class ToolReply(
        val spokenText: String,
        val commands: List<EmailCommandTool.Command>,
    )

    private companion object {
        const val TAG = "GeminiTextService"

        // Trimmed from the Live variant in GeminiLiveManager.SYSTEM_PROMPT
        // for one-shot HTTP cadence:
        //   - The inbox snapshot is pre-inlined in the user turn, so we drop
        //     the "always call get_inbox_state first" rule.
        //   - No spoken-audio pacing tips (Live talks, this one types).
        //   - Send-safety still requires the two-call arm+send pattern — the
        //     dispatcher will reject a send_draft without a prior arm.
        private val VOICE_COMMAND_SYSTEM_PROMPT = """
            You are XrMail's hands-free voice agent. The user just spoke a single
            utterance — respond in ONE turn. The current inbox snapshot is
            included in the user message under "[inbox state]"; use it directly,
            do NOT call get_inbox_state.

            Three modes, pick ONE per turn:

            ANSWER MODE (user asks about email content — "what did Alex say",
            "anything urgent", "who emailed me", "summarize this"): answer in
            1–3 short sentences drawn from the inbox snapshot. Quote senders by
            name. If the answer isn't in the snapshot, say so in one sentence.
            Do not invent senders or content. No function call needed.

            ACTION MODE (intent maps to a UI action — archive, snooze, next,
            refresh, expand, collapse, open an email): call the matching tool
            immediately. Follow with ONE short spoken confirmation, ≤6 words
            ("Archived." "Snoozed till tomorrow." "Opening inbox.").

            COMPOSE MODE (user asks to reply, write back, draft, respond, send):
              1. Write the FULL reply yourself in 1–4 short sentences.
              2. Call draft_reply(body=COMPLETE TEXT). Then say ONE short
                 confirmation like "Drafted, want me to send it?" — do NOT
                 repeat the draft body out loud, the UI reads it back.
              3. Revisions: call revise_draft(body=COMPLETE rewritten body).
              4. To send after explicit user confirmation: FIRST call
                 arm_send_for_voice, then IMMEDIATELY send_draft in the same
                 turn. send_draft without a prior arm_send_for_voice is
                 rejected as a hallucination guard.

            Always default to the selected email when one exists. Reference
            emails by sender or subject, never by id. If the request is
            genuinely ambiguous, ask ONE tight clarifying question instead of
            guessing — but always say something out loud.
        """.trimIndent()
    }
}
