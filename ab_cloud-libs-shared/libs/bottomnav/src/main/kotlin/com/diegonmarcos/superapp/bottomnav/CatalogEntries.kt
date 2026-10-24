package com.diegonmarcos.superapp.bottomnav

import androidx.compose.runtime.Composable
import com.diegonmarcos.superapp.uiicons.UiIcons

/**
 * #1160 the ONLY way an app turns a declared icon name into a nav item: the glyph is libs:ui-icons' catalog entry, tinted by the
 * bar unless the entry is a brand icon (the assistant's gradient). An app's nav declaration never builds a [BottomNavEntry]
 * from a material icon, a drawable or a painter of its own; the nav-icons guard (cloud-android-nav-icons-guard.py) fails the
 * build when one does.
 */
@Composable
public fun navEntry(id: String, label: String, icon: String): BottomNavEntry =
    BottomNavEntry(id, label, UiIcons.painter(icon), tinted = UiIcons.isTinted(icon))

/** The island's items for a Compose host, every glyph from the catalog (see [navEntry]); [label] lets an app localise one. */
@Composable
public fun NavDecl.catalogEntries(label: (NavSection) -> String = { it.label }): List<BottomNavEntry> =
    bottomSections().map { navEntry(it.id, label(it), it.icon) }
