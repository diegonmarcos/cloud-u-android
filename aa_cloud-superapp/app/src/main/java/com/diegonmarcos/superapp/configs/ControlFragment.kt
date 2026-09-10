package com.diegonmarcos.superapp.configs

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.fragment.app.Fragment
import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.launcher.Sections
import com.diegonmarcos.superapp.ui.Haptics
import com.diegonmarcos.superapp.ui.LauncherPalette
import com.diegonmarcos.superapp.ui.StatusLight
import com.diegonmarcos.superapp.ui.snack
import java.util.concurrent.Executors

/**
 * Configs ▸ Panel ▸ Control — the switch board, as a Samsung-style grid of
 * quick-settings tiles.
 *
 * Every group and every tile is declared in `build.json::ui.control_panel` and
 * implemented in [DeviceControls]. This file draws them and nothing else: it
 * decides no capability, names no permission, knows no Settings action, and
 * knows nothing about which group a tile is in.
 *
 * ── The tile ──────────────────────────────────────────────────────────────
 * A filled round badge holding the icon, the label under it, the status light
 * under that. TAP switches; HOLD opens the settings screen behind it.
 *
 * BLACK WHEN OFF, WHITE WHEN ON — AS AN INVERSION, NOT AS TWO LITERALS. The
 * badge fill and the icon on it are the SAME TWO THEME COLOURS SWAPPED: off is
 * `surface` filled with `text_primary`, on is `text_primary` filled with
 * `tile_ink`. Two consequences, and both are the reason it is written this
 * way. Contrast is symmetric, so a theme whose off tile is legible cannot have
 * an illegible on tile — there is no pair of colours that can be got wrong on
 * one side only. And it survives a theme: under Cloud Power Saving,
 * `text_primary` IS white and `tile_ink` IS black, so the owner's words come
 * out exactly, while under Minimalistic Black the same rule reads as terminal
 * green on black inverting to black on green. Hard-coding #000 and #FFF would
 * have given the first theme and made the icon vanish on the others.
 *
 * THE LIGHT SITS OUTSIDE THE FILL, deliberately. [StatusLight]'s colours are
 * the ones that clear WCAG AA against this app's WINDOW background — its own
 * tester recomputes those ratios. Drawing a green dot on a white badge would
 * spend that guarantee: 4.5:1 on black is about 1.8:1 on white. So the badge
 * inverts and the light stays on the page behind it, where the arithmetic that
 * chose its colours still applies.
 *
 * ── Tap and hold do not fight ─────────────────────────────────────────────
 * The long-press listener ALWAYS returns true. That is the whole separation
 * and it is a platform contract, not a heuristic: `View.onTouchEvent` runs
 * `performClick()` on ACTION_UP only when `mHasPerformedLongPress` is false,
 * and that flag is set only by a `performLongClick()` that RETURNED TRUE.
 * Returning false — or leaving the tile without a long-press listener — is
 * what makes a hold toggle the control on the way to opening its menu.
 *
 * There is exactly ONE path from a gesture to a write, the tile's own click
 * listener, and nothing calls it back. This app has already shipped a tile
 * that fired twice because one tap took two routes into the same handler
 * (149 duplicates in one trace), and that was fixed by removing the second
 * route rather than by debouncing the first. No second route is introduced
 * here and no debounce is needed to make up for one.
 *
 * ── A hold on a tile with nothing behind it ───────────────────────────────
 * Six of the seventeen controls have no settings screen — there is nowhere for
 * a hold to go. They still ANSWER: the hold says, in the string table and in
 * both locales, that this one has no settings screen and that a tap switches
 * it. A long-press that silently did nothing is indistinguishable from a
 * broken tile, which is the failure shape this repository carries a CI guard
 * against elsewhere.
 *
 * ── One control, two tiles, one piece of state ────────────────────────────
 * A control flagged `battery_hungry` is drawn twice — once in its functional
 * group, once under Battery Hungers — and the two tiles are two views of ONE
 * thing. There is no per-tile copy of the state to drift: the only state a
 * tile holds is the id it was declared with, [DeviceControls.byId] maps that
 * id to exactly one [DeviceControls.Control], and both [refresh] and [write]
 * fan a landed reading out to EVERY tile bearing the id. That is why they
 * address rows by `rows.filter { it.id == ... }` and never by `firstOrNull` —
 * a `firstOrNull` here is precisely the bug where a tile reads on in one group
 * and off in another.
 *
 * [refresh] also reads each id ONCE rather than once per tile. Seventeen
 * controls drawn as twenty-six tiles would otherwise be twenty-six binder
 * calls and shell probes per tick for seventeen answers.
 *
 * ── What it will not draw ─────────────────────────────────────────────────
 * A [DeviceControls.Control] with no `set` does not switch on a tap. Its tile
 * opens the exact system screen for it and shows the live state read-only.
 * That is the whole difference between this page and the version of it that
 * would have been quicker to write, where Wi-Fi and airplane mode are tiles
 * that light up under the thumb and change nothing on the device.
 *
 * A tile that IS switchable never displays what the user asked for. After a
 * write it re-reads the device and shows THAT, so a refused write is visible
 * as the tile going back — with the reason in a snackbar — rather than as a
 * control that looks set and is not. Nothing here is a [android.widget.Switch]
 * any more, and that removes a whole class of bug with it: a repaint assigns a
 * plain field, so it cannot re-fire a listener and re-issue the last write.
 *
 * ── The lights ────────────────────────────────────────────────────────────
 * Every tile carries a [StatusLight]. It is painted from ONE source and only
 * that source: the [DeviceControls.Control.read] for that id. There is no
 * second state path, no cached colour, no per-tile special case. A control
 * whose read declines to answer is drawn UNKNOWN, never green and never red.
 *
 * THE LIGHT IS NOT THE TILE. The tile shows the SETTING, which is what was
 * asked for; the light shows the STATE, which is what is true. They agree most
 * of the time and the whole page exists for the times they do not — a mesh
 * tunnel the engine dropped, an accept loop that died, an overlay the system
 * killed. A light that were merely the tile's fill recoloured would be green
 * in exactly the situation it is here to catch.
 *
 * AND WHERE THERE IS NO STATE TO SHOW, IT SAYS SO. Some controls have nothing
 * readable but a preference this app stored — see
 * [DeviceControls.Control.observed] for which, and why each one. Those tiles
 * draw "? Not verifiable" permanently, which is the honest answer and the one
 * the owner can act on: it says go and look.
 *
 * STALE IS UNKNOWN. A reading is kept for [STALE_MS] and then stops counting
 * as an answer. The page stops reading the moment it is not visible, so by the
 * time the owner comes back every reading has aged out and the lights say
 * Unknown until fresh reads land. A green dot recalling an hour-old truth is
 * the same lie as a green dot that was never driven by anything.
 *
 * The tile's own fill deliberately does not age out. Un-filling a tile because
 * a read grew old would look like the device turning off; the fill keeps the
 * last real reading and the light carries the honesty about how old it is.
 *
 * ── Accessibility ─────────────────────────────────────────────────────────
 * Replacing labelled rows with icons took away the text a screen reader was
 * reading, so each tile carries the whole fact as its own
 * `contentDescription`: its name and the state, from [StatusLight.description] —
 * the same shared sentence the list rows used, not a second phrasing of it.
 * The icon, label and light inside are marked not-important so TalkBack
 * announces one node and not four fragments of one.
 *
 * A hold-to-open affordance is invisible to somebody who cannot see the grid,
 * so the long-press action is RENAMED through [ViewCompat] — TalkBack then
 * offers "Open settings" by name instead of a generic long press nobody knows
 * is there.
 *
 * ── Battery ───────────────────────────────────────────────────────────────
 * This is a phone. The ticker runs on [onResume] and is cancelled on
 * [onPause], so a page nobody is looking at costs nothing: no wakeups, no
 * binder calls into the WireGuard engine, no shell channel probes. Same shape,
 * and the same [REFRESH_MS] cadence, as the launcher status strip.
 *
 * ── Threading ─────────────────────────────────────────────────────────────
 * Reads and writes both block: shell channels, binder calls into the WireGuard
 * engine, a torch callback's first delivery. All of it runs on one background
 * thread and lands back on the main looper; [DeviceControls] never touches the
 * UI itself. One thread rather than a pool on purpose — these are a dozen
 * short reads, and a pool of them all binding services at once is how a
 * settings page becomes the thing that janks.
 */
class ControlFragment : Fragment() {

    /** Everything one TILE needs to refresh itself in place. Two tiles of the
     *  same control hold two of these and the SAME [id] — which is all either
     *  of them holds about the state, the rest being read live. */
    private class Bound(
        val id: String,
        /** The declared label, repeated into the spoken description: a tile
         *  announced on its own is a bare "On" belonging to nothing. */
        val label: String,
        val control: DeviceControls.Control,
        val tile: View,
        val badge: View,
        val icon: ImageView,
        val labelView: TextView,
        val light: TextView,
        val note: TextView,
    ) {
        /** The last reading the device gave, and WHEN — both written only by
         *  a read that landed. [readAt] 0 means this tile has never been read,
         *  which is an unknown like any other. */
        var reading: Boolean? = null
        var readAt: Long = 0L
    }

    private val rows = mutableListOf<Bound>()
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "control-panel") }
    private val main = Handler(Looper.getMainLooper())

    /**
     * Age the lights, then read the device again — in that order, so a read
     * that hangs or never comes back shows as Unknown rather than as the
     * previous answer held indefinitely.
     */
    private val ticker = object : Runnable {
        override fun run() {
            for (row in rows) paintLight(row)
            refresh()
            main.postDelayed(this, REFRESH_MS)
        }
    }

    override fun onCreateView(inf: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        val ctx = requireContext()
        val pad = dp(16)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        root.addView(title(ctx, getString(R.string.control_title)))
        // The paragraph is a resource, but the four words it quotes are asked
        // of StatusLight at render time — so a page that stopped drawing one
        // of them could not go on describing it.
        root.addView(caption(ctx, getString(R.string.control_caption,
            StatusLight.text(ctx, StatusLight.State.ON),
            StatusLight.text(ctx, StatusLight.State.OFF),
            StatusLight.text(ctx, StatusLight.State.UNKNOWN),
            StatusLight.text(ctx, StatusLight.State.UNVERIFIABLE),
            REFRESH_MS / 1000)))

        rows.clear()
        val columns = DeviceControls.columns
        for (group in DeviceControls.groups) {
            root.addView(section(ctx, groupText(ctx, group.labelRes, group.label),
                groupText(ctx, group.subtitleRes, group.subtitle)))
            // Chunked into rows of `columns`; the last row is padded with
            // weightless spacers so four tiles and two tiles start at the same
            // left edge instead of the short row spreading itself out.
            for (line in group.rows.chunked(columns)) {
                val strip = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
                for (decl in line) addTile(strip, ctx, decl)
                repeat(columns - line.size) { strip.addView(spacer(ctx)) }
                root.addView(strip)
            }
        }
        // A build whose declaration failed to parse must say so rather than
        // render an empty page that reads as "nothing is controllable here".
        if (rows.isEmpty()) root.addView(caption(ctx, getString(R.string.control_none_declared)))

        return ScrollView(ctx).apply { addView(root) }
    }

    override fun onResume() {
        super.onResume()
        // Every one of these can be changed from outside this page — the
        // notification shade, a Settings screen we just sent the user to, the
        // fleet's own workers. Coming back is exactly when the displayed state
        // is most likely to be stale, so the ticker runs immediately rather
        // than after its first interval.
        main.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        // Nothing is looking at these lights, so nothing polls for them. This
        // is the whole battery story of the page.
        main.removeCallbacks(ticker)
    }

    override fun onDestroy() {
        super.onDestroy()
        main.removeCallbacks(ticker)
        io.shutdownNow()
    }

    // ─────────────────────────────── Tiles ──────────────────────────────

    private fun addTile(strip: LinearLayout, ctx: Context, decl: DeviceControls.Row) {
        val control = DeviceControls.byId[decl.id] ?: run {
            // Dropped, never drawn — but the column still has to be held open
            // or the tiles after it slide left out of their grid positions.
            strip.addView(spacer(ctx)); return
        }
        val palette = LauncherPalette.of(ctx)

        // The declared icon, resolved through the same name→drawable lookup
        // every other declared surface in this app uses. Its tint is set by
        // [paintTile], because the tint IS the on/off state.
        val icon = ImageView(ctx).apply {
            setImageResource(Sections.iconResFor(ctx, decl.icon))
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(dp(ICON_DP), dp(ICON_DP))
        }

        // The filled round badge. A GradientDrawable rather than a themed XML
        // drawable because its fill has to be recoloured on every state
        // change, and setColor on a live drawable is the cheap way to do that
        // — no reinflation, no second drawable for the on state.
        val badge = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL }
            layoutParams = LinearLayout.LayoutParams(dp(BADGE_DP), dp(BADGE_DP))
            addView(icon)
        }

        val labelView = TextView(ctx).apply {
            text = decl.label
            textSize = 11f
            gravity = Gravity.CENTER
            maxLines = 2
            setTextColor(palette.textPrimary)
        }

        val light = TextView(ctx).apply {
            textSize = 10f
            gravity = Gravity.CENTER
        }

        // Only ever visible when the control names a reason it cannot move.
        // A tile that just looked inert would say nothing about the grant it
        // is waiting for.
        val note = TextView(ctx).apply {
            textSize = 9f
            gravity = Gravity.CENTER
            visibility = View.GONE
            setTextColor(ContextCompat.getColor(ctx, R.color.control_blocked))
        }

        val tile = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            // Weight 1 across the strip, so the grid divides the width evenly
            // whatever the column count is. minimumHeight keeps the target
            // real on a short tile: a dense grid nobody can hit is the next
            // complaint after a grid that is too sparse.
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            minimumHeight = dp(TOUCH_TARGET_DP)
            minimumWidth = dp(TOUCH_TARGET_DP)
            val v = dp(8)
            setPadding(v, v, v, v)
            addView(badge)
            addView(labelView)
            addView(light)
            addView(note)
        }

        // ONE announcement per tile, not four. Set after the children exist so
        // there is no window where a child is separately focusable.
        for (child in listOf(badge, icon, labelView, light, note)) {
            child.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        tile.isFocusable = true

        // TAP. The only path from a gesture to a write in this file, and
        // nothing calls it back. A control that cannot be set does not pretend
        // to switch — its tap goes where the setting actually lives.
        tile.setOnClickListener { view ->
            Haptics.tap(view)
            if (control.set != null) write(decl, control, !(currentlyOn(decl.id)))
            else openOrSayThereIsNowhere(decl, control)
        }

        // HOLD. Returns true unconditionally — see the class header: that
        // return is what stops the tap from also firing on the way out of the
        // long press, and a `false` here is the whole defect.
        tile.setOnLongClickListener { view ->
            Haptics.tap(view)
            openOrSayThereIsNowhere(decl, control)
            true
        }

        // Name the long press for TalkBack. Without this it is an unnamed
        // gesture, which is the same as not existing for anyone who cannot see
        // that the grid has tiles worth holding.
        ViewCompat.replaceAccessibilityAction(
            tile,
            AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_LONG_CLICK,
            getString(R.string.control_tile_open_action),
            null,
        )

        rows += Bound(decl.id, decl.label, control, tile, badge, icon, labelView, light, note)
        paintTile(rows.last(), on = false)
        paintLight(rows.last())
        strip.addView(tile)
    }

    /**
     * The settings screen behind a control, or a sentence saying there is not
     * one.
     *
     * NEVER A SILENT RETURN. Six controls have no `open`, and a hold on one of
     * them that did nothing at all would be indistinguishable from a tile that
     * is broken, from a gesture that was not registered, and from a page that
     * has stopped responding.
     */
    private fun openOrSayThereIsNowhere(decl: DeviceControls.Row, control: DeviceControls.Control) {
        val open = control.open
        if (open != null) open(requireContext())
        else view?.snack(getString(R.string.control_no_menu, decl.label))
    }

    /** What this control is showing right now, from whichever tile of it was
     *  painted last — they are all painted together, so any of them answers. */
    private fun currentlyOn(id: String): Boolean =
        rows.firstOrNull { it.id == id }?.let { it.reading == true } ?: false

    /**
     * Re-read every control from the device and redraw every tile of it.
     *
     * ONE READ PER CONTROL, not one per tile. The ids are distinct-ed first,
     * because a control drawn in two groups is still one thing to ask about
     * and asking twice is two binder calls for one answer, every tick, for the
     * life of the page.
     */
    private fun refresh() {
        // The ACTIVITY context, not the application one: a couple of controls
        // have to reach the shell they live in (Admin mode repaints the Home
        // grid, the mesh tile opens the WireGuard page). Held only for the
        // length of one short read, and the executor is interrupted in
        // onDestroy, so nothing queued outlives the screen.
        val ctx = context ?: return
        val activity = activity ?: return
        for (id in rows.map { it.id }.distinct()) {
            val control = DeviceControls.byId[id] ?: continue
            io.execute {
                val state = runCatching { control.read(ctx) }.getOrNull()
                val blocked = runCatching { control.blocked(ctx) }.getOrDefault("")
                main.post {
                    if (!isAdded || activity.isFinishing) return@post
                    landed(id, state, blocked)
                }
            }
        }
    }

    /**
     * A read came back. This is the ONLY writer of a tile's reading, which is
     * the only thing a tile is ever painted from — and it writes EVERY tile
     * bearing the id, which is what makes the duplicate in Battery Hungers a
     * second view of one control rather than a second control.
     */
    private fun landed(id: String, state: Boolean?, blocked: String) {
        val now = SystemClock.elapsedRealtime()
        for (row in rows.filter { it.id == id }) {
            row.reading = state
            row.readAt = now
            draw(row, state, blocked)
        }
    }

    /**
     * The tile's reading, or null once it has aged out.
     *
     * A reading older than [STALE_MS] is not an answer any more, and a tile
     * that has never been read (readAt 0) never had one. Both are the same
     * fact to the owner — nobody currently knows — so both are null, which
     * [StatusLight] draws as Unknown rather than picking a colour.
     */
    private fun reading(row: Bound): Boolean? =
        if (row.readAt != 0L && SystemClock.elapsedRealtime() - row.readAt <= STALE_MS) row.reading
        else null

    /**
     * The tile's light, and the only place one is decided.
     *
     * TWO inputs, both from the same [DeviceControls.Control]: the reading,
     * aged out by [reading], and whether that reading was a look at the thing
     * or at a preference. Nothing else reaches here — not the fill, not the
     * last write, not what the user asked for.
     */
    private fun paintLight(row: Bound) {
        val ctx = row.light.context
        val state = StatusLight.of(reading(row), row.control.observed)
        row.light.text = StatusLight.text(ctx, state)
        row.light.setTextColor(StatusLight.colour(ctx, state))
        // The WHOLE tile in words, from the SHARED component rather than a
        // sentence assembled here. A grid of icons took away the row text a
        // screen reader was reading, and the honest replacement is the same
        // four-state word the light is drawn from — not a second "on/off"
        // phrasing beside it, which would have to say "Off" for a tile that is
        // merely unread and would then disagree with the light in the same
        // breath.
        row.tile.contentDescription = StatusLight.description(ctx, row.label, state)
    }

    /**
     * BLACK WHEN OFF, WHITE WHEN ON — the same two theme roles swapped.
     *
     * Neither colour is written here. Both come from [LauncherPalette], so the
     * inversion the owner asked for is the same gesture under every theme
     * instead of a pair of literals that happen to suit one of them.
     */
    private fun paintTile(row: Bound, on: Boolean) {
        val palette = LauncherPalette.of(row.tile.context)
        val fill = if (on) palette.textPrimary else palette.surface
        val ink = if (on) palette.tileInk else palette.textPrimary
        (row.badge.background as? GradientDrawable)?.setColor(fill)
        row.icon.imageTintList = android.content.res.ColorStateList.valueOf(ink)
        row.labelView.setTextColor(palette.textPrimary)
    }

    /** Draw one tile's truth. [blocked] non-blank ⇒ the tile is disabled and
     *  the reason appears under it, so it says what it is waiting for instead
     *  of looking broken. */
    private fun draw(row: Bound, state: Boolean?, blocked: String) {
        paintTile(row, on = state == true)
        paintLight(row)
        row.tile.isEnabled = blocked.isEmpty()
        if (blocked.isNotEmpty()) {
            row.note.text = blocked
            row.note.visibility = View.VISIBLE
        } else {
            row.note.visibility = View.GONE
        }
    }

    /**
     * Ask the device to change, then show what the device says.
     *
     * Every tile of this control is disabled for the duration so a second tap
     * — on either of them — cannot race the first, and the result is always a
     * fresh read: [DeviceControls.Verdict] decides only whether to explain,
     * never what to display.
     */
    private fun write(decl: DeviceControls.Row, control: DeviceControls.Control, want: Boolean) {
        val set = control.set ?: return
        val ctx = context ?: return          // see [refresh] on which context
        val activity = activity ?: return
        for (row in rows.filter { it.id == decl.id }) row.tile.isEnabled = false
        io.execute {
            val verdict = runCatching { set(ctx, want) }.getOrElse {
                DeviceControls.Verdict(false, it.message ?: it::class.java.simpleName)
            }
            val state = runCatching { control.read(ctx) }.getOrNull()
            val blocked = runCatching { control.blocked(ctx) }.getOrDefault("")
            main.post {
                if (!isAdded || activity.isFinishing) return@post
                landed(decl.id, state, blocked)
                if (!verdict.ok) {
                    view?.snack(getString(R.string.control_write_refused, decl.label, verdict.detail),
                        com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
                }
            }
        }
    }

    // ───────────────────────────── Chrome ───────────────────────────────

    /**
     * A group's wording, from the string table when the declaration names a
     * key for it.
     *
     * The key is DATA — build.json says which resource a group's heading is —
     * so adding a group is still a declaration edit, while the words it shows
     * are translated like everything else on the page. A declaration that
     * names no key, or names one this build does not have, falls back to the
     * English text sitting beside it rather than drawing an empty heading.
     */
    private fun groupText(ctx: Context, resName: String, fallback: String): String {
        if (resName.isBlank()) return fallback
        val id = ctx.resources.getIdentifier(resName, "string", ctx.packageName)
        return if (id != 0) ctx.getString(id) else fallback
    }

    private fun title(ctx: Context, s: String) = TextView(ctx).apply {
        text = s
        textSize = 22f
        setTextColor(LauncherPalette.of(ctx).textPrimary)
        setPadding(0, 0, 0, dp(4))
    }

    private fun caption(ctx: Context, s: String) = TextView(ctx).apply {
        text = s
        textSize = 12f
        setTextColor(LauncherPalette.of(ctx).textSecondary)
        setPadding(0, 0, 0, dp(8))
    }

    private fun section(ctx: Context, label: String, subtitle: String) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(16), 0, dp(4))
        addView(TextView(ctx).apply {
            text = label.uppercase()
            textSize = 12f
            setTextColor(LauncherPalette.of(ctx).accent)
        })
        if (subtitle.isNotBlank()) addView(TextView(ctx).apply {
            text = subtitle
            textSize = 11f
            setTextColor(LauncherPalette.of(ctx).textSecondary)
        })
    }

    /** Holds a grid column open where there is no tile for it. */
    private fun spacer(ctx: Context) = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {

        /** Poll cadence WHILE VISIBLE — the launcher status strip's, so the
         *  two live surfaces in this app do not tick at two different rates. */
        private const val REFRESH_MS = 10_000L

        /** How long a reading counts as an answer. Two missed ticks plus
         *  slack: long enough that an ordinary slow read does not blink the
         *  light, short enough that nothing on screen is ever a minute old. */
        private const val STALE_MS = 25_000L

        /** The filled round badge, and the icon centred in it. Samsung's own
         *  quick-settings proportions: a badge comfortably past the 48dp
         *  minimum touch target on its own, with the icon about half of it. */
        private const val BADGE_DP = 56
        private const val ICON_DP = 26

        /** The floor for a whole tile, badge and label together. Android's
         *  accessibility minimum is 48dp in both directions and a grid of
         *  tiles below it is a grid that gets mis-tapped. */
        private const val TOUCH_TARGET_DP = 48

        fun newInstance(): ControlFragment = ControlFragment()
    }
}
