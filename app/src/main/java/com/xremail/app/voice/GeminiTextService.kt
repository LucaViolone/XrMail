package com.xremail.app.voice

import com.xremail.app.BuildConfig
import com.xremail.app.util.XrLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Non-Live Gemini calls via the Gemini Developer API with a raw API key.
 *
 * Why not Firebase AI Logic:
 *   - `GenerativeBackend.googleAI()` routed through a Firebase-managed
 *     free tier pinned to *0* req/min on the dev project (caught 2026-04-19
 *     as QuotaExceededException: "limit: 0").
 *   - `GenerativeBackend.vertexAI()` returned ServiceDisabledException
 *     because Vertex AI isn't enabled on that project and requires billing.
 *   - A paid-tier API key (in `local.properties` as `GEMINI_API_KEY`,
 *     exposed via `BuildConfig.GEMINI_API_KEY`) has its own quota and
 *     just works without any Firebase / GCP console dance.
 *
 * So this class now hits `generativelanguage.googleapis.com` directly
 * with OkHttp (already a dep) + kotlinx.serialization.json (already a
 * dep). No new dependencies, no Firebase at all on this path. Same
 * three public methods — [summarizeEmail], [draftReply], [reply] — and
 * same [ToolReply] shape, so callers are unchanged.
 */
class GeminiTextService(
    private val modelName: String = "gemini-2.0-flash",
    private val apiKey: String = BuildConfig.GEMINI_API_KEY,
) {

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private val json = Json { ignoreUnknownKeys = true }

    private val endpoint: String
        get() = "$BASE_URL/models/$modelName:generateContent?key=$apiKey"

    // ---------------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------------

    suspend fun summarizeEmail(
        subject: String,
        body: String,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val text = """
                Summarize this email in 3 short bullet points for a busy executive.
                Skip greeting / sign-off / boilerplate.
                Subject: $subject
                Body:
                $body
            """.trimIndent()
            extractText(callGemini(systemInstruction = null, userText = text, tools = null))
        }.onFailure { XrLog.w(TAG, "summarizeEmail failed: ${it.message}") }
    }

    suspend fun draftReply(
        subject: String,
        body: String,
        tone: String? = null,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val toneLine = tone?.let { "Tone: $it." } ?: "Tone: professional and brief."
            val text = """
                $toneLine
                Write a reply email body only (no subject line, no greeting, no sign-off).
                Keep it under 5 sentences.
                Original subject: $subject
                Original message:
                $body
            """.trimIndent()
            extractText(callGemini(systemInstruction = null, userText = text, tools = null))
        }.onFailure { XrLog.w(TAG, "draftReply failed: ${it.message}") }
    }

    /**
     * Push-to-talk voice handler. See PushToTalkSession. One-shot model
     * call with tools + inlined inbox snapshot.
     */
    suspend fun reply(
        utterance: String,
        inboxSnapshot: String,
    ): Result<ToolReply> = withContext(Dispatchers.IO) {
        runCatching {
            val snapshot = inboxSnapshot.ifBlank { "(inbox is empty)" }
            val userText = """
                [inbox state]
                $snapshot

                [user said]
                $utterance
            """.trimIndent()
            val response = callGemini(
                systemInstruction = VOICE_COMMAND_SYSTEM_PROMPT,
                userText = userText,
                tools = TOOLS_JSON,
            )
            parseToolReply(response)
        }.onFailure { XrLog.w(TAG, "reply failed: ${it.message}", it) }
    }

    data class ToolReply(
        val spokenText: String,
        val commands: List<EmailCommandTool.Command>,
    )

    // ---------------------------------------------------------------------------
    // HTTP
    // ---------------------------------------------------------------------------

    /**
     * One POST with a small retry on 429 (rate-limit). Tier-1 Postpay on
     * the AI-video key should have 2000 RPM, but in practice the shared
     * project hits transient bursts — a 1.5s pause and a single retry is
     * enough to unblock >90% of the turns without a user-visible error.
     */
    private suspend fun callGemini(
        systemInstruction: String?,
        userText: String,
        tools: JsonElement?,
    ): JsonObject {
        var attempt = 0
        while (true) {
            try {
                return callGeminiOnce(systemInstruction, userText, tools)
            } catch (t: IllegalStateException) {
                val msg = t.message.orEmpty()
                val is429 = msg.startsWith("Gemini 429")
                if (is429 && attempt < 1) {
                    XrLog.w(TAG, "429 — retrying once after 1500ms")
                    attempt++
                    delay(1500)
                    continue
                }
                // Shorten user-facing toast: upstream wraps this msg into
                // the voice ERROR state; keep it under ~40 chars.
                if (is429) throw IllegalStateException("Voice rate-limited — try again")
                throw t
            }
        }
    }

    private fun callGeminiOnce(
        systemInstruction: String?,
        userText: String,
        tools: JsonElement?,
    ): JsonObject {
        if (apiKey.isBlank()) {
            throw IllegalStateException(
                "GEMINI_API_KEY missing. Add it to local.properties: GEMINI_API_KEY=...",
            )
        }
        val body = buildJsonObject {
            if (systemInstruction != null) {
                putJsonObject("systemInstruction") {
                    put(
                        "parts",
                        buildJsonArray {
                            addJsonObject { put("text", systemInstruction) }
                        },
                    )
                }
            }
            put(
                "contents",
                buildJsonArray {
                    addJsonObject {
                        put("role", "user")
                        put(
                            "parts",
                            buildJsonArray {
                                addJsonObject { put("text", userText) }
                            },
                        )
                    }
                },
            )
            if (tools != null) {
                put("tools", tools)
            }
            putJsonObject("generationConfig") {
                // Deterministic-ish replies. 0.7 is SDK default.
                put("temperature", 0.6)
                put("maxOutputTokens", 1024)
            }
        }
        val bodyStr = body.toString()
        XrLog.i(
            TAG,
            "HTTP POST $modelName tools=${tools != null} sys=${systemInstruction != null} " +
                "req_bytes=${bodyStr.length}",
        )
        val req = Request.Builder()
            .url(endpoint)
            .post(bodyStr.toRequestBody("application/json".toMediaType()))
            .build()
        val t0 = System.nanoTime()
        http.newCall(req).execute().use { resp ->
            val payload = resp.body?.string().orEmpty()
            val elapsedMs = (System.nanoTime() - t0) / 1_000_000
            XrLog.i(
                TAG,
                "HTTP ${resp.code} in ${elapsedMs}ms resp_bytes=${payload.length}",
            )
            if (!resp.isSuccessful) {
                // Gemini returns structured error JSON; surface the
                // message not the whole blob so the toast is readable.
                val trimmed = runCatching {
                    json.parseToJsonElement(payload)
                        .jsonObject["error"]?.jsonObject
                        ?.get("message")?.jsonPrimitive?.contentOrNull
                }.getOrNull() ?: payload.take(200)
                XrLog.w(TAG, "HTTP error body: ${payload.take(800)}")
                throw IllegalStateException("Gemini ${resp.code}: $trimmed")
            }
            return json.parseToJsonElement(payload).jsonObject
        }
    }

    // ---------------------------------------------------------------------------
    // Response parsing
    // ---------------------------------------------------------------------------

    private fun firstCandidateParts(response: JsonObject): List<JsonObject> {
        val candidates = response["candidates"]?.jsonArray ?: return emptyList()
        val first = candidates.firstOrNull()?.jsonObject ?: return emptyList()
        val content = first["content"]?.jsonObject ?: return emptyList()
        return content["parts"]?.jsonArray?.mapNotNull { it as? JsonObject }.orEmpty()
    }

    private fun extractText(response: JsonObject): String {
        val parts = firstCandidateParts(response)
        val sb = StringBuilder()
        for (p in parts) {
            val text = p["text"]?.jsonPrimitive?.contentOrNull
            if (!text.isNullOrBlank()) {
                if (sb.isNotEmpty()) sb.append(' ')
                sb.append(text.trim())
            }
        }
        val out = sb.toString().trim()
        if (out.isEmpty()) throw IllegalStateException("Empty model response")
        return out
    }

    private fun parseToolReply(response: JsonObject): ToolReply {
        val parts = firstCandidateParts(response)
        val textBuilder = StringBuilder()
        val commands = mutableListOf<EmailCommandTool.Command>()
        for (p in parts) {
            p["text"]?.jsonPrimitive?.contentOrNull?.let { text ->
                if (text.isNotBlank()) {
                    if (textBuilder.isNotEmpty()) textBuilder.append(' ')
                    textBuilder.append(text.trim())
                }
            }
            val call = p["functionCall"]?.jsonObject
            if (call != null) {
                val name = call["name"]?.jsonPrimitive?.contentOrNull ?: continue
                val argsObj = call["args"]?.jsonObject
                val args: Map<String, String?> = argsObj?.mapValues { (_, v) ->
                    (v as? JsonPrimitive)?.contentOrNull
                } ?: emptyMap()
                EmailCommandTool.parse(name, args)?.let { commands += it }
            }
        }
        return ToolReply(
            spokenText = textBuilder.toString().trim(),
            commands = commands,
        )
    }

    private companion object {
        const val TAG = "GeminiTextService"
        const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta"

        // Voice system prompt. Inlined so this class has zero dependencies
        // on GeminiLiveManager — that file is dormant but kept around for
        // easy revert if we ever want to try streaming audio again.
        private val VOICE_COMMAND_SYSTEM_PROMPT = """
            You are XrMail's hands-free voice agent. The user just spoke a single
            utterance — respond in ONE turn. The current inbox snapshot is
            included in the user message under "[inbox state]"; use it directly,
            do NOT call get_inbox_state.

            CRITICAL RULES:
            1. ALWAYS produce spoken text. Never return an empty reply. Even
               after a tool call you MUST say something short out loud.
            2. Every email line in the snapshot is formatted as
               `id=<ID>, from=<name>, subject="<subject>", priority=<level>`.
               When a tool needs `emailId`, copy the id string VERBATIM from the
               snapshot line that matches what the user referred to. NEVER
               guess or invent an id. If no matching email is in the snapshot,
               say so in one sentence and do not call the tool.

            Three modes, pick ONE per turn:

            ANSWER MODE (user asks about email content — "what did Alex say",
            "anything urgent", "who emailed me", "summarize this"): answer in
            1–3 short sentences drawn from the inbox snapshot. Quote senders by
            name. If the answer isn't in the snapshot, say so in one sentence.
            Do not invent senders or content. No function call needed.

            ACTION MODE (intent maps to a UI action): call the matching tool
            AND ALWAYS speak a short confirmation (≤6 words: "Archived." /
            "Snoozed till tomorrow." / "Opening Maria's email.").

            Intent → tool cheatsheet:
              - "open/go to/show X" → select_email(emailId=…)
              - "read X (out loud)" → two calls: select_email then read_aloud
              - "summarize X / TL;DR X" → select_email then summarize (the
                 summarize tool handles the spoken summary itself, but you
                 still must say one confirmation word like "Summarizing.")
              - "archive/snooze/forward/search/next/refresh/open inbox/focus/
                 collapse/back" → their dedicated tools

            COMPOSE MODE (user asks to reply, write back, draft, respond, send):
              1. Write the FULL reply yourself in 1–4 short sentences.
              2. Call draft_reply(body=COMPLETE TEXT). Then ASK the user what
                 they want to do next: say exactly one short sentence like
                 "Drafted. Want me to read it, show it, or send it?" — do NOT
                 speak the body itself. WAIT for the user's reply in the next
                 turn.
              3. The user's next answer routes like this:
                 - "read it" / "read it back" / "read it out loud"
                     → call read_draft. DO NOT also speak the body yourself,
                       the tool handles it.
                 - "show it" / "let me see it" / "bring it up" / "show me
                   before sending" / (long or important emails where you want
                   a visual review by default)
                     → call show_send_confirmation. Say "Here's the preview."
                 - "send it" / "yes send" / "fire it off"
                     → FIRST call arm_send_for_voice, then IMMEDIATELY
                       send_draft in the same turn. send_draft without a
                       prior arm_send_for_voice is rejected.
                 - Revisions ("change X to Y", "make it shorter")
                     → call revise_draft(body=COMPLETE rewritten body). Then
                       ask "Read, show, or send?" again.
                 - Cancel ("never mind", "cancel", "throw it out")
                     → call cancel_draft.
              4. If the user is unclear, default to show_send_confirmation
                 rather than sending. NEVER send without explicit
                 confirmation in words.

            Always default to the selected email when one exists. In spoken
            replies reference emails by sender or subject, never by id (but
            tool `emailId` args MUST be the id string from the snapshot).
            If the request is genuinely ambiguous, ask ONE tight clarifying
            question instead of guessing — but always say something out loud.
        """.trimIndent()

        // Tool declarations in the raw REST shape. Kept in sync by hand
        // with EmailCommandTool.tool — the Firebase AI Logic `Tool` object
        // can't be serialized to REST JSON directly, and we don't want to
        // pull in the deprecated `google-ai-client-generativeai` SDK just
        // to get a second copy of FunctionDeclaration that does support
        // JSON serialization. If you add a new tool to EmailCommandTool,
        // add the matching entry here AND wire it into EmailCommandTool.parse.
        private val TOOLS_JSON: JsonElement = buildJsonArray {
            addJsonObject {
                put(
                    "functionDeclarations",
                    buildJsonArray {
                        fun add(
                            name: String,
                            description: String,
                            params: Map<String, Pair<String, String>> = emptyMap(),
                            required: List<String> = params.keys.toList(),
                        ) {
                            addJsonObject {
                                put("name", name)
                                put("description", description)
                                if (params.isNotEmpty()) {
                                    putJsonObject("parameters") {
                                        put("type", "OBJECT")
                                        putJsonObject("properties") {
                                            for ((prop, td) in params) {
                                                putJsonObject(prop) {
                                                    put("type", td.first)
                                                    put("description", td.second)
                                                }
                                            }
                                        }
                                        if (required.isNotEmpty()) {
                                            put(
                                                "required",
                                                buildJsonArray { required.forEach { add(it) } },
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        add(
                            "get_inbox_state",
                            "Fetch the current inbox snapshot. The snapshot is " +
                                "already inlined in every user turn for this app, " +
                                "so do NOT call this tool — it exists only as a " +
                                "fallback and will return an empty snapshot.",
                        )
                        add(
                            "select_email",
                            "Open a specific email by id.",
                            mapOf("emailId" to ("STRING" to "The id of the email to open.")),
                        )
                        add(
                            "archive_email",
                            "Archive an email. Omit emailId to archive the selected one.",
                            mapOf("emailId" to ("STRING" to "Email id, optional.")),
                            required = emptyList(),
                        )
                        add(
                            "snooze_email",
                            "Snooze an email until a time phrase like 'tomorrow 9am'.",
                            mapOf(
                                "emailId" to ("STRING" to "Email id, optional."),
                                "until" to ("STRING" to "Natural-language time to resurface."),
                            ),
                            required = listOf("until"),
                        )
                        add(
                            "forward_email",
                            "Forward an email to a recipient.",
                            mapOf(
                                "emailId" to ("STRING" to "Email id, optional."),
                                "to" to ("STRING" to "Recipient email address or contact name."),
                            ),
                            required = listOf("to"),
                        )
                        add(
                            "reply",
                            "Compose a reply. body is the message to send; omit for blank compose.",
                            mapOf(
                                "emailId" to ("STRING" to "Email id, optional."),
                                "body" to ("STRING" to "Full message body, optional."),
                            ),
                            required = emptyList(),
                        )
                        add(
                            "search",
                            "Search the inbox.",
                            mapOf("query" to ("STRING" to "Natural-language search query.")),
                        )
                        add(
                            "read_aloud",
                            "Read the selected email body aloud, verbatim.",
                            mapOf("emailId" to ("STRING" to "Email id, optional.")),
                            required = emptyList(),
                        )
                        add(
                            "summarize",
                            "Summarize the selected email in one or two sentences and speak it.",
                            mapOf("emailId" to ("STRING" to "Email id, optional.")),
                            required = emptyList(),
                        )
                        add(
                            "draft_reply",
                            "Write a complete reply draft for the user. Pass the FULL body in `body` — 1 to 4 short natural sentences, ready to send. Do NOT speak the body yourself. After the call, ask the user 'Want me to read it, show it, or send it?' and wait for their answer — the next turn's reply decides whether to call read_draft, show_send_confirmation, or arm_send_for_voice+send_draft.",
                            mapOf(
                                "emailId" to ("STRING" to "Email id, optional."),
                                "tone" to ("STRING" to "Tone hint, e.g. 'friendly', optional."),
                                "body" to ("STRING" to "Complete draft body, required."),
                            ),
                            required = listOf("body"),
                        )
                        add(
                            "revise_draft",
                            "Replace the in-progress draft with a rewritten version. Pass the COMPLETE rewritten body in `body`.",
                            mapOf("body" to ("STRING" to "Complete revised draft body.")),
                        )
                        add(
                            "cancel_draft",
                            "Discard the in-progress draft without sending.",
                        )
                        add(
                            "arm_send_for_voice",
                            "Two-step safety gate before sending by voice. Call BEFORE send_draft after the user explicitly confirms — send_draft without a prior arm_send_for_voice is rejected.",
                        )
                        add(
                            "send_draft",
                            "Actually send the draft currently on screen. Requires a prior arm_send_for_voice in the same turn.",
                            mapOf("emailId" to ("STRING" to "Email id, optional.")),
                            required = emptyList(),
                        )
                        add(
                            "show_send_confirmation",
                            "Pop up a visual confirmation window of the in-progress draft (recipient, subject, body) with Send and Cancel buttons. Use when the user asks to see the draft ('show it', 'let me see it', 'bring it up'), or when the draft is long/important. Only valid while a draft is active.",
                        )
                        add(
                            "read_draft",
                            "Read the in-progress draft body aloud verbatim. Use only when the user explicitly asks to hear it ('read it', 'read it back'). Only valid while a draft is active.",
                        )
                        add(
                            "filter_category",
                            "Filter the inbox to a category like 'work', 'personal', 'promotions'.",
                            mapOf("category" to ("STRING" to "Category name.")),
                        )
                        add(
                            "refresh",
                            "Collapse every panel back to the ambient HUD and reload the inbox.",
                        )
                        add(
                            "expand_tier",
                            "Escalate the UI to a deeper tier without opening a specific email. Pass one of 'notifications', 'inbox', 'focus'.",
                            mapOf("target" to ("STRING" to "One of 'notifications', 'inbox', or 'focus'.")),
                        )
                        add(
                            "collapse_one_tier",
                            "Step back one tier toward the ambient HUD.",
                        )
                    },
                )
            }
        }
    }
}
