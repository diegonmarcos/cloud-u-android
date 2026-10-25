package com.diegonmarcos.clouddrive.storage

import com.diegonmarcos.clouddrive.storage.DriveCredentialsClient.AccountMount
import com.diegonmarcos.clouddrive.storage.DriveCredentialsClient.Read
import com.diegonmarcos.clouddrive.storage.DriveCredentialsClient.Reply
import com.diegonmarcos.cloudlib.rclone.RcloneRemote
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Drive consuming Cloud Account's credentials: what it reads, what it hands back, and which side wins. Every secret below is a made-up marker. */
class DriveCredentialsClientTest {
    private val marker = "tok-" + "DRIVE-" + "909090"
    private class Fake(var reply: Reply) : DriveCredentialsClient.Transport {
        val calls = mutableListOf<Pair<String, String?>>()
        override fun call(method: String, arg: String?): Reply { calls += method to arg; return reply }
    }
    private fun published(rc: Boolean = true, priv: Boolean = true, unison: String? = null) = JSONObject().put("v", 1)
        .put("rclone", org.json.JSONArray().put(JSONObject().put("name", "gdrive").put("type", "drive")))
        .also { o -> if (rc) o.put("rclone_config", "[gdrive]\ntype = drive\ntoken = $marker\n") }
        .put("ssh", JSONObject().put("public", "ssh-ed25519 AAAA c").also { if (priv) it.put("private", "PRIV-$marker") })
        .put("mounts", org.json.JSONArray().put(JSONObject().put("name", "nas").put("kind", "sshfs").put("host", "nas.mesh.diegonmarcos.com").put("user", "diego").put("port", 22).put("path", "/data").put("mountpoint", "/root/mnt/nas")))
        .also { o -> if (unison != null) o.put("unison", unison) }.toString()

    @Test fun `a read unpacks the layers Account granted and marks the others absent`() {
        val ok = DriveCredentialsClient(Fake(Reply(true, published(), null))).read() as Read.Ok
        assertEquals(listOf("gdrive" to "drive"), ok.snapshot.remotes); assertTrue(ok.snapshot.rcloneConfig!!.contains(marker))
        assertEquals("PRIV-$marker", ok.snapshot.sshPrivate); assertTrue(ok.snapshot.hasSshKey); assertNull(ok.snapshot.missingGrants)
        assertEquals("/root/mnt/nas", ok.snapshot.mounts.single().mountpoint)
        val partial = (DriveCredentialsClient(Fake(Reply(true, published(rc = false, priv = false), null))).read() as Read.Ok).snapshot
        assertNull(partial.rcloneConfig); assertNull(partial.sshPrivate); assertEquals("drive.rclone, ssh.private", partial.missingGrants)
    }

    @Test fun `a refusal, a missing Account and a foreign version are sentences, not crashes`() {
        val refused = DriveCredentialsClient(Fake(Reply(false, null, "no grant for com.diegonmarcos.clouddrive"))).read() as Read.Unavailable
        assertTrue(refused.why, refused.why.contains("Secrets"))
        val gone = DriveCredentialsClient(object : DriveCredentialsClient.Transport { override fun call(method: String, arg: String?): Reply = throw IllegalStateException("x") }).read() as Read.Unavailable
        assertTrue(gone.why.contains("not installed"))
        assertTrue(DriveCredentialsClient(Fake(Reply(true, "{\"v\":9}", null))).read() is Read.Unavailable)
        assertTrue(DriveCredentialsClient(Fake(Reply(true, "nope", null))).read() is Read.Unavailable)
    }

    @Test fun `a remote goes to Account as the contract's put body, blank and multi-line fields dropped, an unsupported type kept local`() {
        val f = Fake(Reply(true, null, null)); val c = DriveCredentialsClient(f)
        assertNull(c.putRemote("gdrive", "drive", mapOf("scope" to "drive", "token" to marker, "client_id" to "", "note" to "a\nb")))
        val (method, body) = f.calls.single(); assertEquals("drive_credentials_put", method)
        val r = JSONObject(body!!).getJSONObject("rclone"); assertEquals("gdrive", r.getString("name")); assertEquals("drive", r.getString("type"))
        assertEquals(setOf("scope", "token"), r.getJSONObject("fields").keys().asSequence().toSet())
        assertTrue(c.putRemote("x", "azureblob", emptyMap())!!.contains("kept on this phone only")); assertEquals(1, f.calls.size)
    }

    @Test fun `removals, mounts and pairs use the contract's other operations`() {
        val f = Fake(Reply(true, null, null)); val c = DriveCredentialsClient(f)
        c.removeRemote("gdrive"); c.removeMount("nas")
        c.putMount(AccountMount("nas", "sshfs", "", "nas.mesh.diegonmarcos.com", "diego", 22, "/data", "/root/mnt/nas"))
        c.putUnison("{\"pairs\":[]}")
        val bodies = f.calls.map { JSONObject(it.second!!) }
        assertEquals("gdrive", bodies[0].getString("remove_rclone")); assertEquals("nas", bodies[1].getString("remove_mount"))
        assertEquals("sshfs", bodies[2].getJSONObject("mount").getString("kind")); assertEquals("{\"pairs\":[]}", bodies[3].getString("unison"))
        assertNotNull(DriveCredentialsClient(Fake(Reply(true, null, null))).putUnison("x".repeat(Wire.MAX_UNISON_BYTES + 1)))
    }

    @Test fun `a refused write returns Account's words and never the value`() {
        val why = DriveCredentialsClient(Fake(Reply(false, null, "no grant for com.diegonmarcos.clouddrive"))).putRemote("g", "drive", mapOf("token" to marker))!!
        assertFalse(why.contains(marker)); assertTrue(why.contains("Secrets"))
    }

    private fun rr(name: String, type: String = "drive", vararg o: Pair<String, String>) = RcloneRemote(name, type, o.toMap())

    @Test fun `a new phone restores what only Account holds, the phone keeps what it has`() {
        val account = "[gdrive]\ntype = drive\ntoken = $marker\n\n[old]\ntype = s3\nprovider = Other\n"
        val restore = CredentialMerge.remotesToRestore(listOf(rr("gdrive", "drive", "token" to "fresh")), account)
        assertEquals(listOf("old"), restore.map { it.name }); assertEquals("Other", restore.single().options["provider"])
        assertEquals(listOf("gdrive", "old"), CredentialMerge.remotesToRestore(emptyList(), account).map { it.name })
        assertEquals(emptyList<RcloneRemote>(), CredentialMerge.remotesToRestore(listOf(rr("a")), null))
    }

    @Test fun `a remote only the phone holds, or whose token was refreshed, is handed to Account`() {
        val account = "[gdrive]\ntype = drive\ntoken = $marker\nclient_id = \n"
        val held = listOf("gdrive" to "drive")
        assertEquals(listOf("new"), CredentialMerge.remotesToPush(listOf(rr("gdrive", "drive", "token" to marker), rr("new", "s3")), held, account).map { it.name })
        assertEquals("a refreshed token differs", listOf("gdrive"), CredentialMerge.remotesToPush(listOf(rr("gdrive", "drive", "token" to "refreshed")), held, account).map { it.name })
        assertEquals("without the secret layer only the missing ones", emptyList<RcloneRemote>(), CredentialMerge.remotesToPush(listOf(rr("gdrive", "drive", "token" to "x")), held, null))
        assertEquals("a backend Account does not store is never pushed", emptyList<RcloneRemote>(), CredentialMerge.remotesToPush(listOf(rr("az", "azureblob")), held, account))
    }

    @Test fun `mounts convert both ways and an Account mount that would not run is skipped`() {
        val rc = FuseWire.Spec("gd", FuseWire.Kind.RCLONE, "gdrive:Docs/x", "/root/mnt/gd")
        val ss = FuseWire.Spec("box", FuseWire.Kind.SSHFS, "me@box.mesh.diegonmarcos.com:/srv", "/root/mnt/box")
        for (s in listOf(rc, ss)) assertEquals(s, CredentialMerge.mountFromAccount(CredentialMerge.mountToAccount(s)))
        assertEquals("gdrive", CredentialMerge.mountToAccount(rc).remote); assertEquals("Docs/x", CredentialMerge.mountToAccount(rc).path)
        assertEquals("me", CredentialMerge.mountToAccount(ss).user); assertEquals("box.mesh.diegonmarcos.com", CredentialMerge.mountToAccount(ss).host)
        assertNull(CredentialMerge.mountFromAccount(AccountMount("x", "bogus", "", "", "", 22, "", "")))
        assertNull(CredentialMerge.mountFromAccount(AccountMount("x", "sshfs", "", "h", "u", 22, "relative", "/root/mnt/x")))
        assertEquals("/root/mnt/nas", CredentialMerge.mountFromAccount(AccountMount("nas", "sshfs", "", "nas.mesh.diegonmarcos.com", "diego", 22, "/data", ""))!!.mountpoint)
        assertEquals(listOf("box"), CredentialMerge.mountsToRestore(listOf(rc), listOf(CredentialMerge.mountToAccount(rc), CredentialMerge.mountToAccount(ss))).map { it.id })
        assertEquals(listOf("box"), CredentialMerge.mountsToPush(listOf(rc, ss), listOf(CredentialMerge.mountToAccount(rc))).map { it.id })
        assertEquals(emptyList<FuseWire.Spec>(), CredentialMerge.mountsToPush(listOf(rc), listOf(CredentialMerge.mountToAccount(rc))))
    }

    @Test fun `the pair list follows the same ownership rule`() {
        assertEquals(CredentialMerge.PairAction.RESTORE, CredentialMerge.pairs("", "{\"pairs\":[1]}", true))
        assertEquals(CredentialMerge.PairAction.PUSH, CredentialMerge.pairs("{\"pairs\":[1]}", null, true))
        assertEquals(CredentialMerge.PairAction.PUSH, CredentialMerge.pairs("{\"pairs\":[1]}", "{\"pairs\":[]}", true))
        assertEquals(CredentialMerge.PairAction.NONE, CredentialMerge.pairs("{\"pairs\":[1]}", "{\"pairs\":[1]}", true))
        assertEquals(CredentialMerge.PairAction.NONE, CredentialMerge.pairs("", null, true))
        assertEquals("Account unreachable: touch nothing", CredentialMerge.PairAction.NONE, CredentialMerge.pairs("{\"pairs\":[1]}", null, false))
    }
}
