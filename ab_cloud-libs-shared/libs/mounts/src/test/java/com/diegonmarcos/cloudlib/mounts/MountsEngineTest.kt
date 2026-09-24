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
 * The pure half of the mounts engine, proven on the JVM in every drive ship:
 * the URI ↔ spec round trip the host's connection catalogue speaks, the
 * WebDAV multistatus parser over real server output, the path arithmetic, the
 * store incl. the host's declare() contract, and the trust-on-first-use host
 * key verifier's file logic. The network engines need a server and are
 * exercised by the Test button.
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

    @Test fun webDavMultistatusParsesNextcloudShapeAndDropsTheCollectionItself() {
        val xml = """<?xml version="1.0"?>
<d:multistatus xmlns:d="DAV:" xmlns:s="http://sabredav.org/ns" xmlns:oc="http://owncloud.org/ns">
 <d:response><d:href>/remote.php/dav/files/diego/docs/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype><d:getlastmodified>Tue, 23 Sep 2026 10:00:00 GMT</d:getlastmodified></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
 <d:response><d:href>/remote.php/dav/files/diego/docs/notes%20v2.md</d:href><d:propstat><d:prop><d:displayname>notes v2.md</d:displayname><d:resourcetype/><d:getcontentlength>1234</d:getcontentlength><d:getlastmodified>Wed, 24 Sep 2026 08:30:00 GMT</d:getlastmodified></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
 <d:response><d:href>https://files.example/remote.php/dav/files/diego/docs/Archive/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
 <d:response><d:href>/remote.php/dav/files/diego/docs/a.txt</d:href><d:propstat><d:prop><d:resourcetype/><d:getcontentlength>2</d:getcontentlength></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
</d:multistatus>"""
        val entries = WebDavParser.parseMultistatus(xml, "/remote.php/dav/files/diego/docs/")
        assertEquals(listOf("Archive", "a.txt", "notes v2.md"), entries.map { it.name })
        assertTrue(entries[0].isDir); assertEquals("/remote.php/dav/files/diego/docs/Archive", entries[0].path); assertEquals(-1L, entries[0].size)
        assertEquals(1234L, entries[2].size); assertEquals("/remote.php/dav/files/diego/docs/notes v2.md", entries[2].path)
        assertEquals(1790238600000L, entries[2].modifiedEpochMillis)   // Wed, 24 Sep 2026 08:30:00 GMT
        assertEquals(0L, entries[1].modifiedEpochMillis)
        // Apache-style prefix (D:) parses the same — the parser is namespace-agnostic.
        val apache = xml.replace("d:", "D:").replace("xmlns:d=", "xmlns:D=")
        assertEquals(3, WebDavParser.parseMultistatus(apache, "/remote.php/dav/files/diego/docs").size)
        assertEquals("/a", WebDavParser.normalise("/a/")); assertEquals("/", WebDavParser.normalise("/")); assertEquals("/", WebDavParser.normalise(""))
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

    @Test fun tofuVerifierRecordsFirstKeyAndRefusesAChangedOne() {
        val file = File(Files.createTempDirectory("kh").toFile(), "mounts/known_hosts")
        val v = TofuHostKeyVerifier(file)
        val kp = java.security.KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }
        val k1 = kp.generateKeyPair().public; val k2 = kp.generateKeyPair().public
        assertTrue("first contact is recorded", v.verify("host.example", 22, k1))
        assertTrue(file.readText().startsWith("host.example:22 "))
        assertTrue("same key again passes", v.verify("host.example", 22, k1))
        assertFalse("a changed key is REFUSED", v.verify("host.example", 22, k2))
        assertTrue("another port is another host", v.verify("host.example", 2222, k2))
        assertEquals(2, file.readLines().size)
    }
}
