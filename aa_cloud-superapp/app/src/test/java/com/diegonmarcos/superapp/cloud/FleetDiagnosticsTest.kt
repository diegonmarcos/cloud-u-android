package com.diegonmarcos.superapp.cloud

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The fleet-diagnostics bridge (cloud.app :8003 reads this app's About, Logcat and Diagnose):
 *
 *   D1 exactly three methods, each answering; anything else is refused with a reason, never an exception
 *   D2 About leaves out the build-time trees (they blow Binder's budget) and keeps the rest
 *   D3 the logcat argument is clamped and the flags are read, whatever the caller sends
 *   D4 a reply over the budget is cut at a line boundary from the OLD end, and a JSON one is refused outright
 *   D5 the provider is declared exported behind the signature permission under `${applicationId}.fleetdiag`,
 *      is read-only, and re-checks the caller itself
 *   D6 the consumer's declaration (cloud-c3-webserver build.json::serving.bridge) names this very authority
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FleetDiagnosticsTest {

    private val calls = mutableListOf<String>()
    private val src = FleetDiagnostics.Source(
        about = { """{"app":{"versionName":"1.2"},"device":{"model":"A37"},"trees":{"ast":"x"}}""" },
        logcat = { n, all, err -> calls += "logcat n=$n all=$all err=$err"; "I/Tag: hello\nE/Tag: boom\n" },
        diagnose = { """{"build":"abc","edge":{"service_running":true}}""" },
    )

    @Test
    fun `D1 three methods answer and anything else is refused with a reason`() {
        for (m in FleetDiagnostics.METHODS) assertTrue("$m must answer", FleetDiagnostics.answer(m, "", src).ok)
        assertEquals(listOf("about", "logcat", "diagnose"), FleetDiagnostics.METHODS)
        val bad = FleetDiagnostics.answer("exec", "rm -rf /", src)
        assertFalse(bad.ok)
        assertTrue(bad.error.contains("unknown method"))
        val boom = FleetDiagnostics.answer("diagnose", "", FleetDiagnostics.Source({ "" }, { _, _, _ -> "" }, { error("no activity") }))
        assertFalse(boom.ok)
        assertTrue("an exception becomes a reason: ${boom.error}", boom.error.contains("no activity"))
    }

    @Test
    fun `D2 about drops the trees and keeps the rest`() {
        val a = FleetDiagnostics.answer("about", "", src)
        assertEquals("application/json", a.mime)
        val o = JSONObject(a.payload)
        assertFalse(o.has("trees"))
        assertEquals("1.2", o.getJSONObject("app").getString("versionName"))
        assertEquals("A37", o.getJSONObject("device").getString("model"))
    }

    @Test
    fun `D3 logcat reads n and the flags, clamped`() {
        FleetDiagnostics.answer("logcat", "n=50&scope=all&errors=1", src)
        FleetDiagnostics.answer("logcat", "n=99999999", src)
        FleetDiagnostics.answer("logcat", "n=-3&scope=everything&errors=yes", src)
        FleetDiagnostics.answer("logcat", null, src)
        assertEquals(
            listOf(
                "logcat n=50 all=true err=true",
                "logcat n=${FleetDiagnostics.LOG_LINES_MAX} all=false err=false",
                "logcat n=1 all=false err=false",
                "logcat n=${FleetDiagnostics.LOG_LINES_DEFAULT} all=false err=false",
            ),
            calls,
        )
        assertEquals("text/plain", FleetDiagnostics.answer("logcat", "", src).mime)
    }

    @Test
    fun `D4 a reply over the budget is bounded`() {
        val line = "I/Tag: " + "x".repeat(92) + "\n"
        val big = line.repeat(FleetDiagnostics.MAX_PAYLOAD / line.length + 500)
        val cut = FleetDiagnostics.clampTail(big)
        assertTrue(cut.length <= FleetDiagnostics.MAX_PAYLOAD)
        assertTrue(cut.startsWith("(older lines dropped"))
        assertTrue("cut at a line boundary", cut.lines().drop(1).all { it.isEmpty() || it.startsWith("I/Tag: ") })
        assertEquals("a small log is untouched", "a\nb\n", FleetDiagnostics.clampTail("a\nb\n"))
        val huge = FleetDiagnostics.Source({ "{}" }, { _, _, _ -> "" }, { """{"k":"${"y".repeat(FleetDiagnostics.MAX_PAYLOAD)}"}""" })
        val a = FleetDiagnostics.answer("diagnose", "", huge)
        assertFalse(a.ok)
        assertTrue(a.error.contains("over the"))
    }

    private fun repoFile(rel: String): File {
        var d: File? = File("").absoluteFile
        while (d != null) { File(d, rel).takeIf { it.isFile }?.let { return it }; d = d.parentFile }
        error("$rel not found above ${File("").absolutePath}")
    }

    @Test
    fun `D5 the provider is exported behind the signature permission and read-only`() {
        val manifest = repoFile("aa_cloud-superapp/app/src/main/AndroidManifest.xml").readText()
        val decl = Regex("""<provider\s+android:name="\.cloud\.FleetDiagnosticsProvider"[^>]*/>""").find(manifest)?.value
            ?: error("FleetDiagnosticsProvider is not declared in the manifest")
        assertTrue(decl, decl.contains("""android:authorities="${'$'}{applicationId}.fleetdiag""""))
        assertTrue(decl, decl.contains("""android:exported="true""""))
        assertTrue(decl, decl.contains("""android:permission="com.diegonmarcos.cloud.permission.CONSTELLATION_DATA""""))
        val src = repoFile("aa_cloud-superapp/app/src/main/java/com/diegonmarcos/superapp/cloud/FleetDiagnosticsProvider.kt").readText()
        assertTrue("call() re-checks the caller", src.contains("checkCallingPermission(FleetConfig.PERMISSION)"))
        for (writer in listOf("insert", "delete", "update", "query"))
            assertTrue("$writer must do nothing", Regex("""override fun $writer\(.*\)(: [A-Za-z?<>*.]+)? = (null|0)$""", RegexOption.MULTILINE).containsMatchIn(src))
        assertEquals("fleetdiag", FleetDiagnostics.SUFFIX)
    }

    @Test
    fun `D6 the consumer's declaration names this authority`() {
        val bj = JSONObject(repoFile("ac_cloud-c3-webserver/build.json").readText())
        val bridge = bj.getJSONObject("serving").getJSONObject("bridge")
        assertEquals("com.diegonmarcos.superapp", bridge.getString("package"))
        assertEquals("com.diegonmarcos.superapp.${FleetDiagnostics.SUFFIX}", bridge.getString("authority"))
    }
}
