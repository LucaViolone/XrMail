package com.xremail.app.voice

import com.google.firebase.ai.type.FunctionDeclaration
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.Tool

/**
 * Function-calling tool definitions the Gemini Live session exposes to the model.
 *
 * The model decides when to call these based on the user's speech; we register
 * them on connect and dispatch each call into [com.xremail.app.viewmodel.EmailViewModel]
 * via [VoiceCommandDispatcher].
 *
 * When adding a new command:
 * 1. Add a subclass to [Command].
 * 2. Add a [FunctionDeclaration] below with matching name/args.
 * 3. Handle the new case in [parse] and in [VoiceCommandDispatcher.dispatch].
 */
object EmailCommandTool {

    sealed class Command {
        data class SelectEmail(val emailId: String) : Command()
        data class ArchiveEmail(val emailId: String?) : Command()
        data class SnoozeEmail(val emailId: String?, val until: String) : Command()
        data class ForwardEmail(val emailId: String?, val to: String) : Command()
        data class Reply(val emailId: String?, val body: String?) : Command()
        data class Search(val query: String) : Command()
        data class ReadAloud(val emailId: String?) : Command()
        data class Summarize(val emailId: String?) : Command()
        /**
         * Generate a fully-written draft reply that the UI shows + reads back.
         * The model must pass a complete, well-formed [body] (one short paragraph,
         * 1–4 sentences). [tone] is informational only — the body should already
         * reflect it. Nothing sends until the user confirms with [SendDraft].
         */
        data class DraftReply(val emailId: String?, val tone: String?, val body: String?) : Command()
        /**
         * Replace the in-progress draft body with [body]. Use when the user
         * says "change X to Y" / "make it shorter" / "rewrite it more
         * formally". The new body fully overwrites the old one — the model
         * is responsible for producing the COMPLETE revised draft, not a diff.
         */
        data class ReviseDraft(val body: String) : Command()
        /**
         * Discard the in-progress draft without sending. "cancel that",
         * "never mind", "throw it out".
         */
        data object CancelDraft : Command()
        data class SendDraft(val emailId: String?) : Command()
        data class FilterCategory(val category: String) : Command()
        data object ShowInbox : Command()
        data object GoBack : Command()
        data object Refresh : Command()
        data class Speak(val text: String) : Command()
        /**
         * Voice-driven tier escalation. Mirrors the gesture / gaze-dwell paths
         * so the user can say "open notifications" / "show inbox" / "focus
         * mode" instead of pinching.
         */
        data class ExpandTier(val target: String) : Command()
        /**
         * Inverse of [ExpandTier]: collapse one tier toward the ambient HUD.
         */
        data object CollapseOneTier : Command()
        /**
         * Skip to the next unread email. Used by the local "next" voice
         * command and by gesture-driven "skip" actions. Maps to
         * [com.xremail.app.viewmodel.EmailViewModel.navigateNextUnread].
         */
        data object NextUnread : Command()
        /**
         * Synthetic command — emitted INTO the dispatcher when Gemini
         * calls `get_inbox_state`. Doesn't change UI state; the
         * dispatcher returns the inbox snapshot via FunctionResponsePart
         * so the model can answer content questions in the same turn.
         * See [GeminiLiveManager.handleFunctionCall].
         */
        data object GetInboxState : Command()
    }

    // ---------------------------------------------------------------------------
    // Gemini Live tool schema
    // ---------------------------------------------------------------------------

    private val selectEmail = FunctionDeclaration(
        "select_email",
        "Open a specific email by id. Use when the user refers to an email in the current inbox.",
        mapOf("emailId" to Schema.string("The id of the email to open.")),
    )

    private val archiveEmail = FunctionDeclaration(
        "archive_email",
        "Archive an email. Omit emailId to archive the currently selected one.",
        mapOf("emailId" to Schema.string("Email id, optional.")),
        optionalParameters = listOf("emailId"),
    )

    private val snoozeEmail = FunctionDeclaration(
        "snooze_email",
        "Snooze an email until a time phrase like 'tomorrow 9am'. Omits emailId = currently selected.",
        mapOf(
            "emailId" to Schema.string("Email id, optional."),
            "until" to Schema.string("Natural language time to resurface, e.g. 'tomorrow 9am'."),
        ),
        optionalParameters = listOf("emailId"),
    )

    private val forwardEmail = FunctionDeclaration(
        "forward_email",
        "Forward an email to a recipient.",
        mapOf(
            "emailId" to Schema.string("Email id, optional."),
            "to" to Schema.string("Recipient email address or contact name."),
        ),
        optionalParameters = listOf("emailId"),
    )

    private val reply = FunctionDeclaration(
        "reply",
        "Compose a reply to the current email. body is the message to send; omit for blank compose.",
        mapOf(
            "emailId" to Schema.string("Email id, optional."),
            "body" to Schema.string("Full message body to send, optional."),
        ),
        optionalParameters = listOf("emailId", "body"),
    )

    private val search = FunctionDeclaration(
        "search",
        "Search the inbox.",
        mapOf("query" to Schema.string("Natural language search query.")),
    )

    private val readAloud = FunctionDeclaration(
        "read_aloud",
        "Read the selected email body aloud (not a summary — verbatim).",
        mapOf("emailId" to Schema.string("Email id, optional.")),
        optionalParameters = listOf("emailId"),
    )

    private val summarize = FunctionDeclaration(
        "summarize",
        "Summarize the selected email in one or two sentences and speak it.",
        mapOf("emailId" to Schema.string("Email id, optional.")),
        optionalParameters = listOf("emailId"),
    )

    private val draftReply = FunctionDeclaration(
        "draft_reply",
        "Write a complete reply draft for the user to review. " +
            "Pass the FULL body text in `body` — 1 to 4 short sentences, " +
            "natural and conversational, ready to send as-is. The UI will " +
            "show the draft and read it back; do NOT also speak the draft " +
            "yourself. After this call returns, just say a short " +
            "confirmation like \"Drafted, want me to send it?\". " +
            "Does NOT send — wait for the user to say \"send it\" or call " +
            "send_draft. Use this whenever the user asks you to reply, " +
            "respond, write back, or compose a message.",
        mapOf(
            "emailId" to Schema.string("Email id, optional. Defaults to the selected email."),
            "tone" to Schema.string("Tone hint, e.g. 'friendly', 'formal', 'apologetic'. Optional."),
            "body" to Schema.string("Complete draft body, ready to send. Required."),
        ),
        optionalParameters = listOf("emailId", "tone"),
    )

    private val reviseDraft = FunctionDeclaration(
        "revise_draft",
        "Replace the in-progress draft with a revised version. Use when " +
            "the user says \"change X to Y\", \"make it shorter\", \"rewrite " +
            "more formally\", \"actually say ...\", etc. Pass the COMPLETE " +
            "rewritten body in `body` — not a diff. The UI will re-read it " +
            "back. Only valid while a draft is on screen (after draft_reply).",
        mapOf("body" to Schema.string("Complete revised draft body.")),
    )

    private val cancelDraft = FunctionDeclaration(
        "cancel_draft",
        "Discard the in-progress draft without sending. Use when the user " +
            "says \"cancel\", \"never mind\", \"throw it out\", \"forget it\".",
        emptyMap(),
    )

    private val sendDraft = FunctionDeclaration(
        "send_draft",
        "Actually send the draft currently on screen. Only valid AFTER " +
            "draft_reply has been called and the user has explicitly " +
            "confirmed (\"send it\", \"yes send\", \"fire it off\"). NEVER " +
            "call this without the user's explicit confirmation — the user " +
            "must hear the draft read back first.",
        mapOf("emailId" to Schema.string("Email id being replied to, optional.")),
        optionalParameters = listOf("emailId"),
    )

    private val filterCategory = FunctionDeclaration(
        "filter_category",
        "Filter the inbox to a category like 'work', 'personal', 'promotions'.",
        mapOf("category" to Schema.string("Category name.")),
    )

    private val showInbox = FunctionDeclaration(
        "show_inbox",
        "Open the inbox view (the full email list with previews). Use when the " +
            "user says 'show me my inbox', 'open inbox', 'let me see my emails'. This " +
            "expands past the ambient banner into the full inbox panel.",
        emptyMap(),
    )

    private val expandTier = FunctionDeclaration(
        "expand_tier",
        "Escalate the UI to a deeper interaction tier without reading a specific email. " +
            "Use when the user wants to go further into the app: 'open notifications', " +
            "'show inbox', 'focus mode', 'open my email panel'. Pass the target tier as " +
            "one of: 'notifications', 'inbox', 'focus'. ('triage' also accepted as a " +
            "legacy alias for 'inbox'.)",
        mapOf("target" to Schema.string(
            "One of 'notifications', 'inbox', or 'focus'."
        )),
    )

    private val collapseOneTier = FunctionDeclaration(
        "collapse_one_tier",
        "Step back one tier toward the ambient HUD. Use when the user says 'go back', " +
            "'close this', 'minimize', 'collapse', 'I'm done'.",
        emptyMap(),
    )

    private val goBack = FunctionDeclaration(
        "go_back",
        "Go back one step in navigation.",
        emptyMap(),
    )

    private val refresh = FunctionDeclaration(
        "refresh",
        "Recover / reset the UI: collapse every panel back to the ambient HUD and reload the inbox. Call this when the user says things like 'refresh', 'reset', 'start over', 'I'm stuck', 'the UI is frozen', or when you detect confusion about what's on screen.",
        emptyMap(),
    )

    private val speak = FunctionDeclaration(
        "speak",
        "Speak a short phrase to the user through local TTS.",
        mapOf("text" to Schema.string("The phrase to speak.")),
    )

    /**
     * On-demand inbox snapshot. The model calls this whenever the user
     * asks ANYTHING about email content (who emailed, what they said,
     * subjects, summaries, etc.). Returns a short formatted string with
     * the unread emails and brief body excerpts.
     *
     * Why a tool instead of pushing context at session start:
     * - Inbox changes (new emails, archives) — pushing once gets stale.
     * - The Gemini Live SDK's `sendTextRealtime` and `send(content(role=
     *   model))` paths both fail unreliably on Galaxy XR's WebSocket
     *   handshake timing, leaving the model ungrounded.
     * - A tool call is the documented, reliable way to inject dynamic
     *   context — it costs ~200ms but always works.
     */
    private val getInboxState = FunctionDeclaration(
        "get_inbox_state",
        "Fetch the current inbox snapshot — sender names, subjects, " +
            "and short body excerpts of every unread email. " +
            "ALWAYS call this BEFORE answering any question about email " +
            "content (who emailed, what they said, subjects, summaries, " +
            "what's in the inbox). Don't guess from memory — the inbox " +
            "changes every minute, only this tool returns ground truth.",
        emptyMap(),
    )

    /**
     * Tools the model sees on each turn. Smaller is faster — every
     * declaration here costs prompt-prefill tokens and ambiguity for the
     * model to disambiguate between similar options.
     *
     * Intentionally excluded from this list (but still parseable for
     * backward compatibility, see [parse]):
     *
     * - `goBack` — superseded by `collapseOneTier`, which has clearer
     *   semantics ("move one step toward the ambient HUD").
     * - `showInbox` — superseded by `expandTier(target='inbox')`. One tool
     *   handles every escalation instead of two overlapping ones.
     * - `speak` — encouraged the model to call a tool just to say something,
     *   when it could (and should) just respond conversationally. Removing
     *   it shaves a function-call round-trip off many short replies.
     */
    val tool: Tool = Tool.functionDeclarations(
        listOf(
            getInboxState,
            selectEmail, archiveEmail, snoozeEmail, forwardEmail, reply, search,
            readAloud, summarize, draftReply, reviseDraft, cancelDraft, sendDraft,
            filterCategory, refresh, expandTier, collapseOneTier,
        ),
    )

    // ---------------------------------------------------------------------------
    // Parsing: FunctionCallPart (from Gemini Live) -> Command
    // ---------------------------------------------------------------------------

    fun parse(name: String, args: Map<String, String?>): Command? = when (name) {
        "select_email" -> args["emailId"]?.let { Command.SelectEmail(it) }
        "archive_email" -> Command.ArchiveEmail(args["emailId"])
        "snooze_email" -> args["until"]?.let { Command.SnoozeEmail(args["emailId"], it) }
        "forward_email" -> args["to"]?.let { Command.ForwardEmail(args["emailId"], it) }
        "reply" -> Command.Reply(args["emailId"], args["body"])
        "search" -> args["query"]?.let { Command.Search(it) }
        "read_aloud" -> Command.ReadAloud(args["emailId"])
        "summarize" -> Command.Summarize(args["emailId"])
        "draft_reply" -> Command.DraftReply(args["emailId"], args["tone"], args["body"])
        "revise_draft" -> args["body"]?.let { Command.ReviseDraft(it) }
        "cancel_draft" -> Command.CancelDraft
        "send_draft" -> Command.SendDraft(args["emailId"])
        "filter_category" -> args["category"]?.let { Command.FilterCategory(it) }
        "show_inbox" -> Command.ShowInbox
        "go_back" -> Command.GoBack
        "refresh" -> Command.Refresh
        "speak" -> args["text"]?.let { Command.Speak(it) }
        "expand_tier" -> args["target"]?.let { Command.ExpandTier(it) }
        "collapse_one_tier" -> Command.CollapseOneTier
        "get_inbox_state" -> Command.GetInboxState
        else -> null
    }
}
