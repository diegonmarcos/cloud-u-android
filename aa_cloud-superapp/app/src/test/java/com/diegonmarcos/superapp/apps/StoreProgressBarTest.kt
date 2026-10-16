package com.diegonmarcos.superapp.apps

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.graphics.drawable.ColorDrawable
import android.os.Looper
import android.util.Base64
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.superapp.appstore.StoreBar
import com.diegonmarcos.superapp.appstore.StoreCloudFragment
import com.diegonmarcos.superapp.appstore.StoreSemantics
import com.diegonmarcos.superapp.ui.KitPageHarness
import com.diegonmarcos.superapp.updater.Fleet
import com.diegonmarcos.superapp.updater.UpdateProgress
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * #785 — the progress bar under the Store buttons, read off the RENDERED page.
 *
 * The real Cloud tab is hosted; the pipeline's two fields (UpdateProgress.state
 * and .job) are driven exactly as Fleet / StoreStages drive them; the bar's own
 * views are read back. It must name the app (and show its icon), the stage, the
 * version, bytes and %, the batch position and what is next — and on a failure
 * the app, the stage and the reason, with a tap that opens that app's row.
 * Each assertion has a control beside it that flips one fact and the verdict.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class StoreProgressBarTest : KitPageHarness() {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val b64 = com.diegonmarcos.superapp.appstore.BuildConfig.CONSTELLATION_FLEET_B64

    @After fun clear() { UpdateProgress.reset() }

    private fun idle() { shadowOf(Looper.getMainLooper()).idle(); compose.waitForIdle() }

    /** A fleet app the page draws a row for — the last group's, so opening it
     *  is a real tab switch whenever there is more than one tab. */
    private fun target(): Fleet.App {
        val json = JSONObject(String(Base64.decode(b64, Base64.DEFAULT)))
        val catalogue = json.optJSONArray("catalogue")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("id") } }.orEmpty()
        val groups = json.getJSONArray("groups")
        val fleet = Fleet.parse(b64).filter { it.pkg.isNotEmpty() && it.id !in catalogue }.associateBy { it.id }
        val app = (groups.length() - 1 downTo 0).firstNotNullOfOrNull { g ->
            val m = groups.getJSONObject(g).optJSONArray("members") ?: return@firstNotNullOfOrNull null
            (0 until m.length()).firstNotNullOfOrNull { fleet[m.optString(it)] }
        }
        assertNotNull("the baked fleet has no grouped app to draw", app)
        return app!!
    }

    /** The progress row as the semantics tree shows it; the row itself is absent while nothing runs. */
    private class ProgressRow(val row: SemanticsNode, val label: SemanticsNode, val bar: SemanticsNode, val icon: SemanticsNode?) {
        val text get() = label.text()
        val indeterminate get() = bar.config[SemanticsProperties.ProgressBarRangeInfo] == ProgressBarRangeInfo.Indeterminate
        val progress get() = (bar.config[SemanticsProperties.ProgressBarRangeInfo].current * 100).toInt()
    }

    private fun bar(): ProgressRow {
        val row = compose.onNodeWithTag(StoreBar.PROGRESS_TAG)
        row.assertExists("the Cloud tab draws no progress row under its buttons")
        // the row is one tap target (it merges its parts), so its label, bar and icon are read unmerged
        return ProgressRow(row.fetchSemanticsNode(),
            compose.onNodeWithTag("store-progress:label", useUnmergedTree = true).fetchSemanticsNode(),
            compose.onNodeWithTag("store-progress:bar", useUnmergedTree = true).fetchSemanticsNode(),
            compose.onAllNodes(hasTestTag("store-progress:icon"), useUnmergedTree = true).fetchSemanticsNodes().firstOrNull())
    }

    private fun noBar() = compose.onNodeWithTag(StoreBar.PROGRESS_TAG).assertDoesNotExist()

    @Test
    fun `the bar names the app, stage, version, bytes, position and next, and a failure opens that row`() {
        com.diegonmarcos.superapp.appstore.StoreJobs.board.reset()
        UpdateProgress.reset()   // the pipeline's state is process-wide; start from nothing running
        val app = target()
        shadowOf(ctx.packageManager).installPackage(PackageInfo().apply {
            packageName = app.pkg; versionName = "1"; longVersionCode = 1
            applicationInfo = ApplicationInfo().apply { packageName = app.pkg }
        })
        shadowOf(ctx.packageManager).setApplicationIcon(app.pkg, ColorDrawable(0xFF2B6CB0.toInt()))
        show(StoreCloudFragment())
        idle()
        noBar()   // nothing running, nothing drawn

        // ── a download in a batch ──
        UpdateProgress.beginJob(UpdateProgress.Job(app.id, app.pkg, app.label,
            UpdateProgress.STAGE_DOWNLOADING, "1.2.3", 3, 12, "Chat"))
        UpdateProgress.update(UpdateProgress.State.Downloading(42, 4_200_000, 10_000_000))
        idle()
        var b = bar()
        val t = b.text
        assertTrue("no app name: $t", t.startsWith("${app.label} 1.2.3"))
        for (part in listOf("downloading", "42%", " / ", "3 of 12", "next: Chat"))
            assertTrue("'$part' missing from: $t", part in t)
        assertFalse(b.indeterminate); assertEquals(42, b.progress)
        assertNotNull("the app's own icon is beside its name", b.icon)

        // Control: the stage is the job's, not a constant — verifying reads as verifying.
        UpdateProgress.stage(UpdateProgress.STAGE_VERIFYING)
        idle()
        assertTrue(bar().text, "verifying" in bar().text && "downloading" !in bar().text)

        // #894 the bar holds one state per item, so a NEW job is what starts it over: a finished job
        // clears the row, and the next one begins indeterminate.
        UpdateProgress.update(UpdateProgress.State.Done)
        idle()
        UpdateProgress.beginJob(UpdateProgress.Job(app.id, app.pkg, app.label, UpdateProgress.STAGE_DOWNLOADING))
        // Control: an unknown size is said, never drawn as a percentage.
        UpdateProgress.update(UpdateProgress.State.Downloading(0, 500_000, -1))
        idle()
        b = bar()
        assertTrue(b.text, "total size unknown" in b.text && "%" !in b.text)
        assertTrue(b.indeterminate)

        // ── the failure: app + stage + reason, after the job has ended ──
        UpdateProgress.update(UpdateProgress.State.Failed("no space left on device", app.id, app.pkg,
            stage = UpdateProgress.STAGE_INSTALLING, app = app.label))
        UpdateProgress.endBatch()
        idle()
        b = bar()
        val err = b.text
        // #831 the bar names the app and points at its row; the reason is drawn whole THERE, never cut here.
        assertTrue(err, err.startsWith("⚠ ") && "failed" in err && "on its row" in err && "no space left" !in err)
        assertEquals("a failure is drawn red", 0xFFF56565.toInt(), b.label.config[StoreSemantics.Ink])

        // ── tap → that app's row, open ──
        val detail = "${app.pkg}  ·  ${app.image}"
        fun detailShown() = compose.onAllNodesWithText(detail).fetchSemanticsNodes().isNotEmpty()
        assertFalse("control: the row is not open before the tap", detailShown())
        assertTrue(SemanticsActions.OnClick in b.row.config)
        compose.onNodeWithTag(StoreBar.PROGRESS_TAG).invokeClick()
        idle()
        assertTrue("the tap did not open ${app.id}'s row", detailShown())

        // Control: a single finished job leaves nothing on the bar; a cancel hides it.
        UpdateProgress.update(UpdateProgress.State.Done)
        idle()
        noBar()
        UpdateProgress.beginJob(UpdateProgress.Job(app.id, app.pkg, app.label, UpdateProgress.STAGE_DOWNLOADING))
        UpdateProgress.update(UpdateProgress.State.Downloading(5, 5, 100))
        idle()
        assertFalse("a lone app has no batch position: ${bar().text}",
            Regex("""\d+ of \d+""").containsMatchIn(bar().text))
        UpdateProgress.update(UpdateProgress.State.Cancelled)
        idle()
        noBar()
    }
}
