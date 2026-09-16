package com.diegonmarcos.superapp.settings

import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.ui.Haptics
import com.diegonmarcos.superapp.ui.LauncherPalette

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment

/**
 * The chrome the Launcher tabs share.
 *
 * These four were private members of LauncherConfigFragment while that fragment
 * was the whole Launcher page. Splitting Profiles out into its own tab gave them
 * a second caller, and a second COPY of a selectable card is exactly the defect
 * #228 found with the status light: two private paintings of one widget that
 * drift the moment either is touched. They were already context-parameterised,
 * so they lift out as top-level functions with no state to carry.
 */

internal fun dp(ctx: Context, v: Int): Int =
    (v * ctx.resources.displayMetrics.density).toInt()

internal fun spacer(ctx: Context, h: Int): View = View(ctx).apply {
    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, h)
}

/** Section title + caption block. */
internal fun sectionHeader(ctx: Context, title: String, subtitle: String): View {
    val palette = LauncherPalette.of(ctx)
    return LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(ctx).apply {
            text = title
            setTextColor(palette.textPrimary)
            setTextAppearance(android.R.style.TextAppearance_Material_Headline)
            setPadding(0, 0, 0, dp(ctx, 8))
        })
        addView(TextView(ctx).apply {
            text = subtitle
            setTextColor(palette.textSecondary)
            setTextAppearance(android.R.style.TextAppearance_Material_Body2)
            setPadding(0, 0, 0, dp(ctx, 16))
        })
    }
}

/** Selectable card used by both the Profiles and the Modes pickers — label up
 *  top, optional subtitle beneath, selected-state filled in brand purple,
 *  unselected on 13% white. */
internal fun genericTile(
    ctx: Context,
    label: String,
    subtitle: String,
    isSelected: Boolean,
    onClick: () -> Unit,
): View {
    val palette = LauncherPalette.of(ctx)
    val tile = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        val pad = dp(ctx, 14); setPadding(pad, pad, pad, pad)
        setBackgroundColor(if (isSelected) palette.surfaceSelected else palette.surface)
        isClickable = true; isFocusable = true
        setOnClickListener {
            Haptics.tap(it)
            onClick()
        }
    }
    tile.addView(TextView(ctx).apply {
        text = (if (isSelected) "● " else "○ ") + label
        setTextColor(palette.textPrimary)
        setTextAppearance(android.R.style.TextAppearance_Material_Subhead)
    })
    if (subtitle.isNotBlank()) {
        tile.addView(TextView(ctx).apply {
            text = subtitle
            setTextColor(palette.textSecondary)
            setTextAppearance(android.R.style.TextAppearance_Material_Caption)
            setPadding(0, dp(ctx, 4), 0, 0)
        })
    }
    return tile
}

/** Force the fragment to rebuild so picker selections refresh. detach+attach in
 *  ONE transaction is collapsed to a no-op by the FragmentManager (the net state
 *  is unchanged) — splitting into two commitNow() calls guarantees onCreateView
 *  re-runs and the "● / ○" selection state updates. */
internal fun Fragment.rerenderPage() {
    runCatching {
        val fm = parentFragmentManager
        fm.beginTransaction().detach(this).commitNow()
        fm.beginTransaction().attach(this).commitNow()
    }
}

/**
 * Push the toggles whose views live in the ACTIVITY SHELL — stars, pets,
 * island waves — at those views, so a switch flipped on this page takes
 * effect now.
 *
 * Those three are NOT recreated when a page re-renders, so their
 * `onAttachedToWindow` cannot re-fire for them; each exposes a public
 * `applyXPref()` for exactly this reason and re-reads the store itself, so
 * there is no value to pass and nothing here to keep in step with the store.
 *
 * It exists because [Fragment.rerenderPage] was never what applied these. The
 * Modes page asked the shell for a MODE change on every toggle, that ends in
 * `Activity.recreate()`, and it was the NEW Activity inflating a fresh
 * backdrop/strip/island that made the switch appear to work — see #349.
 *
 * ponytail: ShellActivity.applyLauncherSettings() is now a second copy of
 * these three pushes (it also writes system brightness, which this page
 * applies itself). It should delegate here. Not done in this commit:
 * ShellActivity.kt is #340 work in progress and outside this change.
 */
internal fun applyShellLiveToggles(activity: android.app.Activity?) {
    if (activity == null) return
    runCatching {
        (activity.findViewById<View>(R.id.galaxy_backdrop)
            as? com.diegonmarcos.superapp.ui.GalaxyBackdropView)?.applyStarsPref()
    }
    runCatching {
        (activity.findViewById<View>(R.id.launcher_status_strip)
            as? com.diegonmarcos.superapp.launcher.LauncherStatusStripView)?.applyPetsPref()
    }
    runCatching {
        (activity.findViewById<View>(R.id.dynamic_island_wave)
            as? com.diegonmarcos.superapp.ui.IslandWaveView)?.applyIslandPref()
    }
}
