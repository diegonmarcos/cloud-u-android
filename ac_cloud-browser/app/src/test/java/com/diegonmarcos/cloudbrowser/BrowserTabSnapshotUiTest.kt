package com.diegonmarcos.cloudbrowser

import android.app.Activity
import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import com.diegonmarcos.superapp.browser.BrowserGridRow
import com.diegonmarcos.superapp.browser.BrowserTab
import com.diegonmarcos.superapp.browser.BrowserTabGrid
import com.diegonmarcos.superapp.browser.TabCardA11y
import com.diegonmarcos.superapp.browser.TabThumbConfig
import com.diegonmarcos.superapp.browser.TabThumbnails
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.Executor

/** A tab-switcher card with a snapshot draws it (top-aligned crop); one without shows the favicon plate. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BrowserTabSnapshotUiTest {

    @get:Rule val tmp = TemporaryFolder()

    private val direct = Executor { it.run() }

    private fun tab(id: String) = BrowserTab("https://$id.example/", id, 1L, id = id)

    private fun grid(thumbs: TabThumbnails, vararg tabs: BrowserTab): BrowserTabGrid {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, Activity::class.java))
        val act = Robolectric.buildActivity(Activity::class.java).setup().get()
        val g = BrowserTabGrid(act, onOpen = {}, onSwipeClose = {}, onPin = {}, onMenu = { _, _ -> },
            onToggleGroup = {}, onReorder = {}, thumbs = thumbs)
        act.setContentView(g, ViewGroup.LayoutParams(720, 1600))
        g.submit(tabs.map { BrowserGridRow.TabCard(it) })
        g.measure(View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1600, View.MeasureSpec.EXACTLY))
        g.layout(0, 0, 720, 1600)
        shadowOf(Looper.getMainLooper()).idle()
        return g
    }

    private fun described(root: View, text: String): List<View> =
        ArrayList<View>().also { root.findViewsWithText(it, text, View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION) }

    @Test
    fun `a card with a snapshot renders the image and hides the fallback plate`() {
        val thumbs = TabThumbnails(tmp.newFolder("t"), TabThumbConfig(), direct, direct)
        val snap = Bitmap.createBitmap(90, 160, Bitmap.Config.RGB_565).also { it.eraseColor(0xFFFF8800.toInt()) }
        thumbs.put("a", snap)
        val g = grid(thumbs, tab("a"))

        val shots = described(g, TabCardA11y.SNAPSHOT)
        assertEquals("one card shows its snapshot", 1, shots.size)
        val img = shots.single() as ImageView
        assertSame(snap, (img.drawable as BitmapDrawable).bitmap)
        // Crop + top alignment: scaled to cover the card width and pinned to y = 0.
        val vals = FloatArray(9).also { img.imageMatrix.getValues(it) }
        assertTrue("scale covers the box", vals[android.graphics.Matrix.MSCALE_X] >= img.width / 90f - 0.001f)
        assertEquals("top edge kept", 0f, vals[android.graphics.Matrix.MTRANS_Y], 0.001f)
        val plate = described(g, TabCardA11y.PLACEHOLDER).single()
        assertEquals(View.GONE, plate.visibility)
    }

    @Test
    fun `a card without a snapshot renders the favicon plate`() {
        val thumbs = TabThumbnails(tmp.newFolder("t"), TabThumbConfig(), direct, direct)
        val icon = tmp.newFile("fav.png")
        icon.outputStream().use { Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it) }
        val g = grid(thumbs, tab("b").copy(iconPath = icon.absolutePath))

        assertEquals("no snapshot drawn", 0, described(g, TabCardA11y.SNAPSHOT).size)
        val plate = described(g, TabCardA11y.PLACEHOLDER).single() as ImageView
        assertEquals(View.VISIBLE, plate.visibility)
        assertNotNull("the favicon is on the plate", plate.drawable)
    }

    @Test
    fun `a snapshot that lands from disk after the first draw replaces the plate`() {
        val dir = tmp.newFolder("t")
        TabThumbnails(dir, TabThumbConfig(), direct, direct).put("c", Bitmap.createBitmap(90, 160, Bitmap.Config.RGB_565))
        val fresh = TabThumbnails(dir, TabThumbConfig(), direct, direct)   // empty memory, file on disk
        assertNull(fresh.cached("c"))
        val g = grid(fresh, tab("c"))
        assertEquals(1, described(g, TabCardA11y.SNAPSHOT).size)
        assertEquals(View.GONE, described(g, TabCardA11y.PLACEHOLDER).single().visibility)
    }
}
