package com.diegonmarcos.superapp.ui

import com.diegonmarcos.superapp.BuildConfig
import com.diegonmarcos.superapp.settings.LauncherTheme
import com.diegonmarcos.superapp.settings.LauncherThemePrefs

import android.content.Context
import android.util.Base64
import androidx.core.content.ContextCompat
import org.json.JSONArray

/**
 * The ONE place a launcher theme becomes a colour.
 *
 * ── Why this file exists ─────────────────────────────────────────────────
 * It did not, and that is the whole of the regression the owner reported:
 * "the two other themes only break the bottom menu nav and the top status and
 * the edge menu, they don't apply nothing of its new full colour design".
 *
 * That sentence is a fingerprint, not a vague complaint. The three surfaces
 * that DID change are precisely the three that read no colour from the app:
 * the system bars (a WindowInsetsController appearance flag), the chrome
 * islands (a visibility flip in ShellActivity.applyLauncherChrome) and the
 * floating edge menu (its own window, drawn by a service). Everything else —
 * every fragment, card, row, grid and background — painted 0xFF-literals
 * chosen by hand for the default black-to-purple gradient. A literal cannot
 * follow a theme, so it was structurally impossible for ANY theme to recolour
 * the content, no matter how many themes were added.
 *
 * The theme record itself was the deeper half of it: build.json declared
 * `features` and `toggles` for each theme and no colours at all, so a theme
 * had nowhere to say what it looked like even if a consumer had asked.
 *
 * ── The contract ─────────────────────────────────────────────────────────
 * A theme declares a `palette` of ROLES; every role names a resource in
 * res/values/colors.xml (or, for [window], a res/drawable). Nothing about a
 * theme is written in Kotlin: adding one is a build.json entry, a colour block
 * and a [LauncherTheme] enum case. There is deliberately no `when (theme)`
 * over colours anywhere in this app, because a `when` is a second place to
 * edit and the one that gets forgotten — which is exactly how two themes
 * shipped reaching the chrome and nothing else.
 *
 * test-launcher-theme-palette.sh fails the build when a theme omits a role,
 * when a role names a token that does not exist, or when a themed surface
 * paints a literal again. That tester is the part that keeps this true after
 * the next theme.
 */
object LauncherPalette {

    /**
     * The colours one theme paints with, already resolved to ARGB.
     *
     * [windowRes] stays a RESOURCE ID rather than an int because the default
     * theme's backdrop is a two-stop gradient DRAWABLE that no single colour
     * can stand in for. A theme is free to name a plain colour instead —
     * Window.setBackgroundDrawableResource accepts either, so the caller never
     * has to know which kind the theme picked.
     */
    data class Palette(
        val themeId: String,
        val windowRes: Int,
        val surface: Int,
        val surfaceSelected: Int,
        val textPrimary: Int,
        val textSecondary: Int,
        val accent: Int,
        val hairline: Int,
        /**
         * The opaque colour that sits ON a lit tile.
         *
         * Configs ▸ Panel ▸ Control inverts a quick-settings tile with its
         * state: OFF fills with [surface] and draws [textPrimary] on it, ON
         * fills with [textPrimary] and draws THIS on it. Contrast is symmetric,
         * so a theme whose OFF tile is legible cannot have an illegible ON one.
         *
         * It is its own role because no existing one can stand in for it. The
         * default theme's [surface] is 13%-alpha white, which on a white fill
         * is a near-invisible icon, and its [windowRes] is a gradient DRAWABLE
         * with no single colour to read.
         */
        val tileInk: Int,
    )

    /** The palette of whatever theme is selected right now. */
    fun of(ctx: Context): Palette = forTheme(ctx, LauncherThemePrefs(ctx).theme)

    fun forTheme(ctx: Context, theme: LauncherTheme): Palette {
        cached?.let { if (it.themeId == theme.id) return it }
        return build(ctx.applicationContext, theme.id).also { cached = it }
    }

    /**
     * Drop the memoised palette.
     *
     * Called when the theme changes. Without it the first palette read after a
     * switch would answer with the OLD theme's colours — the "stored but only
     * read at process start" failure, where a theme appears to do nothing
     * until the app is killed.
     */
    fun invalidate() { cached = null }

    @Volatile private var cached: Palette? = null

    /** themeId → (role → resource name), straight from build.json. */
    private val rolesById: Map<String, Map<String, String>> by lazy { parse() }

    private fun parse(): Map<String, Map<String, String>> = runCatching {
        val arr = JSONArray(String(Base64.decode(BuildConfig.UI_LAUNCHER_THEMES_B64, Base64.NO_WRAP)))
        val out = mutableMapOf<String, Map<String, String>>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (id.isBlank()) continue
            val p = o.optJSONObject("palette") ?: continue
            out[id] = p.keys().asSequence().associateWith { p.optString(it) }
        }
        out
    }.getOrDefault(emptyMap())

    private fun build(ctx: Context, themeId: String): Palette {
        // A role the theme omits falls back to the DEFAULT theme's, never to a
        // constant: a half-declared theme should look like the default one and
        // be caught by the tester, not paint an invented colour that looks
        // deliberate on screen.
        val roles = rolesById[themeId].orEmpty()
        val fallback = rolesById[LauncherTheme.Cloud.id].orEmpty()
        fun name(role: String): String = roles[role]?.takeIf { it.isNotBlank() }
            ?: fallback[role].orEmpty()

        // Drawable first, then colour: a gradient and a flat fill are both
        // legitimate answers for "what is behind everything", and which one a
        // theme chose is the theme's business, not the caller's.
        val windowName = name("window")
        val windowRes = ctx.resources.getIdentifier(windowName, "drawable", ctx.packageName)
            .takeIf { it != 0 }
            ?: ctx.resources.getIdentifier(windowName, "color", ctx.packageName)

        return Palette(
            themeId          = themeId,
            // android.R.color.black rather than 0xFF000000: the last resort is
            // still a resource, so this file holds no colour of its own either.
            windowRes        = if (windowRes != 0) windowRes else android.R.color.black,
            surface          = colour(ctx, name("surface"),          android.R.color.transparent),
            surfaceSelected  = colour(ctx, name("surface_selected"), android.R.color.transparent),
            textPrimary      = colour(ctx, name("text_primary"),     android.R.color.white),
            textSecondary    = colour(ctx, name("text_secondary"),   android.R.color.darker_gray),
            accent           = colour(ctx, name("accent"),           android.R.color.white),
            hairline         = colour(ctx, name("hairline"),         android.R.color.darker_gray),
            tileInk          = colour(ctx, name("tile_ink"),         android.R.color.black),
        )
    }

    private fun colour(ctx: Context, name: String, fallbackRes: Int): Int {
        val id = if (name.isBlank()) 0
                 else ctx.resources.getIdentifier(name, "color", ctx.packageName)
        return ContextCompat.getColor(ctx, if (id != 0) id else fallbackRes)
    }
}
