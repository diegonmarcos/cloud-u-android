package app.cloudnotes.pro

import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import com.diegonmarcos.superapp.ai.AiFleetChatPanel

/** Search > Ask: the fleet's shared AI chat (libs:ai) as a full page; [NativeNav] places and shows it. */
internal fun askPage(ctx: Context, gatewayUrl: String): ComposeView = ComposeView(ctx).apply {
    setContent {
        MaterialTheme(colorScheme = darkColorScheme()) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                AiFleetChatPanel(gatewayUrl)
            }
        }
    }
}
