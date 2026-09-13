package com.diegonmarcos.superapp.ui

import com.diegonmarcos.superapp.BuildConfig
import com.diegonmarcos.superapp.settings.LauncherTheme
import com.diegonmarcos.superapp.settings.LauncherThemePrefs

import android.app.Activity
import android.content.Context
import android.util.Base64
import org.json.JSONArray

/**
 * The ONE place a launcher mode becomes a Material3 style.
 *
 * ── Why this file exists ─────────────────────────────────────────────────
 * [LauncherPalette] is its sibling and covers only half the app. A palette
 * reaches the views this app DRAWS in Kotlin; it cannot reach an XML layout,
 * because a layout resolves its colours through `?attr/` against whatever
 * theme the Activity was created with. There was exactly one such theme —
 * `Theme.Superapp`, with the purple gradient hard-wired as windowBackground —
 * so 24 layouts and 82 attribute references were nailed to the default mode
 * no matter which mode the user picked.
 *
 * The owner reported both symptoms of that in one sentence: "it does draw the
 * black screen but is not full screen; and submenus still same style as
 * default". The first half is the WINDOW still being the gradient underneath
 * a black fragment — visible at every inset and every over-scroll. The second
 * half is those 82 attribute references. One cause, two symptoms, and neither
 * of them fixable by adding more colours to the palette.
 *
 * ── The contract ─────────────────────────────────────────────────────────
 * A mode names its style in build.json (`ui.launcher_themes[*].style`), the
 * same way it already names its palette roles. [apply] resolves that name to
 * a style resource and installs it on the Activity BEFORE its content is
 * inflated, which is the only moment Android lets a theme be chosen. Changing
 * mode therefore has to recreate the Activity — see [restartForModeChange].
 *
 * Adding a mode stays what it was: a build.json entry, a colour block, a
 * style block, a [LauncherTheme] enum case. Still no `when (theme)` over
 * appearance anywhere.
 */
object LauncherStyle {

    /** The style resource for whichever mode is selected right now. */
    fun of(ctx: Context): Int = forTheme(ctx, LauncherThemePrefs(ctx).theme)

    fun forTheme(ctx: Context, theme: LauncherTheme): Int {
        val name = styleById[theme.id]
            // A mode that declares no style falls back to the DEFAULT mode's,
            // never to a bare Material3 parent: a half-declared mode should
            // look like Cloud and be caught by the tester, not render as an
            // unstyled grey Android dialog that looks like a crash.
            ?: styleById[LauncherTheme.Cloud.id]
            ?: return 0
        val pkg = ctx.packageName
        return ctx.resources.getIdentifier(name, "style", pkg)
    }

    /**
     * Install the active mode's style on [activity].
     *
     * MUST be called from onCreate before super.onCreate/setContentView —
     * once a view is inflated its attributes are already resolved, and a
     * later setTheme silently affects nothing that is already on screen.
     * That "silently affects nothing" is exactly how a mode ends up looking
     * applied in code and unapplied on the phone.
     */
    fun apply(activity: Activity) {
        val style = of(activity)
        if (style != 0) activity.setTheme(style)
    }

    /**
     * Re-enter the app under a newly-chosen mode.
     *
     * Android resolves a theme once per Activity, at inflate time, so there
     * is no way to restyle a live Activity's XML surfaces in place. Recreate
     * is not a workaround for that — it is the mechanism. Without it the new
     * mode would apply to code-drawn views immediately and to every XML
     * surface only after the next cold start, which is the same "it does
     * nothing until you kill the app" class of bug the palette's
     * [LauncherPalette.invalidate] exists to prevent.
     */
    fun restartForModeChange(activity: Activity) {
        LauncherPalette.invalidate()
        activity.recreate()
    }

    /** themeId → style name, straight from build.json. */
    private val styleById: Map<String, String> by lazy { parse() }

    private fun parse(): Map<String, String> = runCatching {
        val arr = JSONArray(String(Base64.decode(BuildConfig.UI_LAUNCHER_THEMES_B64, Base64.NO_WRAP)))
        val out = mutableMapOf<String, String>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            val style = o.optString("style")
            if (id.isNotBlank() && style.isNotBlank()) out[id] = style
        }
        out
    }.getOrDefault(emptyMap())
}
