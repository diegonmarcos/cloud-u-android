package com.diegonmarcos.superapp.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.fragment.app.Fragment
import com.diegonmarcos.superapp.ShellActivity

/**
 * Configs → Launcher → Presets (#574; the tab was Profiles).
 *
 * Things you PICK, in one tap, in the groups `build.json::ui.launcher_presets`
 * declares — this class names none of them:
 *
 *   profile → Sandboxes  (ui.launcher_profiles: Work · Personal · Guest)
 *   theme   → Themes     (ui.launcher_themes:   Cloud Purple · Cloud Minimalistic)
 *   mode    → Modes      (ui.launcher_modes:    Power · Focus · Saving Energy)
 *
 * The theme picker is the one that used to open the old "Modes" tab, moved
 * here whole: it calls [LauncherThemes.apply], not a copy of it, and keeps the
 * one behaviour that is easy to lose in a move — a theme legitimately recreates
 * the Activity (Android resolves a Material3 style once, at inflate time), so it
 * tells the shell; a sandbox or a mode names no style and does not.
 *
 * Every group is a box refilled from the store when a pick lands, so a tap
 * repaints its own tiles and the scroll position stays put (#349).
 *
 * Nothing persisted moved: LauncherProfilePrefs / LauncherThemePrefs /
 * LauncherModePrefs key off fixed store names, never off the page id.
 */
class LauncherPresetsFragment : Fragment() {

    /** Every group's refill, so one pick can repaint the ones it affects. */
    private val repaints = mutableListOf<() -> Unit>()

    override fun onCreateView(inf: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        val ctx = requireContext()
        repaints.clear()

        val scroll = ScrollView(ctx).apply {
            val pad = dp(ctx, 16); setPadding(pad, pad, pad, pad)
        }
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(root)

        val groups = LauncherPresets.groups()
        groups.forEachIndexed { i, group ->
            if (i > 0) root.addView(spacer(ctx, dp(ctx, 24)))
            root.addView(sectionHeader(ctx, group.label, group.subtitle))
            val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            root.addView(box)
            val fill: (() -> Unit)? = when (group.kind) {
                LauncherPresets.KIND_PROFILE -> ({ fillSandboxes(box) })
                LauncherPresets.KIND_THEME   -> ({ fillThemes(box) })
                LauncherPresets.KIND_MODE    -> ({ fillModes(box) })
                else -> null
            }
            if (fill != null) { fill(); repaints += fill }
        }
        return scroll
    }

    private fun repaintAll() = repaints.forEach { it() }

    private fun fillSandboxes(box: LinearLayout) {
        val ctx = box.context
        box.removeAllViews()
        val current = LauncherProfilePrefs(ctx).profile
        for (row in LauncherProfiles.loadFromBuildConfig()) {
            box.addView(genericTile(ctx, row.label, row.subtitle, row.id == current.id) {
                LauncherProfilePrefs(ctx).profile = LauncherProfile.fromId(row.id)
                repaintAll()
            })
            box.addView(spacer(ctx, dp(ctx, 8)))
        }
    }

    private fun fillThemes(box: LinearLayout) {
        val ctx = box.context
        box.removeAllViews()
        val current = LauncherThemePrefs(ctx).theme
        // Re-read, never captured: "· modified" is derived from the switches, so
        // a hand-flip on the Controls tab has to show here on the next fill.
        val modified = LauncherThemes.isModified(ctx, current)
        for (row in LauncherThemes.loadFromBuildConfig()) {
            val isCurrent = row.id == current.id
            box.addView(genericTile(
                ctx,
                label = row.label + if (isCurrent && modified) "  ·  modified" else "",
                subtitle = if (isCurrent && modified)
                    "You changed a switch by hand, so this is no longer exactly " +
                        "${row.label}. Tap to re-apply it."
                else row.subtitle,
                isSelected = isCurrent,
            ) {
                // ONE action, both effects: chrome + every toggle the theme declares.
                LauncherThemes.apply(ctx, LauncherTheme.fromId(row.id))
                // The one pick here that legitimately recreates the Activity.
                (activity as? ShellActivity)?.notifyLauncherThemeChanged()
                com.diegonmarcos.superapp.appstore.ConstellationWorker.start(ctx)
            })
            box.addView(spacer(ctx, dp(ctx, 8)))
        }
    }

    private fun fillModes(box: LinearLayout) {
        val ctx = box.context
        box.removeAllViews()
        val selected = LauncherModePrefs(ctx).selected
        for (mode in LauncherModes.load()) {
            val isCurrent = mode.id == selected
            val modified = isCurrent && LauncherModes.isModified(ctx, mode)
            box.addView(genericTile(
                ctx,
                label = mode.label + if (modified) "  ·  modified" else "",
                subtitle = if (modified)
                    "You changed a switch by hand, so this is no longer exactly " +
                        "${mode.label}. Tap to re-apply it."
                else mode.subtitle,
                isSelected = isCurrent,
            ) {
                LauncherModes.apply(ctx, mode)
                // The switches moved under the shell's chrome — same follow-up a
                // flip on the Controls tab does, minus the Activity recreate.
                (activity as? ShellActivity)?.applyLauncherChrome()
                applyShellLiveToggles(activity)
                com.diegonmarcos.superapp.appstore.ConstellationWorker.start(ctx)
                repaintAll()
            })
            box.addView(spacer(ctx, dp(ctx, 8)))
        }
    }

    companion object { fun newInstance() = LauncherPresetsFragment() }
}
