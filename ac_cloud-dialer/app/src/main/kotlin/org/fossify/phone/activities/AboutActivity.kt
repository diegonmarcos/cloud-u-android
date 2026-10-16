package org.fossify.phone.activities

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.superapp.uikit.CloudKitTheme
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import org.fossify.commons.extensions.getProperBackgroundColor
import org.fossify.phone.BuildConfig
import org.fossify.phone.R
import org.fossify.phone.extensions.dialerKitPalette

/**
 * Cloud Dialer's own About screen (Compose, CloudKitTheme). It replaces commons' AboutActivity, which
 * carries donate / "get the Pro app" / rate-us / website links, and whose Compose theme is where
 * commons' repackaging check ("fake version of the app") lives.
 *
 * GPL-3.0 section 5 / 7 still applies to this fork, so this screen is where the upstream credit
 * lives: the Fossify Phone copyright, the licence, and where the corresponding source is.
 */
class AboutActivity : SimpleActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val palette = dialerKitPalette()
        val background = Color(getProperBackgroundColor())
        setContentView(ComposeView(this).apply {
            setContent { CloudKitTheme(palette) { Screen(background) } }
        })
    }

    private fun open(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (ignored: Exception) {
        }
    }

    @Composable
    private fun Screen(background: Color) {
        val p = LocalKitPalette.current
        Column(
            Modifier.fillMaxSize().background(background).systemBarsPadding()
                .verticalScroll(rememberScrollState()).padding(16.dp).testTag("about:screen"),
        ) {
            Text(
                "‹  " + stringResource(R.string.about),
                color = p.textPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.clickable { finish() }.padding(vertical = 8.dp).testTag("about:back"),
            )
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.app_launcher_name), color = p.textPrimary, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text(BuildConfig.VERSION_NAME, color = p.textSecondary, fontSize = 14.sp)
            Spacer(Modifier.height(20.dp))

            Section(stringResource(R.string.about_upstream_title))
            Body(stringResource(R.string.about_upstream_body))
            Link(stringResource(R.string.about_upstream_link), UPSTREAM_URL)

            Section(stringResource(R.string.about_license_title))
            Body(stringResource(R.string.about_license_body))
            Link(stringResource(R.string.about_license_link), LICENSE_URL)

            Section(stringResource(R.string.about_source_title))
            Body(stringResource(R.string.about_source_body))
            Link(stringResource(R.string.about_source_link), SOURCE_URL)

            Section(stringResource(R.string.about_third_party_title))
            Body(stringResource(R.string.about_third_party_body))
        }
    }

    @Composable
    private fun Section(text: String) {
        Spacer(Modifier.height(20.dp))
        Text(text, color = LocalKitPalette.current.accent, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
    }

    @Composable
    private fun Body(text: String) {
        Text(text, color = LocalKitPalette.current.textPrimary, fontSize = 15.sp, lineHeight = 21.sp, modifier = Modifier.fillMaxWidth())
    }

    @Composable
    private fun Link(text: String, url: String) {
        Text(
            text,
            color = LocalKitPalette.current.accent,
            fontSize = 15.sp,
            textDecoration = TextDecoration.Underline,
            modifier = Modifier.clickable { open(url) }.padding(vertical = 8.dp),
        )
    }

    private companion object {
        const val UPSTREAM_URL = "https://github.com/FossifyOrg/Phone"
        const val LICENSE_URL = "https://www.gnu.org/licenses/gpl-3.0.html"
        const val SOURCE_URL = "https://github.com/diegonmarcos/cloud-u-android"
    }
}
