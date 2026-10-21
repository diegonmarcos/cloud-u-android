package com.diegonmarcos.superapp.launcher

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.superapp.adbdebug.AdbPeers
import com.diegonmarcos.superapp.adbdebug.PeerLink
import com.diegonmarcos.superapp.adbdebug.PeerStatus
import com.diegonmarcos.superapp.settings.LauncherSettingsPrefs
import com.diegonmarcos.superapp.ui.KitPageHarness
import com.diegonmarcos.superapp.ui.ShellFxState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * #1080 the home screen's top status shows ADB as TWO dots, one per app that runs an adb channel (SuperApp, Cloud Store).
 *
 *   D1 a dot per app in the fleet's order; green up, amber degraded / connecting, dim down / not installed / not answering;
 *      an app the reading does not name is dim, never missing
 *   D2 the strip draws exactly two dots under the ADB label, SuperApp's left of Cloud Store's, and the other chips keep
 *      their signal dots
 *   D3 the dots are really painted in their own colours (pixels), and the strip is written to a PNG for the report
 * Evidence: app/build/configs-screens/home-top-status-adb-dots.png and ...-zoom.png (CI uploads the folder).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w384dp-h832dp-450dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AdbPeerDotsTest : KitPageHarness() {

    private val sa = AdbPeers.FLEET[0]
    private val cs = AdbPeers.FLEET[1]

    @Before fun petsOff() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        LauncherSettingsPrefs(app).setToggle("pets_anim", false)
        ShellFxState.refresh(app)
    }

    @Test fun `D1 one tint per app in the fleet's order`() {
        assertEquals(listOf(AdbPeerDots.UP, AdbPeerDots.OFF), AdbPeerDots.tints(listOf(PeerStatus(sa, PeerLink.UP), PeerStatus(cs, PeerLink.DOWN))))
        assertEquals(listOf(AdbPeerDots.OFF, AdbPeerDots.UP), AdbPeerDots.tints(listOf(PeerStatus(cs, PeerLink.UP), PeerStatus(sa, PeerLink.UNREACHABLE))))
        assertEquals("an app not named is dim, not missing", listOf(AdbPeerDots.WARN, AdbPeerDots.OFF), AdbPeerDots.tints(listOf(PeerStatus(sa, PeerLink.DEGRADED))))
        assertEquals(listOf(AdbPeerDots.OFF, AdbPeerDots.OFF), AdbPeerDots.tints(emptyList()))
        assertEquals(AdbPeerDots.WARN, AdbPeerDots.tint(PeerLink.CONNECTING))
        assertEquals("SuperApp up, Cloud Store not installed or not answering", AdbPeerDots.describe(listOf(PeerStatus(sa, PeerLink.UP))))
        assertEquals("one dot per app", AdbPeers.FLEET.size, AdbPeerDots.tints(emptyList()).size)
    }

    private fun showStrip(peers: List<PeerStatus>): StatusStripModel {
        val model = StatusStripModel(compose.activity)
        model.noteAdbPeers(peers, pin = true)
        showKit { Column(Modifier.wrapContentSize()) { StatusStripBody(model, compose.activity) } }
        return model
    }

    @Test fun `D2 two dots under ADB, SuperApp first`() {
        showStrip(listOf(PeerStatus(sa, PeerLink.UP), PeerStatus(cs, PeerLink.DOWN)))
        val label = compose.onNodeWithTag("strip:adb").fetchSemanticsNode().boundsInRoot
        val d0 = compose.onNodeWithTag("strip:adb:dot:0", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val d1 = compose.onNodeWithTag("strip:adb:dot:1", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("SuperApp's dot is left of Cloud Store's: ${d0.left} < ${d1.left}", d0.left < d1.left)
        assertTrue("both dots sit under the ADB label", d0.top >= label.top && d1.top >= label.top && d0.bottom <= label.bottom + 1f)
        assertTrue("both dots sit inside the ADB column", d0.left >= label.left - 1f && d1.right <= label.right + 1f)
        assertEquals("exactly two", 0, compose.onAllNodesTagged("strip:adb:dot:2").size)
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesTagged(tag: String) =
        onAllNodes(androidx.compose.ui.test.hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes(false)

    @Test fun `D3 the dots are painted in their own colours`() {
        showStrip(listOf(PeerStatus(sa, PeerLink.UP), PeerStatus(cs, PeerLink.DOWN)))
        val (bmp, origin) = compose.runOnUiThread {
            val root = compose.activity.findViewById<View>(android.R.id.content)
            val loc = IntArray(2).also { root.getLocationInWindow(it) }
            Bitmap.createBitmap(root.width.coerceAtLeast(1), root.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                .also { b -> Canvas(b).also { it.drawColor(android.graphics.Color.BLACK); root.draw(it) } } to loc
        }
        fun centre(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInWindow.center
        /** The most saturated pixel within 2 px of the dot's centre: the dot is 2 dp, a few pixels, so one pixel may be anti-aliased. */
        fun greenness(tag: String): Int {
            val c = centre(tag)
            var best = Int.MIN_VALUE
            for (dx in -2..2) for (dy in -2..2) {
                val x = (c.x - origin[0]).toInt() + dx; val y = (c.y - origin[1]).toInt() + dy
                if (x !in 0 until bmp.width || y !in 0 until bmp.height) continue
                val p = bmp.getPixel(x, y)
                best = maxOf(best, android.graphics.Color.green(p) - android.graphics.Color.red(p))
            }
            return best
        }
        val up = greenness("strip:adb:dot:0"); val down = greenness("strip:adb:dot:1")
        assertTrue("SuperApp's dot (up) is green: g-r = $up", up > 60)
        assertTrue("Cloud Store's dot (down) is dim grey, not green: g-r = $down", down < 25)

        val out = File(System.getProperty("user.dir"), "build/configs-screens").apply { mkdirs() }
        File(out, "home-top-status-adb-dots.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        // the ADB chip, four times larger, so two 2 dp dots can be seen in the report
        val c = centre("strip:adb:dot:0")
        val x0 = ((c.x - origin[0]) - 60).toInt().coerceIn(0, bmp.width - 1); val y0 = ((c.y - origin[1]) - 40).toInt().coerceIn(0, bmp.height - 1)
        val w = minOf(120, bmp.width - x0); val h = minOf(70, bmp.height - y0)
        val zoom = Bitmap.createScaledBitmap(Bitmap.createBitmap(bmp, x0, y0, w, h), w * 4, h * 4, false)
        File(out, "home-top-status-adb-dots-zoom.png").outputStream().use { zoom.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
