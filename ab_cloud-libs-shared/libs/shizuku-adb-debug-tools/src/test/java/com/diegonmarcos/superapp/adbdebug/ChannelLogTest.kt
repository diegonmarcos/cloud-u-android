package com.diegonmarcos.superapp.adbdebug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The in-memory ring buffer, and its promise never to hold a secret. */
class ChannelLogTest {

    @Test fun ringKeepsTheNewestAndDropsTheOldest() {
        val log = ChannelLog(capacity = 3)
        for (i in 1..5) log.add("t", "event $i")
        assertEquals(3, log.size)
        assertEquals(listOf("event 3", "event 4", "event 5"), log.entries().map { it.text })
    }

    @Test fun clearEmptiesIt() {
        val log = ChannelLog(capacity = 10)
        log.add("t", "a"); log.add("t", "b")
        log.clear()
        assertEquals(0, log.size); assertEquals("", log.asText())
    }

    @Test fun textIsOneLinePerEventOldestFirstWithTimeAndTag() {
        var now = 3_661_000L                                     // 01:01:01 UTC
        val log = ChannelLog(10) { now.also { now += 1000 } }
        log.add("probe", "route local-server")
        log.add("connect", "multi\nline")
        assertEquals("01:01:01 [probe] route local-server\n01:01:02 [connect] multi line", log.asText())
    }

    @Test fun listenersHearAddAndClear() {
        val log = ChannelLog(5); var n = 0
        log.addListener { n++ }
        log.add("t", "x"); log.clear()
        assertEquals(2, n)
    }

    // Token-shaped fixtures are ASSEMBLED here, never written as literals: the repo's leak scan reads source text.
    private fun hex(n: Int) = "0123456789abcdef".repeat(8).take(n)
    private fun alnum(n: Int) = "abcdefghijklmnopqrstuvwxyz0123456789".repeat(3).take(n)

    @Test fun aTokenIsRedactedBeforeItIsStored() {
        val token = hex(32)
        val log = ChannelLog(10)
        log.add("boot", "app_process /system/bin --nice-name=superapp-adb com.diegonmarcos.superapp.adbdebug.AdbShellServer $token 38099")
        val stored = log.entries().single().text
        assertFalse(stored.contains(token))
        assertTrue(stored.contains("[redacted] 38099"))
        assertFalse(log.asText().contains(token))
    }

    @Test fun tokenShapesAreAllRedacted() {
        val jwt = listOf("e" + "yJhbGciOiJIUzI1NiJ9", "e" + "yJzdWIiOiIxMjM0NTYifQ", alnum(40)).joinToString(".")
        val b64 = java.util.Base64.getEncoder().encodeToString(alnum(40).toByteArray()).trimEnd('=')
        val secrets = listOf(
            hex(32), hex(64).uppercase(), "gh" + "p_" + alnum(36), "github_" + "pat_" + alnum(40), jwt, b64,
            "s" + "k-" + alnum(30), "AK" + "IA" + "ABCDEFGHIJKLMNOP",
        )
        for (s in secrets) {
            val out = ChannelLog.redact("sent $s to the server")
            assertFalse(s, out.contains(s.take(20)))
            assertTrue(s, out.contains("[redacted]"))
            assertTrue(out, out.startsWith("sent ") && out.endsWith(" to the server"))
        }
    }

    @Test fun labelledSecretsKeepTheLabelAndLoseTheValue() {
        assertEquals("token=[redacted] port 38099", ChannelLog.redact("token=abc123 port 38099"))
        assertEquals("Token: [redacted]", ChannelLog.redact("Token: s3cr3t"))
        assertEquals("password=[redacted]", ChannelLog.redact("password=hunter2"))
        assertEquals("Authorization: [redacted]", ChannelLog.redact("Authorization: abc"))
        assertEquals("sent Bearer [redacted] ok", ChannelLog.redact("sent Bearer abc.def-ghi ok"))
        assertEquals("pairing code: [redacted]", ChannelLog.redact("pairing code: 123456"))
        assertEquals("pairing code=[redacted]", ChannelLog.redact("pairing code=123456"))
    }

    @Test fun aSixDigitCodeNextToTheWordCodeIsRedactedButExitCodesAreNot() {
        assertEquals("entered code [redacted]", ChannelLog.redact("entered code 123456"))
        assertEquals("exit code: 0", ChannelLog.redact("exit code: 0"))
        assertEquals("exit code 127", ChannelLog.redact("exit code 127"))
        assertEquals("rc=134", ChannelLog.redact("rc=134"))
    }

    @Test fun ordinaryDiagnosticsSurvive() {
        for (s in listOf(
            "id -> uid=2000(shell) gid=2000(shell)", "local server :38099 up 01:02 pid 4242", "embedded-adb connected 10.0.0.9:37123",
            "com.diegonmarcos.superapp.adbdebug.AdbShellServer", "/data/app/~~Zm9vYmFy==/com.diegonmarcos.cloudstore-1/base.apk",
            "pm install -r -d /data/local/tmp/app.apk", "exit 0 in 312 ms", "Shizuku v13 granted",
        )) assertEquals(s, ChannelLog.redact(s), s)
    }

    @Test fun overlongLinesAreCut() {
        val log = ChannelLog(2)
        log.add("t", "x ".repeat(1000))
        assertEquals(ChannelLog.MAX_LINE, log.entries().single().text.length)
    }
}
