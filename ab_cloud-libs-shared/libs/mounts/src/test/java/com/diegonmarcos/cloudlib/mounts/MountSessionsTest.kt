package com.diegonmarcos.cloudlib.mounts

import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The mount-lifetime rules: re-opening the screen (or the app) RE-ATTACHES to a live mount and never
 * reconnects it; a dead mount is reopened only after its backoff; the UI's close() never drops the link.
 */
class MountSessionsTest {
    private val spec = MountSpec("m1", "files", MountType.SFTP, "10.0.0.6", 22, "diego", "/srv")

    private class FakeFs(var alive: Boolean = true) : RemoteFs {
        var closed = false
        override fun list(path: String): List<RemoteEntry> { if (!alive) throw IOException("gone"); return emptyList() }
        override fun download(path: String, into: File, onProgress: (Long) -> Unit) {}
        override fun upload(local: File, path: String, onProgress: (Long) -> Unit) {}
        override fun mkdir(path: String) {}
        override fun delete(path: String, isDir: Boolean) {}
        override fun rename(from: String, to: String) {}
        override fun close() { closed = true }
    }

    @Test fun reopen_does_not_remount_a_live_mount() {
        val opened = mutableListOf<FakeFs>()
        val s = MountSessions({ FakeFs().also { opened += it } })
        val first = s.acquire(spec)
        first.close()                        // the screen goes away (Activity destroyed)
        assertFalse("UI release must not close the connection", opened.single().closed)
        s.acquire(spec).list("/")            // the app is reopened
        s.acquire(spec, probe = false)
        assertEquals("a live mount is re-attached, never reopened", 1, opened.size)
        assertEquals(1, s.liveCount())
    }

    @Test fun a_dead_mount_is_reopened_with_backoff() {
        var now = 0L
        var failNext = false
        val opened = mutableListOf<FakeFs>()
        val s = MountSessions({ if (failNext) throw IOException("unreachable") else FakeFs().also { opened += it } }, clock = { now }, backoffInitialMs = 1000, backoffMaxMs = 4000)
        s.acquire(spec)
        opened.single().alive = false
        failNext = true
        try { s.acquire(spec); fail("open should fail") } catch (_: IOException) {}
        failNext = false
        try { s.acquire(spec); fail("inside the backoff window") } catch (_: IllegalStateException) {}
        now = 1000
        s.acquire(spec)
        assertEquals(2, opened.size)
        assertTrue(opened.first().closed)
    }

    @Test fun backoff_doubles_and_caps() {
        val s = MountSessions({ FakeFs() }, backoffInitialMs = 5, backoffMaxMs = 30)
        assertEquals(listOf(0L, 5L, 10L, 20L, 30L, 30L), (0..5).map { s.backoffFor(it) })
    }

    @Test fun health_pass_leaves_healthy_alone_and_revives_dead() {
        val opened = mutableListOf<FakeFs>()
        val s = MountSessions({ FakeFs().also { opened += it } }, backoffInitialMs = 0)
        s.acquire(spec)
        assertEquals(1, s.healthPass()); assertEquals(1, opened.size)
        opened.single().alive = false
        assertEquals(1, s.healthPass()); assertEquals(2, opened.size)
    }

    @Test fun transfers_are_counted_and_disconnect_really_closes() {
        val opened = mutableListOf<FakeFs>()
        val s = MountSessions({ FakeFs().also { opened += it } })
        val fs = s.acquire(spec)
        fs.download("/a", File("x")) {}
        assertEquals(0, s.transfers.value)
        s.disconnect(spec.id)
        assertTrue(opened.single().closed)
        assertEquals(0, s.liveCount())
    }
}
