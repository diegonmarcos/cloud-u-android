package com.diegonmarcos.superapp.launcher

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.superapp.boards.HealthAgg
import com.diegonmarcos.superapp.boards.HealthFixtures
import com.diegonmarcos.superapp.boards.Read
import com.diegonmarcos.superapp.boards.Why
import com.diegonmarcos.superapp.notificationcenter.BadgeCardData
import com.diegonmarcos.superapp.notificationcenter.HealthBadgeModel
import com.diegonmarcos.superapp.notificationcenter.HealthBadgeService
import com.diegonmarcos.superapp.ui.KitPageHarness
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate

/**
 * The MyHealth Notify card drawn by the real BadgeCard from the real [HealthBadgeModel] over the shared board
 * aggregation, with seeded Health Connect values. Frames: data, no permission (with the Grant access button), timeout.
 * Evidence: app/build/configs-screens/myhealth-card-{seeded,noperm,timeout}.png.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w384dp-h832dp-night-450dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MyHealthCardScreenTest : KitPageHarness() {
    private val ctx get() = ApplicationProvider.getApplicationContext<Application>()
    private val today = LocalDate.now()

    private fun data(f: HealthBadgeModel.Face): BadgeCardData.Data {
        val b = orderedBadges(ctx).first { it.id == HealthBadgeService.BADGE_ID }
        val pi = if (f.grant) android.app.PendingIntent.getActivity(ctx, 7716, android.content.Intent("grant"), android.app.PendingIntent.FLAG_IMMUTABLE) else null
        val lines = (f.lines + HealthBadgeModel.updated(4 * 60_000L)).map { BadgeCardData.Line("", it) }
        return BadgeCardData.Data(b, b.label, f.title, f.text, null, lines, true, "", System.currentTimeMillis(), null,
            if (f.grant) "Grant access" else null, pi)
    }

    private fun draw(d: BadgeCardData.Data, shot: String) {
        showKit { Column(androidx.compose.ui.Modifier.fillMaxSize().background(NotifCard.panel).padding(12.dp)) { BadgeCard(d) } }
        compose.onNodeWithTag(BadgeCardTags.card(HealthBadgeService.BADGE_ID)).assertExists()
        snap(shot)
    }

    @Test
    fun `render the MyHealth card seeded, without permission and timed out`() {
        val seeded = HealthBadgeModel.face(Read.Ok(HealthAgg.board(HealthFixtures.seeded(today), today)))
        draw(data(seeded), "myhealth-card-seeded.png")
        compose.onNodeWithTag(BadgeCardTags.action(HealthBadgeService.BADGE_ID)).assertDoesNotExist()

        val noPerm = HealthBadgeModel.face(Read.Unavailable(Why.NO_PERMISSION, "Needs read access in Health Connect: steps, distance, resting heart rate, sleep"))
        draw(data(noPerm), "myhealth-card-noperm.png")
        compose.onNodeWithTag(BadgeCardTags.action(HealthBadgeService.BADGE_ID)).assertExists().assertHasClickAction()

        draw(data(HealthBadgeModel.face(null)), "myhealth-card-timeout.png")
        compose.onNodeWithTag(BadgeCardTags.action(HealthBadgeService.BADGE_ID)).assertDoesNotExist()
    }

    private fun snap(name: String) {
        val root = compose.activity.findViewById<View>(android.R.id.content)
        val bmp = compose.runOnUiThread {
            Bitmap.createBitmap(root.width.coerceAtLeast(1), root.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                .also { b -> Canvas(b).also { it.drawColor(android.graphics.Color.BLACK); root.draw(it) } }
        }
        val out = File(System.getProperty("user.dir"), "build/configs-screens").apply { mkdirs() }
        File(out, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
