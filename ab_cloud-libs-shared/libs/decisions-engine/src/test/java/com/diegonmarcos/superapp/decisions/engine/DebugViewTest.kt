package com.diegonmarcos.superapp.decisions.engine

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** /api/decisions says names and marks, and nothing that was asked, answered, spent or whose. */
class DebugViewTest {
    private val status = JSONObject("""
        {"ok":true,"contract":1,"token":true,"breaker_open":false,"online":true,"metered":true,"battery_saver":false,
         "spent_today_usd":0.123,"daily_usd_cap":0.5,
         "uses":{"probe":{"enabled":true,"class":"background","allowed_for_app":true,"consent":true},
                 "off":{"enabled":false,"class":"gating","allowed_for_app":true,"consent":false},
                 "foreign":{"enabled":true,"class":"gating","allowed_for_app":false,"consent":true}},
         "rejected":["bad"]}""")

    @Test fun statusIsMarksAndNames() {
        val v = DebugView.status(status)
        assertEquals("✓", v.getString("token"))
        assertEquals("✓", v.getString("online"))
        assertEquals("✓", v.getString("metered"))
        assertEquals("✗", v.getString("battery_saver"))
        assertEquals("✗", v.getString("breaker_open"))
        assertEquals("✓", v.getJSONObject("uses").getString("probe"))
        assertEquals("✗", v.getJSONObject("uses").getString("off"))
        assertEquals("✗", v.getJSONObject("uses").getString("foreign"))
        assertEquals("bad", v.getJSONArray("rejected").getString(0))
        assertFalse(v.toString().contains("0.123"))
        assertFalse(v.toString().contains("0.5"))
        assertFalse(v.toString().contains("background"))
    }

    @Test fun anEmptyStatusIsAllCrosses() {
        val v = DebugView.status(JSONObject())
        assertEquals("✗", v.getString("token"))
        assertEquals(0, v.getJSONObject("uses").length())
        assertEquals(0, v.getJSONArray("rejected").length())
    }

    @Test fun theJournalIsItsLines() {
        assertEquals(JSONArray(listOf("a b ✓")).toString(), DebugView.journal(listOf("a b ✓")).getJSONArray("lines").toString())
    }

    @Test fun aProbeIsAMarkAndTheReasonWhenItFailed() {
        val ok = DebugView.probe(JSONObject().put("ok", true).put("results", JSONObject().put("correct", JSONObject().put("p", 0.9))))
        assertEquals("✓", ok.getString("probe"))
        assertFalse(ok.has("reason"))
        assertFalse(ok.toString().contains("0.9"))
        val bad = DebugView.probe(JSONObject().put("ok", false).put("reason", "no_key"))
        assertEquals("✗", bad.getString("probe"))
        assertEquals("no_key", bad.getString("reason"))
        assertTrue(DebugView.probe(JSONObject()).getString("probe") == "✗")
    }
}
