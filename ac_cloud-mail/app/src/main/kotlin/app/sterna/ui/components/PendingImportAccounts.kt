package app.sterna.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.sterna.R
import app.sterna.core.data.account.AuthType
import app.sterna.core.data.account.StoredAccount
import app.sterna.core.data.mail.OAuthProvider
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * The "accounts to sign in" list shown after a K-9 / backup import: every imported account still
 */
@Composable
fun PendingImportAccountsSection(
    accounts: List<StoredAccount>,
    onSignIn: (StoredAccount) -> Unit,
    onDismiss: (StoredAccount) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (accounts.isEmpty()) return
    Column(modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.import_pending_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        for (account in accounts) {
            key(account.id) {
                SwipeToDismissRow(onDismiss = { onDismiss(account) }) {
                    PendingAccountRow(account = account, onClick = { onSignIn(account) })
                }
            }
        }
    }
}

/** Horizontal travel must exceed touch-slop × this before a swipe locks in (matches the inbox). */
private const val SWIPE_SLOP_FACTOR = 1.5f

/** Fraction of the row width a swipe must reach to commit (matches the inbox). */
private const val SWIPE_COMMIT_FRACTION = 0.4f

/**
 * A row that can be swiped away in either direction, reusing the inbox mail-row feel: the "Dismiss"
 */
@Composable
private fun SwipeToDismissRow(
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    val offsetX = remember { Animatable(0f) }
    var rowWidth by remember { mutableIntStateOf(0) }
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val haptics = LocalHapticFeedback.current

    Box(
        Modifier
            .fillMaxWidth()
            .onSizeChanged { rowWidth = it.width }
            .pointerInput(Unit) {
                val slop = viewConfiguration.touchSlop
                coroutineScope {
                    while (true) {
                        val pointerId = awaitPointerEventScope {
                            awaitFirstDown(requireUnconsumed = false).id
                        }
                        // Direction-lock: only treat as a swipe once it is clearly more horizontal
                        // than vertical, so a sideways drift during a scroll isn't stolen.
                        val horizontal = awaitPointerEventScope {
                            var dx = 0f
                            var dy = 0f
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == pointerId }
                                if (change == null || !change.pressed) return@awaitPointerEventScope false
                                dx += change.positionChange().x
                                dy += change.positionChange().y
                                if (abs(dy) > slop && abs(dy) >= abs(dx)) return@awaitPointerEventScope false
                                if (abs(dx) > slop * SWIPE_SLOP_FACTOR && abs(dx) > abs(dy)) {
                                    change.consume()
                                    return@awaitPointerEventScope true
                                }
                            }
                            @Suppress("UNREACHABLE_CODE") false
                        }
                        if (!horizontal) continue
                        offsetX.stop()
                        awaitPointerEventScope {
                            horizontalDrag(pointerId) { change ->
                                val target = (offsetX.value + change.positionChange().x)
                                    .coerceIn(-rowWidth.toFloat(), rowWidth.toFloat())
                                launch { offsetX.snapTo(target) }
                                change.consume()
                            }
                        }
                        val width = rowWidth.toFloat().coerceAtLeast(1f)
                        if (abs(offsetX.value) / width >= SWIPE_COMMIT_FRACTION) {
                            val dir = if (offsetX.value > 0f) 1 else -1
                            launch {
                                offsetX.animateTo(dir * width, tween(220, easing = FastOutSlowInEasing))
                                currentOnDismiss()
                            }
                        } else {
                            launch { offsetX.animateTo(0f) }
                        }
                    }
                }
            },
    ) {
        // Reveal behind the row: neutral until armed, then the action colour + label pop + haptic.
        if (offsetX.value != 0f) {
            val draggingRight = offsetX.value > 0f
            val armed = rowWidth > 0 && abs(offsetX.value) / rowWidth >= SWIPE_COMMIT_FRACTION
            val bg by animateColorAsState(
                targetValue = if (armed) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                animationSpec = tween(120),
                label = "dismissRevealBg",
            )
            val fg by animateColorAsState(
                targetValue = if (armed) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                animationSpec = tween(120),
                label = "dismissRevealFg",
            )
            val labelScale by animateFloatAsState(
                targetValue = if (armed) 1.12f else 1f,
                animationSpec = spring(),
                label = "dismissRevealScale",
            )
            LaunchedEffect(armed) {
                if (armed) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            }
            Box(
                Modifier.matchParentSize().background(bg).padding(horizontal = 24.dp),
                contentAlignment = if (draggingRight) Alignment.CenterStart else Alignment.CenterEnd,
            ) {
                Text(
                    stringResource(R.string.import_pending_dismiss),
                    color = fg,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.graphicsLayer { scaleX = labelScale; scaleY = labelScale },
                )
            }
        }
        Box(
            Modifier
                .graphicsLayer { translationX = offsetX.value }
                .background(MaterialTheme.colorScheme.surface),
        ) {
            content()
        }
    }
}

@Composable
private fun PendingAccountRow(account: StoredAccount, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                account.label(),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                stringResource(authHintRes(account)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Which sign-in a tap will start, so the row tells the user what to expect. */
private fun authHintRes(account: StoredAccount): Int = when {
    account.authType == AuthType.OAUTH && OAuthProvider.forImapHost(account.imapHost) != null ->
        R.string.import_pending_hint_microsoft
    account.authType == AuthType.OAUTH -> R.string.import_pending_hint_oauth
    else -> R.string.import_pending_hint_password
}
