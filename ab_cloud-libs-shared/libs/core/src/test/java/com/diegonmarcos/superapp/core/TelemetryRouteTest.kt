package com.diegonmarcos.superapp.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryRouteTest {

    @Test fun meshIsTriedBeforePublicAndBothCarryTheAppRoute() {
        assertEquals(
            listOf("http://c3-infra-api.app:8081/apk/clouddrive/logs", "https://c3-pvt.example/c3-infra-api/apk/clouddrive/logs"),
            TelemetryRoute.urls("http://c3-infra-api.app:8081/", "https://c3-pvt.example/c3-infra-api", "clouddrive"),
        )
    }

    @Test fun anOriginTheDeclarationDoesNotCarryIsLeftOut() {
        assertEquals(listOf("https://p.example/apk/x/logs"), TelemetryRoute.urls("", "https://p.example", "x"))
        assertTrue(TelemetryRoute.urls("", "", "x").isEmpty())
    }

    private fun tmp(): File = File.createTempFile("tq", "").let { it.delete(); it.mkdirs(); it }

    @Test fun aCrashThatCouldNotBeSentIsKeptAndGoesOutOnTheNextStart() {
        val dir = tmp()
        assertTrue(TelemetryQueue.enqueue(dir, "{\"n\":1}", 1000) != null)
        assertTrue(TelemetryQueue.enqueue(dir, "{\"n\":2}", 2000) != null)
        // the network is still down: nothing goes, nothing is lost
        assertEquals(0, TelemetryQueue.drain(dir) { false })
        assertEquals(2, TelemetryQueue.files(dir).size)
        // the next start: oldest first, and each accepted one is removed
        val seen = mutableListOf<String>()
        assertEquals(2, TelemetryQueue.drain(dir) { seen += it; true })
        assertEquals(listOf("{\"n\":1}", "{\"n\":2}"), seen)
        assertTrue(TelemetryQueue.files(dir).isEmpty())
        dir.deleteRecursively()
    }

    @Test fun drainStopsAtTheFirstRefusalAndKeepsTheRest() {
        val dir = tmp()
        for (i in 1..3) TelemetryQueue.enqueue(dir, "{\"n\":$i}", i * 1000L)
        var calls = 0
        assertEquals(1, TelemetryQueue.drain(dir) { calls++ == 0 })
        assertEquals(2, TelemetryQueue.files(dir).size)
        dir.deleteRecursively()
    }

    @Test fun theQueueKeepsTheNewestAndRefusesAnOversizeEvent() {
        val dir = tmp()
        for (i in 1..(TelemetryQueue.MAX_ITEMS + 3)) TelemetryQueue.enqueue(dir, "{\"n\":$i}", i * 1000L)
        val kept = TelemetryQueue.files(dir).map { it.readText() }
        assertEquals(TelemetryQueue.MAX_ITEMS, kept.size)
        assertEquals("{\"n\":${TelemetryQueue.MAX_ITEMS + 3}}", kept.last())
        assertNull(TelemetryQueue.enqueue(dir, "x".repeat(TelemetryQueue.MAX_ITEM_BYTES + 1)))
        dir.deleteRecursively()
    }
}
