package com.diegonmarcos.cloudlib.mounts.engine

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The protocol clients' pure halves, proven on the JVM in every Cloud Libs ship: the WebDAV multistatus parser
 * over real server output, and the trust-on-first-use host key verifier's file logic. (They moved here with the
 * clients from libs:mounts, #871 move 12.) The clients over a real server are MountCallsTest's.
 */
class MountsEngineProtocolTest {

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
