package app.sterna.ui.onboarding

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import app.sterna.R
import app.sterna.ui.components.EmptyArt
import app.sterna.ui.components.EmptyState
import app.sterna.ui.theme.MailMetrics

/**
 * First-launch privacy welcome. Communicates Sterna's posture (no ads, no tracking,
 */
@Composable
fun WelcomeScreen(onDone: () -> Unit) {
    val pageCount = 2
    var page by rememberSaveable { mutableIntStateOf(0) }
    val lastPage = pageCount - 1

    Scaffold(
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = MailMetrics.s24, vertical = MailMetrics.s16),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(MailMetrics.s8),
            ) {
                PageDots(current = page, count = pageCount)
                Button(
                    onClick = { if (page < lastPage) page++ else onDone() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        stringResource(
                            if (page < lastPage) R.string.welcome_next else R.string.welcome_get_started,
                        ),
                    )
                }
                TextButton(onClick = onDone) {
                    Text(stringResource(R.string.welcome_skip))
                }
            }
        },
    ) { padding ->
        Crossfade(
            targetState = page,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            label = "welcome-page",
        ) { p ->
            val art = if (p == 0) EmptyArt.INBOX_ZERO else EmptyArt.FOLDER
            val title = stringResource(
                if (p == 0) R.string.connect_welcome_title else R.string.welcome_screen2_title,
            )
            val body = stringResource(
                if (p == 0) R.string.welcome_body else R.string.welcome_screen2_body,
            )
            // Scrollable so the content stays reachable at large font scales.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                contentAlignment = Alignment.Center,
            ) {
                EmptyState(art = art, title = title, body = body)
            }
        }
    }
}

/** A small row of page-position dots. Decorative — the controls carry the labels. */
@Composable
private fun PageDots(current: Int, count: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(MailMetrics.s8)) {
        repeat(count) { index ->
            val color = if (index == current) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outlineVariant
            }
            Box(
                Modifier
                    .size(MailMetrics.s8)
                    .clip(CircleShape)
                    .background(color),
            )
        }
    }
}
