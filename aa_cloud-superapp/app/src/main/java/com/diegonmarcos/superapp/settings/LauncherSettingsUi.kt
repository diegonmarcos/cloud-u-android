package com.diegonmarcos.superapp.settings

import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.ui.Haptics

import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import com.diegonmarcos.superapp.uikit.LocalKitPalette

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

/** A vertical gap, in dp. */
@Composable
internal fun GapDp(h: Int) { Spacer(Modifier.height(h.dp)) }

/** Section title + caption block. */
@Composable
internal fun SectionHeaderUi(title: String, subtitle: String) {
    val p = LocalKitPalette.current
    Column(Modifier.fillMaxWidth()) {
        Text(title, color = p.textPrimary, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = 8.dp))
        Text(subtitle, color = p.textSecondary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 16.dp))
    }
}

/** Selectable card used by the Controls pickers — label up top, optional subtitle beneath, selected-state filled in the
 *  brand's selected surface, unselected on the plain surface. */
@Composable
internal fun GenericTileUi(label: String, subtitle: String, isSelected: Boolean, onClick: () -> Unit) {
    val p = LocalKitPalette.current
    val view = LocalView.current
    Column(Modifier.fillMaxWidth().background(if (isSelected) p.surfaceSelected else p.surface)
        .clickable { Haptics.tap(view); onClick() }.padding(14.dp).testTag("generic-tile:$label")) {
        Text((if (isSelected) "● " else "○ ") + label, color = p.textPrimary, style = MaterialTheme.typography.titleMedium)
        if (subtitle.isNotBlank()) Text(subtitle, color = p.textSecondary, style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(top = 4.dp))
    }
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
 * Those three are NOT recreated when a page re-renders. The galaxy and the island wave are Compose layers reading
 * [com.diegonmarcos.superapp.ui.ShellFxState], so a refresh is all they need; the status strip still re-reads the store itself.
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
    // Stars, island and pets are state the Compose layers read: one refresh reaches them all.
    com.diegonmarcos.superapp.ui.ShellFxState.refresh(activity)
}

// ── Icons per row: the View-side half of [GridColumns] ──────────────────────

/**
 * Call from a grid fragment's onCreate. While it is started it listens for
 * [kind]'s key and re-renders itself when the value it last drew with is no
 * longer the answer; a change that landed while it was stopped is caught on the
 * next start. The stepper therefore needs to know no grid by name.
 */
internal fun Fragment.redrawOnGridColumns(kind: GridColumns.Kind) {
    var drawn = GridColumns.get(requireContext(), kind)
    fun redrawIfChanged() {
        val now = GridColumns.get(requireContext(), kind)
        if (now == drawn) return
        drawn = now
        // Posted: the listener can fire inside a FragmentManager transaction.
        (view ?: return).post { if (isAdded) rerenderPage() }
    }
    val l = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == kind.key) redrawIfChanged()
    }
    lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
        override fun onStart(owner: androidx.lifecycle.LifecycleOwner) {
            redrawIfChanged()
            GridColumns.sp(requireContext()).registerOnSharedPreferenceChangeListener(l)
        }
        override fun onStop(owner: androidx.lifecycle.LifecycleOwner) {
            GridColumns.sp(requireContext()).unregisterOnSharedPreferenceChangeListener(l)
        }
    })
}
