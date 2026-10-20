package com.diegonmarcos.cloudlib.mounts

import kotlinx.serialization.builtins.ListSerializer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The client half of the mounts engine (#871) on the JVM, against a FAKE engine: what the client sends (the
 * spec, the secret and the ssh key as text on open; one fs step per verb), what it does with the answers (the
 * entries decoded, a long listing reassembled from chunks, a known_hosts the engine extended written back, a
 * refusal raised with its sentence), what it does when the engine lost the connection (opens it again, once,
 * and repeats the step) and with no engine at all. The engine's own half is libs:mounts-engine's MountCallsTest.
 */
class EngineFsTest {
    private lateinit var dir: File
    private val spec = MountSpec("m1", "files", MountType.SFTP, "10.0.0.6", 22, "diego", "/srv", keyPath = "")

    @Before fun setUp() { dir = Files.createTempDirectory("mounts-client-test").toFile() }

    private class Fake(val unavailable: String? = null, val answer: (String, JSONObject) -> JSONObject) : MountTransport {
        val calls = CopyOnWriteArrayList<Pair<String, JSONObject>>()
        override fun unavailable() = unavailable
        override fun session(): MountTransport.Session {
            if (unavailable != null) throw IOException(unavailable)
            return object : MountTransport.Session {
                private fun ask(method: String, request: JSONObject): JSONObject { calls += method to request; return answer(method, request) }
                override fun open(request: JSONObject) = ask("open", request)
                override fun fs(request: JSONObject) = ask("fs", request)
                override fun chunk(request: JSONObject) = ask("chunk", request)
                override fun closeHandle(request: JSONObject) = ask("close", request)
                override fun close() {}
            }
        }
    }

    private fun ok() = JSONObject().put("ok", true)
    private fun open(t: Fake, secret: MountSecret = MountSecret("pw", null), sp: MountSpec = spec) =
        MountFsFactory.open(t, sp, secret, File(dir, "private/known_hosts"))

    @Test fun openingConnectsNowAndEveryVerbIsOneFsStepOnTheHandle() {
        val f = Fake { m, req -> when (m) {
            "open" -> ok().put("handle", "H1")
            "close" -> ok()
            else -> when (req.getString("verb")) {
                "list" -> ok().put("result", MountWire.json.encodeToString(ListSerializer(RemoteEntry.serializer()), listOf(RemoteEntry("a", "/srv/a", false, 3, 5))))
                "exec" -> ok().put("result", "out\n[exit 0]")
                else -> ok()
            }
        } }
        val fs = open(f)
        assertEquals(listOf("open"), f.calls.map { it.first })
        assertEquals(listOf(RemoteEntry("a", "/srv/a", false, 3, 5)), fs.list("/srv"))
        val into = File(dir, "dl/a"); fs.download("/srv/a", into); fs.upload(File(dir, "x"), "/srv/x"); fs.mkdir("/srv/n"); fs.delete("/srv/a", false); fs.rename("/srv/a", "/srv/b")
        assertEquals("out\n[exit 0]", fs.exec("id"))
        fs.close()
        val steps = f.calls.filter { it.first == "fs" }
        assertEquals(setOf("list", "download", "upload", "mkdir", "delete", "rename", "exec"), steps.map { it.second.getString("verb") }.toSet())
        assertTrue(steps.all { it.second.getString("handle") == "H1" && it.second.getString("verb") in MountWire.VERBS })
        assertEquals(into.absolutePath, steps.first { it.second.getString("verb") == "download" }.second.getJSONObject("args").getString("into"))
        assertTrue("the connection is closed on close()", f.calls.last().first == "close")
        assertEquals("only one open: the handle was kept", 1, f.calls.count { it.first == "open" })
    }

    @Test fun theSpecTheSecretTheKeyTextAndKnownHostsTravelOnOpenAndNothingElseDoes() {
        val key = File(dir, "id"); key.writeText("KEY TEXT\n")
        val hosts = File(dir, "private/known_hosts"); hosts.parentFile.mkdirs(); hosts.writeText("old:22 SHA256:x\n")
        val f = Fake { _, _ -> ok().put("handle", "H").put("known_hosts", "old:22 SHA256:x\nnew:22 SHA256:y\n") }
        MountFsFactory.open(f, spec.copy(keyPath = key.absolutePath), MountSecret(null, "pp"), hosts)
        val r = f.calls.single().second
        assertEquals(spec.copy(keyPath = key.absolutePath), MountWire.json.decodeFromString(MountSpec.serializer(), r.getString("spec")))
        assertTrue(r.isNull("password")); assertEquals("pp", r.getString("passphrase"))
        assertEquals("KEY TEXT\n", r.getString("key")); assertEquals("old:22 SHA256:x\n", r.getString("known_hosts"))
        assertEquals("a known_hosts the engine extended is stored where it came from", "old:22 SHA256:x\nnew:22 SHA256:y\n", hosts.readText())
    }

    @Test fun aPasswordMountSendsNoKeyAtAll() {
        val f = Fake { _, _ -> ok().put("handle", "H") }
        open(f)
        val r = f.calls.single().second
        assertFalse(r.has("key")); assertEquals("pw", r.getString("password")); assertTrue(r.isNull("passphrase"))
    }

    @Test fun aLostConnectionIsReopenedOnceAndTheStepRepeated() {
        var handles = 0; var failedOnce = false
        val f = Fake { m, req -> when (m) {
            "open" -> ok().put("handle", "H${++handles}")
            else -> if (req.getString("handle") == "H1" && !failedOnce) { failedOnce = true; JSONObject().put("ok", false).put("error", "${MountWire.UNKNOWN_HANDLE}: the connection is gone") }
                    else ok().put("result", "[]")
        } }
        val fs = open(f)
        assertTrue(fs.list("/").isEmpty())
        assertEquals(2, f.calls.count { it.first == "open" })
        assertEquals(listOf("H1", "H2"), f.calls.filter { it.first == "fs" }.map { it.second.getString("handle") })
    }

    @Test fun aLongListingIsReassembledFromItsChunks() {
        val whole = MountWire.json.encodeToString(ListSerializer(RemoteEntry.serializer()), (1..50).map { RemoteEntry("n$it", "/n$it", false, it.toLong(), 0) })
        val cut = whole.length / 3
        val f = Fake { m, req -> when (m) {
            "open" -> ok().put("handle", "H")
            "fs" -> ok().put("result", whole.substring(0, cut)).put("more", true).put("id", "L1")
            else -> { assertEquals("L1", req.getString("id")); assertEquals(cut, req.getInt("offset")); ok().put("result", whole.substring(cut)).put("more", false) }
        } }
        assertEquals(50, open(f).list("/").size)
    }

    @Test fun aRefusalIsAnIOExceptionWithTheEnginesSentence() {
        val f = Fake { m, _ -> if (m == "open") ok().put("handle", "H") else JSONObject().put("ok", false).put("error", "the mounts engine has no All files access") }
        val e = runCatching { open(f).download("/a", File(dir, "a")) }.exceptionOrNull()
        assertTrue(e is IOException); assertEquals("the mounts engine has no All files access", e!!.message)
        val login = Fake { _, _ -> JSONObject().put("ok", false).put("error", "Exhausted available authentication methods") }
        assertEquals("Exhausted available authentication methods", runCatching { open(login) }.exceptionOrNull()!!.message)
    }

    @Test fun noEngineInstalledIsASentenceAtOpenNotACrash() {
        val none = Fake(unavailable = "the mounts engine com.x is not installed — install it from Store") { _, _ -> error("never called") }
        assertEquals("the mounts engine com.x is not installed — install it from Store", none.unavailable())
        val e = runCatching { open(none) }.exceptionOrNull()
        assertTrue(e is IOException); assertTrue(e!!.message!!.contains("not installed"))
    }

    @Test fun anSshKeyThatIsMissingIsASentenceBeforeAnythingIsSent() {
        val f = Fake { _, _ -> error("never called") }
        val e = runCatching { MountFsFactory.open(f, spec.copy(keyPath = File(dir, "nokey").absolutePath), MountSecret(null, null), File(dir, "kh")) }.exceptionOrNull()
        assertTrue(e is IOException && e.message!!.contains("ssh key not found")); assertNull(f.calls.firstOrNull())
    }
}
