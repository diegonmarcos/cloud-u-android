package com.diegonmarcos.clouddrive.storage

import com.diegonmarcos.clouddrive.Declarations
import com.diegonmarcos.clouddrive.Declarations.MountProviderDecl
import com.diegonmarcos.clouddrive.storage.MountProviders.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** #1216 the provider lines become real FUSE mounts through FuseWire: valid specs, honest state, no secret in the wizard preset. */
@RunWith(RobolectricTestRunner::class)
class MountProvidersTest {
    private fun line(id: String, remote: String, type: String, path: String = "", ro: Boolean = false, endpoint: String = "", provider: String = "", cls: String = "") =
        MountProviderDecl(id, id, "cloud", remote, type, provider, endpoint, path, ro, cls, false)

    private val r2 = line("r2", "r2", "s3", provider = "Cloudflare")
    private val lfs = line("gh-lfs", "gh_lfs", "http", ro = true, endpoint = "https://media.githubusercontent.com/media/o/r/main/")
    private val others = MountProviderDecl("others", "Others", "lan", "", "", "", "", "", false, "", true)

    @Test fun `a line becomes a mount spec FuseWire accepts`() {
        val spec = MountProviders.specFor(r2)!!
        assertEquals("r2:", spec.source)
        assertEquals("/root/mnt/r2", spec.mountpoint)
        assertEquals(FuseWire.Kind.RCLONE, spec.kind)
        assertNull(FuseWire.refusal(spec))
        assertTrue(MountProviders.specFor(lfs)!!.readOnly)
        assertEquals("gh_lfs:", MountProviders.specFor(lfs)!!.source)
        assertNull("the free line has no spec of its own", MountProviders.specFor(others))
    }

    @Test fun `the declared providers all yield valid distinct specs`() {
        val json = java.io.File(System.getProperty("user.dir")).let { if (java.io.File(it, "build.json").isFile) it else it.parentFile }.resolve("build.json").readText()
        val ui = kotlinx.serialization.json.Json.parseToJsonElement(json) as kotlinx.serialization.json.JsonObject
        val sync = ((ui["ui"] as kotlinx.serialization.json.JsonObject)["sync"]).toString()
        val specs = Declarations.parseMountProviders(sync).mapNotNull { MountProviders.specFor(it) }
        assertEquals(5, specs.size)
        specs.forEach { assertNull(it.id + ": " + FuseWire.refusal(it), FuseWire.refusal(it)) }
        assertEquals(specs.size, specs.map { it.id }.toSet().size)
        assertEquals(specs.size, specs.map { it.mountpoint }.toSet().size)
    }

    @Test fun `state is read from the remotes and the mount table, never assumed`() {
        val live = listOf(FuseWire.Live("/root/mnt/r2", "fuse.rclone", "r2:"))
        assertEquals(State.MISSING, MountProviders.state(r2, emptyMap(), emptyList()))
        assertEquals(State.WRONG_TYPE, MountProviders.state(r2, mapOf("r2" to "drive"), emptyList()))
        assertEquals(State.READY, MountProviders.state(r2, mapOf("r2" to "s3"), emptyList()))
        assertEquals(State.MOUNTED, MountProviders.state(r2, mapOf("r2" to "s3"), live))
        assertEquals(State.READY, MountProviders.state(lfs, mapOf("gh_lfs" to "http"), live))
        assertEquals(State.MISSING, MountProviders.state(others, mapOf("x" to "s3"), live))
    }

    @Test fun `the wizard preset carries public facts only`() {
        val cf = line("cf-ia", "r2_ia", "s3", provider = "Cloudflare", cls = "STANDARD_IA")
        assertEquals(mapOf("provider" to "Cloudflare", "storage_class" to "STANDARD_IA"), MountProviders.wizardPreset(cf))
        assertEquals(mapOf("url" to "https://media.githubusercontent.com/media/o/r/main/"), MountProviders.wizardPreset(lfs))
        val oracle = line("o", "oracle_s3", "s3", provider = "Other", endpoint = "objectstorage.eu-marseille-1.oraclecloud.com")
        assertEquals("objectstorage.eu-marseille-1.oraclecloud.com", MountProviders.wizardPreset(oracle)["endpoint"])
        listOf(r2, lfs, cf, oracle).flatMap { MountProviders.wizardPreset(it).keys }.forEach { assertTrue(it, !RcloneProviders.masked(it)) }
        assertNotNull(MountProviders.MOUNT_ROOT)
    }
}
