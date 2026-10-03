package com.diegonmarcos.superapp.devtools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #792 the port table every member binds from, as this build baked it, and the
 * fallback that must never land in a sibling's slot. Pure JVM.
 */
class AppDebugServerPortsTest {

    @Test
    fun theBakedTableIsUniqueAndInsideTheRange() {
        val ports = AppDebugServer.PORTS
        assertTrue("debug-ports.json baked an empty table", ports.isNotEmpty())
        assertEquals("two packages share a port", ports.size, ports.values.toSet().size)
        ports.forEach { (pkg, p) ->
            assertTrue("$pkg :$p is outside the range", p in AppDebugServer.PORT_FIRST..AppDebugServer.PORT_LAST)
        }
        // The headroom: every member could fall back at once and still find a port.
        val free = AppDebugServer.fallbackPorts(AppDebugServer.PORT_FIRST, AppDebugServer.PORT_LAST, ports.values)
        assertTrue("only ${free.size} unowned ports for ${ports.size} members", free.size >= ports.size)
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
