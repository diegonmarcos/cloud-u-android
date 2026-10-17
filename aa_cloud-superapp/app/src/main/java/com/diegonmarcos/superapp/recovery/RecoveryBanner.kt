package com.diegonmarcos.superapp.recovery

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import com.diegonmarcos.superapp.uikit.kitComposeView
import com.diegonmarcos.superapp.updater.Advisory
import com.diegonmarcos.superapp.updater.BuildConfig as UpdaterBuildConfig
import com.diegonmarcos.superapp.updater.Fleet

/**
 * THE PRIMARY WARNING SURFACE: an in-app card on the home screen.
 *
 * ## Why not (only) a notification
 * A system notification can be swiped away, silenced, or blocked outright at
 * the OS level, and its channel importance is frozen at creation — so the one
 * message that MUST be seen is carried by the one mechanism the user cannot
 * turn off from outside the app. Opening the app is not optional for someone
 * who uses it as their launcher, so this is seen. The notification still
 * exists ([RecoveryNotifier]) because it reaches a user who never opens the
 * app, which is a different person with the same problem; neither replaces the
 * other.
 *
 * ## Why it lives on the Activity, not a home fragment
 * There are several home surfaces (3D, grouped, drawer, tabs, fan) and any of
 * them can be the user's default. A banner added to one of them is a banner
 * most of the fleet never sees. `ShellActivity` is the single ancestor of
 * both `HomeActivity` and `MainActivity`, and `R.id.overlay_container` is the
 * full-bleed host that already spans every one of those fragments — so hosting
 * it here (the activity hands over `overlay_container`) is one anchor instead of five that can drift.
 *
 * It is added as a plain VIEW (a ComposeView, since #773), never a Fragment: `ShellActivity`
 * decides whether the home screen is showing by asking whether `overlay_container` holds a
 * fragment, and a banner that made the launcher think an overlay was open would break
 * navigation to fix a warning.
 */
object RecoveryBanner {

    private const val TAG_VIEW = "recovery-banner"
    const val TAG_CARD = "recovery:banner"
    const val TAG_ACTION = "recovery:banner:action"
    const val TAG_DISMISS = "recovery:banner:dismiss"
    const val TAG_SOURCE = "recovery:banner:source"

    /**
     * Re-render the banner in [activity]. Safe to call on every resume — it
     * removes whatever it added last time and adds nothing when there is
     * nothing to say, so the steady state costs one view lookup.
     */
    fun refresh(activity: Activity, host: FrameLayout?) {
        if (host == null) return
        host.findViewWithTag<View>(TAG_VIEW)?.let { host.removeView(it) }
        val fleet = runCatching { Fleet.parse(UpdaterBuildConfig.CONSTELLATION_FLEET_B64) }
            .getOrDefault(emptyList())
        val item = Advisory.current(activity, fleet).firstOrNull() ?: return
        host.addView(build(activity, item))
    }

    /** Keep the banner live while the activity is on screen: an advisory can
     *  arrive from a background pass or the feed while the user is looking at
     *  the home screen, and a warning that only appears on the NEXT launch is
     *  a warning delayed by however long the phone stays unlocked. */
    fun attach(activity: Activity, host: FrameLayout?) {
        Advisory.setListener { activity.runOnUiThread { refresh(activity, host) } }
        refresh(activity, host)
    }

    fun detach() = Advisory.setListener(null)

    private fun build(activity: Activity, item: Advisory.Item): View =
        activity.kitComposeView(RecoveryPalette.kit) {
            Card(item,
                onAct = { act(activity, item) },
                onDismiss = { Advisory.dismiss(activity, item.id) })
        }.apply {
            tag = TAG_VIEW
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.TOP
                val d = activity.resources.displayMetrics.density
                setMargins((10 * d).toInt(), (6 * d).toInt(), (10 * d).toInt(), 0)
            }
        }

    private fun act(activity: Activity, item: Advisory.Item) {
        // A FEED advisory may name an APK URL directly, and that link is
        // the true last resort: it does not go through this app's sources,
        // this app's fleet manifest, or this app's idea of which tag is
        // current — all of which are as old as the stranded build. Handing
        // it to the browser means the download and the install are both
        // done by software that is not the broken one.
        val link = item.link
        // An .apk link goes through the recovery screen, NOT the browser:
        // that path verifies the download before installing it, and a URL
        // that arrived in prose is exactly the case where skipping
        // verification would be easiest and worst. Any other URL is an
        // ordinary link and the browser is the right place for it.
        if (link != null && !link.endsWith(".apk", ignoreCase = true)) runCatching {
            activity.startActivity(
                android.content.Intent(android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(link)))
        }.onFailure { activity.startActivity(RecoveryActivity.intent(activity, item.appId)) }
        else activity.startActivity(RecoveryActivity.intent(activity, item.appId))
    }

    /**
     * A posted message is a message, not an alarm: it gets a button only
     * when there is somewhere to go. The recovery advisories still say
     * "Fix it" because for them there genuinely is something to fix.
     */
    internal fun actionLabel(item: Advisory.Item): String? = when {
        item.source == "feed" && item.severity == Advisory.Severity.INFO ->
            if (item.link != null) "Open link" else null
        else -> "Fix it — install directly"
    }

    internal fun accentOf(severity: Advisory.Severity, p: com.diegonmarcos.superapp.uikit.KitPalette): Color = when (severity) {
        Advisory.Severity.STUCK -> p.bad
        Advisory.Severity.WARN -> p.warn
        Advisory.Severity.INFO -> p.accent
    }

    @Composable
    internal fun Card(item: Advisory.Item, onAct: () -> Unit, onDismiss: () -> Unit) {
        val p = LocalKitPalette.current
        val accent = accentOf(item.severity, p)
        val shape = RoundedCornerShape(14.dp)
        Column(Modifier.fillMaxWidth().clip(shape).background(RecoveryPalette.bannerFill)
            .border(2.dp, accent, shape)
            // Clickable so the card swallows its own touches instead of letting
            // them through to the home screen underneath — a banner you can tap
            // THROUGH is a banner that launches an app when you meant to fix one.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .padding(start = 14.dp, top = 12.dp, end = 14.dp, bottom = 10.dp).testTag(TAG_CARD)) {
            Text(item.title, color = accent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            if (item.detail.isNotBlank()) {
                Text(item.detail.trim(), color = RecoveryPalette.muted, fontSize = 11.5.sp, maxLines = 5,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            }
            // The advisory published to the feed is the only one whose words can be
            // newer than this build; saying where it came from is the difference
            // between "the app is confused" and "someone has looked at this".
            if (item.source == "feed") {
                Text("advisory from the fleet channel", color = RecoveryPalette.faint, fontSize = 10.sp,
                    modifier = Modifier.padding(top = 3.dp).testTag(TAG_SOURCE))
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val label = actionLabel(item)
                if (label != null) Button(label, accent, onAct, Modifier.weight(1f).testTag(TAG_ACTION))
                // "DISMISS" MEANS DISMISSED. This said "Later" and was backed by an
                // in-memory set — and this app is the LAUNCHER, whose process Android
                // restarts many times an hour, so the banner returned within minutes,
                // every time. The user read that as a permanent, unremovable message,
                // and they were right to: a control that visibly does nothing is worse
                // than no control, because it teaches them the surface is a lie.
                // Advisory.dismiss now persists with commit(), and the label names the
                // duration instead of vaguely promising "later".
                Button("Dismiss", p.surfaceSelected, onDismiss, Modifier.weight(1f).testTag(TAG_DISMISS))
            }
        }
    }

    @Composable
    private fun Button(label: String, color: Color, onTap: () -> Unit, modifier: Modifier) {
        Text(label, color = Color.White, fontSize = 12.sp, textAlign = TextAlign.Center,
            modifier = modifier.clip(RoundedCornerShape(9.dp)).background(color).clickable(onClick = onTap)
                .padding(horizontal = 10.dp, vertical = 7.dp))
    }
}
