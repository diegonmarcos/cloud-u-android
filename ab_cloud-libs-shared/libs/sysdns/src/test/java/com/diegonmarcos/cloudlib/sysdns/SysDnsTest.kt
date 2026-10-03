package com.diegonmarcos.cloudlib.sysdns

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketPermission
import java.security.Permission
import java.util.Base64
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.concurrent.thread
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #741 the two paths a fleet process uses to reach Android's resolver, run on the JVM with a fake
 * resolver in Android's place: the terminals' DNS bridge, and the binaries' CONNECT proxy in the
 * mode rclone needs (any host its user configured, any port).
 */
class SysDnsTest {

    /** A query for example.test A, id 0x1234, no EDNS (ARCOUNT 0). */
    private val query = byteArrayOf(0x12, 0x34, 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0) +
        byteArrayOf(7) + "example".toByteArray() + byteArrayOf(4) + "test".toByteArray() + byteArrayOf(0, 0, 1, 0, 1)

    /** What a resolver answers: the question plus [records] bytes of answer, under ANOTHER id. */
    private fun answer(records: Int): ByteArray {
        val a = query.copyOf()
        a[0] = 0x55; a[1] = 0x66 // netd's own id; the bridge must hand back the client's
        a[2] = 0x81.toByte(); a[3] = 0x80.toByte(); a[7] = 1
        return a + ByteArray(records) { 7 }
    }

    private fun udpAsk(port: Int, q: ByteArray): ByteArray = DatagramSocket().use { s ->
        s.soTimeout = 5_000
        s.send(DatagramPacket(q, q.size, InetAddress.getByName("127.0.0.1"), port))
        val buf = ByteArray(4096)
        val p = DatagramPacket(buf, buf.size)
        s.receive(p)
        buf.copyOf(p.length)
    }

    private fun tcpAsk(port: Int, q: ByteArray): ByteArray = Socket("127.0.0.1", port).use { s ->
        s.soTimeout = 5_000
        DataOutputStream(s.getOutputStream()).apply { writeShort(q.size); write(q); flush() }
        val input = DataInputStream(s.getInputStream())
        ByteArray(input.readUnsignedShort()).also { input.readFully(it) }
    }

    private fun bridge(upstream: SystemDnsBridge.Upstream): SystemDnsBridge {
        val free = ServerSocket(0).run { localPort.also { close() } }
        return SystemDnsBridge(free, upstream) {}
    }

    @Test
    fun aQueryIsAnsweredByTheSystemResolverUnderTheClientsOwnId() {
        val asked = mutableListOf<ByteArray>()
        bridge { q, done -> asked += q; done.reply(answer(16)) }.use { b ->
            val a = udpAsk(b.port(), query)
            assertArrayEquals("the query reached the resolver byte for byte", query, asked.single())
            assertEquals(0x12, a[0].toInt()); assertEquals(0x34, a[1].toInt())
            assertEquals("the resolver's answer, unaltered past the id", answer(16).drop(2), a.drop(2))
            assertEquals("TCP answers the same", answer(16).drop(2), tcpAsk(b.port(), query).drop(2))
        }
    }

    @Test
    fun aLookupTheResolverCannotAnswerIsServfailWithTheQuestionEchoed() {
        bridge { _, done -> done.reply(null) }.use { b ->
            val a = udpAsk(b.port(), query)
            assertEquals("RCODE", 2, a[3].toInt() and 0x0f)
            assertTrue("QR", a[2].toInt() and 0x80 != 0)
            assertArrayEquals("glibc drops an answer whose question does not match", query.copyOfRange(12, query.size), a.copyOfRange(12, a.size))
        }
        bridge { _, _ -> throw IllegalStateException("netd said no") }.use { b ->
            assertEquals("a resolver that throws is a SERVFAIL, not silence", 2, udpAsk(b.port(), query)[3].toInt() and 0x0f)
        }
    }

    /**
     * #791 the crash the phone hit (cld.termux, Android 15): DnsResolver calls back from the MAIN
     * looper, and an app's main thread runs under StrictMode detectNetwork() with death as the
     * penalty, so the bridge's UDP send there killed the terminal on its first lookup. The JVM has
     * no StrictMode; the same policy here is a SecurityManager that refuses every socket permission
     * on a fake main thread (the per-call check BlockGuard makes on the phone) and remembers each
     * refusal. The fake resolver answers on that thread, as netd's callback does.
     */
    @Suppress("DEPRECATION", "removal")
    @Test
    fun anAnswerArrivingOnTheMainLooperIsSentWithoutMainTouchingASocket() {
        val main = Executors.newSingleThreadExecutor { r -> Thread(r, "main").apply { isDaemon = true } }
        val mainThread = main.submit(Callable { Thread.currentThread() }).get()
        val deaths = CopyOnWriteArrayList<String>()
        val previous = System.getSecurityManager()
        System.setSecurityManager(object : SecurityManager() {
            override fun checkPermission(perm: Permission) {
                if (perm is SocketPermission && Thread.currentThread() === mainThread) {
                    deaths += perm.toString()
                    throw SecurityException("NetworkOnMainThreadException: $perm")
                }
            }
            override fun checkPermission(perm: Permission, context: Any?) = checkPermission(perm)
        })
        try {
            // The policy bites exactly what the old bridge did on main: an unconnected UDP send.
            DatagramSocket().use { s ->
                val p = DatagramPacket(query, query.size, InetAddress.getByName("127.0.0.1"), 9)
                val refused = main.submit(Callable { runCatching { s.send(p) }.exceptionOrNull() }).get()
                assertTrue("the fake StrictMode refuses a send on main, got $refused", refused is SecurityException)
            }
            deaths.clear()
            bridge { _, done -> main.execute { done.reply(answer(16)) } }.use { b ->
                assertEquals("UDP answered", 0x34, udpAsk(b.port(), query)[1].toInt())
                assertEquals("TCP answered", 0x34, tcpAsk(b.port(), query)[1].toInt())
            }
            assertEquals("socket calls the main looper made", emptyList<String>(), deaths.toList())
        } finally {
            System.setSecurityManager(previous)
            main.shutdown()
        }
    }

    /** #794 what the SuperApp's DNS page reads off a terminal's /api/sysdns/state. */
    @Test
    fun theStateCountsEveryQueryByHowItEndedAndKeepsTheLastError() {
        fun field(json: String, k: String) = Regex("\"$k\":([^,}]+)").find(json)?.groupValues?.get(1)
        bridge { _, done -> done.reply(answer(16)) }.use { b ->
            assertEquals("a fresh bridge has seen nothing", "0", field(b.stateJson(), "queries"))
            assertEquals("and no query time", "0", field(b.stateJson(), "last_query_ms"))
            udpAsk(b.port(), query); tcpAsk(b.port(), query)
            val s = b.stateJson()
            assertEquals(s, "true", field(s, "listening"))
            assertEquals(s, b.port().toString(), field(s, "port"))
            assertEquals(s, "2", field(s, "queries"))
            assertEquals(s, "2", field(s, "answered"))
            assertEquals(s, "0", field(s, "servfail"))
            assertEquals(s, "0", field(s, "errors"))
            assertEquals(s, "null", field(s, "last_error"))
            assertTrue(s, field(s, "last_query_ms")!!.toLong() > 0)
        }
        bridge { _, done -> done.reply(null) }.use { b ->
            udpAsk(b.port(), query)
            val s = b.stateJson()
            assertEquals(s, "1", field(s, "servfail"))
            assertEquals(s, "0", field(s, "answered"))
            assertEquals("no answer is a SERVFAIL, not an error of the bridge's", "0", field(s, "errors"))
        }
        bridge { _, _ -> throw IllegalStateException("netd said \"no\"") }.use { b ->
            udpAsk(b.port(), query)
            val s = b.stateJson()
            assertEquals(s, "1", field(s, "errors"))
            assertTrue("the last error, JSON-escaped: $s", s.contains("netd said \\\"no\\\""))
            assertTrue(s, field(s, "last_error_ms")!!.toLong() > 0)
            b.close()
            assertEquals("a closed bridge says so", "false", field(b.stateJson(), "listening"))
        }
        assertEquals("{\"listening\":false,\"port\":2053,\"why\":\"taken\"}", SystemDnsBridge.notListeningJson(2053, "taken"))
    }

    @Test
    fun anAnswerTooBigForUdpIsTruncatedThereAndWholeOverTcp() {
        bridge { _, done -> done.reply(answer(900)) }.use { b ->
            val u = udpAsk(b.port(), query)
            assertTrue("TC set over UDP", u[2].toInt() and 0x02 != 0)
            assertEquals("header and question only", query.size, u.size)
            val t = tcpAsk(b.port(), query)
            assertEquals("TCP carries it whole", answer(900).size, t.size)
            assertEquals("and untruncated", 0, t[2].toInt() and 0x02)
            val edns = query.copyOf().also { it[11] = 1 } // ARCOUNT 1: the client sent OPT
            assertEquals("an EDNS client takes 1232 bytes over UDP", answer(900).size, udpAsk(b.port(), edns).size)
        }
    }

    @Test
    fun anyHostModeTunnelsToWhateverTheEngineIsAskedForButStillOnlyForItsOwnChildren() {
        val echo = ServerSocket(0)
        thread(isDaemon = true) { echo.accept().use { c -> c.getOutputStream().write(c.getInputStream().read()) } }
        val proxy = ResolverProxy("rclone", hosts = null, port = null, idleMs = 0) {}
        val credential = proxy.url.substringAfter("//").substringBefore('@')
        fun connect(target: String, auth: String?): Pair<String, Socket> {
            val s = Socket("127.0.0.1", proxy.url.substringAfterLast(':').toInt()).apply { soTimeout = 5_000 }
            s.getOutputStream().write(("CONNECT $target HTTP/1.1\r\nHost: $target\r\n" +
                (auth?.let { "Proxy-Authorization: Basic " + Base64.getEncoder().encodeToString(it.toByteArray()) + "\r\n" } ?: "") +
                "\r\n").toByteArray())
            return ResolverProxy.readHead(s.getInputStream())!!.first() to s
        }
        val (open, s) = connect("localhost:${echo.localPort}", credential)
        s.use {
            assertEquals("HTTP/1.1 200 Connection established", open)
            s.getOutputStream().write(42)
            assertEquals(42, s.getInputStream().read())
        }
        val (stranger, t) = connect("localhost:${echo.localPort}", null)
        t.close()
        assertTrue(stranger, stranger.startsWith("HTTP/1.1 407"))
        val (noPort, u) = connect("localhost", credential)
        u.close()
        assertEquals("HTTP/1.1 403 the rclone engine does not tunnel to localhost", noPort)
    }
}
