package com.diegonmarcos.superapp.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.fragment.app.Fragment

/**
 * Configs → Launcher → Profiles.
 *
 * Split out of LauncherConfigFragment, which had become one endless scroll
 * holding three unrelated decisions: which profile you are in, which mode paints
 * the launcher, and how you reach it one-handed. Profiles came out first because
 * it is the only one of the three that changes what the other two MEAN — the
 * picked profile is the foundation for which apps and folders the Phone tab
 * surfaces, so it is a question asked BEFORE the mode, not a header halfway down
 * the mode's page.
 *
 * Nothing persisted moved with the split: LauncherProfilePrefs keys off the
 * fixed store name "launcher_profile", never off the page id, so this tab opens
 * on whatever profile was picked before it existed.
 */
class LauncherProfilesFragment : Fragment() {

    override fun onCreateView(inf: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        val ctx = requireContext()
        val prefs = LauncherProfilePrefs(ctx)

        val scroll = ScrollView(ctx).apply {
            val pad = dp(ctx, 16); setPadding(pad, pad, pad, pad)
        }
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(root)

        root.addView(sectionHeader(ctx, "Profile",
            "Personal / Work / Guest. The picked profile is the foundation for " +
                "filtering which apps + folders the Phone tab will surface " +
                "(wired in a follow-up patch)."))

        val current = prefs.profile
        for (row in LauncherProfiles.loadFromBuildConfig()) {
            root.addView(genericTile(
                ctx,
                label      = row.label,
                subtitle   = row.subtitle,
                isSelected = row.id == current.id,
            ) {
                prefs.profile = LauncherProfile.fromId(row.id)
                rerenderPage()
            })
            root.addView(spacer(ctx, dp(ctx, 8)))
        }

        return scroll
    }

    companion object { fun newInstance() = LauncherProfilesFragment() }
}
