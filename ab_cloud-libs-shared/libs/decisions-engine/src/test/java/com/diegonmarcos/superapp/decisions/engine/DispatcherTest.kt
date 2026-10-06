package com.diegonmarcos.superapp.decisions.engine

import com.diegonmarcos.superapp.decisions.Http
import com.diegonmarcos.superapp.decisions.core.AnswerCache
import com.diegonmarcos.superapp.decisions.core.CircuitBreaker
import com.diegonmarcos.superapp.decisions.core.ConsentBook
import com.diegonmarcos.superapp.decisions.core.Engine
import com.diegonmarcos.superapp.decisions.core.Env
import com.diegonmarcos.superapp.decisions.core.Journal
import com.diegonmarcos.superapp.decisions.core.Ledger
import com.diegonmarcos.superapp.decisions.core.MemorySink
import com.diegonmarcos.superapp.decisions.core.MemoryStore
import com.diegonmarcos.superapp.decisions.core.Policy
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The service's three methods over the engine that ships: the REAL decisions.json (so a declaration that
 * stops parsing, or a probe that stops being servable, turns this red), a fake network and no Android.
 */
class DispatcherTest {
    private val self = "com.diegonmarcos.cloudlib.decisionsengine"
    private val policy = Policy.parse(JSONObject(File("src/main/assets/decisions.json").readText()))
    private var posts = 0
    private val http = object : Http {
        override fun send(url: String, token: String?, body: String?, timeoutMs: Int): Http.Response {
            posts++
            val q = JSONObject(body!!).getJSONObject("questions")
            val answers = JSONObject()
            for (id in q.keys()) answers.put(id, JSONObject().put("type", "noul").put("noul", 0.97))
            return Http.Response(200, JSONObject().put("answers", answers).toString())
        }
    }
    private val env = object : Env {
        override fun online() = true
        override fun metered() = false
        override fun batterySaver() = false
    }
    private val clock = { 1_800_000_000_000L }
    private val engine = Engine(policy, http, { "token" }, env, Ledger(MemoryStore(), policy.budget, clock),
        AnswerCache(MemoryStore(), policy.cacheMax, clock), CircuitBreaker(3, 1000, clock), ConsentBook(MemoryStore()),
        Journal(MemorySink(), policy.journalMax, clock), clock)
    private val d = Dispatcher { engine }

    private val probe = JSONObject().put("use", "probe").put("state", JSONObject().put("statement", "2 + 2 = 4"))
        .put("questions", JSONObject().put("correct", JSONObject().put("type", "noul").put("instructions", "It is correct.")))

    @Test fun decideRunsTheEngineForTheCallerTheBinderNamed() {
        val ok = JSONObject(d.decide(self, probe.toString()))
        assertTrue(ok.toString(), ok.getBoolean("ok"))
        assertEquals(1, posts)
        val other = JSONObject(d.decide("com.some.other.app", probe.toString()))
        assertFalse(other.getBoolean("ok"))
        assertEquals("app_not_allowed", other.getString("reason"))
        assertEquals("app_not_allowed", JSONObject(d.decide("", probe.toString())).getString("reason"))
        assertEquals(1, posts)
    }

    @Test fun statusIsAnsweredForTheCaller() {
        val s = JSONObject(d.status(self))
        assertTrue(s.getBoolean("ok"))
        assertTrue(s.getJSONObject("uses").getJSONObject("probe").getBoolean("allowed_for_app"))
        assertFalse(JSONObject(d.status("com.some.other.app")).getJSONObject("uses").getJSONObject("probe").getBoolean("allowed_for_app"))
    }

    @Test fun consentIsAcceptedFromTheSettingAppOnly() {
        val req = JSONObject().put("app", self).put("use", "probe").put("granted", false).toString()
        assertEquals("not_a_consent_setter", JSONObject(d.consent(self, req)).getString("reason"))
        assertTrue(JSONObject(d.consent("com.diegonmarcos.superapp", req)).getBoolean("ok"))
        // the probe is implicit-consent, but a recorded NO is respected
        assertEquals("no_consent", JSONObject(d.decide(self, probe.toString())).getString("reason"))
    }

    @Test fun withoutAPolicyEveryMethodIsNoPolicy() {
        val none = Dispatcher { null }
        for (reply in listOf(none.decide(self, "{}"), none.status(self), none.consent(self, "{}"))) {
            val r = JSONObject(reply)
            assertFalse(r.getBoolean("ok"))
            assertEquals("no_policy", r.getString("reason"))
        }
    }
}
