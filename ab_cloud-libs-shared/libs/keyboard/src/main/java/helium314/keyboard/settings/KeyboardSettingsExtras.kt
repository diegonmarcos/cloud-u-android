package helium314.keyboard.settings

import android.content.Context

/**
 * #1352 entries the hosting app puts at the top of the keyboard's Configs, under the fleet's own additions. The standalone
 * Cloud Keyboard registers Configs ▸ General and Configs ▸ About (libs:ui-nav-bars) from its Application; a host that
 * registers nothing (the keyboard engines APK) shows nothing, and this lib does not depend on the pages.
 */
object KeyboardSettingsExtras {
    class Entry(val label: String, val description: String, val open: (Context) -> Unit)

    @Volatile var entries: List<Entry> = emptyList()
}
