package com.diegonmarcos.superapp.launcher

import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.system.ScreenLocker
import com.diegonmarcos.superapp.ui.Haptics
import com.diegonmarcos.superapp.ui.LauncherPalette
import com.diegonmarcos.superapp.settings.LauncherTheme
import com.diegonmarcos.superapp.settings.LauncherThemes
import com.diegonmarcos.superapp.settings.PowerSavingAppsPrefs

import android.content.Context
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
 * ── A MODE, not a palette ────────────────────────────────────────────────
 * Samsung's Super Power Saving does not recolour the launcher, it REPLACES it:
 * one screen, a small fixed grid, everything else gone. That is what this is.
 * The reduction is structural and lives in three places, not in any colour:
 * [LauncherNavController.goHome] routes here instead of to the 3D home,
 * ShellActivity.applyLauncherChrome hides the status strip, the toolbar island
 * and the bottom nav, and build.json's `features` for this theme turn off
 * home_3d, bottom_nav, dynamic_island and animations while leaving `drawer` on
 * so the edge menus stay reachable. Deleting this file's colours would leave
 * the mode; deleting those three would leave a black skin.
 *
 * The layout is deliberately boring, and every part of that is a requirement
 * rather than taste: the theme's own true-black window (an OLED panel spends no
 * power on black pixels — #000000, never a near-black like #121212, which
 * lights every subpixel), no accent colour, no gradient, no rounded card
 * chrome. The time sits top-left, the settings-access icon top-right, and the
 * apps occupy the BOTTOM two rows — thumb-reachable, six per row. Nothing else
 * is on the screen.
 *
 * ── Where the colours come from ──────────────────────────────────────────
 * [LauncherPalette], which resolves this theme's declared palette from
 * build.json. They were `Color.BLACK` and `0xFFDDDDDD` here, and a literal is
 * a colour chosen at build time that no theme can reach — which is precisely
 * why picking a theme used to change the system bars and the edge menu and
 * nothing the user was actually looking at.
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
        val palette = LauncherPalette.of(ctx)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            // setBackgroundResource, not setBackgroundColor: the `window` role
            // names whichever resource paints that surface, and a theme is free
            // to make it a gradient drawable rather than a flat colour.
            setBackgroundResource(palette.windowRes)
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
                setTextColor(palette.textPrimary)
                textSize = 34f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })

            addView(ImageView(ctx).apply {
                setImageResource(android.R.drawable.ic_menu_preferences)
                // The theme's accent, which for this theme is declared WHITE:
                // an accent here would be the one coloured pixel on an
                // otherwise stock-looking screen. It stays a role rather than a
                // literal so the next theme's version of this screen is its own
                // decision instead of ours.
                setColorFilter(palette.accent)
                layoutParams = LinearLayout.LayoutParams(dp(ctx, 28), dp(ctx, 28))
                isClickable = true; isFocusable = true
                contentDescription = ctx.getString(R.string.power_saving_settings)
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
        // Real app icons, from the same central classification the Phone tab
        // reads. A grid of bare words was not the "exact match of Samsung super
        // power saving" this screen claims to be, and an icon is what a user
        // recognises an app by before they have read anything.
        root.addView(
            AppIconTile.grid(
                ctx,
                slots = PowerSavingAppsPrefs(ctx).resolved().map {
                    AppIconTile.Slot(id = it.id, label = it.label, target = it.target)
                },
                columns = LauncherThemes.gridColumnsFor(LauncherTheme.CloudPowerSaving.id),
                showSelection = false,
            ) { slot -> dispatch(slot.target) },
        )
        root.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 24),
            )
        })

        // Double-tap anywhere to lock, exactly as the other home panes do.
        ScreenLocker.attachDoubleTapLock(root)
        return root
    }

    /** Same door every other tile uses, so `app:` / `extapp:` / a URL all behave
     *  here exactly as they do on a normal grid — including install-if-missing. */
    private fun dispatch(target: String) {
        (activity as? TileGridFragment.TileClickListener)?.onTileClicked(target)
    }

    private fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    companion object { fun newInstance() = PowerSavingFragment() }
}
