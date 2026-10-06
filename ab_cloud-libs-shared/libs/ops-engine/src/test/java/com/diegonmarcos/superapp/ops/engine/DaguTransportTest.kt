package com.diegonmarcos.superapp.ops.engine

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the Dagu page used to parse inside DaguClient (pre-#871), EXECUTED against both payload
 * shapes Dagu has served, and the engine's https-and-own-hosts-only refusal.
 */
class DaguTransportTest {

    @Test fun currentSchemaIsParsed() {
        val body = """{"dags":[{"dag":{"name":"backup","displayName":"Nightly backup","description":"d",
            "schedule":[{"expression":"0 3 * * *","kind":"cron"}]},"fileName":"backup-file",
            "latestDAGRun":{"status":4,"startedAt":"2026-06-10T12:00:00Z","finishedAt":"2026-06-10T12:34:56Z"}}]}"""
        val d = DaguTransport.dagList(body).getJSONObject(0)
        assertEquals("backup", d.getString("name"))
        assertEquals("backup-file", d.getString("fileName"))
        assertEquals("Nightly backup", d.getString("displayLabel"))
        assertEquals("0 3 * * *", d.getString("schedule"))
        val run = d.getJSONObject("lastRun")
        assertEquals(4, run.getInt("status"))
        assertEquals(1781094896000L, run.getLong("finishedAtMs"))
        assertEquals(1781092800000L, run.getLong("startedAtMs"))
    }

    @Test fun legacySchemaIsParsed() {
        val body = """{"DAGs":[{"Config":{"Name":"old","Schedule":"*/5 * * * *","Description":"x"},
            "Status":{"Status":2,"FinishedAt":"2026-06-10 12:34:56"}}]}"""
        val d = DaguTransport.dagList(body).getJSONObject(0)
        assertEquals("old", d.getString("name"))
        assertEquals("old", d.getString("fileName"))
        assertEquals("*/5 * * * *", d.getString("schedule"))
        assertEquals(2, d.getJSONObject("lastRun").getInt("status"))
    }

    @Test fun aDagThatNeverRanHasNoLastRun() {
        val d = DaguTransport.dagList("""{"dags":[{"dag":{"name":"n"}}]}""").getJSONObject(0)
        assertFalse(d.has("lastRun"))
        assertEquals(0L, DaguTransport.parseEpochMs("0001-01-01T00:00:00Z"))
    }

    @Test fun pathSegmentsArePercentEncoded() {
        assertEquals("a%20b%2Fc", DaguTransport.encodePathSegment("a b/c"))
    }

    @Test fun onlyHttpsToTheFleetsOwnOpsHostIsAllowed() {
        val hosts = listOf("workflows.example.org")
        assertNull(DaguTransport.refusal("https://workflows.example.org/api/v1/dags", hosts))
        assertNotNull("plain http", DaguTransport.refusal("http://workflows.example.org/api/v1/dags", hosts))
        assertNotNull("another host", DaguTransport.refusal("https://evil.example.net/api/v1/dags", hosts))
        assertNotNull("a host that merely starts with ours", DaguTransport.refusal("https://workflows.example.org.evil.net/x", hosts))
        assertNotNull("not a url", DaguTransport.refusal("not a url", hosts))
    }

    @Test fun aCallToAnotherHostIsRefusedBeforeAnyConnection() {
        val answer = JSONObject(DaguTransport.list(JSONObject().put("server", "https://evil.example.net").put("token", "t")))
        assertFalse(answer.getBoolean("ok"))
        assertTrue(answer.getString("error"), answer.getString("error").startsWith("refused:"))
    }

    @Test fun startWithoutANameIsAnErrorNotACall() {
        val answer = JSONObject(DaguTransport.start(JSONObject().put("server", "https://x").put("token", "t")))
        assertFalse(answer.getBoolean("ok"))
    }
}
