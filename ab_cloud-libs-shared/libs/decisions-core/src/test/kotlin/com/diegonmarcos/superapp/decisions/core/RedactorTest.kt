package com.diegonmarcos.superapp.decisions.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RedactorTest {
    private val rules = Policy.parse(Fixtures.block()).redact
    private val r = Redactor(rules)

    @Test fun stringsGoThroughThePatterns() {
        val out = r.clean("token sk-or-v1-0123456789abcdef x") as Redactor.Cleaned
        assertEquals("token [X] x", out.state)
        assertEquals(1, out.masked)
        assertEquals("Bearer [X]", r.clean("Bearer abc123").state)
    }

    @Test fun anUntouchedStringCountsForNothing() {
        val c = r.clean("plain")
        assertEquals("plain", c.state)
        assertEquals(0, c.masked)
        assertEquals(0, c.dropped)
    }

    @Test fun dropKeysAreRemovedWhateverTheirCase() {
        val c = r.clean(JSONObject().put("Body", "mail text").put("HTML", "<p>").put("keep", "me"))
        val o = c.state as JSONObject
        assertFalse(o.has("Body"))
        assertFalse(o.has("HTML"))
        assertEquals("me", o.getString("keep"))
        assertEquals(2, c.dropped)
    }

    @Test fun theValueOfASecretLookingKeyIsMaskedWhateverItHolds() {
        val c = r.clean(JSONObject().put("MY_TOKEN", "abc").put("passwd", 12345).put("obj", JSONObject().put("password", JSONObject().put("x", 1))).put("fine", 7))
        val o = c.state as JSONObject
        assertEquals("[X]", o.getString("MY_TOKEN"))
        assertEquals("[X]", o.getString("passwd"))
        assertEquals("[X]", o.getJSONObject("obj").getString("password"))
        assertEquals(7, o.getInt("fine"))
        assertEquals(3, c.masked)
    }

    @Test fun aSecretKeyWithNoValueStaysNull() {
        val o = r.clean(JSONObject().put("token", JSONObject.NULL)).state as JSONObject
        assertTrue(o.isNull("token"))
        assertEquals(0, r.clean(JSONObject().put("token", JSONObject.NULL)).masked)
    }

    @Test fun nestingIsWalkedAndNonStringsPassThrough() {
        val state = JSONObject()
            .put("list", JSONArray().put("sk-or-v1-0123456789abcdef").put(3).put(true).put(JSONObject().put("Body", "x").put("s", "Bearer q")))
            .put("n", 1.5).put("b", false).put("z", JSONObject.NULL)
        val c = r.clean(state)
        val o = c.state as JSONObject
        val l = o.getJSONArray("list")
        assertEquals("[X]", l.getString(0))
        assertEquals(3, l.getInt(1))
        assertEquals(true, l.getBoolean(2))
        assertFalse(l.getJSONObject(3).has("Body"))
        assertEquals("Bearer [X]", l.getJSONObject(3).getString("s"))
        assertEquals(1.5, o.getDouble("n"), 0.0)
        assertEquals(false, o.getBoolean("b"))
        assertTrue(o.isNull("z"))
        assertEquals(2, c.masked)
        assertEquals(1, c.dropped)
    }

    @Test fun theCallersObjectIsNeverMutated() {
        val state = JSONObject().put("Body", "x").put("token", "t").put("s", "sk-or-v1-0123456789abcdef")
        r.clean(state)
        assertEquals("x", state.getString("Body"))
        assertEquals("t", state.getString("token"))
        assertEquals("sk-or-v1-0123456789abcdef", state.getString("s"))
    }

    @Test fun nullAndNumbersAreStates() {
        assertNull(r.clean(null).state)
        assertEquals(5, r.clean(5).state)
    }

    @Test fun theCapIsInclusiveAndCountsTheSerialisedState() {
        val at = JSONObject().put("s", "x".repeat(192))
        assertEquals(200, at.toString().length)
        assertTrue(r.fits(at))
        assertFalse(r.fits(JSONObject().put("s", "x".repeat(193))))
        assertTrue(r.fits("x".repeat(200)))
        assertFalse(r.fits("x".repeat(201)))
    }
}
