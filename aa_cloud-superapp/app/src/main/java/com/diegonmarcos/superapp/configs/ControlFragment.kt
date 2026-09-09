package com.diegonmarcos.superapp.configs

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.launcher.Sections
import com.diegonmarcos.superapp.ui.StatusLight
import com.diegonmarcos.superapp.ui.snack
import java.util.concurrent.Executors

/**
 * Configs ▸ Panel ▸ Control — the switch board.
 *
 * Three labelled groups (Phone / Cloud / System), every row declared in
 * `build.json::ui.control_panel` and implemented in [DeviceControls]. This
 * file draws them and nothing else: it decides no capability, names no
 * permission and knows no Settings action.
 *
 * ── What it will not draw ─────────────────────────────────────────────────
 * A [DeviceControls.Control] with no `set` is NOT given a Switch. It gets a
 * row that opens the exact system screen for it, with the live state read
 * beside it. That is the whole difference between this page and the version
 * of it that would have been quicker to write, where Wi-Fi and airplane mode
 * are switches that move under the thumb and change nothing on the device.
 *
 * A switch that IS drawn never displays what the user asked for. After a
 * write it re-reads the device and shows THAT, so a refused write is visible
 * as the switch coming back — with the reason in a snackbar — rather than as
 * a control that looks set and is not.
 *
 * ── The lights ────────────────────────────────────────────────────────────
 * Every row carries a [StatusLight]. It is painted from ONE source and only
 * that source: the [DeviceControls.Control.read] for that row, which is the
 * same live read the switch is drawn from. There is no second state path, no
 * cached colour, no per-row special case. A row whose read declines to answer
 * — the platform will not say, the call threw, the flash unit is held by
 * another app — is drawn UNKNOWN, never green and never red.
 *
 * THE LIGHT IS NOT THE SWITCH. The switch shows the SETTING, which is what
 * was asked for; the light shows the STATE, which is what is true. They agree
 * most of the time and the whole page exists for the times they do not — a
 * mesh tunnel the engine dropped, an accept loop that died, an overlay the
 * system killed. A light that were merely the switch position recoloured
 * would be green in exactly the situation it is here to catch.
 *
 * AND WHERE THERE IS NO STATE TO SHOW, IT SAYS SO. Some rows have nothing
 * readable but a preference this app stored — see
 * [DeviceControls.Control.observed] for which, and why each one. A preference
 * is the setting again, so lighting it green would be the switch recoloured
 * after all. Those rows draw "? Not verifiable" permanently, which is the
 * honest answer and the one the owner can act on: it says go and look.
 *
 * STALE IS UNKNOWN. A reading is kept for [STALE_MS] and then stops counting
 * as an answer. This is what makes the light honest across the gap where
 * nothing is polling: the page stops reading the moment it is not visible
 * (see below), so by the time the owner comes back every reading has aged out
 * and the lights say Unknown until fresh reads land — usually within the same
 * frame or two. A green dot recalling an hour-old truth is the same lie as a
 * green dot that was never driven by anything.
 *
 * The switch POSITION deliberately does not age out. Snapping a switch off
 * because a read grew old would look like the device turning off; the switch
 * keeps the last real reading and the light carries the honesty about how old
 * that reading is.
 *
 * ── Battery ───────────────────────────────────────────────────────────────
 * This is a phone. The ticker runs on [onResume] and is cancelled on
 * [onPause], so a page nobody is looking at costs nothing: no wakeups, no
 * binder calls into the WireGuard engine, no shell channel probes. Same
 * shape, and the same [REFRESH_MS] cadence, as the launcher status strip.
 *
 * ── Threading ─────────────────────────────────────────────────────────────
 * Reads and writes both block: shell channels, binder calls into the
 * WireGuard engine, a torch callback's first delivery. All of it runs on one
 * background thread and lands back on the main looper; [DeviceControls] never
 * touches the UI itself. One thread rather than a pool on purpose — these are
 * a dozen short reads, and a pool of them all binding services at once is how
 * a settings page becomes the thing that janks.
 */
class ControlFragment : Fragment() {

    /** Everything a rendered row needs to refresh itself in place. */
    private class Bound(
        val id: String,
        /** The declared label, repeated into the light's contentDescription:
         *  a light announced on its own is a bare "On" belonging to nothing. */
        val label: String,
        val control: DeviceControls.Control,
        val switch: Switch?,
        val light: TextView,
        val note: TextView,
        val listener: CompoundButton.OnCheckedChangeListener?,
    ) {
        /** The last reading the device gave, and WHEN — both written only by
         *  a read that landed. [readAt] 0 means this row has never been read,
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
        for (group in DeviceControls.groups) {
            root.addView(section(ctx, group.label, group.subtitle))
            for (row in group.rows) addRow(root, ctx, row)
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

    // ─────────────────────────────── Rows ───────────────────────────────

    private fun addRow(parent: LinearLayout, ctx: Context, decl: DeviceControls.Row) {
        val control = DeviceControls.byId[decl.id] ?: return   // dropped, never drawn

        // The declared icon, resolved through the same name→drawable lookup
        // every other declared surface in this app uses. Tinted to the row's
        // own label colour so seventeen drawables authored at different times
        // read as one set here.
        val icon = ImageView(ctx).apply {
            setImageResource(Sections.iconResFor(ctx, decl.icon))
            imageTintList = android.content.res.ColorStateList.valueOf(labelColour(ctx))
            // Decorative: the row's own label already says what this controls,
            // and a screen reader announcing the drawable name after it is
            // noise between the label and the state the owner came for.
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply {
                rightMargin = dp(12)
            }
        }

        // NOT named `text`. A local called `text` outranks the implicit
        // receiver inside every nested `TextView(ctx).apply { }` in this
        // function, so each `text = ...` in those blocks binds to the local
        // LinearLayout instead of TextView.setText — four assignments that
        // read as setting a caption but mean reassigning this column.
        val labelColumn = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        labelColumn.addView(TextView(ctx).apply {
            text = decl.label
            textSize = 15f
            setTextColor(labelColour(ctx))
        })
        val note = TextView(ctx).apply {
            text = decl.subtitle
            textSize = 11f
            setTextColor(noteColour(ctx))
        }
        labelColumn.addView(note)

        // One indicator per row, whatever kind of row it is. Starts Unknown
        // because at this instant it IS: nothing has been read yet.
        val light = TextView(ctx).apply {
            textSize = 12f
            setPadding(0, 0, dp(8), 0)
        }

        val line = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val v = dp(6)
            setPadding(0, v, 0, v)
            addView(icon)
            addView(labelColumn)
            addView(light)
        }

        if (control.set != null) {
            // A REAL switch. Its checked state is only ever written from a
            // device read — see [draw] — never from the tap that asked.
            val sw = Switch(ctx)
            val listener = CompoundButton.OnCheckedChangeListener { _, want -> write(decl, control, sw, want) }
            sw.setOnCheckedChangeListener(listener)
            line.addView(sw)
            // Tapping the LABEL of a gated switch goes where the grant is;
            // the switch itself stays disabled so the tap cannot look like a
            // flip that worked.
            control.open?.let { open -> line.setOnClickListener { open(requireContext()) } }
            rows += Bound(decl.id, decl.label, control, sw, light, note, listener)
        } else {
            // NOT a switch: the state is read-only and the row is a door. The
            // light IS the state readout here — there is no second column
            // saying On/Off beside it, because two renderings of one fact are
            // two things that can disagree.
            line.addView(TextView(ctx).apply {
                text = "›"
                textSize = 18f
                setTextColor(noteColour(ctx))
            })
            control.open?.let { open -> line.setOnClickListener { open(requireContext()) } }
            rows += Bound(decl.id, decl.label, control, null, light, note, null)
        }

        paintLight(rows.last())
        parent.addView(line)
    }

    /**
     * Re-read every row from the device and redraw it.
     *
     * The switch is set with its listener detached, because assigning
     * `isChecked` fires [CompoundButton.OnCheckedChangeListener] — so a plain
     * refresh would re-issue the last write on every resume, which for the
     * mesh or the API server means tearing something down and building it back
     * up for having looked at the page.
     */
    private fun refresh() {
        // The ACTIVITY context, not the application one: a couple of controls
        // have to reach the shell they live in (Admin mode repaints the Home
        // grid, the mesh row opens the WireGuard page). Held only for the
        // length of one short read, and the executor is interrupted in
        // onDestroy, so nothing queued outlives the screen.
        val ctx = context ?: return
        val activity = activity ?: return
        for (row in rows) io.execute {
            val state = runCatching { row.control.read(ctx) }.getOrNull()
            val blocked = runCatching { row.control.blocked(ctx) }.getOrDefault("")
            main.post {
                if (!isAdded || activity.isFinishing) return@post
                landed(row, state, blocked)
            }
        }
    }

    /** A read came back. This is the ONLY writer of a row's reading, which is
     *  the only thing a light is ever painted from. */
    private fun landed(row: Bound, state: Boolean?, blocked: String) {
        row.reading = state
        row.readAt = SystemClock.elapsedRealtime()
        draw(row, state, blocked)
    }

    /**
     * The row's reading, or null once it has aged out.
     *
     * A reading older than [STALE_MS] is not an answer any more, and a row
     * that has never been read (readAt 0) never had one. Both are the same
     * fact to the owner — nobody currently knows — so both are null, which
     * [StatusLight] draws as Unknown rather than picking a colour.
     */
    private fun reading(row: Bound): Boolean? =
        if (row.readAt != 0L && SystemClock.elapsedRealtime() - row.readAt <= STALE_MS) row.reading
        else null

    /**
     * The row's light, and the only place one is decided.
     *
     * TWO inputs, both from the same [DeviceControls.Control]: the reading,
     * aged out by [reading], and whether that reading was a look at the thing
     * or at a preference. Nothing else reaches here — not the switch, not the
     * last write, not what the user asked for. The contentDescription is the
     * same fact in words, because colour and glyph are both invisible to a
     * screen reader.
     */
    private fun paintLight(row: Bound) {
        val ctx = row.light.context
        val state = StatusLight.of(reading(row), row.control.observed)
        row.light.text = StatusLight.text(ctx, state)
        row.light.setTextColor(StatusLight.colour(ctx, state))
        row.light.contentDescription = StatusLight.description(ctx, row.label, state)
    }

    /** Draw one row's truth. [blocked] non-blank ⇒ the switch is disabled and
     *  the reason replaces the subtitle, so the row says what it is waiting
     *  for instead of looking broken. */
    private fun draw(row: Bound, state: Boolean?, blocked: String) {
        row.switch?.let { sw ->
            sw.setOnCheckedChangeListener(null)
            sw.isChecked = state == true
            sw.isEnabled = blocked.isEmpty()
            sw.setOnCheckedChangeListener(row.listener)
        }
        paintLight(row)
        if (blocked.isNotEmpty()) {
            row.note.text = blocked
            row.note.setTextColor(ContextCompat.getColor(row.note.context, R.color.control_blocked))
        } else {
            row.note.setTextColor(noteColour(row.note.context))
        }
    }

    /**
     * Ask the device to change, then show what the device says.
     *
     * The switch is disabled for the duration so a second tap cannot race the
     * first, and the result is always a fresh read: [DeviceControls.Verdict]
     * decides only whether to explain, never what to display.
     */
    private fun write(
        decl: DeviceControls.Row,
        control: DeviceControls.Control,
        sw: Switch,
        want: Boolean,
    ) {
        val set = control.set ?: return
        val ctx = context ?: return          // see [refresh] on which context
        val activity = activity ?: return
        sw.isEnabled = false
        io.execute {
            val verdict = runCatching { set(ctx, want) }.getOrElse {
                DeviceControls.Verdict(false, it.message ?: it::class.java.simpleName)
            }
            val state = runCatching { control.read(ctx) }.getOrNull()
            val blocked = runCatching { control.blocked(ctx) }.getOrDefault("")
            main.post {
                if (!isAdded || activity.isFinishing) return@post
                rows.firstOrNull { it.id == decl.id }?.let { landed(it, state, blocked) }
                if (!verdict.ok) {
                    view?.snack(getString(R.string.control_write_refused, decl.label, verdict.detail),
                        com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
                }
            }
        }
    }

    // ───────────────────────────── Chrome ───────────────────────────────

    private fun title(ctx: Context, s: String) = TextView(ctx).apply {
        text = s
        textSize = 22f
        setTextColor(labelColour(ctx))
        setPadding(0, 0, 0, dp(4))
    }

    private fun caption(ctx: Context, s: String) = TextView(ctx).apply {
        text = s
        textSize = 12f
        setTextColor(noteColour(ctx))
        setPadding(0, 0, 0, dp(8))
    }

    private fun section(ctx: Context, label: String, subtitle: String) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(16), 0, dp(4))
        addView(TextView(ctx).apply {
            text = label.uppercase()
            textSize = 12f
            setTextColor(ContextCompat.getColor(ctx, R.color.control_section))
        })
        if (subtitle.isNotBlank()) addView(TextView(ctx).apply {
            text = subtitle
            textSize = 11f
            setTextColor(noteColour(ctx))
        })
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // Every colour on this page is a resource, never a literal here. This app
    // paints over a black→purple gradient by default and over pure black under
    // the two oled_black launcher themes; a colour compiled into this file
    // cannot follow either of them, and the one surface that must never become
    // hard to read is the one saying whether things work.
    private fun labelColour(ctx: Context): Int = ContextCompat.getColor(ctx, R.color.control_label)
    private fun noteColour(ctx: Context): Int = ContextCompat.getColor(ctx, R.color.control_note)

    companion object {

        /** Poll cadence WHILE VISIBLE — the launcher status strip's, so the
         *  two live surfaces in this app do not tick at two different rates. */
        private const val REFRESH_MS = 10_000L

        /** How long a reading counts as an answer. Two missed ticks plus
         *  slack: long enough that an ordinary slow read does not blink the
         *  light, short enough that nothing on screen is ever a minute old. */
        private const val STALE_MS = 25_000L

        fun newInstance(): ControlFragment = ControlFragment()
    }
}
