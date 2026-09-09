package app.sterna.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.sterna.R
import app.sterna.ui.components.EmptyArt
import app.sterna.ui.components.EmptyState

/**
 * Shown at startup when the stored account list could not be read
 */
@Composable
fun AccountsUnreadableScreen() {
    Surface(Modifier.fillMaxSize()) {
        // Scrollable so the whole message stays reachable at large font scales.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.Center,
        ) {
            EmptyState(
                art = EmptyArt.OFFLINE,
                title = stringResource(R.string.accounts_unreadable_title),
                body = stringResource(R.string.accounts_unreadable_body),
            )
        }
    }
}
