package org.fossify.phone.activities

import android.os.Bundle
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.superapp.uikit.CloudKitTheme
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import org.fossify.commons.extensions.addBlockedNumber
import org.fossify.commons.extensions.copyToClipboard
import org.fossify.commons.extensions.formatDateOrTime
import org.fossify.commons.extensions.getProperBackgroundColor
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.phone.R
import org.fossify.phone.extensions.config
import org.fossify.phone.extensions.dialerKitPalette
import org.fossify.phone.extensions.startCallWithConfirmationCheck
import org.fossify.phone.helpers.SCREENING_REASON_BLOCKED
import org.fossify.phone.helpers.SCREENING_REASON_HIDDEN
import org.fossify.phone.helpers.SCREENING_REASON_SPAM_PREFIX
import org.fossify.phone.helpers.SCREENING_REASON_UNKNOWN
import org.fossify.phone.helpers.ScreeningLogStore
import org.fossify.phone.spam.ScreeningLog

/**
 * Cloud Dialer (patch 0013): the screened-call history. Every call the
 * CallScreeningService silently rejected (blocked number, spam rule,
 * not-in-contacts, hidden number) is listed newest-first with its reason;
 * tapping an entry offers call back / allow / block / copy.
 *
 * 2026-10-16 owner decision: all apps must be Compose. The screen is a ComposeView under
 * CloudKitTheme (libs:ui-kit) over the Fossify theme's colours; the activity still extends
 * upstream's SimpleActivity so the default-dialer plumbing is unchanged.
 */
class ScreeningLogActivity : SimpleActivity() {
    /** Newest-first entries; null until the first read finishes. */
    private var entries by mutableStateOf<List<ScreeningLog.Entry>?>(null)

    /** The entry whose actions dialog is open. */
    private var actionsFor by mutableStateOf<ScreeningLog.Entry?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val palette = dialerKitPalette()
        val background = Color(getProperBackgroundColor())
        setContentView(ComposeView(this).apply {
            setContent { CloudKitTheme(palette) { Screen(background) } }
        })
    }

    override fun onResume() {
        super.onResume()
        refreshItems()
    }

    private fun refreshItems() {
        ensureBackgroundThread {
            val read = ScreeningLogStore.read(this)
            runOnUiThread { entries = read }
        }
    }

    private fun reasonText(reason: String): String = when {
        reason.startsWith(SCREENING_REASON_SPAM_PREFIX) -> {
            val category = reason.removePrefix(SCREENING_REASON_SPAM_PREFIX)
            val base = getString(R.string.reason_known_spam)
            if (category.isEmpty()) base else "$base · $category"
        }

        reason == SCREENING_REASON_BLOCKED -> getString(R.string.reason_blocked_number)
        reason == SCREENING_REASON_UNKNOWN -> getString(R.string.reason_not_in_contacts)
        reason == SCREENING_REASON_HIDDEN -> getString(R.string.reason_hidden_number)
        else -> reason
    }

    @Composable
    private fun Screen(background: Color) {
        val list = entries
        Column(Modifier.fillMaxSize().background(background).systemBarsPadding()) {
            TopBar()
            if (list != null && list.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(R.string.no_screened_calls),
                        color = LocalKitPalette.current.textPrimary,
                        fontSize = 18.sp,
                        fontStyle = FontStyle.Italic,
                        modifier = Modifier.alpha(0.8f).testTag("screening:placeholder"),
                    )
                }
            } else if (list != null) {
                LazyColumn(
                    Modifier.fillMaxSize().testTag("screening:list"),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(list) { entry -> EntryCard(entry) }
                }
            }
        }
        actionsFor?.let { EntryActions(it) { actionsFor = null } }
    }

    @Composable
    private fun TopBar() {
        val p = LocalKitPalette.current
        var menu by remember { mutableStateOf(false) }
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "←", color = p.textPrimary, fontSize = 24.sp,
                modifier = Modifier.clickable { finish() }.padding(horizontal = 16.dp, vertical = 12.dp)
                    .testTag("screening:back"),
            )
            Text(
                stringResource(R.string.screened_calls), color = p.textPrimary, fontSize = 20.sp,
                modifier = Modifier.weight(1f),
            )
            Box {
                Text(
                    "⋮", color = p.textPrimary, fontSize = 24.sp,
                    modifier = Modifier.clickable { menu = true }.padding(horizontal = 16.dp, vertical = 12.dp)
                        .testTag("screening:menu"),
                )
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.clear_screening_log)) },
                        onClick = {
                            menu = false
                            ScreeningLogStore.clear(this@ScreeningLogActivity)
                            refreshItems()
                        },
                        modifier = Modifier.testTag("screening:clear"),
                    )
                }
            }
        }
    }

    @Composable
    private fun EntryCard(entry: ScreeningLog.Entry) {
        val p = LocalKitPalette.current
        val time = remember(entry) {
            (entry.timestamp / 1000).toInt()
                .formatDateOrTime(this, hideTimeAtOtherDays = false, showYearEvenIfCurrent = true)
        }
        val details = remember(entry) { "$time • ${reasonText(entry.reason)}" }
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp)
                .clip(RoundedCornerShape(12.dp)).background(p.surface)
                .clickable { if (entry.number.isNotEmpty()) actionsFor = entry }
                .padding(16.dp),
        ) {
            Text(
                entry.number.ifEmpty { stringResource(R.string.reason_hidden_number) },
                color = p.textPrimary, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                details, color = p.textSecondary, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }

    /** Call back / allow-or-disallow / block / copy, for one screened number. */
    @Composable
    private fun EntryActions(entry: ScreeningLog.Entry, onClose: () -> Unit) {
        val isAllowed = remember(entry) { config.isNumberAllowed(entry.number) }
        val choices = listOf(
            stringResource(R.string.call_back_number),
            stringResource(if (isAllowed) R.string.disallow_number else R.string.allow_number),
            stringResource(R.string.block_number),
            stringResource(R.string.copy_number_to_clipboard),
        )
        AlertDialog(
            onDismissRequest = onClose,
            title = { Text(entry.number) },
            text = {
                Column {
                    choices.forEachIndexed { which, label ->
                        TextButton(
                            onClick = { onClose(); runAction(entry, isAllowed, which) },
                            modifier = Modifier.fillMaxWidth().testTag("screening:action:$which"),
                        ) { Text(label, modifier = Modifier.fillMaxWidth()) }
                    }
                }
            },
            confirmButton = {},
        )
    }

    private fun runAction(entry: ScreeningLog.Entry, isAllowed: Boolean, which: Int) {
        when (which) {
            0 -> startCallWithConfirmationCheck(entry.number, entry.number)
            1 -> {
                if (isAllowed) config.removeAllowedNumber(entry.number) else config.addAllowedNumber(entry.number)
                toast(if (isAllowed) R.string.number_disallowed else R.string.number_allowed)
            }

            2 -> ensureBackgroundThread { addBlockedNumber(entry.number) }
            3 -> copyToClipboard(entry.number)
        }
    }
}
