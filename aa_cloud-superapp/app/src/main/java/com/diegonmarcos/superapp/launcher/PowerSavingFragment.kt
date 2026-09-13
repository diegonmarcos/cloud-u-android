package com.diegonmarcos.superapp.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
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
 * Cloud Power Saving — a whole screen of its own, written from zero.
 *
 * WHAT WENT WRONG TWICE, so it does not go wrong a third time. The first version
 * was a palette: the same 3D cube home, repainted black. The second version got
 * the layout right but still drew its twelve app slots through [AppIconTile], the
 * SAME builder the colourful default home uses — so on the phone it still read as
 * "the normal launcher, in black". Every pixel below is drawn by this file. There
 * is no shared tile, no shared card, no shared row. That is the point of the
 * mode: it must look like a different phone, not like this app with the lights
 * off.
 *
 * The reference is Samsung's Maximum power saving mode, top to bottom, plus the
 * four additions that were asked for on top of it:
 *
 *   ┌──────────────────────────────────────┐
 *   │ ◗ POWER SAVING              ⚙   ⋮   │ ← addition 2: config, top right
 *   │                                      │
 *   │  14:32                               │ ← hour, hairline, very large
 *   │  Saturday, 13 September              │
 *   │                                      │
 *   │  ┌────────────────────────────────┐  │
 *   │  │ 62 %                           │  │ ← battery panel, outlined
 *   │  │ ▰▰▰▰▰▰▰▰▰▱▱▱▱▱▱▱▱▱▱▱▱▱▱▱▱▱▱▱▱▱ │  │
 *   │  │ ESTIMATED USAGE TIME REMAINING │  │
 *   │  │ 2 d 4 h                        │  │
 *   │  └────────────────────────────────┘  │
 *   │                                      │
 *   │  ( 60 Hz ) ( Brightness 35% ) (Dark) │ ← addition 4: what the mode is doing
 *   │                                      │
 *   │              (black)                 │ ← the void. Most of the screen.
 *   │                                      │
 *   │  ▢ ▢ ▢ ▢ ▢ ▢                         │ ← addition 1: two rows …
 *   │  ▢ ▢ ▢ ▢ ▢ ▢                         │ ←   … of six apps
 *   │                                      │ ← dock strip, left empty on purpose
 *   └──────────────────────────────────────┘
 *
 * Addition 3, the edge menus, needs no code here and that is deliberate: they are
 * attached by the shell and no theme record can switch them off ([LauncherThemes]
 * drops any `edge_menus` key it is handed). Keeping this fragment out of their
 * way IS enabling them — it draws no swipe handler, consumes no horizontal drag,
 * and puts nothing under the screen edges.
 *
 * THE SAVING IS REAL, not a black wallpaper:
 *
 *   - the clock runs on ACTION_TIME_TICK, the system's existing once-a-minute
 *     broadcast. No handler, no postDelayed, no 1 Hz wakeup. Registered only
 *     while resumed, so a backgrounded screen costs exactly nothing.
 *   - charge comes from the sticky ACTION_BATTERY_CHANGED read with a null
 *     receiver — a value already in memory, not a subscription that would wake us
 *     on every percent, temperature and plug change.
 *   - the window asks the display for its LOWEST advertised refresh rate. On a
 *     120 Hz panel that is the single biggest lever a foreground screen has.
 *   - the window dims to [DIM_BRIGHTNESS].
 *   - every colour comes from the theme's palette, which is OLED black: a black
 *     pixel is an unlit pixel, and on this panel that is most of the saving.
 *
 * All four are restored in onPause, so the mode cannot leak its settings into the
 * rest of the app.
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
        val palette = LauncherPalette.of(ctx)

        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            // The palette, never a local Color.BLACK. One source of colour, so the
            // contrast tester that reads the theme record catches drift instead of
            // a constant hidden in a fragment shipping it.
            setBackgroundResource(palette.windowRes)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            addView(headerRow(ctx, palette))
            addView(clockBlock(ctx, palette))
            addView(batteryPanel(ctx, palette))
            addView(savingChips(ctx, palette))
            addView(theVoid(ctx))
            addView(appGrid(ctx, palette))
            paintClock()
            paintCharge()
        }
    }

    // ── the screen, top to bottom ────────────────────────────────────────────────

    /**
     * A mode banner on the left and the two controls on the right. Samsung puts a
     * ⋮ and a ⚙ up here and nothing else on the row, and the banner is what makes
     * the screen self-explanatory — without it a black screen full of outlines
     * reads as a crash rather than as a mode.
     */
    private fun headerRow(ctx: Context, palette: LauncherPalette.Palette): View =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(ctx, 22), dp(ctx, 14), dp(ctx, 18), 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            )

            addView(TextView(ctx).apply {
                text = getString(R.string.power_saving_header)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTextColor(palette.accent)
                letterSpacing = 0.22f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            })

            addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))

            // Two separate targets, not one menu: the gear is the errand that
            // brings anyone here (leave the mode / change its apps) and burying it
            // one tap deeper is the difference between a mode and a trap.
            addView(headerButton(ctx, palette, "⚙") { open("section:config") })
            addView(headerButton(ctx, palette, "⋮") { open("page:config/launcher") })
        }

    private fun headerButton(
        ctx: Context, palette: LauncherPalette.Palette, glyph: String, onTap: () -> Unit,
    ): View = TextView(ctx).apply {
        text = glyph
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        setTextColor(palette.textSecondary)
        gravity = Gravity.CENTER
        // 44dp: the minimum comfortable touch target. A 20sp glyph alone is about
        // half that, and a control you have to aim at is not an affordance.
        layoutParams = LinearLayout.LayoutParams(dp(ctx, 44), dp(ctx, 44))
        setOnClickListener { onTap() }
    }

    private fun clockBlock(ctx: Context, palette: LauncherPalette.Palette): View =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 22), dp(ctx, 18), dp(ctx, 22), 0)
            clockView = TextView(ctx).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 72f)
                setTextColor(palette.textPrimary)
                // sans-serif-thin is the closest stock face to One UI's clock, and a
                // hairline face lights fewer subpixels than a regular one — on this
                // screen the typeface is itself a power decision.
                typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
                includeFontPadding = false
            }
            dateView = TextView(ctx).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(palette.textSecondary)
                setPadding(0, dp(ctx, 8), 0, 0)
            }
            addView(clockView)
            addView(dateView)
        }

    /**
     * The battery is the whole reason this mode exists, so it gets a panel rather
     * than a line: charge, a gauge, and the estimate under a quiet caption.
     */
    private fun batteryPanel(ctx: Context, palette: LauncherPalette.Palette): View =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = outline(ctx, radiusDp = 18, color = palette.hairline)
            setPadding(dp(ctx, 18), dp(ctx, 16), dp(ctx, 18), dp(ctx, 16))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                marginStart = dp(ctx, 22); marginEnd = dp(ctx, 22); topMargin = dp(ctx, 26)
            }

            chargeView = TextView(ctx).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 38f)
                setTextColor(palette.textPrimary)
                typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
                includeFontPadding = false
            }
            addView(chargeView)

            // Two weighted children inside a capsule track. Weights mean the gauge
            // is correct at every screen width with no measure pass and no post{}
            // — the previous version computed a pixel width after layout and held
            // the PREVIOUS charge for one frame on every repaint.
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                background = capsule(ctx, palette.textSecondary, alpha = 0x30, heightDp = 7)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 7),
                ).apply { topMargin = dp(ctx, 14) }

                gaugeFill = View(ctx).apply {
                    background = capsule(ctx, palette.accent, alpha = 0xFF, heightDp = 7)
                }
                gaugeRest = View(ctx)
                addView(gaugeFill, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0f))
                addView(gaugeRest, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            })

            addView(TextView(ctx).apply {
                text = getString(R.string.power_saving_estimate_label)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
                setTextColor(palette.textSecondary)
                letterSpacing = 0.16f
                setPadding(0, dp(ctx, 16), 0, 0)
            })
            estimateView = TextView(ctx).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextColor(palette.textPrimary)
                setPadding(0, dp(ctx, 3), 0, 0)
            }
            addView(estimateView)
        }

    /**
     * What the mode is doing to the hardware, stated on the screen. A saving mode
     * that shows no evidence of saving anything is indistinguishable from a dark
     * theme, which is exactly the complaint this rewrite answers. The refresh chip
     * is filled in for real in [applyDisplaySaving] — it reports the rate the
     * display accepted, not the rate we asked for.
     */
    private fun savingChips(ctx: Context, palette: LauncherPalette.Palette): View =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(ctx, 22), dp(ctx, 18), dp(ctx, 22), 0)
            refreshChip = chip(ctx, palette, getString(R.string.power_saving_chip_hz, "—"))
            addView(refreshChip)
            addView(chip(ctx, palette, getString(
                R.string.power_saving_chip_brightness, (DIM_BRIGHTNESS * 100).toInt(),
            )))
            addView(chip(ctx, palette, getString(R.string.power_saving_chip_dark)))
        }

    private fun chip(ctx: Context, palette: LauncherPalette.Palette, label: String): TextView =
        TextView(ctx).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setTextColor(palette.textSecondary)
            background = outline(ctx, radiusDp = 13, color = palette.hairline)
            setPadding(dp(ctx, 11), dp(ctx, 6), dp(ctx, 11), dp(ctx, 6))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = dp(ctx, 8) }
        }

    /** The black middle. It is most of the screen and that is the design. */
    private fun theVoid(ctx: Context): View =
        View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
            )
        }

    /**
     * Two rows of six, drawn here and nowhere else, sitting one row ABOVE the
     * bottom of the screen.
     *
     * WHY THE LAST ROW IS EMPTY. The bottom row of an Android home screen is the
     * dock strip, and the platform caps it (5 on this device) — six tiles down
     * there either lose one or get squeezed. Rather than fight that cap, the strip
     * is surrendered: [RESERVED_DOCK_ROW_HEIGHT_DP] of nothing is left at the
     * bottom and both full rows of six are stacked above it. Empty is also free on
     * an OLED panel, so the compromise costs this mode nothing.
     *
     * The count is not hardcoded: the theme declares `features.grid` ("6x2") and
     * `home_apps`, [PowerSavingAppsPrefs] overlays the owner's own pick per slot,
     * and the rows below wrap at the declared column count. Twelve slots at six
     * columns IS two rows of six; changing build.json changes the screen with no
     * edit here.
     */
    private fun appGrid(ctx: Context, palette: LauncherPalette.Palette): View {
        val columns = LauncherThemes
            .gridColumnsFor(LauncherTheme.CloudPowerSaving.id)
            .coerceAtLeast(1)
        val slots = PowerSavingAppsPrefs(ctx).resolved()

        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 14), 0, dp(ctx, 14), 0)
            slots.chunked(columns).forEach { rowSlots ->
                addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = dp(ctx, 14) }
                    rowSlots.forEach { slot ->
                        addView(appCell(ctx, palette, slot.label, slot.target))
                    }
                    // A short last row must stay left-aligned on the same pitch as
                    // the full row above it. Without these fillers five icons would
                    // spread out and the two rows would visibly disagree.
                    repeat(columns - rowSlots.size) {
                        addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
                    }
                })
            }
            // The surrendered dock strip. A plain spacer, not padding, because it
            // is a row of the grid conceptually — one slot tall, deliberately blank.
            addView(View(ctx), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, RESERVED_DOCK_ROW_HEIGHT_DP),
            ))
        }
    }

    /**
     * One app: a hairline rounded square holding the label's initial, with the
     * label under it. No launcher icon, by choice — a grid of the app's colourful
     * icons is precisely what makes a black screen look like the normal home with
     * the lights off, and a monochrome outline costs no icon load, no bitmap
     * decode and no cache lookup on a screen whose whole job is to spend nothing.
     */
    private fun appCell(
        ctx: Context, palette: LauncherPalette.Palette, label: String, target: String,
    ): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        setOnClickListener { open(target) }

        addView(TextView(ctx).apply {
            text = label.trim().firstOrNull()?.uppercase(Locale.getDefault()) ?: "·"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTextColor(palette.textPrimary)
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            background = outline(ctx, radiusDp = 14, color = withAlpha(palette.textPrimary, 0x59))
            layoutParams = LinearLayout.LayoutParams(dp(ctx, 46), dp(ctx, 46))
        })

        addView(TextView(ctx).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
            setTextColor(palette.textSecondary)
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(ctx, 2), dp(ctx, 7), dp(ctx, 2), 0)
        })
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
        // Sticky broadcast read with a null receiver: this is a read of a value the
        // system already holds, NOT a subscription.
        val status = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = status?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = status?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val plugged = (status?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0

        chargeView.text = if (percent >= 0) "$percent %" else "— %"
        // An unknown charge draws an empty gauge rather than a full one: a gauge
        // that reads 100% because the platform did not answer is a lie about the
        // one number this screen exists to report.
        val filled = percent.coerceAtLeast(0)
        (gaugeFill.layoutParams as LinearLayout.LayoutParams).weight = filled.toFloat()
        (gaugeRest.layoutParams as LinearLayout.LayoutParams).weight = (100 - filled).toFloat()
        gaugeFill.requestLayout()
        gaugeRest.requestLayout()

        estimateView.text = estimateFor(ctx, percent, plugged)
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
            ?: return getString(R.string.power_saving_estimate_unknown)
        // AVERAGE, not INSTANTANEOUS: the instantaneous draw at the moment the
        // screen woke is never representative of a screen that is mostly black.
        val microAmps = manager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE)
        val microAmpHours =
            manager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        // A device that does not implement the property answers Long.MIN_VALUE,
        // which is negative and would sail past a bare "is it discharging" test
        // straight into an estimate of several million years. Anything outside a
        // plausible handset draw is treated as no answer at all.
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
            // claiming zero time left while it is plainly still running.
            getString(R.string.power_saving_estimate_hours, maxOf(1, rest))
        }
    }

    // ── the saving itself ───────────────────────────────────────────────────────

    override fun onResume() {
        super.onResume()
        // ACTION_TIME_TICK is a protected system broadcast, so it needs no export
        // flag: the Android 14 receiver-flag requirement covers app broadcasts.
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

    // ── drawing helpers ─────────────────────────────────────────────────────────

    /** A hairline rounded outline. Every framed thing on this screen is one of
     *  these, which is what gives the mode a single visual language instead of
     *  borrowing the app's cards. The stroke colour is passed in, not derived
     *  here: panels use the palette's own [LauncherPalette.Palette.hairline] and
     *  the app tiles use a brighter mix, so the twelve apps read as the
     *  foreground and the chrome recedes. */
    private fun outline(ctx: Context, radiusDp: Int, color: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(ctx, radiusDp).toFloat()
            setColor(Color.TRANSPARENT)
            setStroke(maxOf(1, dp(ctx, 1)), color)
        }

    private fun capsule(ctx: Context, color: Int, alpha: Int, heightDp: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(ctx, heightDp).toFloat() / 2f
            setColor(withAlpha(color, alpha))
        }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

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

        /** Height of the blank dock strip kept at the very bottom, so both rows of
         *  six clear the platform's 5-icon dock cap. One app cell tall: 46dp tile +
         *  7dp gap + 9sp label + the 14dp row pitch, rounded to a round number. */
        private const val RESERVED_DOCK_ROW_HEIGHT_DP = 86

        fun newInstance() = PowerSavingFragment()
    }
}
