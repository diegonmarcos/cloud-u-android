package com.diegonmarcos.superapp.launcher

import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.apps.PhoneAppsFragment
import com.diegonmarcos.superapp.ui.Haptics
import com.diegonmarcos.superapp.ui.LauncherPalette

import android.content.Context
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * One way to draw an app as a tile, shared by the Power Saving home pane and by
 * the twelve-slot editor in Configs ▸ Launcher ▸ Theme.
 *
 * WHY IT IS SHARED. The editor used to be twelve stacked [android.widget.Spinner]s
 * of app NAMES while the home pane those spinners configure drew labels in a
 * grid — two renderings of the same twelve things, neither of them showing the
 * icon the user actually recognises an app by. A third hand-rolled tile would
 * have been the third. The icons and labels come from
 * [PhoneAppsFragment.snapshot], which is the same classification the Phone tab
 * and the search index already read, so an app is the same app everywhere.
 *
 * ── Off the main thread, always ──────────────────────────────────────────
 * [PhoneAppsFragment.snapshot] is usually a warm cache and free, but on a cold
 * process it enumerates every launchable activity and loads a badged icon for
 * each — the ~600ms its own comment documents. Twelve of those on the main
 * thread is a visibly frozen screen, so the tiles are built and attached with
 * their placeholder immediately and the icons arrive afterwards. A plain
 * Thread rather than a coroutine, matching [PhoneAppsFragment.warmUp]: this
 * module does not otherwise depend on kotlinx-coroutines and one
 * fire-and-forget load is not a reason to.
 *
 * ── An app that is not there ─────────────────────────────────────────────
 * Slots are declared in build.json and edited by the user, so a slot can
 * outlive the app it points at. That tile draws a placeholder glyph AND says
 * the word — never a blank hole, which reads as a rendering bug, and never a
 * crash. It stays tappable because the tile grammar's `extapp:` targets route
 * to install-if-missing; a dead-looking tile the user never taps is how that
 * fallback goes unused.
 */
object AppIconTile {

    /** One tile's worth of input. [selected] drives the selected-state chrome;
     *  pass false where a grid is a display rather than a picker. */
    data class Slot(
        val id: String,
        val label: String,
        val target: String,
        val selected: Boolean = false,
    )

    /**
     * A vertical stack of rows, [columns] tiles per row, real icons filled in
     * once they load.
     *
     * @param showSelection false ⇒ no tile draws selected chrome, whatever
     *   [Slot.selected] says. The home pane displays; the editor picks.
     */
    fun grid(
        ctx: Context,
        slots: List<Slot>,
        columns: Int,
        showSelection: Boolean,
        onClick: (Slot) -> Unit,
    ): LinearLayout {
        val palette = LauncherPalette.of(ctx)
        val pending = mutableListOf<Pair<Slot, ImageView>>()

        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        for (row in slots.chunked(columns)) {
            column.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                for (slot in row) {
                    val icon = ImageView(ctx).apply {
                        setImageResource(PLACEHOLDER)
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        val sz = dp(ctx, 40)
                        layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                            gravity = Gravity.CENTER_HORIZONTAL
                        }
                    }
                    pending += slot to icon
                    addView(tile(ctx, slot, icon, palette, showSelection, onClick))
                }
                // A short last row keeps its columns under the full row above
                // instead of stretching to fill: a half-empty row that spreads
                // out reads as a different grid, not as the same one ending.
                repeat(columns - row.size) {
                    addView(View(ctx).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f,
                        )
                    })
                }
            })
        }
        fillIcons(ctx, pending)
        return column
    }

    private fun tile(
        ctx: Context,
        slot: Slot,
        icon: ImageView,
        palette: LauncherPalette.Palette,
        showSelection: Boolean,
        onClick: (Slot) -> Unit,
    ): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        val pad = dp(ctx, 6)
        setPadding(pad, dp(ctx, 10), pad, dp(ctx, 10))
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            .apply { setMargins(dp(ctx, 2), dp(ctx, 2), dp(ctx, 2), dp(ctx, 2)) }
        val picked = showSelection && slot.selected
        setBackgroundColor(if (picked) palette.surfaceSelected else palette.surface)
        isClickable = true
        isFocusable = true
        setOnClickListener { Haptics.tap(it); onClick(slot) }

        addView(icon)
        addView(TextView(ctx).apply {
            text = slot.label
            // Marking selection with the background alone would be the one
            // channel a colour-blind user and a greyscale screenshot both lose,
            // and this grid is a picker — which one is chosen has to survive
            // that. Same ● / ○ vocabulary the theme tiles above it already use.
            if (showSelection) {
                text = ctx.getString(
                    if (picked) R.string.app_tile_selected else R.string.app_tile_unselected,
                    slot.label,
                )
            }
            setTextColor(if (picked) palette.textPrimary else palette.textSecondary)
            textSize = 11f
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(ctx, 4), 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        })
    }

    /**
     * Resolve every tile's icon on a background thread and post the results
     * back. One pass over the app list for the whole grid, not one per tile.
     */
    private fun fillIcons(ctx: Context, pending: List<Pair<Slot, ImageView>>) {
        if (pending.isEmpty()) return
        val app = ctx.applicationContext
        val main = Handler(Looper.getMainLooper())
        Thread {
            val byPackage = runCatching {
                PhoneAppsFragment.snapshot(app).associateBy { it.packageName }
            }.getOrDefault(emptyMap())
            for ((slot, view) in pending) {
                val icon = packagesFor(slot.target).firstNotNullOfOrNull { pkg ->
                    // The snapshot is profile-filtered (Guest mode whitelists),
                    // so a miss there does NOT mean the app is gone. Ask the
                    // package manager before saying so — labelling an installed
                    // app "not installed" is a worse lie than a missing icon.
                    byPackage[pkg]?.icon ?: directIcon(app, pkg)
                }
                main.post {
                    if (icon != null) {
                        view.setImageDrawable(icon)
                        view.contentDescription = slot.label
                    } else {
                        view.contentDescription =
                            app.getString(R.string.app_tile_not_installed, slot.label)
                    }
                }
            }
        }.apply {
            name = "AppIconTile.fillIcons"
            isDaemon = true
            priority = Thread.MIN_PRIORITY
            start()
        }
    }

    /** Its real icon, or null when nothing on this device can launch [pkg]. */
    private fun directIcon(ctx: Context, pkg: String): Drawable? = runCatching {
        val pm = ctx.packageManager
        if (pm.getLaunchIntentForPackage(pkg) == null) null else pm.getApplicationIcon(pkg)
    }.getOrNull()

    /**
     * The packages a tile target could be satisfied by, best first.
     *
     * `extapp:<id>` is one of ours and may be installed as the hub, as the
     * resigned-stock alternative, or not at all — all three are declared in
     * build.json::ui.external_apps, so they are read from there rather than
     * guessed from the id.
     */
    fun packagesFor(target: String): List<String> = when {
        target.startsWith("app:") -> listOf(target.removePrefix("app:"))
        target.startsWith("extapp:") -> {
            val id = target.removePrefix("extapp:").substringBefore('#').substringBefore('/')
            Sections.externalApp(id)
                ?.let { listOf(it.hubPackage, it.altPackage, it.installPackage) }
                .orEmpty()
                .filter { it.isNotBlank() }
                .distinct()
        }
        else -> emptyList()
    }

    /** Android's own "some app" glyph. A framework drawable rather than one of
     *  ours on purpose: the placeholder should read as the system saying it has
     *  nothing, not as a piece of this app's art. */
    private const val PLACEHOLDER = android.R.drawable.sym_def_app_icon

    private fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()
}
