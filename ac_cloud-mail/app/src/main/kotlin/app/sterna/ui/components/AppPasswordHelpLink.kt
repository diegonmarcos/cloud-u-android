package app.sterna.ui.components

import android.net.Uri
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.sterna.R
import app.sterna.ui.browser.InAppBrowser
import app.sterna.ui.rememberLeaveOnce

/** Microsoft's app-password creation page (used for the OAuth→app-password fallback). */
private const val MS_APP_PASSWORD_URL = "https://account.live.com/proofs/AppPassword"

/**
 * One tap to Microsoft's app-password page, offered wherever we ask for a password Microsoft will
 */
@Composable
fun AppPasswordHelpLink() {
    val context = LocalContext.current
    val leaveOnce = rememberLeaveOnce()
    TextButton(
        onClick = {
            leaveOnce { InAppBrowser.openLink(context, Uri.parse(MS_APP_PASSWORD_URL)) }
        },
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
    ) {
        Icon(Icons.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(stringResource(R.string.connect_app_password_help))
    }
}
