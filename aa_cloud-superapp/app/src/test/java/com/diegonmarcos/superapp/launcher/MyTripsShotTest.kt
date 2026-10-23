package com.diegonmarcos.superapp.launcher

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.diegonmarcos.superapp.boards.Boards
import com.diegonmarcos.superapp.boards.BoardsSource
import com.diegonmarcos.superapp.boards.BoardsWire
import com.diegonmarcos.superapp.boards.InboxItem
import com.diegonmarcos.superapp.boards.PlacesDay
import com.diegonmarcos.superapp.boards.Read
import com.diegonmarcos.superapp.boards.StepsBoard
import com.diegonmarcos.superapp.boards.TaskProject
import com.diegonmarcos.superapp.boards.TimelineBoard
import com.diegonmarcos.superapp.ui.KitPageHarness
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * CI evidence for MyTrips (#1114): the card drawn from the JSON `tracker/timeline` answer for seeded data - start, move,
 * stop, move, stop, move, sleep - to app/build/board-shots/mytrips.png (the ship workflow uploads that folder).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w412dp-h2400dp-xxhdpi")
class MyTripsShotTest : KitPageHarness() {
    private val real = Boards.source

    private class Src(val t: Read<TimelineBoard>) : BoardsSource {
        override fun fleetMail(limit: Int): Read<List<InboxItem>> = Read.Ok(emptyList())
        override fun phoneNotifications(): Read<List<InboxItem>> = Read.Ok(emptyList())
        override fun steps(days: Int): Read<StepsBoard> = Read.Ok(StepsBoard(0, emptyList(), "", "", "", null))
        override fun places(days: Int): Read<List<PlacesDay>> = Read.Ok(emptyList())
        override fun timeline(hours: Int) = t
        override fun taskProjects(): Read<List<TaskProject>> = Read.Ok(emptyList())
    }

    @After fun restore() { Boards.source = real }

    private fun seg(kind: String, a: Long, b: Long, place: String = "", lat: Double? = null, lon: Double? = null, steps: Long = 0, dist: Long = 0, src: String = "") =
        JSONObject().put("kind", kind).put("start", a).put("end", b).put("place", place).put("steps", steps).put("distance_m", dist)
            .put("loc_src", src).apply { if (lat != null && lon != null) put("lat", lat).put("lon", lon) }

    @Test fun `seeded MyTrips timeline`() {
        val now = 1_760_000_000_000L; val h = 3_600_000L
        val arr = JSONArray()
            .put(seg("stop", now - 24 * h, now - 17 * h, "Home, Madrid", 40.4168, -3.7038, 40, 0, "gps"))
            .put(seg("move", now - 17 * h, now - 16 * h, steps = 3200, dist = 2400))
            .put(seg("stop", now - 16 * h, now - 9 * h, "Calle de Alcala, Madrid", 40.42, -3.69, 1800, 0, "net"))
            .put(seg("move", now - 9 * h, now - 8 * h - 20 * 60_000L, steps = 1100, dist = 9200))
            .put(seg("stop", now - 8 * h - 20 * 60_000L, now - 6 * h, "Mercado, Madrid", 40.409, -3.71, 2300, 0, "net"))
            .put(seg("move", now - 6 * h, now - 5 * h - 30 * 60_000L, steps = 900, dist = 700))
            .put(seg("sleep", now - 5 * h - 30 * 60_000L, now - 10 * 60_000L, "Home, Madrid", 40.4168, -3.7038, 60, 0, "gps"))
        val body = JSONObject().put("now", now).put("hours", 24).put("segments", arr).toString()
        val b = BoardsWire.timeline(body)
        assertEquals(listOf("stop", "move", "stop", "move", "stop", "move", "sleep"), b.segments.map { it.kind })
        Boards.source = { Src(Read.Ok(b)) }
        showKit { Box(Modifier.fillMaxWidth().background(Color(0xFF0A0A0A))) { BoardTimelineBody(0) {} } }
        compose.waitUntil(5_000) { compose.onAllNodes(androidx.compose.ui.test.hasText("Sleep", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        // Draw the view tree into a software bitmap: captureToImage() waits for a hardware redraw that Robolectric never delivers.
        var drawn: android.graphics.Bitmap? = null
        compose.runOnUiThread {
            val v = compose.activity.window.decorView
            drawn = android.graphics.Bitmap.createBitmap(v.width.coerceAtLeast(1), v.height.coerceAtLeast(1), android.graphics.Bitmap.Config.ARGB_8888)
            v.draw(android.graphics.Canvas(drawn!!))
        }
        val bmp = drawn!!
        val dir = File("build/board-shots").apply { mkdirs() }
        File(dir, "mytrips.png").outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        val seen = HashSet<Int>()
        for (x in 0 until bmp.width step 7) for (y in 0 until bmp.height step 7) seen += bmp.getPixel(x, y)
        assertTrue("mytrips.png is blank (${seen.size} colours)", seen.size > 8)
    }
}
