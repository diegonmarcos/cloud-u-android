package com.diegonmarcos.superapp.decisions.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PolicyTest {

    private fun policy(tweak: (JSONObject) -> Unit) = Policy.parse(Fixtures.block(tweak))

    private fun use(d: JSONObject, name: String = "ui") = d.getJSONObject("uses").getJSONObject(name)

    private fun rejects(message: String, tweak: (JSONObject) -> Unit) {
        try {
            policy(tweak)
            fail("expected a rejection: $message")
        } catch (e: IllegalArgumentException) {
            assertTrue("'${e.message}' should mention '$message'", e.message!!.contains(message))
        }
    }

    private fun rejectedUse(tweak: (JSONObject) -> Unit): String? = policy { tweak(use(it)) }.rejected["ui"]

    @Test fun theDeclaredShapeParses() {
        val p = Policy.parse(Fixtures.block())
        assertEquals("https://example.test/decisions", p.endpoint)
        assertEquals("m/1", p.model)
        assertEquals("openrouter", p.provider)
        assertEquals(8000, p.timeoutMs)
        assertEquals(0.01, p.budget.dailyUsdCap, 0.0)
        assertEquals(3, p.budget.perAppDailyCalls)
        assertEquals(0.001, p.budget.estCallUsd, 0.0)
        assertEquals(2, p.breaker.failures)
        assertEquals(60L, p.breaker.openS)
        assertEquals(200, p.redact.maxChars)
        assertEquals("[X]", p.redact.mask)
        assertEquals(setOf("body", "html"), p.redact.dropKeys)
        assertEquals(2, p.redact.patterns.size)
        assertEquals(setOf("mail", "git"), p.sensitiveContent)
        assertEquals(setOf(Fixtures.SETTER), p.consentSetters)
        assertEquals(2, p.cacheMax)
        assertEquals(4, p.journalMax)
        assertTrue(p.suppress.metered && p.suppress.offline && p.suppress.batterySaver)
        assertTrue(p.rejected.isEmpty())
        assertEquals(setOf("ui", "bg", "gate", "mailuse", "off"), p.uses.keys)
    }

    @Test fun aUseKeepsEveryDeclaredField() {
        val u = Policy.parse(Fixtures.block()).uses.getValue("ui")
        assertEquals("ui", u.name)
        assertTrue(u.enabled)
        assertEquals(0.8, u.threshold, 0.0)
        assertEquals(setOf("a", "b"), u.allowed)
        assertEquals(UseClass.USER_FACING, u.cls)
        assertEquals(0L, u.ttlS)
        assertEquals(2, u.maxCallsPerHour)
        assertFalse(u.consentRequired)
        assertEquals("none", u.content)
        assertEquals(setOf(Fixtures.APP), u.apps)
        val bg = Policy.parse(Fixtures.block()).uses.getValue("bg")
        assertEquals(60L, bg.ttlS)
        assertEquals(UseClass.BACKGROUND, bg.cls)
        assertTrue(Policy.parse(Fixtures.block()).uses.getValue("mailuse").consentRequired)
        assertFalse(Policy.parse(Fixtures.block()).uses.getValue("off").enabled)
    }

    @Test fun aUseDefaultsToDisabledAndToRequiringConsent() {
        val p = policy { d -> d.getJSONObject("uses").put("bare", JSONObject()
            .put("class", "gating").put("threshold", 0.5).put("max_calls_per_hour", 1).put("apps", org.json.JSONArray().put(Fixtures.APP))) }
        val u = p.uses.getValue("bare")
        assertFalse(u.enabled)
        assertTrue(u.consentRequired)
        assertTrue(u.allowed.isEmpty())
        assertEquals("", u.content)
    }

    @Test fun theRealManifestDeclaresAServablePolicy() {
        val p = Policy.parse(Fixtures.realManifest())
        assertTrue("every declared use validates: ${p.rejected}", p.rejected.isEmpty())
        assertTrue(p.endpoint.startsWith("https://openrouter.ai/"))
        assertEquals("typesafe/jev-1.13", p.model)
        assertEquals("openrouter", p.provider)
        assertTrue(p.budget.dailyUsdCap > 0 && p.budget.perAppDailyCalls > 0)
        assertEquals(setOf("mail", "git"), p.sensitiveContent)
        assertEquals(11, p.redact.patterns.size)
        assertNotNull(p.uses["probe"])
        assertEquals(UseClass.BACKGROUND, p.uses.getValue("probe").cls)
        assertTrue(p.consentSetters.contains("com.diegonmarcos.superapp"))
        for (u in p.uses.values) {
            if (u.content in p.sensitiveContent) assertTrue("${u.name} reads sensitive content", u.consentRequired)
            if (u.cls == UseClass.BACKGROUND) assertTrue("${u.name} caches", u.ttlS > 0)
        }
    }

    @Test fun theRealRedactionPatternsMaskWhatTheyAreMeantTo() {
        val r = Redactor(Policy.parse(Fixtures.realManifest()).redact)
        val samples = listOf(
            "key sk-or-v1-0123456789abcdef0123456789abcdef end",
            "-----BEGIN OPENSSH PRIVATE KEY-----\nabc\n-----END OPENSSH PRIVATE KEY-----",
            "AGE-SECRET-KEY-1ABCDEF0123456789",
            "ENC[AES256_GCM,data:abc,iv:def]",
            "ghp_0123456789abcdefghijABCDEFGHIJ",
            "AKIAABCDEFGHIJKLMNOP",
            "xoxb-1234567890-abcdef",
            "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.abcdefghijklmnop",
        )
        for (s in samples) assertTrue("masked: $s", r.redactString(s).contains("[REDACTED]") && !r.redactString(s).contains("0123456789abcdef0123456789abcdef"))
        assertEquals("Authorization: Bearer [REDACTED]", r.redactString("Authorization: Bearer abc.def.ghi"))
        assertEquals("OPENROUTER_API_KEY=[REDACTED]", r.redactString("OPENROUTER_API_KEY=supersecretvalue"))
        assertEquals("https://user:[REDACTED]@host/x", r.redactString("https://user:hunter2pw@host/x"))
        assertEquals("nothing secret in this text", r.redactString("nothing secret in this text"))
    }

    @Test fun aBlockThatIsNotUsableThrows() {
        try {
            Policy.parse(JSONObject())
            fail("an empty document")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("https"))
        }
        rejects("https") { it.put("endpoint", "http://example.test/d") }
        rejects("https") { it.put("endpoint", "") }
        rejects("model") { it.put("model", " ") }
        rejects("provider") { it.put("provider", "") }
        rejects("timeout_ms") { it.put("timeout_ms", 999) }
        rejects("timeout_ms") { it.put("timeout_ms", 60001) }
        rejects("budget") { it.remove("budget") }
        rejects("suppress") { it.remove("suppress") }
        rejects("breaker") { it.remove("breaker") }
        rejects("redact") { it.remove("redact") }
        rejects("daily_usd_cap") { it.getJSONObject("budget").put("daily_usd_cap", 0) }
        rejects("per_app_daily_calls") { it.getJSONObject("budget").put("per_app_daily_calls", -1) }
        rejects("est_call_usd") { it.getJSONObject("budget").remove("est_call_usd") }
        rejects("failures") { it.getJSONObject("breaker").put("failures", 0) }
        rejects("open_s") { it.getJSONObject("breaker").put("open_s", 0) }
        rejects("max_chars") { it.getJSONObject("redact").put("max_chars", 0) }
        rejects("mask") { it.getJSONObject("redact").put("mask", "") }
        rejects("secret_key") { it.getJSONObject("redact").put("secret_key", "") }
    }

    @Test fun theLimitsOfABlockAreInclusive() {
        policy { it.put("timeout_ms", 1000) }
        policy { it.put("timeout_ms", 60000) }
        assertEquals(1, policy { it.getJSONObject("budget").put("per_app_daily_calls", 1) }.budget.perAppDailyCalls)
    }

    @Test fun aUseThatDoesNotValidateIsRejectedWithItsReasonNotServed() {
        assertTrue(rejectedUse { it.put("class", "nope") }!!.contains("class"))
        assertTrue(rejectedUse { it.remove("class") }!!.contains("class"))
        assertTrue(rejectedUse { it.put("threshold", 0.0) }!!.contains("threshold"))
        assertTrue(rejectedUse { it.put("threshold", 1.01) }!!.contains("threshold"))
        assertTrue(rejectedUse { it.remove("threshold") }!!.contains("threshold"))
        assertTrue(rejectedUse { it.put("max_calls_per_hour", 0) }!!.contains("max_calls_per_hour"))
        assertTrue(rejectedUse { it.remove("max_calls_per_hour") }!!.contains("max_calls_per_hour"))
        assertTrue(rejectedUse { it.put("ttl_s", -1) }!!.contains("ttl_s"))
        assertTrue(rejectedUse { it.put("apps", org.json.JSONArray()) }!!.contains("apps"))
        assertTrue(rejectedUse { it.remove("apps") }!!.contains("apps"))
        assertTrue(rejectedUse { it.put("consent", "maybe") }!!.contains("consent"))
        assertTrue(rejectedUse { it.put("content", "mail").put("consent", "implicit") }!!.contains("mail"))
        assertTrue(rejectedUse { it.put("content", "git").put("consent", "implicit") }!!.contains("git"))
        val p = policy { it.getJSONObject("uses").put("ui", "not an object") }
        assertTrue(p.rejected.getValue("ui").contains("object"))
        assertFalse(p.uses.containsKey("ui"))
    }

    @Test fun aBackgroundUseMustCacheButOthersMayNot() {
        val bg = policy { it.getJSONObject("uses").getJSONObject("bg").put("ttl_s", 0) }
        assertTrue(bg.rejected.getValue("bg").contains("ttl_s"))
        assertTrue(policy { it.getJSONObject("uses").getJSONObject("bg").put("ttl_s", 1) }.rejected.isEmpty())
        assertTrue(policy { use(it).put("ttl_s", 0) }.rejected.isEmpty())
        assertTrue(policy { use(it).put("threshold", 1.0) }.rejected.isEmpty())
        assertTrue(policy { use(it).put("max_calls_per_hour", 1) }.rejected.isEmpty())
    }

    @Test fun sensitiveContentNeedsConsentButOtherContentMayBeImplicit() {
        assertTrue(policy { use(it).put("content", "calc").put("consent", "implicit") }.rejected.isEmpty())
        assertTrue(policy { use(it).put("content", "mail").put("consent", "required") }.rejected.isEmpty())
        assertTrue(policy { use(it).put("content", "mail").remove("consent") }.rejected.isEmpty())
    }

    @Test fun commentKeysInUsesAreNotUses() {
        val p = policy { it.getJSONObject("uses").put("_doc", "text") }
        assertFalse(p.uses.containsKey("_doc"))
        assertFalse(p.rejected.containsKey("_doc"))
    }
}
