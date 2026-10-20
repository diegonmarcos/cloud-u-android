package com.diegonmarcos.cloudlib.mounts.engine

import com.diegonmarcos.cloudlib.mounts.MountSecret
import com.diegonmarcos.cloudlib.mounts.MountSpec
import com.diegonmarcos.cloudlib.mounts.MountType
import com.diegonmarcos.cloudlib.mounts.MountWire
import com.diegonmarcos.cloudlib.mounts.RemoteEntry
import com.diegonmarcos.cloudlib.mounts.RemoteFs
import kotlinx.serialization.builtins.ListSerializer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.BufferedInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap

/**
 * The mounts engine's JSON surface and a REAL protocol client behind it: a tiny in-memory WebDAV server on
 * loopback (PROPFIND / GET / PUT / MKCOL / DELETE / MOVE) is opened, listed, downloaded from, uploaded to,
 * renamed on and deleted from through MountCalls, the way the app drives it. The handle protocol (a lost
 * connection answers "unknown handle", an idle one is reaped, a closed one is gone), the chunked listing, the
 * ssh scratch files and the refusals are tested around it. ftp and sftp need a server this suite does not
 * start; their clients are the maintained libraries', exercised by the Test button.
 */
class MountCallsTest {
    private lateinit var root: File
    private lateinit var scratch: File
    private lateinit var server: MiniHttp
    private val files = ConcurrentHashMap<String, ByteArray>()      // "/dir/name" -> bytes
    private val dirs = java.util.Collections.synchronizedSet(linkedSetOf("/"))
    private var now = 1_000_000L

    @Before fun setUp() {
        root = Files.createTempDirectory("mount-calls-test").toFile()
        scratch = File(root, "scratch")
        server = MiniHttp { method, rawPath, headers, body -> dav(method, rawPath, headers, body) }
    }

    @After fun tearDown() { server.close(); root.deleteRecursively() }

    /**
     * A tiny HTTP/1.1 server on a loopback socket (the JDK's com.sun.net.httpserver is not on an Android
     * module's unit-test compile classpath), one request per connection, enough for OkHttp's WebDAV verbs.
     */
    private class MiniHttp(private val handler: (String, String, Map<String, String>, ByteArray) -> Reply) : AutoCloseable {
        class Reply(val code: Int, val body: ByteArray = ByteArray(0), val type: String? = null, val headers: Map<String, String> = emptyMap())
        private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = socket.localPort
        @Volatile private var running = true

        init {
            Thread {
                while (running) {
                    val c = try { socket.accept() } catch (e: Exception) { break }
                    Thread { serve(c) }.apply { isDaemon = true; start() }
                }
            }.apply { isDaemon = true; start() }
        }

        private fun readLine(i: BufferedInputStream): String? {
            val sb = StringBuilder()
            while (true) {
                val b = i.read()
                if (b < 0) return if (sb.isEmpty()) null else sb.toString()
                if (b == '\n'.code) return sb.toString().trimEnd('\r')
                sb.append(b.toChar())
            }
        }

        private fun serve(c: Socket) {
            c.use {
                val i = BufferedInputStream(it.getInputStream())
                val first = readLine(i) ?: return
                val parts = first.split(' ')
                val headers = HashMap<String, String>()
                while (true) {
                    val h = readLine(i) ?: break
                    if (h.isEmpty()) break
                    headers[h.substringBefore(':').trim().lowercase()] = h.substringAfter(':').trim()
                }
                val length = headers["content-length"]?.toInt() ?: 0
                val body = ByteArray(length)
                var read = 0
                while (read < length) { val n = i.read(body, read, length - read); if (n < 0) break; read += n }
                val r = try { handler(parts[0], parts[1], headers, body) } catch (t: Throwable) { Reply(500) }
                val head = StringBuilder("HTTP/1.1 ${r.code} X\r\nContent-Length: ${r.body.size}\r\nConnection: close\r\n")
                r.type?.let { t -> head.append("Content-Type: $t\r\n") }
                r.headers.forEach { (k, v) -> head.append("$k: $v\r\n") }
                head.append("\r\n")
                it.getOutputStream().apply { write(head.toString().toByteArray()); write(r.body); flush() }
            }
        }

        override fun close() { running = false; runCatching { socket.close() } }
    }

    private fun dav(method: String, rawPath: String, headers: Map<String, String>, body: ByteArray): MiniHttp.Reply {
        val p = URLDecoder.decode(rawPath.substringBefore('?'), "UTF-8").let { if (it.length > 1) it.trimEnd('/') else it }
        return when (method) {
            "PROPFIND" -> {
                if (p !in dirs) return MiniHttp.Reply(404)
                val sb = StringBuilder("""<?xml version="1.0"?><d:multistatus xmlns:d="DAV:">""")
                sb.append("<d:response><d:href>${enc(p)}/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>")
                val prefix = if (p == "/") "/" else "$p/"
                for (d in dirs.toList()) if (d != "/" && d.startsWith(prefix) && !d.removePrefix(prefix).contains('/'))
                    sb.append("<d:response><d:href>${enc(d)}/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>")
                for ((f, b) in files) if (f.startsWith(prefix) && !f.removePrefix(prefix).contains('/'))
                    sb.append("<d:response><d:href>${enc(f)}</d:href><d:propstat><d:prop><d:resourcetype/><d:getcontentlength>${b.size}</d:getcontentlength></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>")
                sb.append("</d:multistatus>")
                MiniHttp.Reply(207, sb.toString().toByteArray(), "application/xml")
            }
            "GET" -> files[p]?.let { MiniHttp.Reply(200, it) } ?: MiniHttp.Reply(404)
            "PUT" -> { files[p] = body; MiniHttp.Reply(201) }
            "MKCOL" -> { dirs += p; MiniHttp.Reply(201) }
            "DELETE" -> { files.remove(p); dirs.remove(p); MiniHttp.Reply(204) }
            "MOVE" -> {
                val to = URLDecoder.decode(java.net.URI(headers.getValue("destination")).rawPath, "UTF-8")
                files.remove(p)?.let { files[to] = it }
                MiniHttp.Reply(201)
            }
            else -> MiniHttp.Reply(405)
        }
    }

    private fun enc(p: String) = p.split('/').joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

    private fun spec() = MountSpec("m1", "dav", MountType.WEBDAV, "127.0.0.1", server.port, "u", "/")
    private fun calls(access: Boolean = true, opener: ((MountSpec, MountSecret, File) -> RemoteFs)? = null) =
        if (opener == null) MountCalls(scratch, "test", { access }, clock = { now })
        else MountCalls(scratch, "test", { access }, opener, { now })
    private fun openReq(sp: MountSpec = spec()) = JSONObject().put("spec", MountWire.json.encodeToString(MountSpec.serializer(), sp))
        .put("password", "pw").put("passphrase", JSONObject.NULL).put("known_hosts", "")
    private fun fsReq(handle: String, verb: String, args: JSONObject = JSONObject()) = JSONObject().put("handle", handle).put("verb", verb).put("args", args)
    private fun ok(answer: String): JSONObject = JSONObject(answer).also { assertTrue(it.toString(), it.getBoolean("ok")) }
    private fun entries(a: JSONObject): List<RemoteEntry> = MountWire.json.decodeFromString(ListSerializer(RemoteEntry.serializer()), a.getString("result"))

    @Test fun aWebDavMountIsOpenedListedUploadedDownloadedRenamedAndDeleted() {
        val c = calls()
        dirs += "/docs"; files["/docs/a.txt"] = "alpha".toByteArray()
        val h = ok(c.open(openReq())).getString("handle")
        assertEquals(listOf("docs"), entries(ok(c.fs(fsReq(h, "list", JSONObject().put("path", "/"))))).map { it.name })
        assertEquals(listOf("a.txt"), entries(ok(c.fs(fsReq(h, "list", JSONObject().put("path", "/docs"))))).map { it.name })

        val into = File(root, "dl/a.txt")
        into.parentFile.mkdirs()
        val dl = ok(c.fs(fsReq(h, "download", JSONObject().put("path", "/docs/a.txt").put("into", into.absolutePath))))
        assertFalse("a download answers no result", dl.has("result")); assertEquals("alpha", into.readText())

        val up = File(root, "up.bin"); up.writeText("beta payload")
        ok(c.fs(fsReq(h, "upload", JSONObject().put("local", up.absolutePath).put("path", "/docs/b.bin"))))
        assertEquals("beta payload", String(files["/docs/b.bin"]!!))

        ok(c.fs(fsReq(h, "mkdir", JSONObject().put("path", "/docs/new"))))
        assertTrue("/docs/new" in dirs)
        ok(c.fs(fsReq(h, "rename", JSONObject().put("from", "/docs/b.bin").put("to", "/docs/c.bin"))))
        assertTrue("/docs/c.bin" in files && "/docs/b.bin" !in files)
        ok(c.fs(fsReq(h, "delete", JSONObject().put("path", "/docs/c.bin").put("is_dir", false))))
        assertFalse("/docs/c.bin" in files)

        assertFalse("webdav has no command channel, so no result", ok(c.fs(fsReq(h, "exec", JSONObject().put("command", "id")))).has("result"))
    }

    @Test fun aLongListingComesBackInChunksThatJoinToTheWhole() {
        val c = calls()
        dirs += "/big"
        repeat(6000) { files["/big/file-%05d-with-a-fairly-long-name.dat".format(it)] = ByteArray(it % 7) }
        val h = ok(c.open(openReq())).getString("handle")
        val first = ok(c.fs(fsReq(h, "list", JSONObject().put("path", "/big"))))
        assertTrue(first.getBoolean("more")); assertEquals(MountCalls.CHUNK, first.getString("result").length)
        val sb = StringBuilder(first.getString("result")); var more = true; val id = first.getString("id")
        while (more) {
            val next = ok(c.chunk(JSONObject().put("id", id).put("offset", sb.length)))
            sb.append(next.getString("result")); more = next.getBoolean("more")
        }
        assertEquals(6000, MountWire.json.decodeFromString(ListSerializer(RemoteEntry.serializer()), sb.toString()).size)
        assertFalse(JSONObject(c.chunk(JSONObject().put("id", id).put("offset", 0))).getBoolean("ok"))
    }

    @Test fun aHandleTheEngineNoLongerKnowsAnswersUnknownHandleSoTheClientCanReopen() {
        val c = calls()
        val a = JSONObject(c.fs(fsReq("gone", "list", JSONObject().put("path", "/"))))
        assertFalse(a.getBoolean("ok")); assertTrue(a.getString("error").startsWith(MountWire.UNKNOWN_HANDLE))
        val h = ok(c.open(openReq())).getString("handle")
        ok(c.close(JSONObject().put("handle", h)))
        assertTrue(JSONObject(c.fs(fsReq(h, "list", JSONObject().put("path", "/")))).getString("error").startsWith(MountWire.UNKNOWN_HANDLE))
    }

    @Test fun anIdleConnectionIsClosedAndForgotten() {
        var closed = 0
        val fake = object : RemoteFs {
            override fun list(path: String) = emptyList<RemoteEntry>()
            override fun download(path: String, into: File, onProgress: (Long) -> Unit) {}
            override fun upload(local: File, path: String, onProgress: (Long) -> Unit) {}
            override fun mkdir(path: String) {}
            override fun delete(path: String, isDir: Boolean) {}
            override fun rename(from: String, to: String) {}
            override fun close() { closed++ }
        }
        val c = calls(opener = { _, _, _ -> fake })
        val h = ok(c.open(openReq())).getString("handle")
        ok(c.fs(fsReq(h, "list", JSONObject().put("path", "/"))))
        now += MountCalls.IDLE_MS + 1
        assertTrue(JSONObject(c.fs(fsReq(h, "list", JSONObject().put("path", "/")))).getString("error").startsWith(MountWire.UNKNOWN_HANDLE))
        assertEquals(1, closed)
    }

    @Test fun theKeyAndKnownHostsAreFilesOnlyForTheConnectAndAnExtendedKnownHostsComesBack() {
        var seenKey: String? = null; var seenKeyPath: String? = null; var seenHosts: String? = null; var secretSeen: MountSecret? = null
        val fake = object : RemoteFs {
            override fun list(path: String) = emptyList<RemoteEntry>()
            override fun download(path: String, into: File, onProgress: (Long) -> Unit) {}
            override fun upload(local: File, path: String, onProgress: (Long) -> Unit) {}
            override fun mkdir(path: String) {}
            override fun delete(path: String, isDir: Boolean) {}
            override fun rename(from: String, to: String) {}
            override fun close() {}
        }
        val c = calls(opener = { sp, secret, hosts ->
            seenKeyPath = sp.keyPath; seenKey = File(sp.keyPath).readText(); seenHosts = hosts.readText(); secretSeen = secret
            hosts.appendText("h.example:22 SHA256:abc\n")                  // a first contact, recorded by the verifier
            fake
        })
        val sp = spec().copy(type = MountType.SFTP, keyPath = "/data/user/0/app/files/id_ed25519")
        val req = openReq(sp).put("password", JSONObject.NULL).put("passphrase", "pp").put("key", "PRIVATE KEY TEXT\n").put("known_hosts", "old:22 SHA256:old\n")
        val a = ok(c.open(req))
        assertEquals("PRIVATE KEY TEXT\n", seenKey); assertEquals("old:22 SHA256:old\n", seenHosts)
        assertEquals(MountSecret(null, "pp"), secretSeen)
        assertEquals("old:22 SHA256:old\nh.example:22 SHA256:abc\n", a.getString("known_hosts"))
        assertFalse("the engine used its own copy of the key, not the caller's path", seenKeyPath == sp.keyPath)
        assertEquals("the key and known_hosts are gone once connected", 0, scratch.listFiles().orEmpty().size)
    }

    @Test fun aRefusedLoginOrDeadHostIsASentenceNotACrash() {
        val a = JSONObject(calls().open(openReq(spec().copy(type = MountType.SFTP, port = 1))))        // sftp connects at open; nothing listens on port 1
        assertFalse(a.getBoolean("ok")); assertTrue(a.getString("error").isNotBlank())
        assertEquals(0, scratch.listFiles().orEmpty().size)
    }

    @Test fun anUnknownVerbIsRefusedAndASharedStorageTransferWithoutAllFilesAccessSaysWhatToGrant() {
        val c = calls(access = false)
        val h = ok(c.open(openReq())).getString("handle")
        val bad = JSONObject(c.fs(fsReq(h, "chmod")))
        assertFalse(bad.getBoolean("ok")); assertTrue(bad.getString("error").contains("list"))
        val shared = JSONObject(c.fs(fsReq(h, "download", JSONObject().put("path", "/x").put("into", "/storage/emulated/0/CloudDrive/mount-downloads/x"))))
        assertFalse(shared.getBoolean("ok")); assertTrue(shared.getString("error").contains("All files access"))
        assertNull("a private path needs no grant", JSONObject(c.fs(fsReq(h, "list", JSONObject().put("path", "/")))).optString("error").takeIf { it.isNotEmpty() })
    }

    @Test fun infoNamesTheVersionTheGrantAndTheOpenConnections() {
        val c = calls(access = false)
        ok(c.open(openReq()))
        val i = JSONObject(c.info())
        assertEquals("test", i.getString("version")); assertFalse(i.getBoolean("files_access")); assertEquals(1, i.getInt("open"))
    }
}
