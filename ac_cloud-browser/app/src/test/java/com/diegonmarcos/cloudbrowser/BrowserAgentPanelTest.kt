package com.diegonmarcos.cloudbrowser

import com.diegonmarcos.superapp.browser.AgentLine
import com.diegonmarcos.superapp.browser.AgentPanelText
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #823 what the side panel shows for each answer of the runner. */
class BrowserAgentPanelTest {

    @Test
    fun `an answer is the assistant's line`() {
        val l = AgentPanelText.lineFor(JSONObject().put("status", "answered").put("message", "Done."))!!
        assertEquals(AgentLine.ASSISTANT, l.role); assertEquals("Done.", l.text)
    }

    @Test
    fun `a waiting action adds no text - its consent card is what shows`() {
        assertNull(AgentPanelText.lineFor(JSONObject().put("status", "pending_confirmation").put("confirm", "The assistant wants to click")))
    }

    @Test
    fun `a failure is a note with its reason, never silence`() {
        val l = AgentPanelText.lineFor(JSONObject().put("status", "failed").put("error", "no openrouter token in the fleet Account"))!!
        assertEquals(AgentLine.NOTE, l.role); assertTrue(l.text.contains("no openrouter token"))
        assertEquals(AgentLine.NOTE, AgentPanelText.lineFor(JSONObject().put("ok", false).put("error", "x"))!!.role)
    }

    @Test
    fun `his decision is written into the transcript`() {
        assertTrue(AgentPanelText.decisionLine(false, "click a").text.startsWith("Denied: "))
        assertTrue(AgentPanelText.decisionLine(true, "click a").text.startsWith("Allowed: "))
    }

    @Test
    fun `a summary shows its text and where it came from`() {
        val ok = AgentPanelText.summaryLines(JSONObject().put("ok", true).put("summary", "• a").put("route", "on_device")
            .put("engine", "extractive").put("fell_back", true).put("reason", "offline"))
        assertEquals(listOf(AgentLine.ASSISTANT, AgentLine.NOTE), ok.map { it.role })
        assertTrue(ok[1].text.contains("on device") && ok[1].text.contains("offline"))
        val bad = AgentPanelText.summaryLines(JSONObject().put("ok", false).put("error", "no page is open"))
        assertEquals(1, bad.size); assertTrue(bad[0].text.contains("no page is open"))
    }
}
