package com.xremail.app.voice

/**
 * Function-calling tool definitions for the Gemini Live API session.
 *
 * These define the actions the voice model can invoke. The Gemini Live API
 * handles natural language understanding — no manual intent parsing needed.
 *
 * Production: pass these as `tools` to Firebase.ai().liveModel(...).
 */
object EmailCommandTool {

    sealed class Command {
        data class SelectEmail(val emailId: String) : Command()
        data class ArchiveEmail(val emailId: String) : Command()
        data class SnoozeEmail(val emailId: String, val until: String) : Command()
        data class ForwardEmail(val emailId: String, val to: String) : Command()
        data class Reply(val emailId: String, val body: String?) : Command()
        data class Search(val query: String) : Command()
        data class ReadAloud(val emailId: String) : Command()
        data class Summarize(val emailId: String) : Command()
        data class DraftReply(val emailId: String, val tone: String?) : Command()
        data class SendDraft(val emailId: String) : Command()
        data class FilterCategory(val category: String) : Command()
        data object ShowInbox : Command()
        data object GoBack : Command()
    }

    /**
     * Tool schema that would be passed to the Gemini Live API.
     * In production this is a JSON schema; here we define the structure in Kotlin.
     *
     * ```
     * val emailCommandTool = Tool(
     *     functionDeclarations = listOf(
     *         FunctionDeclaration("archive_email", "Archive the current email",
     *             Schema.obj("emailId" to Schema.string())),
     *         FunctionDeclaration("reply", "Reply to an email",
     *             Schema.obj("emailId" to Schema.string(), "body" to Schema.string().optional())),
     *         // ... etc
     *     )
     * )
     * ```
     */
    val toolNames = listOf(
        "select_email", "archive_email", "snooze_email", "forward_email",
        "reply", "search", "read_aloud", "summarize", "draft_reply",
        "send_draft", "filter_category", "show_inbox", "go_back",
    )
}
