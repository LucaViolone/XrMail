package com.xremail.app.voice

import com.google.firebase.ai.type.FunctionCallPart
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Maps Gemini Live [FunctionCallPart]s to [EmailCommandTool.Command] and JSON confirmations for the model.
 */
object VoiceCommandParser {

    data class VoiceToolResult(
        val command: EmailCommandTool.Command?,
        val modelResponse: JsonObject,
    )

    fun handleFunctionCall(
        functionCall: FunctionCallPart,
        isVoiceSendArmed: () -> Boolean,
    ): VoiceToolResult {
        return parseToolInvocation(functionCall.name, functionCall.args, isVoiceSendArmed)
    }

    internal fun parseToolInvocation(
        name: String,
        args: Map<String, JsonElement>,
        isVoiceSendArmed: () -> Boolean = { true },
    ): VoiceToolResult {
        return when (name) {
            "select_email" -> {
                val id = args.str("emailId")
                if (id.isBlank()) {
                    VoiceToolResult(null, errorPayload("emailId required"))
                } else {
                    VoiceToolResult(
                        EmailCommandTool.Command.SelectEmail(id),
                        successPayload("Opened email $id"),
                    )
                }
            }
            "archive_email" -> {
                val id = args.str("emailId")
                if (id.isBlank()) VoiceToolResult(null, errorPayload("emailId required"))
                else VoiceToolResult(
                    EmailCommandTool.Command.ArchiveEmail(id),
                    successPayload("Archived $id"),
                )
            }
            "snooze_email" -> {
                val id = args.str("emailId")
                val until = args.str("until")
                if (id.isBlank()) VoiceToolResult(null, errorPayload("emailId required"))
                else VoiceToolResult(
                    EmailCommandTool.Command.SnoozeEmail(id, until),
                    successPayload("Snoozed $id"),
                )
            }
            "forward_email" -> {
                val id = args.str("emailId")
                val to = args.str("to")
                if (id.isBlank() || to.isBlank()) {
                    VoiceToolResult(null, errorPayload("emailId and to required"))
                } else {
                    VoiceToolResult(
                        EmailCommandTool.Command.ForwardEmail(id, to),
                        successPayload("Forwarding to $to"),
                    )
                }
            }
            "reply" -> {
                val id = args.str("emailId")
                val body = args.str("body").ifBlank { null }
                if (id.isBlank()) VoiceToolResult(null, errorPayload("emailId required"))
                else VoiceToolResult(
                    EmailCommandTool.Command.Reply(id, body),
                    successPayload("Reply draft updated"),
                )
            }
            "search" -> {
                val q = args.str("query")
                if (q.isBlank()) VoiceToolResult(null, errorPayload("query required"))
                else VoiceToolResult(
                    EmailCommandTool.Command.Search(q),
                    successPayload("Searching"),
                )
            }
            "read_aloud" -> {
                val id = args.str("emailId")
                if (id.isBlank()) VoiceToolResult(null, errorPayload("emailId required"))
                else VoiceToolResult(
                    EmailCommandTool.Command.ReadAloud(id),
                    successPayload("Reading"),
                )
            }
            "summarize" -> {
                val id = args.str("emailId")
                if (id.isBlank()) VoiceToolResult(null, errorPayload("emailId required"))
                else VoiceToolResult(
                    EmailCommandTool.Command.Summarize(id),
                    successPayload("Summarizing"),
                )
            }
            "draft_reply" -> {
                val id = args.str("emailId")
                val tone = args.str("tone").ifBlank { null }
                if (id.isBlank()) VoiceToolResult(null, errorPayload("emailId required"))
                else VoiceToolResult(
                    EmailCommandTool.Command.DraftReply(id, tone),
                    successPayload("Draft ready for review"),
                )
            }
            "send_draft" -> {
                if (!isVoiceSendArmed()) {
                    VoiceToolResult(
                        null,
                        errorPayload(
                            "Send not armed. Call arm_send_for_voice after the user agrees to send, " +
                                "then send_draft only after explicit confirmation.",
                        ),
                    )
                } else {
                    val id = args.str("emailId")
                    if (id.isBlank()) VoiceToolResult(null, errorPayload("emailId required"))
                    else VoiceToolResult(
                        EmailCommandTool.Command.SendDraft(id),
                        successPayload("Sending draft"),
                    )
                }
            }
            "filter_category" -> {
                val c = args.str("category")
                if (c.isBlank()) VoiceToolResult(null, errorPayload("category required"))
                else VoiceToolResult(
                    EmailCommandTool.Command.FilterCategory(c),
                    successPayload("Filter applied"),
                )
            }
            "show_inbox" -> VoiceToolResult(
                EmailCommandTool.Command.ShowInbox,
                successPayload("Showing inbox"),
            )
            "go_back" -> VoiceToolResult(
                EmailCommandTool.Command.GoBack,
                successPayload("Going back"),
            )
            "set_compose_body" -> {
                val body = args.str("body")
                if (body.isBlank()) VoiceToolResult(null, errorPayload("body required"))
                else VoiceToolResult(
                    EmailCommandTool.Command.SetComposeBody(body),
                    successPayload("Compose body updated"),
                )
            }
            "arm_send_for_voice" -> VoiceToolResult(
                EmailCommandTool.Command.ArmSendForVoice,
                successPayload("Send armed; wait for explicit confirmation before send_draft"),
            )
            else -> VoiceToolResult(null, errorPayload("Unknown tool: $name"))
        }
    }

    private fun successPayload(message: String) = JsonObject(
        mapOf(
            "success" to JsonPrimitive(true),
            "message" to JsonPrimitive(message),
        ),
    )

    private fun errorPayload(message: String) = JsonObject(
        mapOf(
            "success" to JsonPrimitive(false),
            "error" to JsonPrimitive(message),
        ),
    )

    private fun Map<String, JsonElement>.str(key: String): String =
        this[key]?.jsonPrimitive?.contentOrNull ?: ""
}
