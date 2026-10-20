package com.diegonmarcos.cloudlib.mounts

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure half of the mounts contract, proven on the JVM in every drive ship:
 * the URI ↔ spec round trip the host's connection catalogue speaks, the path
 * arithmetic and the store incl. the host's declare() contract. (#871 move 12:
 * the WebDAV multistatus parser and the trust-on-first-use host key verifier
 * moved with the protocol clients to libs:mounts-engine's own suite.)
 */
class MountsEngineTest {

    @Test fun uriParsesEveryShapeAndRoundTrips() {
        val a = MountUri.parse("sftp://diego@10.0.0.6:2222/srv/data")!!
        assertEquals(MountType.SFTP, a.type); assertEquals("diego", a.username); assertEquals("10.0.0.6", a.host); assertEquals(2222, a.port); assertEquals("/srv/data", a.path)
        assertEquals("sftp://diego@10.0.0.6:2222/srv/data", a.uri)
        val b = MountUri.parse("davs://files.example")!!
        assertEquals(MountType.WEBDAVS, b.type); assertEquals(443, b.port); assertEquals("/", b.path); assertEquals("", b.username)
        assertEquals("davs://files.example/", b.uri)
        val c = MountUri.parse("ftp://[fd00::6]:2121/")!!
        assertEquals("fd00::6", c.host); assertEquals(2121, c.port); assertEquals("ftp://[fd00::6]:2121/", c.uri)
        val d = MountUri.parse("ssh://root@host/")!!
        assertEquals(MountType.SSH, d.type); assertEquals(22, d.port); assertEquals("root@host", d.name)
        assertNull(MountUri.parse("smb://host/share"))     // not a type this engine speaks
        assertNull(MountUri.parse("sftp://user@:22/"))     // empty host
        assertNull(MountUri.parse("sftp://host:abc/"))     // bad port
        assertNull(MountUri.parse("no scheme"))
        assertEquals(a.id, MountUri.parse("sftp://diego@10.0.0.6:2222/srv/data")!!.id)
        assertNotEquals(a.id, MountUri.parse("sftp://diego@10.0.0.6:2223/srv/data")!!.id)
    }

    @Test fun remotePathsJoinParentName() {
        assertEquals("/a/b", RemotePaths.join("/a", "b")); assertEquals("/a/b", RemotePaths.join("/a/", "/b")); assertEquals("/b", RemotePaths.join("/", "b"))
        assertEquals("/a", RemotePaths.parent("/a/b")); assertEquals("/", RemotePaths.parent("/a")); assertEquals("/", RemotePaths.parent("/a/")); assertEquals("/", RemotePaths.parent("/"))
        assertEquals("b", RemotePaths.name("/a/b/")); assertEquals("b", RemotePaths.name("/a/b"))
    }

    @Test fun storeDeclareKeepsUserMountsAndReplacesDeclared() {
        val store = MountStore(File(Files.createTempDirectory("m").toFile(), "mounts/mounts.json"))
        val mine = MountUri.parse("sftp://me@my.host/", name = "mine")!!
        store.upsert(mine)
        store.declare(listOf(MountUri.parse("davs://files.example/", name = "cloud")!!))
        assertEquals(setOf("cloud", "mine"), store.load().map { it.name }.toSet())
        assertTrue(store.load().first { it.name == "cloud" }.declared)
        assertFalse(store.load().first { it.name == "mine" }.declared)
        store.declare(listOf(MountUri.parse("ftp://ftp.example/", name = "ftp")!!))
        assertEquals(setOf("ftp", "mine"), store.load().map { it.name }.toSet())
        store.remove(mine.id)
        assertEquals(listOf("ftp"), store.load().map { it.name })
        assertEquals(store.load(), MountStore(File(Files.createTempDirectory("m2").toFile(), "x.json")).also { it.save(store.load()) }.load())
    }
}
