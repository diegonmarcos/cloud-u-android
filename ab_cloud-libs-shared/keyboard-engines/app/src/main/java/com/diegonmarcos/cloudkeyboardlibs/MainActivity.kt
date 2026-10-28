package com.diegonmarcos.cloudkeyboardlibs

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.diegonmarcos.superapp.uikit.CloudKitTheme
import com.diegonmarcos.superapp.uikit.KitPalette
import com.diegonmarcos.superapp.uikit.LocalKitPalette

/**
 * Minimal launcher Activity, Compose under the fleet's CloudKitTheme.
 *
 * Displays a brief description of the feature pack (stickers/GIF, voice,
 * translate) and an Update button that opens the latest APK on GitHub Releases.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fun c(id: Int) = ContextCompat.getColor(this, id)
        val palette = KitPalette.fromArgb(
            surface = c(R.color.kit_surface),
            surfaceSelected = c(R.color.kit_surface_selected),
            textPrimary = c(R.color.kit_text_primary),
            textSecondary = c(R.color.kit_text_secondary),
            accent = c(R.color.kit_accent),
            hairline = c(R.color.kit_hairline),
            tileInk = c(R.color.kit_tile_ink),
        )
        setContent {
            CloudKitTheme(palette) {
                val p = LocalKitPalette.current
                Column(Modifier.fillMaxSize().background(p.surface).systemBarsPadding().padding(24.dp)) {
                    Text(
                        stringResource(R.string.feature_pack_title),
                        color = p.textPrimary,
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Text(
                        stringResource(R.string.feature_pack_body),
                        color = p.textSecondary,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(top = 16.dp, bottom = 24.dp),
                    )
                    Text(
                        stringResource(R.string.feature_pack_update),
                        color = p.tileInk,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier
                            .background(p.accent)
                            .clickable {
                                startActivity(
                                    Intent(Intent.ACTION_VIEW,
                                        Uri.parse("https://github.com/diegonmarcos/cloudy-u-android/releases/latest/download/Cloud-Keyboard-Libs.apk"))
                                )
                            }
                            .padding(horizontal = 24.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }
}
