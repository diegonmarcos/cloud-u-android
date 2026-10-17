package com.diegonmarcos.superapp.appstore

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.util.Base64
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import com.diegonmarcos.superapp.ui.KitPageHarness
import com.diegonmarcos.superapp.updater.Fleet
import com.diegonmarcos.superapp.updater.cache.ApkCache
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * libs:appstore SharedUidMigration — an update that changes the app's Linux uid:
 *
 *   M1 INSTALL_FAILED_UID_CHANGED (legacy -24), SHARED_USER_INCOMPATIBLE (-8) and UPDATE_INCOMPATIBLE (-7)
 *      are recognised, from the legacy code and from the row's note text; other failures are not
 *   M2 a -24 result turns the row into the migration stage: no Install/Download verb, and Install,
 *      Update all and the auto chain never call the installer again while the old build is installed;
 *      once it is uninstalled, Install is live again (step 3)
 *   M3 the card renders its four steps, with only the current one's action live
 *   M4 the backup lands at /storage/emulated/0/<storage.shared_root>/<backup_dir>/<pkg>/<archive>
 *      (declared in fleet-identity.json, read from ac_cloud-termux/build.json), and the command the
 *      terminal runs writes exactly there
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SharedUidMigrationTest : KitPageHarness() {

    private val pkg = "cld.termux.nix"
    private val reported = "INSTALL_FAILED_UID_CHANGED: Package cld.termux.nix shared user changed from " +
        "cld.termux.nix to cld.termux.shared · code: legacy=-24"

    private val ctx get() = RuntimeEnvironment.getApplication()

    private fun app(): Fleet.App {
        val o = JSONObject().put("apps", JSONArray().put(JSONObject().put("id", "nix-on-droid").put("label", "Cloud Nix")
            .put("package", pkg).put("registry", "ghcr.io").put("namespace", "x").put("image", "x")))
        return Fleet.parse(Base64.encodeToString(o.toString().toByteArray(), Base64.NO_WRAP)).single()
    }

    private fun installOld(code: Long = 7L) {
        val pi = PackageInfo().apply {
            packageName = pkg; longVersionCode = code; lastUpdateTime = 1_000L
            applicationInfo = ApplicationInfo().apply { packageName = pkg }
        }
        shadowOf(ctx.packageManager).installPackage(pi)
    }

    private val realInstaller = StoreStages.installer

    @After fun restore() { StoreStages.installer = realInstaller }

    @Test
    fun `M1 the uid-change results are recognised and nothing else is`() {
        assertEquals(SharedUidMigration.Cause.UID_CHANGED, SharedUidMigration.cause(-24, null))
        assertEquals(SharedUidMigration.Cause.UID_CHANGED, SharedUidMigration.cause(Int.MIN_VALUE, reported))
        assertEquals(SharedUidMigration.Cause.UID_CHANGED, SharedUidMigration.cause("install failed · code: INSTALL_FAILED_UID_CHANGED"))
        assertEquals(SharedUidMigration.Cause.SHARED_USER_INCOMPATIBLE, SharedUidMigration.cause(-8, null))
        assertEquals(SharedUidMigration.Cause.SHARED_USER_INCOMPATIBLE, SharedUidMigration.cause("x · code: INSTALL_FAILED_SHARED_USER_INCOMPATIBLE"))
        assertEquals(SharedUidMigration.Cause.UPDATE_INCOMPATIBLE, SharedUidMigration.cause(-7, null))
        assertNull(SharedUidMigration.cause(-115, "Session was abandoned"))
        assertNull(SharedUidMigration.cause("User rejected · code: legacy=-240"))
        assertNull(SharedUidMigration.cause(null))
    }

    @Test
    fun `M2 a -24 result is the migration state and nothing retries it`() {
        installOld()
        val app = app()
        val calls = AtomicInteger()
        StoreStages.installer = { _, _, _ -> calls.incrementAndGet(); null }
        ApkCache.note(ctx, pkg, ApkCache.STAGE_INSTALL, reported)

        val s = StoreStages.stage(ctx, app)
        assertEquals(SharedUidMigration.STAGE, s.id)
        assertFalse("no Install verb on a uid change", StoreStages.INSTALL in s.actions)
        assertFalse("no Download verb on a uid change", StoreStages.DOWNLOAD in s.actions)
        assertNull("not an error stage the row's Retry would re-run", s.failedAt)
        val r = SharedUidMigration.record(ctx, pkg)
        assertNotNull(r)
        assertEquals(SharedUidMigration.Cause.UID_CHANGED, r!!.cause)
        assertTrue(SharedUidMigration.blocks(ctx, app))
        assertEquals(SharedUidMigration.Step.BACKUP, SharedUidMigration.current(r, r.before, null))

        // Install (the row, onForeground's re-prompt), Update all and the auto chain all stop here.
        repeat(3) { assertEquals(SharedUidMigration.STAGE, StoreStages.install(ctx, app).id) }
        val batch = StoreStages.updateAll(ctx, listOf(app), online = false, dryRun = true)
        assertEquals(StoreStages.SKIPPED, batch.outcomes.single().result)
        assertEquals("the installer is never called again", 0, calls.get())

        // A second identical failure does not restart the migration.
        val since = r.since
        ApkCache.note(ctx, pkg, ApkCache.STAGE_INSTALL, reported)
        StoreStages.stage(ctx, app)
        assertEquals(since, SharedUidMigration.record(ctx, pkg)!!.since)

        // Step 2 done (old build uninstalled): the block lifts and the row is a plain Install again.
        shadowOf(ctx.packageManager).deletePackage(pkg)
        assertFalse(SharedUidMigration.blocks(ctx, app))
        assertEquals(SharedUidMigration.Step.INSTALL, SharedUidMigration.current(r, "", null))
        val after = StoreStages.stage(ctx, app)
        assertTrue(after.id != SharedUidMigration.STAGE)
        assertNull("the -24 note is history once the old build is gone", ApkCache.noteOf(ctx, pkg))
        // Step 3 landed (a new build): the card moves to Restore.
        assertEquals(SharedUidMigration.Step.RESTORE, SharedUidMigration.current(r, "8/2000", null))
    }

    @Test
    fun `M3 the card renders its four steps`() {
        val r = SharedUidMigration.Record(pkg, SharedUidMigration.Cause.UID_CHANGED, "v8", "7/1000", 1L)
        val path = "/storage/emulated/0/CloudDrive/.cloud-migrate/$pkg/home.tar.gz"
        showKit {
            SharedUidMigrationCard(pkg, "Cloud Nix", r, path, installedNow = "7/1000", backup = null, waitsFor = "cld.termux",
                onBackup = {}, onUninstall = {}, onInstall = {}, onOpen = {}, onDone = {})
        }
        compose.waitForIdle()
        compose.onNodeWithTag(SharedUidMigration.TAG_CARD + pkg, useUnmergedTree = true).assertExists()
        for (t in listOf(SharedUidMigration.TAG_BACKUP, SharedUidMigration.TAG_UNINSTALL, SharedUidMigration.TAG_INSTALL, SharedUidMigration.TAG_OPEN))
            compose.onNodeWithTag(t + pkg + ":step", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(SharedUidMigration.TAG_BACKUP + pkg, useUnmergedTree = true).assertIsEnabled()
        compose.onNodeWithTag(SharedUidMigration.TAG_UNINSTALL + pkg, useUnmergedTree = true).assertIsNotEnabled()
        compose.onNodeWithTag(SharedUidMigration.TAG_INSTALL + pkg, useUnmergedTree = true).assertIsNotEnabled()

        // Pure: a complete backup moves the user to Uninstall — held while termux (which owns the store) is pending.
        val b = SharedUidMigration.Backup(4_200_000L, 5_000L, inProgress = false)
        val held = SharedUidMigration.steps(pkg, "Cloud Nix", path, SharedUidMigration.Step.UNINSTALL, b, "cld.termux", {}, {}, {}, {})
        assertEquals(listOf("backup", "uninstall", "install", "restore"), held.map { it.id })
        assertTrue(held[0].detail, held[0].state == com.diegonmarcos.superapp.uikit.KitStepState.DONE)
        assertFalse(held[1].action!!.enabled)
        val free = SharedUidMigration.steps(pkg, "Cloud Nix", path, SharedUidMigration.Step.UNINSTALL, b, null, {}, {}, {}, {})
        assertTrue(free[1].action!!.enabled)
        assertEquals("termux goes before nix", "cld.termux",
            SharedUidMigration.waitsFor(listOf("cld.termux", pkg), pkg) { it == "cld.termux" })
        assertNull(SharedUidMigration.waitsFor(listOf("cld.termux", pkg), "cld.termux") { true })
    }

    @Test
    fun `M4 the backup command writes to the declared path`() {
        val bj = JSONObject(File("../../ac_cloud-termux/build.json").readText())
        val root = bj.getJSONObject("storage").getString("shared_root")
        val mig = bj.getJSONObject("shared_store").getJSONObject("migration")
        val plan = SharedUidMigration.planFor(FleetIdentity.baked(), pkg)
        assertNotNull("fleet-identity.json declares migration: shared_uid with its plan", plan)
        val path = plan!!.backupPath(pkg, "/storage/emulated/0")
        assertEquals("/storage/emulated/0/$root/${mig.getString("backup_dir")}/$pkg/${mig.getString("archive")}", path)
        assertEquals("/storage/emulated/0/CloudDrive/.cloud-migrate/cld.termux.nix/home.tar.gz", path)
        assertEquals("termux goes first: it owns the store", listOf("cld.termux", "cld.termux.nix"), plan.order.take(2))
        // the plan the terminal group declares is the one cloud-drive reuses before it joins
        assertEquals(path.replace(pkg, "com.diegonmarcos.clouddrive"),
            SharedUidMigration.planFor(FleetIdentity.baked(), "com.diegonmarcos.clouddrive")!!.backupPath("com.diegonmarcos.clouddrive", "/storage/emulated/0"))

        val script = SharedUidMigration.backupScript(path)
        assertTrue(script, script.contains("mkdir -p '/storage/emulated/0/CloudDrive/.cloud-migrate/cld.termux.nix'"))
        assertTrue(script, script.contains("tar -czf '$path.part' ."))
        assertTrue(script, script.endsWith("mv -f '$path.part' '$path'"))

        val call = SharedUidMigration.execCall(pkg, path)
        assertEquals("cld.termux.nix.CLOUD_EXEC", call.action)
        assertEquals("/data/data/cld.termux.nix/files/usr/bin/sh", call.executable)
        assertEquals("/data/data/cld.termux.nix/files/home", call.workdir)
        assertEquals("-c", call.args[0])
        assertTrue(call.args[1].contains("nohup"))
        val i = SharedUidMigration.runCommandIntent(pkg, path)
        assertEquals("cld.termux.nix.RUN_COMMAND", i.action)
        assertEquals(pkg, i.`package`)
        assertEquals(script, i.getStringArrayExtra("$pkg.RUN_COMMAND_ARGUMENTS")!![1])

        // an archive being written is not a backup; a finished one is, with its size and time
        val dir = File(ctx.cacheDir, "mig/$pkg").apply { mkdirs() }
        val f = File(dir, "home.tar.gz")
        File(f.path + ".part").writeText("partial")
        assertTrue(SharedUidMigration.backupOf(f.path)!!.inProgress)
        File(f.path + ".part").renameTo(f)
        val done = SharedUidMigration.backupOf(f.path)!!
        assertFalse(done.inProgress)
        assertEquals(f.length(), done.bytes)
        f.delete(); dir.delete()
    }
}
