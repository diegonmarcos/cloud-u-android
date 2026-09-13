package com.diegonmarcos.superapp.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Typeface
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.settings.LauncherTheme
import com.diegonmarcos.superapp.settings.LauncherThemes
import com.diegonmarcos.superapp.settings.PowerSavingAppsPrefs
import com.diegonmarcos.superapp.ui.LauncherPalette
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Cloud Power Saving — the whole screen, not a palette.
 *
 * This is a rewrite from zero. The version it replaces was never once rendered on
 * this phone: [LauncherNavController] asked `isDefaultLauncher()` before it asked
 * which theme was picked, and One UI Home owns the home button here, so choosing
 * Power Saving repainted the colours and then handed back the 3D cube. That is the
 * reported "it is only a colour change from the default one". The gate is gone;
 * what is below is the screen itself.
 *
 * The reference is Samsung's Maximum power saving mode, top to bottom:
 *
 *   ┌───────────────────────────────┐
 *   │ (system status bar, kept)   ⚙ │  ← the only chrome, top right
 *   │                               │
 *   │  14:32                        │  ← hour, thin, very large
 *   │  Saturday, 13 September       │  ← date, dim
 *   │                               │
 *   │  62 %                         │  ← charge, large
 *   │  ▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁  │  ← one hairline bar
 *   │  About 2 d 4 h left           │  ← estimate, dim
 *   │                               │
 *   │           (black)             │  ← the void. Samsung's screen is mostly this.
 *   │                               │
 *   │  ○ ○ ○ ○ ○ ○                  │  ← six apps
 *   │  ○ ○ ○ ○ ○ ○                  │  ← six apps
 *   └───────────────────────────────┘
 *
 * Two rows of six at the bottom, config top right, and the edge menus untouched —
 * the three additions asked for on top of the Samsung layout. Edge menus need no
 * code here: they are attached by the shell and no theme record can switch them
 * off (`LauncherThemes` drops any `edge_menus` key it is handed), so leaving this
 * fragment out of their way IS enabling them.
 *
 * The saving is real, not decorative:
 *
 *   - the clock is driven by ACTION_TIME_TICK, the system's existing once-a-minute
 *     broadcast. No handler, no postDelayed, no 1 Hz wakeup. Registered only while
 *     resumed, so a backgrounded screen costs exactly nothing.
 *   - charge is read from the sticky ACTION_BATTERY_CHANGED with a null receiver —
 *     a value that is already in memory, not a subscription — and re-read on the
 *     same minute tick.
 *   - the window asks the display for its LOWEST advertised refresh rate. On a
 *     120 Hz panel that is the single biggest lever a foreground screen has.
 *   - the window dims to [DIM_BRIGHTNESS]. Both are restored in onPause, so the
 *     mode cannot leak its settings into the rest of the app.
 *
 * The app grid is [AppIconTile.grid], the same builder every other home surface
 * uses. Reimplementing tiles here would be a second thing to keep in step with the
 * launcher's icon cache for no gain.
 */
class PowerSavingFragment : Fragment() {

    private lateinit var clockView: TextView
    private lateinit var dateView: TextView
    private lateinit var chargeView: TextView
    private lateinit var chargeBar: View
    private lateinit var estimateView: TextView

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
        val palette = LauncherPalette.of(ctx)

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            // The palette, not a local Color.BLACK. This screen must be OLED black —
            // a black pixel is an unlit pixel, and that is most of the saving — but
            // the place that guarantees it is the theme record plus the contrast
            // test that reads it, not a constant hidden in a fragment. One source of
            // colour; drift gets caught by the tester rather than shipped.
            setBackgroundResource(palette.windowRes)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }

        root.addView(configRow(ctx, palette))
        root.addView(clockBlock(ctx, palette))
        root.addView(batteryBlock(ctx, palette))
        root.addView(void(ctx))
        root.addView(appRows(ctx))

        paintClock()
        paintCharge()
        return root
    }

    // ── the screen, top to bottom ────────────────────────────────────────────────

    /** Top right only. Samsung puts Settings there and nothing else on the row. */
    private fun configRow(ctx: Context, palette: LauncherPalette.Palette): View =
        FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 56),
            )
            addView(TextView(ctx).apply {
                text = "⚙"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                setTextColor(palette.textSecondary)
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.END or Gravity.CENTER_VERTICAL,
                ).apply { marginEnd = dp(ctx, 20) }
                setOnClickListener { open("section:config") }
            })
        }

    private fun clockBlock(ctx: Context, palette: LauncherPalette.Palette): View =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 24), dp(ctx, 8), dp(ctx, 24), 0)
            clockView = TextView(ctx).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 64f)
                setTextColor(palette.textPrimary)
                // sans-serif-thin is the closest stock face to One UI's clock, and
                // a thin face lights fewer subpixels than a regular one.
                typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
                includeFontPadding = false
            }
            dateView = TextView(ctx).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(palette.textSecondary)
                setPadding(0, dp(ctx, 6), 0, 0)
            }
            addView(clockView)
            addView(dateView)
        }

    private fun batteryBlock(ctx: Context, palette: LauncherPalette.Palette): View =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 24), dp(ctx, 28), dp(ctx, 24), 0)
            chargeView = TextView(ctx).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 32f)
                setTextColor(palette.textPrimary)
                typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
                includeFontPadding = false
            }
            chargeBar = View(ctx).apply {
                setBackgroundColor(palette.accent)
                layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 2)).apply {
                    topMargin = dp(ctx, 10)
                }
            }
            estimateView = TextView(ctx).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(palette.textSecondary)
                setPadding(0, dp(ctx, 8), 0, 0)
            }
            addView(chargeView)
            addView(chargeBar)
            addView(estimateView)
        }

    /** The black middle. It is most of the screen and that is the point. */
    private fun void(ctx: Context): View =
        View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
            )
        }

    /**
     * Two rows of six. The count is not hardcoded here — the theme declares
     * `features.grid` ("6x2") and `home_apps`, [PowerSavingAppsPrefs] overlays the
     * user's own picks per slot, and the grid builder wraps at the declared column
     * count. Twelve slots at six columns IS two rows of six; changing the data
     * changes the screen with no edit here.
     */
    private fun appRows(ctx: Context): View {
        val slots = PowerSavingAppsPrefs(ctx).resolved().map {
            AppIconTile.Slot(id = it.id, label = it.label, target = it.target)
        }
        return AppIconTile.grid(
            ctx = ctx,
            slots = slots,
            // Read at the call site, not through a local: the grid this pane draws
            // and the grid the twelve-slot editor draws must be the SAME expression,
            // or "6x2" in build.json stops being the only place the shape is stated.
            columns = LauncherThemes.gridColumnsFor(LauncherTheme.CloudPowerSaving.id),
            showSelection = false,
            onClick = { slot -> open(slot.target) },
        ).apply {
            setPadding(dp(ctx, 8), 0, dp(ctx, 8), dp(ctx, 28))
        }
    }

    // ── painting ────────────────────────────────────────────────────────────────

    private fun paintClock() {
        if (!isAdded) return
        val now = Date()
        // Locale.getDefault(), not a fixed one: the phone is Spanish and a Samsung
        // screen shows the Samsung locale's date.
        clockView.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
        dateView.text = SimpleDateFormat("EEEE, d MMMM", Locale.getDefault())
            .format(now)
            .replaceFirstChar { it.titlecase(Locale.getDefault()) }
    }

    private fun paintCharge() {
        if (!isAdded) return
        val ctx = requireContext()
        // Sticky broadcast: this is a read of a value the system already holds, not
        // a subscription. A registered battery receiver would wake us on every
        // percent, temperature and plug change — the opposite of the mode.
        val status = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = status?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = status?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val plugged = (status?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0

        chargeView.text = if (percent >= 0) "$percent %" else "— %"
        (chargeBar.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
            // Collapse first, then draw in post{}. Without this the bar would hold
            // the PREVIOUS charge's width for one frame on every repaint, which on a
            // once-a-minute tick is a visible twitch rather than a redraw.
            lp.width = 0
            lp.weight = 0f
            chargeBar.layoutParams = lp
        }
        chargeBar.post { drawChargeBar(ctx, percent) }
        estimateView.text = estimateFor(ctx, percent, plugged)
    }

    /** One hairline whose filled width is the charge. Measured after layout so it
     *  needs no weights and no second container. */
    private fun drawChargeBar(ctx: Context, percent: Int) {
        if (!isAdded) return
        val parent = chargeBar.parent as? ViewGroup ?: return
        val usable = parent.width - parent.paddingStart - parent.paddingEnd
        if (usable <= 0) return
        val lp = chargeBar.layoutParams as LinearLayout.LayoutParams
        lp.width = if (percent >= 0) usable * percent / 100 else dp(ctx, 1)
        chargeBar.layoutParams = lp
    }

    /**
     * Samsung's "estimated usage time remaining". We do not have Samsung's model,
     * and inventing one would be a number that lies. BatteryManager's own
     * charge-counter/current pair is the honest source; when the platform will not
     * answer, the line says so rather than guessing.
     */
    private fun estimateFor(ctx: Context, percent: Int, plugged: Boolean): String {
        if (plugged) return getString(R.string.power_saving_charging)
        val manager = ctx.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            ?: return ""
        // AVERAGE, not INSTANTANEOUS: the instantaneous draw of the moment the
        // screen woke is never representative of a screen that is mostly black.
        val microAmps = manager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE)
        val microAmpHours =
            manager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        // A device that does not implement the property answers Long.MIN_VALUE, which
        // is negative and would sail past a bare "is it discharging" test straight
        // into an estimate of several million years. Anything outside a plausible
        // handset draw is treated as no answer at all.
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
            // Under an hour still reads as "about 1 h": the alternative is a screen
            // that claims zero time left while it is plainly still running.
            getString(R.string.power_saving_estimate_hours, maxOf(1, rest))
        }
    }

    // ── the saving itself ───────────────────────────────────────────────────────

    override fun onResume() {
        super.onResume()
        requireContext().registerReceiver(minuteTick, IntentFilter(Intent.ACTION_TIME_TICK))
        paintClock()
        paintCharge()
        applyDisplaySaving()
    }

    override fun onPause() {
        super.onPause()
        runCatching { requireContext().unregisterReceiver(minuteTick) }
        restoreDisplay()
    }

    /** Slowest advertised refresh rate plus a dimmed window — the two levers a
     *  foreground screen actually has. Both are window attributes, so leaving the
     *  mode restores them with no cleanup to forget. */
    private fun applyDisplaySaving() {
        val window = activity?.window ?: return
        val attributes = window.attributes
        savedRefreshRate = attributes.preferredRefreshRate
        savedBrightness = attributes.screenBrightness
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            @Suppress("DEPRECATION")
            val slowest = window.windowManager.defaultDisplay.supportedModes
                .minByOrNull { it.refreshRate }?.refreshRate
            if (slowest != null && slowest > 0f) attributes.preferredRefreshRate = slowest
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

    // ── dispatch ────────────────────────────────────────────────────────────────

    /** Every tap leaves through the shell's one dispatcher, so a Power Saving tile
     *  resolves exactly like the same target anywhere else in the app. */
    private fun open(target: String) {
        if (target.isBlank()) return
        (activity as? TileGridFragment.TileClickListener)?.onTileClicked(target)
    }

    private fun dp(ctx: Context, value: Int): Int =
        (value * ctx.resources.displayMetrics.density).toInt()

    companion object {
        /** Samsung's mode dims noticeably; this is the same order of reduction and
         *  it is the difference between a saving mode and a black wallpaper. */
        private const val DIM_BRIGHTNESS = 0.35f

        fun newInstance() = PowerSavingFragment()
    }
}
