package com.xremail.app.voice

import com.google.firebase.ai.type.FunctionDeclaration
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.Tool
import com.google.firebase.ai.type.content

/**
 * Function-calling tool definitions for the Gemini Live API session.
 *
 * Production: pass [emailAssistantTool] as `tools` to `Firebase.ai().liveModel(...)`.
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
        data class SetComposeBody(val body: String) : Command()
        data object ShowInbox : Command()
        data object GoBack : Command()
        data object ArmSendForVoice : Command()
    }

    val toolNames = listOf(
        "select_email", "archive_email", "snooze_email", "forward_email",
        "reply", "search", "read_aloud", "summarize", "draft_reply",
        "send_draft", "filter_category", "show_inbox", "go_back",
        "set_compose_body", "arm_send_for_voice",
    )

    private val selectEmailFn = FunctionDeclaration(
        name = "select_email",
        description = "Open and focus the email with the given id.",
        parameters = mapOf(
            "emailId" to Schema.str("Identifier of the email in the current mailbox."),
        ),
    )

    private val archiveEmailFn = FunctionDeclaration(
        name = "archive_email",
        description = "Archive or remove the email from the inbox.",
        parameters = mapOf(
            "emailId" to Schema.str("Identifier of the email to archive."),
        ),
    )

    private val snoozeEmailFn = FunctionDeclaration(
        name = "snooze_email",
        description = "Snooze the email until a natural-language or ISO time.",
        parameters = mapOf(
            "emailId" to Schema.str("Identifier of the email."),
            "until" to Schema.str("When to resurface, e.g. tomorrow 9am or ISO-8601."),
        ),
    )

    private val forwardEmailFn = FunctionDeclaration(
        name = "forward_email",
        description = "Forward the email to another address.",
        parameters = mapOf(
            "emailId" to Schema.str("Identifier of the email."),
            "to" to Schema.str("Recipient email address."),
        ),
    )

    private val replyFn = FunctionDeclaration(
        name = "reply",
        description = "Set reply body text for the compose field or thread.",
        parameters = mapOf(
            "emailId" to Schema.str("Identifier of the email being replied to."),
            "body" to Schema.str("Full reply body text."),
        ),
        optionalParameters = listOf("body"),
    )

    private val searchFn = FunctionDeclaration(
        name = "search",
        description = "Search inbox by keywords or sender.",
        parameters = mapOf(
            "query" to Schema.str("Search query."),
        ),
    )

    private val readAloudFn = FunctionDeclaration(
        name = "read_aloud",
        description = "Read the email content aloud using text-to-speech.",
        parameters = mapOf(
            "emailId" to Schema.str("Identifier of the email."),
        ),
    )

    private val summarizeFn = FunctionDeclaration(
        name = "summarize",
        description = "Generate a short spoken summary of the email.",
        parameters = mapOf(
            "emailId" to Schema.str("Identifier of the email."),
        ),
    )

    private val draftReplyFn = FunctionDeclaration(
        name = "draft_reply",
        description = "Draft an AI reply in the compose panel with optional tone.",
        parameters = mapOf(
            "emailId" to Schema.str("Identifier of the email."),
            "tone" to Schema.str("Tone: professional, friendly, or brief."),
        ),
        optionalParameters = listOf("tone"),
    )

    private val sendDraftFn = FunctionDeclaration(
        name = "send_draft",
        description = "Send the current draft. ONLY after user gives explicit oral confirmation and arm_send_for_voice was used in this session.",
        parameters = mapOf(
            "emailId" to Schema.str("Identifier of the email thread being answered."),
        ),
    )

    private val filterCategoryFn = FunctionDeclaration(
        name = "filter_category",
        description = "Filter inbox by category token.",
        parameters = mapOf(
            "category" to Schema.str(
                "One of: PEOPLE, UPDATES, PROMOTIONS, NEWSLETTERS, TRANSACTIONAL, or ALL.",
            ),
        ),
    )

    private val showInboxFn = FunctionDeclaration(
        name = "show_inbox",
        description = "Show the full inbox clearing filters.",
        parameters = emptyMap(),
    )

    private val goBackFn = FunctionDeclaration(
        name = "go_back",
        description = "Leave compose and return to reading mode if possible.",
        parameters = emptyMap(),
    )

    private val setComposeBodyFn = FunctionDeclaration(
        name = "set_compose_body",
        description = "Replace the entire compose body with transcribed or generated text while composing.",
        parameters = mapOf(
            "body" to Schema.str("Full email body for the draft."),
        ),
    )

    private val armSendFn = FunctionDeclaration(
        name = "arm_send_for_voice",
        description = "Call when the user clearly intends to send (before send_draft). Required once per send attempt.",
        parameters = emptyMap(),
    )

    val emailAssistantTool: Tool = Tool.functionDeclarations(
        listOf(
            selectEmailFn,
            archiveEmailFn,
            snoozeEmailFn,
            forwardEmailFn,
            replyFn,
            searchFn,
            readAloudFn,
            summarizeFn,
            draftReplyFn,
            sendDraftFn,
            filterCategoryFn,
            showInboxFn,
            goBackFn,
            setComposeBodyFn,
            armSendFn,
        ),
    )

    val systemInstruction = content {
        text(
            """
            You are a concise voice assistant inside an email app on XR hardware.
            Keep spoken replies very short (under 15 words) unless reading email content.
            Use tools to change the UI; do not claim an action happened without calling the right tool.
            For sending: first call arm_send_for_voice when the user says they want to send mail.
            Only call send_draft after the user gives a clear explicit confirmation (e.g. "send it", "yes send").
            If send would happen without that two-step flow, refuse and ask for confirmation.
            """.trimIndent(),
        )
    }
}
