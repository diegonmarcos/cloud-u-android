package com.diegonmarcos.superapp.apps

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.graphics.drawable.ColorDrawable
import android.os.Looper
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.superapp.appstore.StoreBar
import com.diegonmarcos.superapp.appstore.StoreCloudFragment
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
import org.robolectric.Robolectric
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
class StoreProgressBarTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val b64 = com.diegonmarcos.superapp.appstore.BuildConfig.CONSTELLATION_FLEET_B64

    @After fun clear() { UpdateProgress.reset() }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun texts(v: View): List<TextView> = when (v) {
        is TextView -> listOf(v)
        is ViewGroup -> (0 until v.childCount).flatMap { texts(v.getChildAt(it)) }
        else -> emptyList()
    }

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

    private class Bar(val row: ViewGroup) {
        val head = row.getChildAt(0) as ViewGroup
        val icon = head.getChildAt(0) as ImageView
        val label = head.getChildAt(1) as TextView
        val bar = row.getChildAt(1) as ProgressBar
    }

    private fun bar(root: View): Bar {
        val row = root.findViewWithTag<ViewGroup>(StoreBar.PROGRESS_TAG)
        assertNotNull("the Cloud tab draws no progress row under its buttons", row)
        return Bar(row!!)
    }

    @Test
    fun `the bar names the app, stage, version, bytes, position and next, and a failure opens that row`() {
        UpdateProgress.reset()   // the pipeline's state is process-wide; start from nothing running
        val app = target()
        shadowOf(ctx.packageManager).installPackage(PackageInfo().apply {
            packageName = app.pkg; versionName = "1"; longVersionCode = 1
            applicationInfo = ApplicationInfo().apply { packageName = app.pkg }
        })
        shadowOf(ctx.packageManager).setApplicationIcon(app.pkg, ColorDrawable(0xFF2B6CB0.toInt()))
        val act = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        val f = StoreCloudFragment()
        act.supportFragmentManager.beginTransaction().add(android.R.id.content, f).commitNow()
        idle()
        val root = f.requireView()
        assertEquals("nothing running, nothing drawn", View.GONE, bar(root).row.visibility)

        // ── a download in a batch ──
        UpdateProgress.beginJob(UpdateProgress.Job(app.id, app.pkg, app.label,
            UpdateProgress.STAGE_DOWNLOADING, "1.2.3", 3, 12, "Chat"))
        UpdateProgress.update(UpdateProgress.State.Downloading(42, 4_200_000, 10_000_000))
        idle()
        var b = bar(root)
        val t = b.label.text.toString()
        assertEquals(View.VISIBLE, b.row.visibility)
        assertTrue("no app name: $t", t.startsWith("${app.label} 1.2.3"))
        for (part in listOf("downloading", "42%", " / ", "3 of 12", "next: Chat"))
            assertTrue("'$part' missing from: $t", part in t)
        assertFalse(b.bar.isIndeterminate); assertEquals(42, b.bar.progress)
        assertEquals("the app's own icon is beside its name", View.VISIBLE, b.icon.visibility)

        // Control: the stage is the job's, not a constant — verifying reads as verifying.
        UpdateProgress.stage(UpdateProgress.STAGE_VERIFYING)
        idle()
        assertTrue(bar(root).label.text.toString(), "verifying" in bar(root).label.text && "downloading" !in bar(root).label.text)

        // Control: an unknown size is said, never drawn as a percentage.
        UpdateProgress.update(UpdateProgress.State.Downloading(0, 500_000, -1))
        idle()
        b = bar(root)
        assertTrue(b.label.text.toString(), "total size unknown" in b.label.text && "%" !in b.label.text)
        assertTrue(b.bar.isIndeterminate)

        // ── the failure: app + stage + reason, after the job has ended ──
        UpdateProgress.update(UpdateProgress.State.Failed("no space left on device", app.id, app.pkg,
            stage = UpdateProgress.STAGE_INSTALLING, app = app.label))
        UpdateProgress.endBatch()
        idle()
        b = bar(root)
        val err = b.label.text.toString()
        assertTrue(err, err.startsWith("✗ ${app.label}") && "failed at installing" in err && "no space left" in err)
        assertEquals("a failure is drawn red", 0xFFF56565.toInt(), b.label.currentTextColor)

        // ── tap → that app's row, open ──
        val detail = "${app.pkg}  ·  ${app.image}"
        fun detailShown() = texts(root).any { it.text.toString() == detail && it.isShown }
        assertFalse("control: the row is not open before the tap", detailShown())
        assertTrue(b.row.isClickable)
        b.row.performClick()
        idle()
        assertTrue("the tap did not open ${app.id}'s row", detailShown())

        // Control: a single finished job leaves nothing on the bar; a cancel hides it.
        UpdateProgress.update(UpdateProgress.State.Done)
        idle()
        assertEquals(View.GONE, bar(root).row.visibility)
        UpdateProgress.beginJob(UpdateProgress.Job(app.id, app.pkg, app.label, UpdateProgress.STAGE_DOWNLOADING))
        UpdateProgress.update(UpdateProgress.State.Downloading(5, 5, 100))
        idle()
        assertEquals(View.VISIBLE, bar(root).row.visibility)
        assertFalse("a lone app has no batch position: ${bar(root).label.text}",
            Regex("""\d+ of \d+""").containsMatchIn(bar(root).label.text))
        UpdateProgress.update(UpdateProgress.State.Cancelled)
        idle()
        assertEquals(View.GONE, bar(root).row.visibility)
    }
}
