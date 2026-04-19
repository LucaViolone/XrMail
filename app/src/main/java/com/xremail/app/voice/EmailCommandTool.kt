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
        data class DraftReply(val emailId: String?, val tone: String?) : Command()
        data class SendDraft(val emailId: String?) : Command()
        data class FilterCategory(val category: String) : Command()
        data object ShowInbox : Command()
        data object GoBack : Command()
        data object Refresh : Command()
        data class Speak(val text: String) : Command()
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
        "Generate a draft reply for review (does not send).",
        mapOf(
            "emailId" to Schema.string("Email id, optional."),
            "tone" to Schema.string("Tone of the draft, e.g. formal, friendly."),
        ),
        optionalParameters = listOf("emailId", "tone"),
    )

    private val sendDraft = FunctionDeclaration(
        "send_draft",
        "Send the draft currently on screen.",
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
        "Return to the inbox view.",
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

    val tool: Tool = Tool.functionDeclarations(
        listOf(
            selectEmail, archiveEmail, snoozeEmail, forwardEmail, reply, search,
            readAloud, summarize, draftReply, sendDraft, filterCategory,
            showInbox, goBack, refresh, speak,
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
        "draft_reply" -> Command.DraftReply(args["emailId"], args["tone"])
        "send_draft" -> Command.SendDraft(args["emailId"])
        "filter_category" -> args["category"]?.let { Command.FilterCategory(it) }
        "show_inbox" -> Command.ShowInbox
        "go_back" -> Command.GoBack
        "refresh" -> Command.Refresh
        "speak" -> args["text"]?.let { Command.Speak(it) }
        else -> null
    }
}
