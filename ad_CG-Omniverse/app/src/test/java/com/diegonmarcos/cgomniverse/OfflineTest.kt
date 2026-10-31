package com.diegonmarcos.cgomniverse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Offline worlds: the link status store.json carries (offline is not unverified, an unknown value is never online) and when Galaxy
 * calls a loading world offline (timeout, no host, refused, HTTP 404/410) versus the generic fallback (any other failure).
 */
class OfflineTest {
    @Test fun statusParsesEachValue() {
        assertEquals(LinkStatus.ONLINE, LinkStatus.parse("online"))
        assertEquals(LinkStatus.OFFLINE, LinkStatus.parse("offline"))
        assertEquals(LinkStatus.UNVERIFIED, LinkStatus.parse("unverified"))
    }

    @Test fun anUnknownStatusIsUnverifiedNeverOnline() {
        assertEquals(LinkStatus.UNVERIFIED, LinkStatus.parse("blocked"))
        assertEquals(LinkStatus.UNVERIFIED, LinkStatus.parse(""))
        assertEquals(LinkStatus.UNVERIFIED, LinkStatus.parse(null))
    }

    @Test fun parseCarriesStatusAndDate() {
        val json = """{"entries":[{"id":"a","url":"http://a/","status":"offline","checked":"2026-10-31"},{"id":"b","url":"https://b/"}]}"""
        val (a, b) = Store.parse(json)
        assertEquals(LinkStatus.OFFLINE, a.status); assertEquals("2026-10-31", a.checked)
        assertEquals(LinkStatus.UNVERIFIED, b.status); assertEquals("", b.checked)
    }

    @Test fun theRealStoreMarksAWayToGoOffline() {
        val f = listOf("../data/store.json", "data/store.json").map(::File).first { it.exists() }
        val worlds = Store.parse(f.readText())
        assertEquals(LinkStatus.OFFLINE, worlds.first { it.id == "a-way-to-go" }.status)
        assertTrue(worlds.all { it.checked.isEmpty() || Regex("""\d{4}-\d{2}-\d{2}""").matches(it.checked) })
    }

    @Test fun goneHttpIsOffline() {
        assertTrue(Offline.isOfflineHttp(404)); assertTrue(Offline.isOfflineHttp(410))
        assertFalse(Offline.isOfflineHttp(200)); assertFalse(Offline.isOfflineHttp(500)); assertFalse(Offline.isOfflineHttp(403))
    }

    @Test fun unreachableHostIsOffline() {
        assertTrue(Offline.isOfflineError(Offline.ERROR_TIMEOUT))
        assertTrue(Offline.isOfflineError(Offline.ERROR_HOST_LOOKUP))
        assertTrue(Offline.isOfflineError(Offline.ERROR_CONNECT))
        assertFalse(Offline.isOfflineError(-11)) // ERROR_FAILED_SSL_HANDSHAKE: not offline, the generic fallback
        assertFalse(Offline.isOfflineError(-1))  // ERROR_UNKNOWN (e.g. a cleartext block): not offline
    }

    @Test fun theWatchdogFiresOnlyForAnUnfinishedLoadPastItsTime() {
        assertTrue(Offline.timedOut(Offline.LOAD_TIMEOUT_MS, finished = false))
        assertFalse(Offline.timedOut(Offline.LOAD_TIMEOUT_MS - 1, finished = false))
        assertFalse(Offline.timedOut(Offline.LOAD_TIMEOUT_MS * 2, finished = true))
        // it must fire inside the proof walk's 25 s wait on A Way to Go, and not before a slow live world gets 10 s
        assertTrue(Offline.LOAD_TIMEOUT_MS in 10_000L..20_000L)
    }

    @Test fun waybackIsTheArchiveCopyFromStoreJson() {
        val f = listOf("../data/store.json", "data/store.json").map(::File).first { it.exists() }
        val w = Store.parse(f.readText()).first { it.id == "a-way-to-go" }
        assertEquals("https://web.archive.org/web/2020/http://a-way-to-go.com/", w.wayback)
        assertEquals("", Store.parse("""{"entries":[{"id":"a","url":"http://a/"}]}""").single().wayback)
    }
}
