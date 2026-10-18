package com.diegonmarcos.superapp.launcher

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
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

    /**
     * The One UI 8 notification panel on its own, at A37 size, with realistic data: a WhatsApp group of three (folded,
     * "+2"), Gmail with two (opened, each item with its own time), one Mail and one System item, then the bottom pill
     * row. app/build/configs-screens/oneui.png.
     */
    @Test
    fun `render the One UI notification panel at A37 size`() {
        val now = System.currentTimeMillis()
        val min = 60_000L
        fun r(id: String, ago: Long, title: String, text: String) = NotifRow(now - ago, title, text, id = id)
        val views = listOf(
            NotifGroup("com.whatsapp", "WhatsApp", "", listOf(
                r("wa:1", 0, "Family · Mamá", "¿Venís el domingo a comer? Hago lasagna"),
                r("wa:2", 3 * min, "Family · Pablo", "Yo llego tarde, guardadme un plato"),
                r("wa:3", 9 * min, "Family · Lucía", "Foto"),
            )),
            NotifGroup("com.google.android.gm", "Gmail", "", listOf(
                r("gm:1", 5 * min, "Hetzner Online", "Invoice 2291 for October is available. Amount due €7.14, paid by card ending 4421."),
                r("gm:2", 42 * min, "GitHub", "[cloud-u-android] Run succeeded: Ship → SuperApp on main (#4120)"),
            )),
            NotifGroup("mail", "Mail", "", listOf(
                r("ml:1", 2 * 3_600_000L, "Ana García", "Re: weekend plans — sounds good, let's meet at the station at ten and take the early train."),
            )),
            NotifGroup("system", "System", "", listOf(
                r("sy:1", 3 * 3_600_000L, "Software update", "Android 16 · One UI 8 security patch is ready to install."),
            )),
        ).map { GroupView(it, GroupChip("${it.rows.size}", Tone.OK)) }
        val st = AggregatorState(ctx, "oneui-render", emptyList(), emptyList())
        st.collapsedGroups["com.google.android.gm"] = false
        st.markRead("sy:1", true)
        showKit {
            androidx.compose.foundation.layout.Column(
                androidx.compose.ui.Modifier.fillMaxSize()
                    .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(
                        androidx.compose.ui.graphics.Color(0xFF1C1E26), androidx.compose.ui.graphics.Color(0xFF121216))))
                    .padding(horizontal = 12.dp, vertical = 16.dp),
            ) {
                GroupList(st, views) { }
                NotifyListActions(st, always = true)
            }
        }
        compose.waitForIdle()
        snap("oneui.png")
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

    @Test
    fun `render the Notify page at A37 size`() {
        show(AggregatorStackFragment.newInstance("communication", "", "my-rss"))
        compose.waitForIdle()
        compose.onNodeWithTag(AggTags.PAGE).assertExists()
        // The persistent-badge panel fills the first screen; scroll the notification cards into view.
        compose.onNodeWithTag(AggTags.group("Backups")).performScrollTo()
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
