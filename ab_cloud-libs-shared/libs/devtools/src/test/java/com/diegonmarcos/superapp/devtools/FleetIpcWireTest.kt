package com.diegonmarcos.superapp.devtools

import com.diegonmarcos.superapp.devtools.FleetIpcWire.Reply
import com.diegonmarcos.superapp.devtools.FleetIpcWire.Via
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.TimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #ipc the fleet IPC layer's decisions, on a plain JVM (a0_docs/eng-specs/fleet-ipc.md):
 *  1. the IPC call works: a door that answers is the answer, and HTTP is never asked
 *  2. a non-fleet caller is refused (the provider's gate), and a refusal is final
 *  3. the fallback works: a member with no IPC door (a build from before this layer) is
 *     asked on its loopback debug port, through a real socket
 */
class FleetIpcWireTest {

    @Test
    fun ipcAnswerIsTheAnswer_httpNeverAsked() {
        var httpAsked = false
        val r = FleetIpcWire.ladder({ Reply(200, "pong com.x\n") }, { httpAsked = true; Reply(200, "http") })
        assertEquals(200, r.status); assertEquals(Via.IPC, r.via); assertEquals("pong com.x\n", r.body)
        assertFalse("HTTP must not be asked when IPC answered", httpAsked)
    }

    @Test
    fun nonFleetCallerIsRefused() {
        val refused = FleetIpcWire.gate(granted = false)!!
        assertEquals(403, refused.status)
        assertTrue(refused.body.contains("CONSTELLATION_DATA"))
        assertNull("a fleet caller passes the gate", FleetIpcWire.gate(granted = true))
    }

    @Test
    fun refusalIsFinal_noHttpRetry() {
        var httpAsked = false
        val r = FleetIpcWire.ladder({ throw SecurityException("Permission Denial") }, { httpAsked = true; Reply(200, "x") })
        assertEquals(403, r.status); assertFalse(httpAsked)
    }

    @Test
    fun timeoutIs504_noHttpRetry() {
        var httpAsked = false
        val r = FleetIpcWire.ladder({ FleetIpcWire.within(50) { Thread.sleep(2000); Reply(200, "late") } },
            { httpAsked = true; Reply(200, "x") })
        assertEquals(504, r.status); assertFalse(httpAsked)
    }

    @Test(expected = TimeoutException::class)
    fun withinThrowsPastItsDeadline() {
        FleetIpcWire.within(20) { Thread.sleep(1000) }
    }

    @Test
    fun noDoor_fallsBackToTheHttpPort_overARealSocket() {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        var seen = ""
        val t = Thread {
            server.accept().use { s ->
                val r = s.getInputStream().bufferedReader()
                seen = generateSequence { r.readLine()?.takeIf { it.isNotEmpty() } }.joinToString("\n")
                s.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n{\"ok\":true}".toByteArray())
            }
        }.apply { start() }
        val r = FleetIpcWire.ladder({ null }, {
            FleetIpcWire.httpGet(server.localPort, FleetIpcWire.path("system/info", mapOf("n" to "a b")), "tok")
        })
        t.join(3000); server.close()
        assertEquals(200, r.status); assertEquals(Via.HTTP, r.via); assertEquals("{\"ok\":true}", r.body)
        assertEquals("application/json", r.type)
        assertTrue(seen, seen.startsWith("GET /api/system/info?n=a+b HTTP/1.0"))
        assertTrue("the fleet token rides the fallback", seen.contains("Authorization: Bearer tok"))
    }

    @Test
    fun noDoorAndNoHttp_isUnreachable() {
        val r = FleetIpcWire.ladder({ null }, { null })
        assertEquals(503, r.status); assertEquals(Via.NONE, r.via)
        assertEquals(503, FleetIpcWire.ladder({ null }, null).status)
    }
}
