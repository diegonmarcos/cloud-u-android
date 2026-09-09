package com.diegonmarcos.superapp.launcher

import com.diegonmarcos.superapp.system.ScreenLocker
import com.diegonmarcos.superapp.ui.Haptics
import com.diegonmarcos.superapp.settings.PowerSavingAppsPrefs

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.text.format.DateFormat
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment

/**
 * Cloud Power Saving — the home pane rendered when
 * LauncherTheme.CloudPowerSaving is active.
 *
 * WHY THIS FILE EXISTS: it did not, and that was the bug. Only Minimalist Black
 * had a home pane of its own; Power Saving hid the chrome in ShellActivity and
 * then fell through to [Home3DFragment], so the mode whose entire purpose is to
 * look like an ordinary phone rendered in the colourful house design — gradient
 * cards, accent purple, the 3D cube. A "power saving" screen that looks
 * expensive is the one thing it must not be.
 *
 * The layout is deliberately boring, and every part of that is a requirement
 * rather than taste: full black (an OLED panel spends no power on black
 * pixels), no accent colour, no gradient, no rounded card chrome. The time sits
 * top-left, the settings-access icon top-right, and the apps occupy the BOTTOM
 * two rows — thumb-reachable, six per row. Nothing else is on the screen.
 *
 * The twelve tiles are DATA, not constants: defaults live in
 * build.json::ui.launcher_themes[cloud_power_saving].home_apps and the user's
 * edits in [PowerSavingAppsPrefs]. Each tile dispatches its declared target
 * through the ordinary tile path ([TileGridFragment.TileClickListener]), which
 * is what keeps `extapp:` targets falling back to "install it" instead of doing
 * nothing — a tile that resolves to nothing is invisible until someone taps it.
 */
class PowerSavingFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val ctx = inflater.context
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }

        // ── Top row: time left, settings right ──────────────────────────────
        root.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(ctx, 20), dp(ctx, 28), dp(ctx, 20), 0)

            addView(TextView(ctx).apply {
                // 24h or 12h as the DEVICE is set, not as we assume. A launcher
                // pretending to be the stock one gets the clock format from the
                // same place the stock one does.
                text = DateFormat.getTimeFormat(ctx).format(java.util.Date())
                setTextColor(Color.WHITE)
                textSize = 34f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })

            addView(ImageView(ctx).apply {
                setImageResource(android.R.drawable.ic_menu_preferences)
                // Plain white, no tint colour: an accent here would be the one
                // coloured pixel on an otherwise stock-looking screen.
                setColorFilter(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(dp(ctx, 28), dp(ctx, 28))
                isClickable = true; isFocusable = true
                contentDescription = "Settings"
                setOnClickListener {
                    Haptics.tap(it)
                    dispatch("page:config/launcher")
                }
            })
        })

        // The empty middle. This spacer IS the design — it is what keeps the
        // apps on the bottom two rows and nothing else on the screen.
        root.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
            )
        })

        // ── Bottom two rows of six ──────────────────────────────────────────
        val slots = PowerSavingAppsPrefs(ctx).resolved()
        slots.chunked(COLUMNS).forEach { row ->
            root.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(ctx, 8), dp(ctx, 8), dp(ctx, 8), dp(ctx, 8))
                row.forEach { slot -> addView(tile(ctx, slot.label, slot.target)) }
                // Short rows keep their columns aligned with the full row above
                // instead of stretching to fill — a half-empty row that spreads
                // out reads as a different grid.
                repeat(COLUMNS - row.size) { addView(spacerCell(ctx)) }
            })
        }
        root.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 24),
            )
        })

        // Double-tap anywhere to lock, exactly as the other home panes do.
        ScreenLocker.attachDoubleTapLock(root)
        return root
    }

    /** One app cell: label only, no card, no rounded background, no accent. */
    private fun tile(ctx: Context, label: String, target: String): View =
        TextView(ctx).apply {
            text = label
            setTextColor(0xFFDDDDDD.toInt())
            textSize = 11f
            gravity = Gravity.CENTER
            maxLines = 1
            setPadding(dp(ctx, 2), dp(ctx, 10), dp(ctx, 2), dp(ctx, 10))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            isClickable = true; isFocusable = true
            setOnClickListener {
                Haptics.tap(it)
                dispatch(target)
            }
        }

    private fun spacerCell(ctx: Context): View = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    }

    /** Same door every other tile uses, so `app:` / `extapp:` / a URL all behave
     *  here exactly as they do on a normal grid — including install-if-missing. */
    private fun dispatch(target: String) {
        (activity as? TileGridFragment.TileClickListener)?.onTileClicked(target)
    }

    private fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    companion object {
        /** Six per row — the grid build.json declares as "6x2". */
        private const val COLUMNS = 6
        fun newInstance() = PowerSavingFragment()
    }
}
