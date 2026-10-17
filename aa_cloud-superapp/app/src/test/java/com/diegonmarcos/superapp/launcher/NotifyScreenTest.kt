package com.diegonmarcos.superapp.launcher

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.superapp.core.NotificationStore
import com.diegonmarcos.superapp.ui.KitPageHarness
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The Notify page (communication/my-rss, mirrored as Launcher ▸ Notify) with sample notifications, drawn on the owner's
 * phone: Samsung Galaxy A37, 384 x 832 dp at 450 dpi. Evidence for the One UI 8 card restyle:
 * app/build/configs-screens/notify-after.png (the Configs screens workflow uploads the folder).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w384dp-h832dp-450dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NotifyScreenTest : KitPageHarness() {

    private val ctx get() = ApplicationProvider.getApplicationContext<Application>()
    private val realPoller = AggregatorModel.ntfyPoller

    @Before
    fun seed() {
        AggregatorModel.ntfyPoller = { topics -> topics.associateWith { NtfyResult(true, "", emptyList()) } }
        NotificationStore.clear(ctx)
        val samples = listOf(
            Triple("Backups", "Nightly backup finished", "oci-vm1 · 4.2 GB in 3m 12s, 0 errors. Next run 03:00."),
            Triple("Mail", "Invoice #2291 from Hetzner", "Your invoice for October is ready. Amount due €7.14, paid by card."),
            Triple("Mail", "Re: weekend plans", "Sounds good — let's meet at the station at ten and take the early train."),
            Triple("Mail", "Your parcel is on its way", "Tracking 1Z999 · arriving tomorrow between 9:00 and 13:00."),
            Triple("Fleet", "gcp-proxy disk at 91 %", "Root volume is filling up; caches can be pruned from the C3 page.",),
            Triple("Chat", "Ana", "Did you see the new build? The notification page looks like a phone now."),
            Triple("Chat", "Dev channel", "CI is green on main again after the Gradle cache fix."),
        )
        for ((src, title, body) in samples) {
            NotificationStore.push(ctx, src, title, body,
                if (src == "Fleet") NotificationStore.Sev.WARN else NotificationStore.Sev.INFO)
        }
    }

    @After
    fun restore() {
        AggregatorModel.ntfyPoller = realPoller
        NotificationStore.clear(ctx)
    }

    @Test
    fun `render the Notify page at A37 size`() {
        show(AggregatorStackFragment.newInstance("communication", "", "my-rss"))
        compose.waitForIdle()
        compose.onNodeWithTag(AggTags.PAGE).assertExists()
        // The persistent-badge panel fills the first screen; scroll the notification cards into view.
        compose.onNodeWithTag(AggTags.LIST_ACTIONS).performScrollTo()
        compose.waitForIdle()
        val root = compose.activity.findViewById<View>(android.R.id.content)
        val bmp = compose.runOnUiThread {
            Bitmap.createBitmap(root.width.coerceAtLeast(1), root.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                .also { b -> Canvas(b).also { it.drawColor(android.graphics.Color.BLACK); root.draw(it) } }
        }
        val out = File(System.getProperty("user.dir"), "build/configs-screens").apply { mkdirs() }
        File(out, "notify-after.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
