package com.diegonmarcos.clouddrive.configs

import com.diegonmarcos.clouddrive.Declarations
import com.diegonmarcos.cloudlib.gitsync.ManagedRepo
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #587 what a drive sign-in yields, decided by the EXACT planner the phone runs,
 * against THIS repository's build.json::auth.applies (the module dir is the working
 * directory of a Gradle unit test, so ../build.json is the app's own) and the
 * artifact shapes the superapp's import schema declares. Pure: no store is touched.
 * Robolectric only because org.json is an Android class on the unit-test classpath.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DriveAuthApplyTest {

    private val root = File(System.getProperty("user.dir")).let { if (File(it, "build.json").isFile) it else it.parentFile }
    private val appliesText: String = Json { ignoreUnknownKeys = true }
        .parseToJsonElement(File(root, "build.json").readText()).jsonObject["auth"]!!.jsonObject["applies"].toString()
    private val applies = Declarations.parseAuthApplies(appliesText)

    private fun repo(name: String, auth: String) = ManagedRepo(id = "id-$name", name = name, path = "/store/$name", authKind = auth)

    @Test fun `the declaration names the two drive sections and their keys`() {
        assertEquals(setOf("git", "rclone"), applies.keys)
        assertEquals("github_token", applies["git"])
        assertEquals("remotes", applies["rclone"])
    }

    @Test fun `a token reaches every managed https repository and no other`() {
        val artifact = JSONObject("""{"_meta":{"user":"u"},"git":{"github_token":" ghp_x "}}""")
        val r = DriveAuthApply.plan(artifact, applies, listOf(repo("a", "https"), repo("b", "ssh"), repo("c", "https")))
        val git = r.steps.single { it.section == "git" }
        assertTrue(git.line, git.ok)
        assertTrue(git.line, git.line.contains("2 repositories") && git.line.contains("a") && git.line.contains("c") && !git.line.contains(", b"))
        assertEquals("ghp_x", DriveAuthApply.gitToken(artifact, "github_token").first)
        assertTrue(r.ok)
    }

    @Test fun `a token with no https repository yet says clone first, and nothing is ok`() {
        val artifact = JSONObject("""{"git":{"github_token":"ghp_x"}}""")
        val r = DriveAuthApply.plan(artifact, applies, listOf(repo("a", "ssh")))
        assertFalse(r.ok)
        assertTrue(r.text(), r.text().contains("clone into the store first"))
    }

    @Test fun `a pending placeholder is not a token, and says so`() {
        val artifact = JSONObject("""{"git":{"github_token":{"pending":true,"source":"vault","reason":"not emitted"}}}""")
        val (token, why) = DriveAuthApply.gitToken(artifact, "github_token")
        assertEquals(null, token)
        assertTrue(why, why.contains("pending") && why.contains("not emitted"))
    }

    @Test fun `the vault envelope is read through to its bundle`() {
        val artifact = JSONObject("""{"schema":{},"bundle":{"schema_version":1,"git":{"github_token":"ghp_y"}}}""")
        assertEquals("ghp_y", DriveAuthApply.gitToken(artifact, "github_token").first)
    }

    @Test fun `an artifact without the sections reports each absence, none ok`() {
        val r = DriveAuthApply.plan(JSONObject("""{"wg":{},"mail":{}}"""), applies, listOf(repo("a", "https")))
        assertEquals(applies.size, r.steps.size)
        assertTrue(r.steps.none { it.ok })
        assertTrue(r.text(), r.text().contains("no git section") && r.text().contains("no rclone section"))
    }

    @Test fun `rclone remotes become conf sections, invalid names and typeless entries dropped`() {
        val artifact = JSONObject("""{"rclone":{"remotes":{
            "oracle_s3":{"type":"s3","options":{"provider":"Other","access_key_id":"AK","secret_access_key":"SK"}},
            "bad:name":{"type":"s3"},
            "notype":{"options":{"x":"y"}}
        }}}""")
        val (remotes, why) = DriveAuthApply.rcloneRemotes(artifact, "remotes")
        assertEquals(listOf("oracle_s3"), remotes.map { it.name })
        assertEquals("s3", remotes.single().type)
        assertEquals("SK", remotes.single().options["secret_access_key"])
        assertEquals("rclone.remotes", why)
        val r = DriveAuthApply.plan(artifact, applies, emptyList())
        assertTrue(r.steps.single { it.section == "rclone" }.ok)
    }

    @Test fun `an unknown declared section is refused loudly, never silently skipped`() {
        val r = DriveAuthApply.plan(JSONObject("{}"), mapOf("mesh" to "wg0_private_key"), emptyList())
        assertFalse(r.ok)
        assertTrue(r.text(), r.text().contains("mesh") && r.text().contains("nothing here knows"))
    }

    @Test fun `a blank or broken bake declares nothing`() {
        assertTrue(Declarations.parseAuthApplies("").isEmpty())
        assertTrue(Declarations.parseAuthApplies("not json").isEmpty())
        assertTrue(Declarations.parseAuthApplies("""{"_doc":"x","git":{"into":"y"}}""").isEmpty())
    }
}
