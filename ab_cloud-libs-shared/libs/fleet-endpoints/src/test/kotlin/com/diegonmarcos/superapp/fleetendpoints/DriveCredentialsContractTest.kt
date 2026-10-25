package com.diegonmarcos.superapp.fleetendpoints

import com.diegonmarcos.superapp.fleetendpoints.DriveCredentialsContract as D
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Drive/Git credential wire: default deny per secret layer, a generated rclone.conf and ssh_config, declared ssh hosts. */
class DriveCredentialsContractTest {
    private val marker = "tok-" + "DRIVE-" + "424242" // a made-up marker, not a credential
    private val remotes = listOf(
        D.Remote("gdrive", "drive", mapOf("scope" to "drive", "token" to marker, "client_id" to "cid")),
        D.Remote("gws", "drive", mapOf("scope" to "drive", "service_account_credentials" to "{\"k\":1}")),
        D.Remote("bad name", "drive", emptyMap()))
    private val mounts = listOf(D.Mount("nas", "sshfs", "", "nas.mesh.diegonmarcos.com", "diego", 22, "/data", "/mnt/nas", password = marker))
    private val hosts = listOf(D.SshHost("github.com", "github.com", "git", 22, "~/.ssh/id_ed25519"))
    private fun json(rc: Boolean, ssh: Boolean, mt: Boolean) =
        JSONObject(D.toJson(remotes, "ssh-ed25519 AAAA c", "SHA256:x", hosts, "PRIV-" + marker, mounts, rc, ssh, mt))

    @Test fun thePublicLayerCarriesNoSecretAndTheSecretKeysAreAbsent() {
        val j = json(false, false, false)
        assertFalse(j.toString().contains(marker))
        assertFalse(j.has("rclone_config")); assertFalse(j.has("mount_secrets")); assertFalse(j.getJSONObject("ssh").has("private"))
        assertEquals("ssh-ed25519 AAAA c", j.getJSONObject("ssh").getString("public"))
        assertEquals(3, j.getJSONArray("rclone").length())
        assertEquals("service_account", j.getJSONArray("rclone").getJSONObject(1).getString("auth"))
    }

    @Test fun eachSecretLayerOpensOnlyWithItsOwnFlag() {
        assertTrue(json(true, false, false).getString("rclone_config").contains(marker))
        assertFalse(json(true, false, false).toString().contains("PRIV-"))
        assertEquals("PRIV-$marker", json(false, true, false).getJSONObject("ssh").getString("private"))
        assertEquals(marker, json(false, false, true).getJSONObject("mount_secrets").getJSONObject("nas").getString("password"))
        assertFalse(json(false, false, true).has("rclone_config"))
    }

    @Test fun rcloneConfigIsIniAndDropsABadRemoteName() {
        val c = D.rcloneConfig(remotes)
        assertTrue(c.startsWith("[gdrive]\ntype = drive\n"))
        assertTrue(c.contains("[gws]")); assertFalse(c.contains("bad name"))
    }

    @Test fun sshConfigHasHostHostNameUserPortIdentityFile() {
        val c = D.sshConfig(hosts + D.SshHost("gitea", "gitea.mesh.diegonmarcos.com", "git", 2222, "~/.ssh/id_ed25519"))
        for (k in listOf("Host github.com", "HostName github.com", "User git", "Port 22", "IdentityFile ~/.ssh/id_ed25519", "Host gitea", "HostName gitea.mesh.diegonmarcos.com", "Port 2222"))
            assertTrue(k, c.contains(k))
    }

    @Test fun sshHostsComeFromTheDeclarationAndAServiceNamesItsMeshHost() {
        val decl = """{"services":[{"id":"gitea","kind":"git","mesh_dns":"gitea.mesh.diegonmarcos.com","mesh_port":3002,"mesh_scheme":"http","policy":"mesh_only"}],
            "ssh_hosts":[{"host":"github.com","hostname":"github.com"},{"host":"gitea","service":"gitea","port":2222},{"host":"x","service":"nope"},{"host":"y","hostname":"y.example","port":0}]}"""
        val h = D.parseSshHosts(decl, FleetEndpointsContract.parseDecl(decl), "~/.ssh/id")
        assertEquals(listOf("github.com", "gitea"), h.map { it.host })
        assertEquals("gitea.mesh.diegonmarcos.com", h[1].hostname); assertEquals(2222, h[1].port); assertEquals("git", h[1].user)
    }

    @Test fun parseRoundTripsAndMasksInToString() {
        val p = D.parse(D.toJson(remotes, "k", "f", hosts, "PRIV", mounts, true, true, true))!!
        assertEquals(listOf("gdrive", "gws", "bad name"), p.remotes)
        assertTrue(p.rcloneConfig!!.contains(marker)); assertEquals("PRIV", p.privateKey)
        assertFalse(p.toString().contains(marker)); assertFalse(p.toString().contains("PRIV"))
        assertNull(D.parse("""{"v":9}""")); assertNull(D.parse("nope"))
        assertNull(D.parse(D.toJson(remotes, "k", "f", hosts, "PRIV", mounts, false, false, false))!!.rcloneConfig)
    }

    @Test fun theGrantsAreDistinctAndDoNotOverlapTheEndpointGrants() {
        assertEquals(D.GRANTS.size, D.GRANTS.toSet().size)
        assertTrue(D.GRANTS.none { it in listOf(FleetEndpointsContract.GRANT_ENDPOINTS, FleetEndpointsContract.GRANT_BEARER) })
    }

    @Test fun thePutBodyIsOneOfFourOperationsAndAnUnusableNameIsNone() {
        val r = D.parsePut("""{"rclone":{"name":"gdrive","type":"drive","fields":{"token":"t","scope":"drive"}}}""") as D.Put.RemoteWrite
        assertEquals("gdrive", r.remote.name); assertEquals("t", r.remote.fields["token"])
        assertTrue(D.parsePut("""{"mount":{"name":"nas","kind":"sshfs","host":"h","port":2222}}""") is D.Put.MountWrite)
        assertEquals(D.Put.RemoveRemote("gdrive"), D.parsePut("""{"remove_rclone":"gdrive"}"""))
        assertEquals(D.Put.RemoveMount("nas"), D.parsePut("""{"remove_mount":"nas"}"""))
        assertNull(D.parsePut("""{"rclone":{"name":"bad name"}}""")); assertNull(D.parsePut("nope")); assertNull(D.parsePut(null)); assertNull(D.parsePut("{}"))
    }
}
