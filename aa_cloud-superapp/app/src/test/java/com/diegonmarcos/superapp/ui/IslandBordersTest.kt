package com.diegonmarcos.superapp.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.diegonmarcos.superapp.media.MusicIsland
import com.diegonmarcos.superapp.media.MusicIslandState
import com.diegonmarcos.superapp.media.NowPlayingLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The islands' borders, proven on what is painted at two moments of the animation clock.
 *
 *   B1 the Dynamic Island's neon ring stays WHERE THE PILL IS and only its colours move. The first Compose port rotated the whole
 *      canvas, which spun the pill's outline itself: a coloured bar sweeping through the island, clipped at its edges.
 *   B2 the music mini-island has NO animated border: its outline pixels are identical at both moments (its wave moves INSIDE it).
 *   B3 the music island never hosts the neon ring (the ring belongs to the Dynamic Island only).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class IslandBordersTest : KitPageHarness() {

    private fun paint(): Bitmap {
        val v = compose.activity.findViewById<View>(android.R.id.content)
        return compose.runOnUiThread {
            Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888).also { v.draw(Canvas(it)) }
        }
    }

    /** Two paints of the same content, 1.8 s of animation apart (half a lap of the ring). */
    private fun twice(content: @androidx.compose.runtime.Composable () -> Unit): Pair<Bitmap, Bitmap> {
        ShellFxState.frozen = false
        compose.mainClock.autoAdvance = false
        showKit(content)
        compose.mainClock.advanceTimeBy(100)
        val a = paint()
        compose.mainClock.advanceTimeBy(1_800)
        val b = paint()
        return a to b
    }

    private fun mask(b: Bitmap, w: Int, h: Int) = (0 until h).map { y -> (0 until w).map { x -> (b.getPixel(x, y) ushr 24) != 0 } }

    @Test fun `B1 the neon ring stays on the pill and only its colours move`() {
        val (a, b) = twice { Box(Modifier.size(120.dp, 22.dp)) { PrismaticShimmer() } }
        val w = minOf(a.width, b.width); val h = minOf(a.height, b.height)
        assertEquals("the ring's footprint moved: the pill's outline is spinning, not just its colours", mask(a, w, h), mask(b, w, h))
        var changed = 0
        for (y in 0 until h) for (x in 0 until w) if (a.getPixel(x, y) != b.getPixel(x, y)) changed++
        assertTrue("the ring's colours did not move at all", changed > 0)
    }

    @Test fun `B2 the music island's border is static`() {
        val state = MusicIslandState().also { it.playing = NowPlayingLine("com.example.none", "Artist — Song") }
        val (a, b) = twice { Box(Modifier.size(90.dp, 16.dp)) { MusicIsland(state, onTap = {}) } }
        val w = minOf(a.width, b.width); val h = minOf(a.height, b.height)
        // The outline band: 2 dp at xxhdpi would be 6 px; use a generous 3 px ring all round, where only the border lives.
        val band = 3
        for (y in 0 until h) for (x in 0 until w) {
            val edge = x < band || y < band || x >= w - band || y >= h - band
            if (edge) assertEquals("border pixel ($x,$y) changed between two moments: the music island has an animated border",
                a.getPixel(x, y), b.getPixel(x, y))
        }
    }

    @Test fun `B3 the music island does not host the neon ring`() {
        val src = generateSequence(java.io.File("").absoluteFile) { it.parentFile }
            .map { java.io.File(it, "src/main/java/com/diegonmarcos/superapp/media/MusicIslandUi.kt") }.first { it.isFile }.readText()
        assertTrue("MusicIslandUi must not draw the Dynamic Island's neon frame", !src.contains("PrismaticShimmer") && !src.contains("sweepGradient") && !src.contains("SweepGradient"))
        assertNotEquals("", src)
    }
}
