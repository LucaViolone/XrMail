package com.xremail.app.voice

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCommandParserTest {

    @Test
    fun sendDraftRejectedWhenNotArmed() {
        val r = VoiceCommandParser.parseToolInvocation(
            name = "send_draft",
            args = mapOf("emailId" to JsonPrimitive("1")),
            isVoiceSendArmed = { false },
        )
        assertNull(r.command)
        assertEquals(false, r.modelResponse["success"]!!.jsonPrimitive.content.toBooleanStrict())
    }

    @Test
    fun sendDraftAcceptedWhenArmed() {
        val r = VoiceCommandParser.parseToolInvocation(
            name = "send_draft",
            args = mapOf("emailId" to JsonPrimitive("2")),
            isVoiceSendArmed = { true },
        )
        assertNotNull(r.command)
        assertTrue(r.command is EmailCommandTool.Command.SendDraft)
        assertEquals("2", (r.command as EmailCommandTool.Command.SendDraft).emailId)
        assertEquals(true, r.modelResponse["success"]!!.jsonPrimitive.content.toBooleanStrict())
    }

    @Test
    fun armSendEmitsCommand() {
        val r = VoiceCommandParser.parseToolInvocation(
            name = "arm_send_for_voice",
            args = emptyMap(),
        )
        assertEquals(EmailCommandTool.Command.ArmSendForVoice, r.command)
    }

    @Test
    fun setComposeBody() {
        val r = VoiceCommandParser.parseToolInvocation(
            name = "set_compose_body",
            args = mapOf("body" to JsonPrimitive("Hello there")),
        )
        assertTrue(r.command is EmailCommandTool.Command.SetComposeBody)
        assertEquals("Hello there", (r.command as EmailCommandTool.Command.SetComposeBody).body)
    }

    @Test
    fun summarize() {
        val r = VoiceCommandParser.parseToolInvocation(
            name = "summarize",
            args = mapOf("emailId" to JsonPrimitive("3")),
        )
        assertTrue(r.command is EmailCommandTool.Command.Summarize)
    }
}
