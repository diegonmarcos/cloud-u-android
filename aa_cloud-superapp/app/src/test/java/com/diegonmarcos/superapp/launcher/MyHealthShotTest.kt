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
import com.diegonmarcos.superapp.boards.HealthAgg
import com.diegonmarcos.superapp.boards.HealthBoard
import com.diegonmarcos.superapp.boards.HealthFixtures
import com.diegonmarcos.superapp.boards.InboxItem
import com.diegonmarcos.superapp.boards.PlacesDay
import com.diegonmarcos.superapp.boards.Read
import com.diegonmarcos.superapp.boards.StepsBoard
import com.diegonmarcos.superapp.boards.TaskProject
import com.diegonmarcos.superapp.boards.Why
import com.diegonmarcos.superapp.health.HealthBoardReader
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
 * CI evidence for the My-Health board: draws the real panel with seeded Health Connect values and writes PNGs to
 * app/build/board-shots/ (the ship workflow uploads that folder). Also asserts the picture is not blank.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w412dp-h2400dp-xxhdpi")
class MyHealthShotTest : KitPageHarness() {
    private val real = Boards.source

    private class Src(val h: Read<HealthBoard>) : BoardsSource {
        override fun fleetMail(limit: Int): Read<List<InboxItem>> = Read.Ok(emptyList())
        override fun phoneNotifications(): Read<List<InboxItem>> = Read.Ok(emptyList())
        override fun steps(days: Int): Read<StepsBoard> = Read.Unavailable(Why.NOT_INSTALLED, "unused")
        override fun health(days: Int) = h
        override fun places(days: Int): Read<List<PlacesDay>> = Read.Ok(emptyList())
        override fun taskProjects(): Read<List<TaskProject>> = Read.Ok(emptyList())
    }

    @After fun restore() { Boards.source = real }

    private fun shoot(name: String, board: HealthBoard, waitTag: String) {
        Boards.source = { Src(Read.Ok(board)) }
        showKit { Box(Modifier.fillMaxWidth().background(Color(0xFF0A0A0A))) { BoardStepsBody(0) {} } }
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag(waitTag)).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
        val dir = File("build/board-shots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        val seen = HashSet<Int>()
        for (x in 0 until bmp.width step 7) for (y in 0 until bmp.height step 7) seen += bmp.getPixel(x, y)
        assertTrue("$name.png is blank (${seen.size} colours)", seen.size > 8)
    }

    @Test fun `seeded MyHealth board`() {
        val today = LocalDate.now()
        shoot("my-health", HealthAgg.board(HealthFixtures.seeded(today), today), "sleep-chart")
    }

    @Test fun `MyHealth board asking for permissions`() {
        val today = LocalDate.now()
        val raw = HealthFixtures.seeded(today).copy(granted = setOf(HealthBoardReader.STEPS, HealthBoardReader.DISTANCE),
            restingHr = emptyMap(), sleep = emptyMap())
        shoot("my-health-permissions", HealthAgg.board(raw, today), "health-permissions")
    }
}
