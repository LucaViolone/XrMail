package com.xremail.app.voice

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * Function-calling tool definitions for the Gemini Live API session.
 *
 * The sealed [Command] hierarchy maps 1:1 to the function declarations
 * sent in the WebSocket setup message. [VoiceCommandExecutor] handles
 * every variant, translating voice commands into ViewModel actions.
 */
object EmailCommandTool {

    sealed class Command {
        // Tier navigation
        data object ExpandToNotifications : Command()
        data object ExpandToTriage : Command()
        data object ExpandToFocus : Command()
        data object CollapseToHud : Command()
        data object CollapseOneTier : Command()

        // Email selection
        data class SelectEmail(val emailId: String) : Command()
        data object SelectNextUnread : Command()

        // Email actions
        data object ArchiveSelected : Command()
        data class ArchiveEmail(val emailId: String) : Command()
        data object SnoozeSelected : Command()
        data class SnoozeEmail(val emailId: String, val until: String? = null) : Command()
        data object StarSelected : Command()
        data object ForwardSelected : Command()

        // TTS / reading
        data object ReadTopPriority : Command()
        data object ReadNextUnread : Command()
        data class ReadEmail(val emailId: String) : Command()
        data object ReadSummary : Command()
        data object StopTts : Command()
        data object PauseTts : Command()
        data object ResumeTts : Command()

        // Compose / reply
        data class VoiceReply(val briefInstruction: String) : Command()
        data object ConfirmSend : Command()
        data object CancelCompose : Command()
        data class EditDraft(val instruction: String) : Command()

        // Filtering
        data class FilterCategory(val category: String) : Command()
        data object ShowAllEmails : Command()
        data class Search(val query: String) : Command()

        // Utility
        data object WhatIsUrgent : Command()
        data object HowManyUnread : Command()
    }

    /**
     * Parses a Gemini `toolCall.functionCalls[]` entry into a [Command].
     * [args] is the raw JSON object from the function call.
     */
    fun parse(name: String, args: JsonObject?): Command? {
        return when (name) {
            "expand_to_notifications" -> Command.ExpandToNotifications
            "expand_to_triage" -> Command.ExpandToTriage
            "expand_to_focus" -> Command.ExpandToFocus
            "collapse_to_hud" -> Command.CollapseToHud
            "collapse_one_tier" -> Command.CollapseOneTier

            "select_email" -> Command.SelectEmail(args?.str("emailId") ?: return null)
            "select_next_unread" -> Command.SelectNextUnread

            "archive_selected" -> Command.ArchiveSelected
            "archive_email" -> Command.ArchiveEmail(args?.str("emailId") ?: return null)
            "snooze_selected" -> Command.SnoozeSelected
            "snooze_email" -> Command.SnoozeEmail(
                emailId = args?.str("emailId") ?: return null,
                until = args.str("until"),
            )
            "star_selected" -> Command.StarSelected
            "forward_selected" -> Command.ForwardSelected

            "read_top_priority" -> Command.ReadTopPriority
            "read_next_unread" -> Command.ReadNextUnread
            "read_email" -> Command.ReadEmail(args?.str("emailId") ?: return null)
            "read_summary" -> Command.ReadSummary
            "stop_tts" -> Command.StopTts
            "pause_tts" -> Command.PauseTts
            "resume_tts" -> Command.ResumeTts

            "voice_reply" -> Command.VoiceReply(args?.str("briefInstruction") ?: "")
            "confirm_send" -> Command.ConfirmSend
            "cancel_compose" -> Command.CancelCompose
            "edit_draft" -> Command.EditDraft(args?.str("instruction") ?: "")

            "filter_category" -> Command.FilterCategory(args?.str("category") ?: return null)
            "show_all_emails" -> Command.ShowAllEmails
            "search" -> Command.Search(args?.str("query") ?: "")

            "what_is_urgent" -> Command.WhatIsUrgent
            "how_many_unread" -> Command.HowManyUnread

            else -> null
        }
    }

    /**
     * Returns the `tools` JSON array for the Gemini WebSocket setup message.
     */
    fun toolDeclarationsJson(): JsonArray {
        val declarations = JsonArray()

        fun decl(name: String, desc: String, params: JsonObject? = null) {
            val obj = JsonObject().apply {
                addProperty("name", name)
                addProperty("description", desc)
                if (params != null) add("parameters", params)
            }
            declarations.add(obj)
        }

        fun objParams(vararg fields: Pair<String, String>): JsonObject {
            val props = JsonObject()
            for ((fieldName, fieldDesc) in fields) {
                props.add(fieldName, JsonObject().apply {
                    addProperty("type", "STRING")
                    addProperty("description", fieldDesc)
                })
            }
            return JsonObject().apply {
                addProperty("type", "OBJECT")
                add("properties", props)
            }
        }

        // Tier navigation
        decl("expand_to_notifications", "Show the notification cards overlay")
        decl("expand_to_triage", "Open the email triage/inbox view")
        decl("expand_to_focus", "Expand to the full email focus view with reader and sidebar")
        decl("collapse_to_hud", "Minimize everything back to the ambient HUD")
        decl("collapse_one_tier", "Go back one level of the interface")

        // Email selection
        decl("select_email", "Select a specific email by its ID", objParams("emailId" to "The email ID"))
        decl("select_next_unread", "Jump to the next unread email")

        // Email actions
        decl("archive_selected", "Archive the currently selected email")
        decl("archive_email", "Archive a specific email by ID", objParams("emailId" to "The email ID to archive"))
        decl("snooze_selected", "Snooze the currently selected email")
        decl("snooze_email", "Snooze a specific email", objParams(
            "emailId" to "The email ID to snooze",
            "until" to "When to resurface, e.g. 'tomorrow' or '3pm'",
        ))
        decl("star_selected", "Toggle star on the currently selected email")
        decl("forward_selected", "Forward the currently selected email")

        // TTS / reading
        decl("read_top_priority", "Read aloud the highest priority unread email summary")
        decl("read_next_unread", "Read the next unread email summary aloud")
        decl("read_email", "Read the full body of a specific email aloud", objParams("emailId" to "The email ID to read"))
        decl("read_summary", "Read the AI summary of the currently selected email aloud")
        decl("stop_tts", "Stop reading aloud immediately")
        decl("pause_tts", "Pause the current readout")
        decl("resume_tts", "Resume the paused readout")

        // Compose / reply
        decl("voice_reply", "Reply to the current email using a brief instruction",
            objParams("briefInstruction" to "Short instruction like 'say I will be there at 3'"))
        decl("confirm_send", "Confirm and send the current draft")
        decl("cancel_compose", "Cancel the current email composition")
        decl("edit_draft", "Edit the current draft with a new instruction",
            objParams("instruction" to "What to change in the draft"))

        // Filtering
        decl("filter_category", "Filter inbox by category",
            objParams("category" to "Category name: people, updates, promotions, newsletters, transactional, or all"))
        decl("show_all_emails", "Show all emails without filtering")
        decl("search", "Search emails by keyword", objParams("query" to "The search query"))

        // Utility
        decl("what_is_urgent", "List the most urgent unread emails")
        decl("how_many_unread", "Tell the user how many unread emails they have")

        val toolsArray = JsonArray()
        toolsArray.add(JsonObject().apply {
            add("functionDeclarations", declarations)
        })
        return toolsArray
    }

    private fun JsonObject.str(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString
}
