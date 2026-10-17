package com.diegonmarcos.superapp.apps

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.diegonmarcos.superapp.ui.KitPageHarness
import com.diegonmarcos.superapp.uikit.CloudKitTheme
import com.diegonmarcos.superapp.uikit.KitPalette
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Phone > Apps: an opened folder is a solid card over the dimmed page. The Compose port painted it with the theme's `surface`,
 * which is 13%-alpha white under the Cloud theme, so the apps showed over the page behind.
 *   F1 the folder card draws opaque even under a see-through theme surface
 *   F2 the launcher's tile-folder card uses the same fill
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FolderSheetOpaqueTest : KitPageHarness() {

    private val seeThrough = KitPalette.fromArgb(
        surface = 0x21FFFFFF, surfaceSelected = 0x33FFFFFF, textPrimary = 0xFFFFFFFF.toInt(),
        textSecondary = 0xCCFFFFFF.toInt(), accent = 0xFF9CC2FF.toInt(), hairline = 0x33FFFFFF, tileInk = 0xFF000000.toInt(),
    )

    @Test fun `F1 the folder card is opaque under a see-through theme`() {
        assertTrue("the fill constant is see-through", FolderSheetFill.alpha >= 0.9f)
        lateinit var host: androidx.compose.ui.platform.ComposeView
        compose.runOnUiThread {
            host = androidx.compose.ui.platform.ComposeView(compose.activity).apply {
                setContent { CloudKitTheme(seeThrough) { FolderSheetCard("Folder") { Box(Modifier.size(200.dp, 100.dp)) } } }
            }
            compose.activity.setContentView(host)
        }
        compose.waitForIdle()
        // Paint the host the way the shell tests do, then read a pixel inside the card (clear of corners and text).
        val bmp = compose.runOnUiThread {
            android.graphics.Bitmap.createBitmap(host.width, host.height, android.graphics.Bitmap.Config.ARGB_8888)
                .also { host.draw(android.graphics.Canvas(it)) }
        }
        val c = bmp.getPixel(bmp.width / 2, bmp.height - 40)
        val alpha = (c ushr 24) / 255f
        assertTrue("folder card pixel is $alpha alpha: the apps would show the page through it", alpha >= 0.9f)
    }

    @Test fun `F2 the tile folder card uses the same opaque fill`() {
        val src = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "src/main/java/com/diegonmarcos/superapp") }.first { it.isDirectory }
        val t = File(src, "launcher/TileFolderDialog.kt").readText()
        assertTrue("TileFolderSheet must paint with FolderSheetFill, not the theme surface", t.contains("background(com.diegonmarcos.superapp.apps.FolderSheetFill)"))
    }
}
