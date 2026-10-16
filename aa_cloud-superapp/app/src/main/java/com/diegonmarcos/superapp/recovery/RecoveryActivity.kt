package com.diegonmarcos.superapp.recovery

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import com.diegonmarcos.superapp.uikit.kitComposeView
import com.diegonmarcos.superapp.updater.BootstrapInstall
import com.diegonmarcos.superapp.updater.BuildAge
import com.diegonmarcos.superapp.updater.BuildConfig as UpdaterBuildConfig
import com.diegonmarcos.superapp.updater.Fleet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * RECOVERY — the screen a stranded device can always reach.
 *
 * Every other install surface in this app assumes something works: the
 * Constellation store assumes a privileged shell channel, auto-update assumes
 * a worker that runs and a channel it can use, and both of those assume the
 * installed build is new enough to know how to do the right thing. When that
 * chain breaks, the device cannot take the fix that would repair it — it is
 * stuck by construction, and until now the only way out was a person with a
 * cable, which does not exist for most of a fleet this size.
 *
 * This screen assumes NOTHING but a network. It downloads through the same
 * verified source ladder, and hands the bytes to the ordinary Android package
 * installer. The user sees the standard confirmation sheet and nothing else.
 *
 * Deliberately its OWN Activity rather than a page inside the launcher: it is
 * the tap target for the home banner AND for the advisory notification, and a
 * notification that has to route through the launcher's tile grammar to reach
 * a screen is a notification that lands on Home when that grammar changes.
 *
 * Compose since #773 (was programmatic Views): it still needs no layout XML and no launcher
 * theme — it draws under [RecoveryPalette], fixed, so the screen keeps working when the
 * rest of the app does not.
 */
class RecoveryActivity : ComponentActivity() {

    /** The app to put first; replaced when the activity is re-launched at another row. */
    private var preselect by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preselect = intent?.getStringExtra(EXTRA_APP_ID)
        addOnNewIntentListener { i ->
            setIntent(i)
            preselect = i.getStringExtra(EXTRA_APP_ID)
        }
        // setContentView, not activity-compose's setContent: ComponentActivity installs the view-tree
        // owners the ComposeView needs, and the screen takes no dependency beyond libs:ui-kit.
        val screen: ComposeView = kitComposeView(RecoveryPalette.kit) { Screen(preselect) }
        setContentView(screen)
    }

    @Composable
    private fun Screen(preselect: String?) {
        val p = LocalKitPalette.current
        Column(Modifier.fillMaxSize().background(RecoveryPalette.page)
            .padding(start = 14.dp, top = 28.dp, end = 14.dp, bottom = 14.dp)) {
            Text("Recovery — direct install", color = p.textPrimary, fontSize = 18.sp,
                fontWeight = FontWeight.Bold, modifier = Modifier.testTag(TAG_TITLE))
            Text("Downloads the published build and hands it to Android's own " +
                "installer. Works with no pairing, no Shizuku and no special " +
                "access — you will just be asked to confirm each install.",
                color = p.textSecondary, fontSize = 11.5.sp, modifier = Modifier.padding(top = 6.dp, bottom = 4.dp))
            if (!BootstrapInstall.canRequestInstalls(this@RecoveryActivity)) {
                // Not a gate — Android offers the grant inline on the first attempt.
                // Saying so first is the difference between an unexpected settings
                // screen and an expected one.
                Text("Android will first ask you to allow this app to install apps. " +
                    "That is expected; allow it and the install continues.",
                    color = p.warn, fontSize = 11.sp, modifier = Modifier.padding(vertical = 4.dp).testTag(TAG_GRANT_NOTE))
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                val fleet = remember {
                    runCatching { Fleet.parse(UpdaterBuildConfig.CONSTELLATION_FLEET_B64) }
                        .getOrDefault(emptyList()).filter { !it.blocked }
                }
                if (fleet.isEmpty()) {
                    Text("No fleet manifest in this build — nothing to recover from here.",
                        color = p.textSecondary, modifier = Modifier.testTag(TAG_EMPTY))
                } else {
                    for (app in ordered(fleet, { it.id }, { it.pkg }, preselect, packageName)) {
                        AppCard(app, highlight = app.id == preselect)
                    }
                }
            }
        }
    }

    @Composable
    private fun AppCard(app: Fleet.App, highlight: Boolean) {
        val p = LocalKitPalette.current
        val scope = rememberCoroutineScope()
        // WHAT YOU HAVE, stated before anything is downloaded. The version the
        // fetch will actually install is only knowable after the fetch, so the
        // status line below is rewritten with both numbers once it lands —
        // "is it really newer?" must be answerable from the screen, not taken
        // on trust because a button said Install.
        val installedCode = remember(app.id) {
            BootstrapInstall.installedVersionCode(this, app.pkg)
                ?: app.altId?.let { BootstrapInstall.installedVersionCode(this, it) }
        }
        var status by remember(app.id) {
            mutableStateOf(if (installedCode == null) "not installed"
                           else "installed: build ${BuildAge.describe(installedCode)}")
        }
        var busy by remember(app.id) { mutableStateOf(false) }
        val shape = RoundedCornerShape(12.dp)
        Column(Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(shape).background(p.surface)
            .then(if (highlight) Modifier.border(2.dp, p.bad, shape) else Modifier)
            .padding(horizontal = 12.dp, vertical = 10.dp).testTag(cardTag(app.id))) {
            Text(app.label + if (app.pkg == packageName) "  (this app)" else "",
                color = p.textPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
            Text(status, color = p.textSecondary, fontSize = 11.sp,
                modifier = Modifier.padding(top = 3.dp, bottom = 6.dp).testTag(statusTag(app.id)))
            Text("Download + install directly", color = p.textPrimary, fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).background(p.ok)
                    .clickable(enabled = !busy) {
                        busy = true
                        status = "fetching…"
                        scope.launch {
                            val r = withContext(Dispatchers.IO) { BootstrapInstall.launch(this@RecoveryActivity, app) }
                            busy = false
                            r.onSuccess { c ->
                                // Report the EVIDENCE, not just success: "digest
                                // sha256:…" and "size 32012393 B" are different
                                // guarantees and the screen should not imply they are
                                // the same one.
                                status = c.versionLine() +
                                    (c.behindBy?.let { "\n$it" } ?: "") +
                                    "\nverified by ${c.evidence}" +
                                    if (c.isNewer) "" else "\nNOTE: this is not newer than what you have."
                            }.onFailure {
                                status = "could not fetch: ${it.message}"
                            }
                        }
                    }
                    .padding(horizontal = 10.dp, vertical = 8.dp).testTag(goTag(app.id)))
        }
    }

    override fun onResume() {
        super.onResume()
        // Reaching this screen at all means the user acted on the advisory, but
        // it is the INSTALL that resolves it — so nothing is cleared here.
        // Advisory.recordSuccess fires from the install path itself.
        RecoveryNotifier.cancelAll(this)
    }

    companion object {
        const val EXTRA_APP_ID = "recovery_app_id"

        const val TAG_TITLE = "recovery:title"
        const val TAG_GRANT_NOTE = "recovery:grant-note"
        const val TAG_EMPTY = "recovery:empty"
        fun cardTag(id: String) = "recovery:card:$id"
        fun statusTag(id: String) = "recovery:status:$id"
        fun goTag(id: String) = "recovery:go:$id"

        /**
         * [preselect] first, then this app, then everything else. The banner and the notification
         * both know WHICH app is stuck, and making the user hunt for it in a 40-entry list is how a
         * one-tap fix becomes a support request. Self is ALWAYS next: this app is the one whose
         * breakage takes every other recovery path down with it. Stable within a rank.
         */
        internal fun <T> ordered(items: List<T>, id: (T) -> String, pkg: (T) -> String,
                                 preselect: String?, selfPkg: String): List<T> =
            items.sortedBy {
                when {
                    id(it) == preselect -> 0
                    pkg(it) == selfPkg -> 1
                    else -> 2
                }
            }

        /** Deep link straight at [appId]'s row. Null opens the list. */
        fun intent(ctx: Context, appId: String?): Intent =
            Intent(ctx, RecoveryActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .apply { if (appId != null) putExtra(EXTRA_APP_ID, appId) }
    }
}
