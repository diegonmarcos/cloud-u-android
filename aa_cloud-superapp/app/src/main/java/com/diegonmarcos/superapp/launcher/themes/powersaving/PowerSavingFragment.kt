package com.diegonmarcos.superapp.launcher.themes.powersaving

import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.launcher.TileGridFragment
import com.diegonmarcos.superapp.settings.PowerSavingAppsPrefs

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The Cloud Power Saving mode's home screen.
 *
 * ── What changed, and why it had to ──────────────────────────────────────
 * The previous version of this screen drew itself through [
 * com.diegonmarcos.superapp.ui.LauncherPalette] and rebuilt the default
 * launcher's shapes in darker colours: a header, cards, a 6x2 grid of icon
 * tiles. The owner's verdict was blunt and correct — "this theme must have
 * all its own designs, all different, nothing can be equal". A recoloured
 * copy of the default launcher is still the default launcher.
 *
 * So this file now imports NOTHING from `ui/` and takes every colour, metric,
 * typeface and component from [PowerSavingDesign], which this mode owns
 * outright. The rule is structural rather than a thing to remember: there is
 * no palette in scope here to borrow from even by accident.
 *
 * ── The screen itself ────────────────────────────────────────────────────
 * Four blocks on pure black, separated by voids rather than by cards:
 *
 *   TIME      one huge hairline-weight clock, the date under it in the
 *             quietest legible grey. Nothing else competes with it.
 *   CHARGE    the number this mode exists for, at display size, with a
 *             one-pixel gauge instead of a filled progress bar — a filled
 *             bar would light a whole rectangle to say what a line says.
 *   SAVING    what the mode is actually doing right now, as chips. Off
 *             chips are outlines, on chips are inverted. There is no colour
 *             to signal with here, so inversion IS the state language.
 *   APPS      a text-only list, NOT an icon grid. This is the single
 *             biggest departure from every other mode and the single
 *             biggest saving on the screen: twelve launcher icons are
 *             twelve full-colour bitmaps driving every subpixel at once,
 *             while twelve lines of #C8C8C8 condensed text are a few
 *             thousand dim pixels. It also reads faster, which shortens
 *             screen-on time — the saving that dwarfs all the others.
 */
class PowerSavingFragment : Fragment() {

    private lateinit var clockView: TextView
    private lateinit var dateView: TextView
    private lateinit var chargeView: TextView
    private lateinit var gaugeFill: View
    private lateinit var gaugeRest: View
    private lateinit var estimateView: TextView
    private lateinit var refreshChip: TextView

    /** Restored verbatim in onPause so the mode owns these only while it is shown. */
    private var savedRefreshRate: Float = 0f
    private var savedBrightness: Float = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE

    /** The system's own once-a-minute tick. Cheaper than any timer we could write. */
    private val minuteTick = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            paintClock()
            paintCharge()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, state: Bundle?,
    ): View {
        val ctx = requireContext()

        val column = PowerSavingDesign.page(ctx).apply {
            addView(header(ctx))
            addView(PowerSavingDesign.space(ctx, PowerSavingDesign.GAP_SECTION))
            addView(timeBlock(ctx))
            addView(PowerSavingDesign.space(ctx, PowerSavingDesign.GAP_SECTION))
            addView(chargeBlock(ctx))
            addView(savingBlock(ctx))
            addView(appsBlock(ctx))
            addView(PowerSavingDesign.space(ctx, PowerSavingDesign.GAP_SECTION))
        }

        // A scroller so a long app list never clips on a short screen. The
        // black reaches every edge because the scroller itself is black —
        // the over-scroll glow is what used to leak the default gradient in.
        val root = ScrollView(ctx).apply {
            setBackgroundColor(PowerSavingDesign.INK_BLACK)
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            )
            addView(column)
        }

        // Home is the root of this mode, so it carries no back bar — but it
        // still has to keep the clock out from under the status bar. Padding
        // the CONTENT and not the scroller keeps the black full-bleed.
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            column.setPadding(
                PowerSavingDesign.dp(ctx, PowerSavingDesign.GAP_WIDE),
                bars.top + PowerSavingDesign.dp(ctx, PowerSavingDesign.GAP),
                PowerSavingDesign.dp(ctx, PowerSavingDesign.GAP_WIDE),
                bars.bottom + PowerSavingDesign.dp(ctx, PowerSavingDesign.GAP),
            )
            insets
        }

        paintClock()
        paintCharge()
        return root
    }

    // ── the screen, top to bottom ────────────────────────────────────────

    /**
     * The mode banner, and the one way out.
     *
     * Without the banner a black screen of outlines reads as a crash rather
     * than as a mode; without the gear, a user who wants to leave has to
     * remember where the setting lives. One label, one control, no toolbar.
     */
    private fun header(ctx: Context): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        addView(
            PowerSavingDesign.label(ctx, getString(R.string.power_saving_header)).apply {
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f,
                )
            },
        )
        addView(
            PowerSavingDesign.title(ctx, "⚙").apply {
                // 44dp: the minimum comfortable touch target. A glyph on its
                // own is about half that, and a control you have to aim at is
                // not an affordance.
                minimumWidth = PowerSavingDesign.dp(ctx, 44)
                minimumHeight = PowerSavingDesign.dp(ctx, 44)
                gravity = Gravity.CENTER
                isClickable = true
                contentDescription = getString(R.string.power_saving_settings)
                setOnClickListener { open("page:config/launcher") }
            },
        )
    }

    private fun timeBlock(ctx: Context): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        clockView = PowerSavingDesign.display(ctx, "")
        dateView = PowerSavingDesign.body(ctx, "", PowerSavingDesign.INK_TERTIARY)
        addView(clockView)
        addView(PowerSavingDesign.space(ctx, PowerSavingDesign.GAP_TIGHT))
        addView(dateView)
    }

    /**
     * Charge as a number first and a picture second.
     *
     * The gauge is two weighted views inside a 1dp-tall strip: the lit part
     * and the unlit part. That is the whole widget — no drawable, no
     * animation, no track behind it. A conventional progress bar would light
     * its full-width track permanently just to show where the end is, and on
     * this panel the track would cost more than the reading.
     */
    private fun chargeBlock(ctx: Context): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        )

        chargeView = PowerSavingDesign.title(ctx, "— %")
        addView(chargeView)
        addView(PowerSavingDesign.space(ctx, PowerSavingDesign.GAP_TIGHT))

        gaugeFill = View(ctx).apply {
            setBackgroundColor(PowerSavingDesign.INK_PRIMARY)
            layoutParams = LinearLayout.LayoutParams(0, PowerSavingDesign.dp(ctx, 1), 0f)
        }
        gaugeRest = View(ctx).apply {
            setBackgroundColor(PowerSavingDesign.HAIRLINE)
            layoutParams = LinearLayout.LayoutParams(0, PowerSavingDesign.dp(ctx, 1), 100f)
        }
        addView(
            LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, PowerSavingDesign.dp(ctx, 1),
                )
                addView(gaugeFill)
                addView(gaugeRest)
            },
        )

        addView(PowerSavingDesign.space(ctx, PowerSavingDesign.GAP_TIGHT))
        estimateView = PowerSavingDesign.label(ctx, "", PowerSavingDesign.INK_SECONDARY)
        addView(estimateView)
    }

    /** What the mode is doing, stated rather than implied. */
    private fun savingBlock(ctx: Context): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        addView(PowerSavingDesign.sectionHeader(ctx, getString(R.string.power_saving_section_saving)))
        addView(PowerSavingDesign.space(ctx, PowerSavingDesign.GAP))

        refreshChip = PowerSavingDesign.chip(ctx, getString(R.string.power_saving_chip_hz, "—"), lit = true)
        addView(
            LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                val gap = PowerSavingDesign.dp(ctx, PowerSavingDesign.GAP_TIGHT)
                fun chip(view: View) = addView(
                    view,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { if (childCount > 0) marginStart = gap },
                )
                chip(refreshChip)
                chip(
                    PowerSavingDesign.chip(
                        ctx,
                        getString(
                            R.string.power_saving_chip_brightness,
                            (DIM_BRIGHTNESS * 100).toInt(),
                        ),
                        lit = true,
                    ),
                )
                chip(PowerSavingDesign.chip(ctx, getString(R.string.power_saving_chip_dark), lit = true))
            },
        )
    }

    /**
     * The apps, as text.
     *
     * Same twelve slots the user edits in Configs — the data is unchanged,
     * only the drawing is. No icon is loaded at all, which is why this list
     * costs a fraction of the grid it replaces both in lit pixels and in the
     * bitmap decode that used to happen on every return to home.
     */
    private fun appsBlock(ctx: Context): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        addView(PowerSavingDesign.sectionHeader(ctx, getString(R.string.power_saving_section_apps)))

        val slots = PowerSavingAppsPrefs(ctx).resolved()
        if (slots.isEmpty()) {
            addView(
                PowerSavingDesign.row(ctx, getString(R.string.power_saving_apps_empty)),
            )
            return@apply
        }
        slots.forEach { slot ->
            addView(
                PowerSavingDesign.row(
                    ctx,
                    slot.label,
                    value = "›",
                    valueColour = PowerSavingDesign.INK_TERTIARY,
                ) { open(slot.target) },
            )
            addView(PowerSavingDesign.rule(ctx))
        }
    }

    // ── painting ────────────────────────────────────────────────────────

    private fun paintClock() {
        if (!isAdded) return
        // Locale.getDefault(), not a fixed one: the phone is Spanish and a
        // Samsung screen shows the Samsung locale's date.
        val now = Date()
        clockView.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
        dateView.text = SimpleDateFormat("EEEE, d MMMM", Locale.getDefault())
            .format(now)
            .replaceFirstChar { it.titlecase(Locale.getDefault()) }
    }

    private fun paintCharge() {
        if (!isAdded) return
        val ctx = requireContext()
        // Sticky broadcast read with a null receiver: this is a read of a
        // value the system already holds, NOT a subscription.
        val status = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = status?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = status?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val plugged = (status?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0

        chargeView.text = if (percent >= 0) "$percent %" else "— %"
        // An unknown charge draws an empty gauge rather than a full one: a
        // gauge that reads 100% because the platform did not answer is a lie
        // about the one number this screen exists to report.
        val filled = percent.coerceAtLeast(0)
        (gaugeFill.layoutParams as LinearLayout.LayoutParams).weight = filled.toFloat()
        (gaugeRest.layoutParams as LinearLayout.LayoutParams).weight = (100 - filled).toFloat()
        gaugeFill.requestLayout()
        gaugeRest.requestLayout()

        estimateView.text = estimateFor(ctx, percent, plugged)
    }

    /**
     * Samsung's "estimated usage time remaining". We do not have Samsung's
     * model, and inventing one would be a number that lies. BatteryManager's
     * own charge-counter/current pair is the honest source; when the platform
     * will not answer, the line says so rather than guessing.
     */
    private fun estimateFor(ctx: Context, percent: Int, plugged: Boolean): String {
        if (plugged) return getString(R.string.power_saving_charging)
        val manager = ctx.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            ?: return getString(R.string.power_saving_estimate_unknown)
        // AVERAGE, not INSTANTANEOUS: the instantaneous draw at the moment
        // the screen woke is never representative of a mostly-black screen.
        val microAmps = manager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE)
        val microAmpHours =
            manager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        // A device that does not implement the property answers Long.MIN_VALUE,
        // which is negative and would sail past a bare "is it discharging"
        // test straight into an estimate of several million years. Anything
        // outside a plausible handset draw is treated as no answer at all.
        val discharging = microAmps in -20_000_000L..-1L
        if (!discharging || microAmpHours <= 0L || percent < 0) {
            return getString(R.string.power_saving_estimate_unknown)
        }
        val hours = microAmpHours.toDouble() / (-microAmps).toDouble()
        val days = (hours / 24).toInt()
        val rest = (hours - days * 24).toInt()
        return if (days > 0) {
            getString(R.string.power_saving_estimate_days, days, rest)
        } else {
            // Under an hour still reads as "about 1 h": the alternative is a
            // screen claiming zero time left while it is plainly still running.
            getString(R.string.power_saving_estimate_hours, maxOf(1, rest))
        }
    }

    // ── the saving itself ───────────────────────────────────────────────

    override fun onResume() {
        super.onResume()
        // ACTION_TIME_TICK is a protected system broadcast, so it needs no
        // export flag: the Android 14 receiver-flag requirement covers app
        // broadcasts.
        requireContext().registerReceiver(minuteTick, IntentFilter(Intent.ACTION_TIME_TICK))
        activity?.let { PowerSavingChrome.applyWindow(it) }
        paintClock()
        paintCharge()
        applyDisplaySaving()
    }

    override fun onPause() {
        super.onPause()
        runCatching { requireContext().unregisterReceiver(minuteTick) }
        restoreDisplay()
    }

    /** Slowest advertised refresh rate plus a dimmed window — the two levers
     *  a foreground screen actually has. Both are window attributes, so
     *  leaving the mode restores them with no cleanup to forget. */
    private fun applyDisplaySaving() {
        val window = activity?.window ?: return
        val attributes = window.attributes
        savedRefreshRate = attributes.preferredRefreshRate
        savedBrightness = attributes.screenBrightness
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            @Suppress("DEPRECATION")
            val slowest = window.windowManager.defaultDisplay.supportedModes
                .minByOrNull { it.refreshRate }?.refreshRate
            if (slowest != null && slowest > 0f) {
                attributes.preferredRefreshRate = slowest
                if (isAdded) {
                    refreshChip.text =
                        getString(R.string.power_saving_chip_hz, slowest.toInt().toString())
                }
            }
        }
        attributes.screenBrightness = DIM_BRIGHTNESS
        window.attributes = attributes
    }

    private fun restoreDisplay() {
        val window = activity?.window ?: return
        val attributes = window.attributes
        attributes.preferredRefreshRate = savedRefreshRate
        attributes.screenBrightness = savedBrightness
        window.attributes = attributes
    }

    // ── dispatch ────────────────────────────────────────────────────────

    /** Every tap leaves through the shell's one dispatcher, so a Power Saving
     *  row resolves exactly like the same target anywhere else in the app. */
    private fun open(target: String) {
        if (target.isBlank()) return
        (activity as? TileGridFragment.TileClickListener)?.onTileClicked(target)
    }

    companion object {
        /** Samsung's mode dims noticeably; this is the same order of reduction
         *  and it is the difference between a saving mode and a black
         *  wallpaper. */
        private const val DIM_BRIGHTNESS = 0.35f

        fun newInstance() = PowerSavingFragment()
    }
}
