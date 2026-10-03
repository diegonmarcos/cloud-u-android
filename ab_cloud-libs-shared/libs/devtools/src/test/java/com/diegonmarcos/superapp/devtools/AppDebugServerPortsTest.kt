package com.diegonmarcos.superapp.devtools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #792 the port slice this build baked, and the fallback that must never land
 * in a sibling's slot. Pure JVM. #796: the slice holds this root's own
 * package(s) (the whole fleet only in the SuperApp), and the fallback is a
 * declared sub-range no package is assigned, so the checks are on the slice's
 * consistency with the range, not on fleet-wide headroom (the ports guard
 * holds that against the one table).
 */
class AppDebugServerPortsTest {

    @Test
    fun theBakedSliceIsUniqueInsideTheRangeAndOutsideTheFallback() {
        val ports = AppDebugServer.PORTS
        assertTrue("debug-api.json baked an empty slice", ports.isNotEmpty())
        assertEquals("two packages share a port", ports.size, ports.values.toSet().size)
        assertTrue("fallback ${AppDebugServer.FALLBACK_FIRST}..${AppDebugServer.FALLBACK_LAST} is not inside the range",
            AppDebugServer.PORT_FIRST <= AppDebugServer.FALLBACK_FIRST &&
                AppDebugServer.FALLBACK_FIRST <= AppDebugServer.FALLBACK_LAST &&
                AppDebugServer.FALLBACK_LAST <= AppDebugServer.PORT_LAST)
        ports.forEach { (pkg, p) ->
            assertTrue("$pkg :$p is outside the range", p in AppDebugServer.PORT_FIRST..AppDebugServer.PORT_LAST)
            assertFalse("$pkg :$p is inside the fallback sub-range", p in AppDebugServer.FALLBACK_FIRST..AppDebugServer.FALLBACK_LAST)
        }
        // The fallback scan never offers an owned port, so it is the whole sub-range here.
        val free = AppDebugServer.fallbackPorts(AppDebugServer.FALLBACK_FIRST, AppDebugServer.FALLBACK_LAST, ports.values)
        assertEquals((AppDebugServer.FALLBACK_FIRST..AppDebugServer.FALLBACK_LAST).toList(), free)
    }

    @Test
    fun parseReadsTheBuildConfigShape() {
        assertEquals(mapOf("a.b" to 38140, "c.d" to 38141), AppDebugServer.parsePorts("a.b=38140,c.d=38141"))
        assertEquals(emptyMap<String, Int>(), AppDebugServer.parsePorts(""))
        assertEquals(mapOf("a.b" to 1), AppDebugServer.parsePorts("a.b=1,junk,=2,e.f=x"))
    }

    @Test
    fun aFallbackNeverTakesAnOwnedPort() {
        val free = AppDebugServer.fallbackPorts(10, 15, listOf(11, 13))
        assertEquals(listOf(10, 12, 14, 15), free)
        assertFalse(free.any { it == 11 || it == 13 })
    }
}
