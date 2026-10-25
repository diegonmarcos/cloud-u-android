package com.diegonmarcos.clouddrive.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Rclone page's wizard is driven by `rclone config providers` JSON, never a hand-made list: this is that JSON's shape, parsed. */
class RcloneProvidersTest {
    // A cut of the real `rclone config providers` output (field names are rclone's own).
    private val json = """[
      {"Name":"s3","Description":"Amazon S3 Compliant Storage Providers","Options":[
        {"Name":"provider","Help":"Choose your S3 provider.","Provider":"","Default":"","Exclusive":true,"Type":"string","Examples":[{"Value":"AWS","Help":"Amazon Web Services (AWS) S3","Provider":""},{"Value":"Other","Help":"Any other","Provider":""}]},
        {"Name":"access_key_id","Help":"AWS Access Key ID.","Provider":"","Default":"","Sensitive":true,"Type":"string"},
        {"Name":"region","Help":"Region to connect to.","Provider":"AWS","Default":"","Type":"string","Examples":[{"Value":"us-east-1","Help":"Virginia","Provider":"AWS"},{"Value":"other-v2-signature","Help":"v2","Provider":"!AWS"}]},
        {"Name":"endpoint","Help":"Endpoint for S3 API.","Provider":"!AWS","Default":"","Type":"string"},
        {"Name":"chunk_size","Help":"Chunk size.","Provider":"","Default":5242880,"DefaultStr":"5Mi","Advanced":true,"Type":"SizeSuffix"},
        {"Name":"internal","Help":"never asked","Provider":"","Hide":2,"Type":"string"}
      ]},
      {"Name":"drive","Description":"Google Drive","Options":[
        {"Name":"client_id","Help":"Google Application Client Id","Provider":"","Type":"string"},
        {"Name":"scope","Help":"Comma separated list of scopes.","Provider":"","Exclusive":true,"Type":"string","Examples":[{"Value":"drive","Help":"Full access","Provider":""}]},
        {"Name":"service_account_credentials","Help":"Service Account Credentials JSON blob.","Provider":"","Advanced":true,"Sensitive":true,"Type":"string"},
        {"Name":"team_drive","Help":"ID of the Shared Drive (Team Drive).","Provider":"","Advanced":true,"Sensitive":true,"Type":"string"},
        {"Name":"token","Help":"OAuth Access Token as a JSON blob.","Provider":"","Advanced":true,"Sensitive":true,"Type":"string"},
        {"Name":"auth_url","Help":"Auth server URL.","Provider":"","Advanced":true,"Type":"string"}
      ]},
      {"Name":"alias","Description":"Alias for an existing remote","Options":[{"Name":"remote","Help":"Remote or path to alias.","Required":true,"Provider":"","Type":"string"}]}
    ]"""
    private val providers = RcloneProviders.parse(json)
    private fun p(n: String) = providers.first { it.name == n }

    @Test fun `the backend list is rclone's, in name order, and only that`() {
        assertEquals(listOf("alias", "drive", "s3"), providers.map { it.name })
        assertEquals(emptyList<RcloneProviders.Provider>(), RcloneProviders.parse("not json"))
        assertEquals(emptyList<RcloneProviders.Provider>(), RcloneProviders.parse(""))
    }

    @Test fun `option fields come from the schema`() {
        val chunk = p("s3").options.first { it.name == "chunk_size" }
        assertEquals("5Mi", chunk.default); assertTrue(chunk.advanced); assertEquals("SizeSuffix", chunk.type)
        assertTrue(p("alias").options.single().required)
        assertTrue(p("s3").options.first { it.name == "access_key_id" }.sensitive)
        assertTrue(p("s3").options.first { it.name == "provider" }.exclusive)
    }

    @Test fun `oauth backends and Google Drive extras are recognised from option names`() {
        assertTrue(p("drive").oauth); assertTrue(p("drive").hasServiceAccount); assertTrue(p("drive").hasSharedDrives)
        assertFalse(p("s3").oauth); assertFalse(p("s3").hasServiceAccount)
    }

    @Test fun `provider rules include, exclude and always`() {
        assertTrue(RcloneProviders.applies("", "x"))
        assertTrue(RcloneProviders.applies("AWS,Ceph", "Ceph")); assertFalse(RcloneProviders.applies("AWS,Ceph", "Other"))
        assertTrue(RcloneProviders.applies("!AWS", "Other")); assertFalse(RcloneProviders.applies("!AWS,Ceph", "Ceph"))
        assertTrue(RcloneProviders.applies("!AWS", ""))
    }

    @Test fun `steps follow the chosen provider variant and hide advanced, hidden and oauth-owned options`() {
        val aws = RcloneProviders.steps(p("s3"), mapOf("provider" to "AWS"), advanced = false).map { it.name }
        assertEquals(listOf("provider", "access_key_id", "region"), aws)
        val other = RcloneProviders.steps(p("s3"), mapOf("provider" to "Other"), advanced = false).map { it.name }
        assertEquals(listOf("provider", "access_key_id", "endpoint"), other)
        assertTrue("chunk_size" in RcloneProviders.steps(p("s3"), mapOf("provider" to "AWS"), advanced = true).map { it.name })
        assertFalse("internal" in RcloneProviders.steps(p("s3"), emptyMap(), advanced = true).map { it.name })
        val drive = RcloneProviders.steps(p("drive"), emptyMap(), advanced = true).map { it.name }
        assertFalse("token" in drive); assertFalse("auth_url" in drive); assertTrue("team_drive" in drive)
        assertEquals(listOf("us-east-1"), RcloneProviders.examples(p("s3").options.first { it.name == "region" }, mapOf("provider" to "AWS")).map { it.value })
    }

    @Test fun `create and update are the real rclone config verbs, non-interactive, with only answered keys`() {
        assertEquals(listOf("config", "create", "my drive", "drive", "scope=drive", "team_drive=0AB", "--non-interactive"),
            RcloneProviders.createArgs("my drive", "drive", linkedMapOf("scope" to "drive", "client_id" to "", "team_drive" to "0AB")))
        assertEquals(listOf("config", "update", "r1", "region=eu", "--non-interactive"), RcloneProviders.updateArgs("r1", mapOf("region" to "eu")))
        assertEquals(listOf("config", "delete", "r1"), RcloneProviders.deleteArgs("r1"))
        assertEquals(listOf("config", "show", "r1"), RcloneProviders.showArgs("r1"))
        assertEquals(listOf("lsd", "r1:"), RcloneProviders.lsdArgs("r1"))
        assertEquals(listOf("about", "r1:", "--json"), RcloneProviders.aboutArgs("r1"))
        assertEquals(listOf("backend", "drives", "r1:"), RcloneProviders.drivesArgs("r1"))
        assertEquals(listOf("config", "providers"), RcloneProviders.providersArgs())
    }

    @Test fun `a name or key that could become a flag or a second argument is refused`() {
        for (bad in listOf("", "-x", " x", "x ", "a:b", "a/b", "a=b")) assertFalse("'$bad'", RcloneProviders.validName(bad))
        assertTrue(RcloneProviders.validName("work drive")); assertTrue(RcloneProviders.validName("gd.team-1"))
        assertEquals(listOf("config", "create", "r", "s3"), RcloneProviders.createArgs("r", "s3", mapOf("--config" to "x", "a b" to "y", "A" to "z")).dropLast(1))
        var failed = false
        try { RcloneProviders.createArgs("-x", "s3", emptyMap()) } catch (e: IllegalArgumentException) { failed = true }
        assertTrue(failed)
    }

    @Test fun `authorize output is read for the sign-in url and the token`() {
        val lines = listOf("2024/01/01 NOTICE: Make sure your Redirect URL is set to \"http://127.0.0.1:53682/\" in your custom config.", "NOTICE: If your browser doesn't open automatically go to the following link: http://127.0.0.1:53682/auth?state=abc_def")
        assertEquals("http://" + "127.0.0.1:53682/auth?state=abc_def", RcloneProviders.authUrl(lines))
        assertNull(RcloneProviders.authUrl(listOf("nothing")))
        val out = "Waiting for code...\nGot code\nPaste the following into your remote machine --->\n{\"access_token\":\"x\",\"expiry\":\"e\"}\n<---End paste\n"
        assertEquals("{\"access_token\":\"x\",\"expiry\":\"e\"}", RcloneProviders.token(out))
        assertNull(RcloneProviders.token("Paste the following into your remote machine --->\nnot json\n<---End paste"))
        assertNull(RcloneProviders.token("no markers"))
        assertEquals(listOf("authorize", "drive", "id", "secret", "--auth-no-open-browser"), RcloneProviders.authorizeArgs("drive", "id", "secret"))
        assertEquals(listOf("authorize", "drive", "--auth-no-open-browser"), RcloneProviders.authorizeArgs("drive"))
    }

    @Test fun `lsd, about and shared drives are parsed from what rclone prints, unknown stays unknown`() {
        assertEquals(listOf("Documents", "My Photos"), RcloneProviders.lsd("          -1 2024-05-01 10:00:00        -1 Documents\n          -1 2024-05-02 11:00:00        -1 My Photos\n"))
        assertEquals(emptyList<String>(), RcloneProviders.lsd(""))
        val a = RcloneProviders.about("{\"total\":100,\"used\":40,\"free\":60}")!!
        assertEquals(100L, a.total); assertEquals(60L, a.free); assertNull(a.trashed)
        assertNull(RcloneProviders.about("oops"))
        assertEquals(listOf(RcloneProviders.SharedDrive("0AB", "Team")), RcloneProviders.drives("[{\"id\":\"0AB\",\"name\":\"Team\",\"kind\":\"drive#drive\"},{\"id\":\"\",\"name\":\"x\"}]"))
    }

    @Test fun `secret-looking keys are masked`() {
        for (k in listOf("token", "pass", "client_secret", "access_key_id", "service_account_credentials")) assertTrue(k, RcloneProviders.masked(k))
        for (k in listOf("scope", "region", "endpoint", "team_drive")) assertFalse(k, RcloneProviders.masked(k))
    }
}
