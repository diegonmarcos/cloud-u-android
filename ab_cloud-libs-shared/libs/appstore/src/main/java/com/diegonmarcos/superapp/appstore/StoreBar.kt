package com.diegonmarcos.superapp.appstore

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.diegonmarcos.superapp.adbdebug.AdbShellLink
import com.diegonmarcos.superapp.adbdebug.ChannelSelector
import com.diegonmarcos.superapp.adbdebug.ControlStatus
import com.diegonmarcos.superapp.adbdebug.PackageVerifier
import com.diegonmarcos.superapp.updater.AutoUpdatePrefs
import kotlin.concurrent.thread

/**
 * THE Store top bar (#565) — one declaration, drawn by both Store tabs. It was
 * StoreCloudFragment.renderHeader; Phone Apps needed the same bar, and a second
 * copy is how two bars drift (#228).
 *
 * Only three verbs belong to the page: Check all, Install all, Update all. The
 * page passes each as a lambda, or null when it cannot do it — Phone Apps has no
 * privileged channel to install or update a package it does not own (#225), so
 * it passes null and the bar draws that button disabled with [Verbs.disabledReason]
 * under it. Every other control (auto-update, Wi-Fi-only, Play Protect, Wireless
 * Debugging, Developer options) is device state, identical on both tabs.
 *
 * Each control is tagged with its [Item] id, so a test can read the rendered bar
 * rather than this source.
 */
object StoreBar {

    const val TAG = "store-bar"
    /** #785 the live progress row under this bar (drawn by the Cloud tab). */
    const val PROGRESS_TAG = "store-progress"

    enum class Item { CHECK, INSTALL, DOWNLOAD, UPDATE, AUTO_UPDATE, WIFI_ONLY, PLAY_PROTECT, WIRELESS_DEBUG }

    class Verbs(
        val checkAll: () -> Unit,
        val installAll: (() -> Unit)?,
        val updateAll: (() -> Unit)?,
        /** Why a null verb is null. Drawn under the bar whenever one is. */
        val disabledReason: Int = 0,
        /** #784 pre-fetch every update into the cache, install nothing. Only the
         *  Cloud tab owns a fleet cache to fill, so null here draws NO button
         *  (rather than a disabled one with nothing to explain). */
        val downloadAll: (() -> Unit)? = null,
    )

    private val cDim = 0x99FFFFFF.toInt()

    /** (Re)draws the bar into [into]. A toggle redraws it from the device's new state. */
    fun render(host: Fragment, into: LinearLayout, verbs: Verbs) {
        val ctx = host.requireContext()
        into.removeAllViews(); into.tag = TAG
        fun redraw() { if (host.isAdded) render(host, into, verbs) }
        into.setTag(R.id.store_redraw, ::redraw)
        // Exactly two rows of controls: the batch actions and the auto-update
        // switch on the first, the configs and the remaining switches on the
        // second. Check all refreshes what is on offer, changing nothing; Install
        // all takes only what is not on the device; Update all is every group in
        // one pass, never tab-scoped.
        val actionRow = row(ctx); val configRow = row(ctx)
        into.addView(actionRow); into.addView(configRow)

        actionRow.addView(btn(ctx, Item.CHECK, ctx.getString(R.string.store_bar_check_all), 0xFF2B6CB0.toInt(), verbs.checkAll))
        actionRow.addView(btn(ctx, Item.INSTALL, ctx.getString(R.string.store_bar_install_all), 0xFF2B6CB0.toInt(), verbs.installAll))
        verbs.downloadAll?.let {
            actionRow.addView(btn(ctx, Item.DOWNLOAD, ctx.getString(R.string.store_bar_download_all), 0xFF2B6CB0.toInt(), it))
        }
        actionRow.addView(btn(ctx, Item.UPDATE, ctx.getString(R.string.store_bar_update_all), 0xFF2B6CB0.toInt(), verbs.updateAll))
        val autoOn = AutoUpdatePrefs.enabled(ctx)
        actionRow.addView(btn(ctx, Item.AUTO_UPDATE, ctx.getString(R.string.store_bar_auto_update, onOff(ctx, autoOn)),
            if (autoOn) 0xFF2F855A.toInt() else 0xFF4A4A55.toInt()) {
            AutoUpdatePrefs.setEnabled(ctx, !autoOn)
            // Reconcile the periodic workers immediately: start() schedules
            // when enabled, cancels when disabled (both re-check the pref).
            com.diegonmarcos.superapp.updater.Updater.start(ctx)
            ConstellationWorker.start(ctx)
            toast(ctx, ctx.getString(R.string.store_bar_auto_update_toast, onOff(ctx, !autoOn)))
            redraw()
        })
        // Only gates the UNATTENDED passes: the buttons above are the user
        // asking, so they download on any network regardless of this.
        val wifiOnly = AutoUpdatePrefs.requireUnmetered(ctx)
        configRow.addView(btn(ctx, Item.WIFI_ONLY, ctx.getString(R.string.store_bar_wifi_only, onOff(ctx, wifiOnly)),
            if (wifiOnly) 0xFF2F855A.toInt() else 0xFF4A4A55.toInt()) {
            AutoUpdatePrefs.setRequireUnmetered(ctx, !wifiOnly)
            toast(ctx, ctx.getString(R.string.store_bar_wifi_only_toast, onOff(ctx, !wifiOnly)))
            redraw()
        })
        if (verbs.disabledReason != 0 &&
            (verbs.installAll == null || verbs.updateAll == null)) into.addView(caption(ctx, ctx.getString(verbs.disabledReason)))
        into.addView(caption(ctx, ctx.getString(
            if (AutoUpdatePrefs.canInstallSilently(ctx)) R.string.store_bar_silent_on else R.string.store_bar_silent_off)))

        // Play Protect's per-INSTALL scan prompt. The label is what the device STORES
        // (ON / OFF / unknown when no key can be read), never a default. Tap toggles
        // through the shell channel (or WRITE_SECURE_SETTINGS); when that is absent or
        // refused it opens Play Protect's own settings. Re-read on resume.
        val pp = StoreStatus.playProtect(ctx)
        val ppLabel = when (pp) {
            ControlStatus.Tri.ON -> onOff(ctx, true)
            ControlStatus.Tri.OFF -> onOff(ctx, false)
            ControlStatus.Tri.UNKNOWN -> ctx.getString(R.string.store_unknown_state)
        }
        configRow.addView(btn(ctx, Item.PLAY_PROTECT, ctx.getString(R.string.store_bar_play_protect, ppLabel),
            when (pp) { ControlStatus.Tri.ON -> 0xFF4A4A55.toInt(); ControlStatus.Tri.OFF -> 0xFF2F855A.toInt(); else -> AMBER }) {
            if (pp == ControlStatus.Tri.UNKNOWN) { openPlayProtect(host); return@btn }
            // setScanning binds Shizuku, which blocks - never on the main thread.
            thread(name = "play-protect-toggle") {
                val r = PackageVerifier.setScanning(ctx, pp == ControlStatus.Tri.OFF)
                into.post {
                    if (r.ok) Toast.makeText(ctx, r.state.describe() + " - via " + r.channel, Toast.LENGTH_LONG).show()
                    else { Toast.makeText(ctx, ctx.getString(R.string.store_bar_play_protect_settings, r.output.lineSequence().firstOrNull().orEmpty()), Toast.LENGTH_LONG).show(); openPlayProtect(host) }
                    StoreStatus.invalidate(); redraw()
                }
            }
        })
        into.addView(caption(ctx, ctx.getString(when (pp) {
            ControlStatus.Tri.UNKNOWN -> R.string.store_bar_play_protect_unknown
            ControlStatus.Tri.OFF -> R.string.store_bar_play_protect_off
            ControlStatus.Tri.ON -> R.string.store_bar_play_protect_on })))

        // The privileged channel itself: a cached live `id` round trip (uid 2000) over the
        // embedded adb or Shizuku, probed off the main thread. NOT the system "wireless
        // debugging" switch - that can be on while the channel is dead.
        val ch = StoreStatus.channel()
        configRow.addView(channelButton(ctx, into, ::redraw))
        into.addView(caption(ctx, ctx.getString(when (ch?.state) {
            ControlStatus.Channel.UP -> R.string.store_bar_channel_up_caption
            ControlStatus.Channel.DOWN -> R.string.store_bar_channel_down_caption
            ControlStatus.Channel.NOT_PAIRED -> R.string.store_bar_channel_unpaired_caption
            null -> R.string.store_bar_channel_checking })))
    }

    /**
     * THE Wireless Debugging chip (#918): the privileged channel's live status as its label, and ONE tap
     * target - the ADB Shell page (libs:shizuku-adb-debug-tools AdbShellPage), where pairing, connecting,
     * the local server, the logs and the setup checklist live. It carries no channel control of its own:
     * test-adb-shell-one-place.sh fails if this builder ever starts one again. Drawn by this bar and by
     * Access > Android Perms. [redraw] repaints whoever hosts it.
     */
    fun channelButton(ctx: Context, into: View, redraw: () -> Unit): TextView {
        val ch = StoreStatus.channel()
        if (!StoreStatus.fresh()) StoreStatus.refresh(ctx) { into.post { redraw() } }
        val chLabel = ch?.shizuku?.let { ChannelSelector.shizukuLabel(it) } ?: when (ch?.state) {
            null -> ctx.getString(R.string.store_bar_channel_checking)
            ControlStatus.Channel.UP -> ChannelSelector.label(ch?.via, down = false)
            ControlStatus.Channel.DOWN -> ChannelSelector.label(null, down = true)
            ControlStatus.Channel.NOT_PAIRED -> ChannelSelector.label(null, down = false)
        }
        return btn(ctx, Item.WIRELESS_DEBUG, chLabel,
            when (ch?.state) { ControlStatus.Channel.UP -> 0xFF2F855A.toInt(); ControlStatus.Channel.DOWN -> 0xFFC05621.toInt()
                else -> 0xFF4A4A55.toInt() }) { AdbShellLink.open(ctx) }
    }

    /** A row holding THE chip for a Compose host; [onChange] fires whenever it redraws (status changed). */
    fun channelHost(ctx: Context, onChange: () -> Unit): View {
        val box = row(ctx)
        fun draw() {
            box.removeAllViews()
            box.addView(channelButton(ctx, box) { draw(); onChange() })
        }
        draw()
        return box
    }

    /** Back in front (e.g. from settings): drop the cached readings and repaint [bar]. */
    fun onResume(bar: View?) {
        StoreStatus.invalidate()
        @Suppress("UNCHECKED_CAST")
        (bar?.getTag(R.id.store_redraw) as? (() -> Unit))?.invoke()
    }

    private val AMBER = 0xFFB7791F.toInt()

    private fun openPlayProtect(host: Fragment) {
        val pm = host.requireContext().packageManager
        val ladder = listOf(
            Intent().setClassName("com.google.android.gms", "com.google.android.gms.security.settings.VerifyAppsSettingsActivity"),
            Intent(Settings.ACTION_SECURITY_SETTINGS), Intent(Settings.ACTION_SETTINGS))
        for (i in ladder) if (i.resolveActivity(pm) != null && runCatching { host.startActivity(i) }.isSuccess) return
    }

    private fun onOff(ctx: Context, on: Boolean) = ctx.getString(if (on) R.string.store_on else R.string.store_off)
    private fun toast(ctx: Context, t: String) = Toast.makeText(ctx, t, Toast.LENGTH_SHORT).show()
    private fun dp(ctx: Context, v: Int) = StoreDensity.dp(ctx, v)
    private fun row(ctx: Context) = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
    private fun caption(ctx: Context, t: String) = TextView(ctx).apply {
        text = t; textSize = StoreDensity.T_META; setTextColor(cDim); setPadding(0, 0, 0, dp(ctx, StoreDensity.S8))
    }

    /** A null [onClick] is a verb this page cannot do: drawn, dimmed, not clickable. */
    private fun btn(ctx: Context, item: Item, label: String, bg: Int, onClick: (() -> Unit)?) =
        button(ctx, StoreControls.load(ctx).action, label, bg, onClick).apply { tag = item }

    // ── #793 THE Store's action button and filter chip ────────────────────────
    // This bar's button is the Store's action button: every Store app row
    // (StoreCloudFragment.btn), the Store's filter chips, and every Apps Mesh
    // tool, member row button and filter chip are drawn by these two builders,
    // in the styles appstore-controls.json declares (`action`, `filter_style`).
    // Each view they draw is marked under R.id.store_control, so a test can tell
    // the shared component from a TextView merely painted to look like it.

    const val BUTTON = "store-control:button"

    fun isButton(v: View) = v.getTag(R.id.store_control) == BUTTON
    fun isChip(v: View) = v.getTag(R.id.store_control) is StoreControls.Style
    fun isPage(v: View) = v.getTag(R.id.store_control) == PAGE

    const val PAGE = "store-control:page"

    /** #809 THE page button outside line 2: opens another page, in [style]
     *  (`page_style`) — icon, label, chevron — wrapping its caption. */
    fun page(ctx: Context, style: StoreControls.Style, icon: String, label: String, onClick: () -> Unit) = TextView(ctx).apply {
        setTag(R.id.store_control, PAGE)
        text = listOf(icon, label, style.chevron).filter { it.isNotEmpty() }.joinToString("  ")
        maxLines = 1; textSize = StoreDensity.T_META; gravity = Gravity.CENTER_VERTICAL
        typeface = if (style.bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        setPadding(dp(ctx, StoreDensity.S8), dp(ctx, StoreDensity.S6), dp(ctx, StoreDensity.S8), dp(ctx, StoreDensity.S6))
        setTextColor(style.text)
        background = StoreControls.background(ctx, style, false)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(ctx, StoreDensity.S2), dp(ctx, StoreDensity.S4), dp(ctx, StoreDensity.S2), dp(ctx, StoreDensity.S2)) }
        isClickable = true; setOnClickListener { onClick() }
    }

    /** The action button: [style] in [fill], the verb's own colour; weight 1, so
     *  a row of them shares its width. Null [onClick] = drawn, dimmed, inert. */
    fun button(ctx: Context, style: StoreControls.Style, label: String, fill: Int?, onClick: (() -> Unit)?) = TextView(ctx).apply {
        setTag(R.id.store_control, BUTTON)
        text = label; gravity = Gravity.CENTER; textSize = StoreDensity.T_META
        typeface = if (style.bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        setPadding(dp(ctx, StoreDensity.S8), dp(ctx, StoreDensity.S6), dp(ctx, StoreDensity.S8), dp(ctx, StoreDensity.S6))
        setTextColor(style.text)
        background = StoreControls.background(ctx, style, false, if (onClick != null) fill else DISABLED)
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            .apply { setMargins(dp(ctx, StoreDensity.S2), dp(ctx, StoreDensity.S4), dp(ctx, StoreDensity.S2), dp(ctx, StoreDensity.S2)) }
        isEnabled = onClick != null
        if (onClick != null) { isClickable = true; setOnClickListener { onClick() } } else alpha = 0.45f
    }

    /** One filter chip of a row that partitions a list; [paint] draws it on or
     *  off. [first] has no leading gap. It carries its style under its mark, so
     *  its plain tag stays free for the page that draws it. */
    fun chip(ctx: Context, style: StoreControls.Style, label: String, first: Boolean, onClick: () -> Unit) = TextView(ctx).apply {
        setTag(R.id.store_control, style)
        text = label; textSize = StoreDensity.T_CAPTION; gravity = Gravity.CENTER; maxLines = 1
        typeface = if (style.bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        setPadding(dp(ctx, StoreDensity.S6), dp(ctx, StoreDensity.S6), dp(ctx, StoreDensity.S6), dp(ctx, StoreDensity.S6))
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            .apply { setMargins(if (first) 0 else dp(ctx, StoreDensity.S4), 0, 0, 0) }
        isClickable = true; setOnClickListener { onClick() }
    }

    fun paint(chip: TextView, active: Boolean) {
        val style = chip.getTag(R.id.store_control) as StoreControls.Style
        chip.background = StoreControls.background(chip.context, style, active)
        chip.setTextColor(if (active) style.textActive else style.text)
    }

    private const val DISABLED = 0xFF3A3A44.toInt()
}

/** The Store's page frame: the tab strip on top (when this page draws it) and the page's scrolling
 *  content below it; a page's action bar is the first thing IN that content, under the tabs, as the
 *  Cloud page's header is (#896.3). */
object StorePage {
    fun frame(ctx: Context, strip: View?, content: View): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val p = StoreDensity.dp(ctx, StoreDensity.S8)
            if (strip != null) { strip.setPadding(p, p, p, 0); addView(strip) }
            addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
}

/**
 * THE Store's top tab strip - one builder for every Store page that has tabs.
 *
 * It is what StoreCloudFragment.tabBar / tabButton / paintTabs were, lifted out so Phone
 * (Installed | Declared) draws the SAME strip as Cloud instead of
 * a second copy (#896). A strip is a list of lines, each a list of [StoreControls.Control]s
 * wearing the style their declaration names; a line whose controls all `stretch` fills the
 * width (a segmented control), any other wraps and scrolls (a set of pages). The running
 * index across the lines is the page's one tab ordering, which is what [paint] and the
 * caller's `onSelect` speak.
 *
 * No minimum height: a Store tab is as tall as its text and padding (data-dense; test-store-app.sh holds it).
 */
object StoreTabs {

    /** The strip: [lines] as one column, each line one row. [buttons] is filled in tab order. */
    fun bar(ctx: Context, lines: List<List<StoreControls.Control>>,
            buttons: MutableList<TextView>, onSelect: (Int) -> Unit): View {
        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, 0, 0, StoreDensity.dp(ctx, StoreDensity.S8)); layoutParams = lp
        }
        buttons.clear()
        for (line in lines) {
            if (line.isEmpty()) continue
            // Only BETWEEN lines, so a page with one line draws no stray rule.
            if (column.childCount > 0) column.addView(divider(ctx))
            val strip = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            for (control in line) {
                val index = buttons.size
                val t = button(ctx, control) { onSelect(index) }
                buttons.add(t); strip.addView(t)
            }
            // A wrapping line scrolls rather than clipping its last chip on a
            // narrow phone; a stretched line fills the width by definition.
            column.addView(if (line.all { it.style.stretch }) strip
                else HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(strip) })
        }
        paint(buttons, 0)
        return column
    }

    /** The hairline plus the real space that makes a second line a second table. */
    fun divider(ctx: Context) = View(ctx).apply {
        setBackgroundColor(0xFF2A2A33.toInt())
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, StoreDensity.dp(ctx, StoreDensity.S1))
            .apply { setMargins(0, StoreDensity.dp(ctx, StoreDensity.S12), 0, StoreDensity.dp(ctx, StoreDensity.S8)) }
    }

    /**
     * THE ONE tab-button builder. [control]'s style is carried as the view's tag so [paint]
     * stays one pass over one list. A stretching style (weight 1f) is a partition filling its
     * bar; a wrapping one sits left at its own width, because a set of pages is not a partition.
     * The icon leads and the chevron trails only when the style declares them.
     */
    fun button(ctx: Context, control: StoreControls.Control, onClick: () -> Unit) = TextView(ctx).apply {
        val style = control.style
        text = listOf(control.icon, control.label, style.chevron).filter { it.isNotEmpty() }.joinToString("  ")
        maxLines = 1
        tag = style
        isClickable = true
        setOnClickListener { onClick() }
        typeface = if (style.bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        textSize = StoreDensity.T_BODY
        if (style.stretch) {
            gravity = Gravity.CENTER
            setPadding(StoreDensity.dp(ctx, StoreDensity.S4), StoreDensity.dp(ctx, StoreDensity.S6), StoreDensity.dp(ctx, StoreDensity.S4), StoreDensity.dp(ctx, StoreDensity.S6))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        } else {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(StoreDensity.dp(ctx, StoreDensity.S12), StoreDensity.dp(ctx, StoreDensity.S6), StoreDensity.dp(ctx, StoreDensity.S12), StoreDensity.dp(ctx, StoreDensity.S6))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(0, 0, StoreDensity.dp(ctx, StoreDensity.S8), 0) }
        }
    }

    /** Selection reads per style: each button carries its declared style as its tag, so this is a
     *  single pass over one list and the active look is whatever that style's `_active` fields say. */
    fun paint(buttons: List<TextView>, selected: Int) = buttons.forEachIndexed { i, t ->
        val on = i == selected
        val style = t.tag as StoreControls.Style
        t.background = StoreControls.background(t.context, style, on)
        t.setTextColor(if (on) style.textActive else style.text)
    }
}
