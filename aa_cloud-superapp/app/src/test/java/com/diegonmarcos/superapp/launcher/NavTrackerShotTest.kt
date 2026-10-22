package com.diegonmarcos.superapp.launcher

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onRoot
import com.diegonmarcos.superapp.boards.Boards
import com.diegonmarcos.superapp.boards.BoardsSource
import com.diegonmarcos.superapp.boards.BoardsWire
import com.diegonmarcos.superapp.boards.InboxItem
import com.diegonmarcos.superapp.boards.PlacesDay
import com.diegonmarcos.superapp.boards.Read
import com.diegonmarcos.superapp.boards.StepsBoard
import com.diegonmarcos.superapp.boards.TaskProject
import com.diegonmarcos.superapp.ui.KitPageHarness
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate

/**
 * CI evidence for Navigation/Tracker: draws the real card from the JSON tracker/places answers for seeded stops (named
 * and unnamed, measured and predicted) and writes a PNG to app/build/board-shots/ (the ship workflow uploads that folder).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w412dp-h2400dp-xxhdpi")
class NavTrackerShotTest : KitPageHarness() {
    private val real = Boards.source

    private class Src(val p: Read<List<PlacesDay>>) : BoardsSource {
        override fun fleetMail(limit: Int): Read<List<InboxItem>> = Read.Ok(emptyList())
        override fun phoneNotifications(): Read<List<InboxItem>> = Read.Ok(emptyList())
        override fun steps(days: Int): Read<StepsBoard> = Read.Ok(StepsBoard(0, emptyList(), "", "", "", null))
        override fun places(days: Int) = p
        override fun taskProjects(): Read<List<TaskProject>> = Read.Ok(emptyList())
    }

    @After fun restore() { Boards.source = real }

    private fun day(d: LocalDate, vararg v: String) =
        """{"date":"$d","visits":[${v.joinToString(",")}]}"""

    private fun visit(part: String, label: String, lat: Double, lon: Double, predicted: Boolean = false) =
        """{"part":"$part","label":"$label","lat":$lat,"lon":$lon,"predicted":$predicted}"""

    @Test fun `seeded Navigation Tracker card`() {
        val t = LocalDate.now()
        val body = """{"days":[""" + listOf(
            day(t, visit("08:00–09:10", "Home", 40.4168, -3.7038), visit("10:00–17:45", "Calle de Alcala, Madrid", 40.4200, -3.6900),
                visit("19:00–19:40", "40.4300, -3.6800", 40.4300, -3.6800, true)),
            day(t.minusDays(1), visit("07:50–09:00", "Home", 40.4168, -3.7038), visit("12:30–13:30", "Mercado, Madrid", 40.4090, -3.7100)),
            day(t.minusDays(2), visit("09:00–18:00", "Home", 40.4168, -3.7038)),
        ).joinToString(",") + """],"fixes":0,"stops":7}"""
        val days = BoardsWire.places(body)
        assertTrue(days.size == 3 && days[0].visits.size == 3)
        Boards.source = { Src(Read.Ok(days)) }
        showKit { Box(Modifier.fillMaxWidth().background(Color(0xFF0A0A0A))) { BoardPlacesBody(0) {} } }
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("places-day:$t")).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
        val dir = File("build/board-shots").apply { mkdirs() }
        File(dir, "navigation-tracker.png").outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        val seen = HashSet<Int>()
        for (x in 0 until bmp.width step 7) for (y in 0 until bmp.height step 7) seen += bmp.getPixel(x, y)
        assertTrue("navigation-tracker.png is blank (${seen.size} colours)", seen.size > 8)
    }
}
