package com.diegonmarcos.superapp.configs

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.fragment.app.Fragment
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
        val control: DeviceControls.Control,
        val switch: Switch?,
        val state: TextView?,
        val note: TextView,
        val listener: CompoundButton.OnCheckedChangeListener?,
    )

    private val rows = mutableListOf<Bound>()
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "control-panel") }
    private val main = Handler(Looper.getMainLooper())

    override fun onCreateView(inf: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        val ctx = requireContext()
        val pad = dp(16)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        root.addView(title(ctx, "Control"))
        root.addView(caption(ctx,
            "The things flipped often enough to deserve one screen, grouped by " +
            "WHAT THEY CHANGE: the handset, the fleet, or this app.\n\n" +
            "Only what can really move is a switch. A row ending in › instead " +
            "opens the system screen that owns it — Android does not let an app " +
            "flip those, and a switch that pretended otherwise would leave the " +
            "device exactly as it was. A greyed switch says which grant it is " +
            "waiting for; tap the row to go give it."))

        rows.clear()
        for (group in DeviceControls.groups) {
            root.addView(section(ctx, group.label, group.subtitle))
            for (row in group.rows) addRow(root, ctx, row)
        }
        // A build whose declaration failed to parse must say so rather than
        // render an empty page that reads as "nothing is controllable here".
        if (rows.isEmpty()) root.addView(caption(ctx,
            "No controls are declared — build.json::ui.control_panel is empty or " +
            "did not reach this build."))

        return ScrollView(ctx).apply { addView(root) }
    }

    override fun onResume() {
        super.onResume()
        // Every one of these can be changed from outside this page — the
        // notification shade, a Settings screen we just sent the user to, the
        // fleet's own workers. Coming back is exactly when the displayed state
        // is most likely to be stale.
        refresh()
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdownNow()
    }

    // ─────────────────────────────── Rows ───────────────────────────────

    private fun addRow(parent: LinearLayout, ctx: Context, decl: DeviceControls.Row) {
        val control = DeviceControls.byId[decl.id] ?: return   // dropped, never drawn

        val text = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        text.addView(TextView(ctx).apply {
            text = decl.label
            textSize = 15f
            setTextColor(COLOR_LABEL)
        })
        val note = TextView(ctx).apply {
            text = decl.subtitle
            textSize = 11f
            setTextColor(COLOR_NOTE)
        }
        text.addView(note)

        val line = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val v = dp(6)
            setPadding(0, v, 0, v)
            addView(text)
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
            rows += Bound(decl.id, control, sw, null, note, listener)
        } else {
            // NOT a switch: the state is read-only and the row is a door.
            val state = TextView(ctx).apply {
                text = "…"
                textSize = 13f
                setTextColor(COLOR_NOTE)
                setPadding(0, 0, dp(8), 0)
            }
            line.addView(state)
            line.addView(TextView(ctx).apply {
                text = "›"
                textSize = 18f
                setTextColor(COLOR_NOTE)
            })
            control.open?.let { open -> line.setOnClickListener { open(requireContext()) } }
            rows += Bound(decl.id, control, null, state, note, null)
        }

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
                draw(row, state, blocked)
            }
        }
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
        row.state?.text = when (state) {
            true -> "On"
            false -> "Off"
            null -> "—"
        }
        if (blocked.isNotEmpty()) {
            row.note.text = blocked
            row.note.setTextColor(COLOR_BLOCKED)
        } else {
            row.note.setTextColor(COLOR_NOTE)
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
                rows.firstOrNull { it.id == decl.id }?.let { draw(it, state, blocked) }
                if (!verdict.ok) {
                    view?.snack("${decl.label}: didn't change — ${verdict.detail}",
                        com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
                }
            }
        }
    }

    // ───────────────────────────── Chrome ───────────────────────────────

    private fun title(ctx: Context, s: String) = TextView(ctx).apply {
        text = s
        textSize = 22f
        setTextColor(COLOR_LABEL)
        setPadding(0, 0, 0, dp(4))
    }

    private fun caption(ctx: Context, s: String) = TextView(ctx).apply {
        text = s
        textSize = 12f
        setTextColor(COLOR_NOTE)
        setPadding(0, 0, 0, dp(8))
    }

    private fun section(ctx: Context, label: String, subtitle: String) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(16), 0, dp(4))
        addView(TextView(ctx).apply {
            text = label.uppercase()
            textSize = 12f
            setTextColor(COLOR_SECTION)
        })
        if (subtitle.isNotBlank()) addView(TextView(ctx).apply {
            text = subtitle
            textSize = 11f
            setTextColor(COLOR_NOTE)
        })
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val COLOR_LABEL = 0xFFFFFFFF.toInt()
        private const val COLOR_NOTE = 0xAAFFFFFF.toInt()
        private const val COLOR_SECTION = 0xFF8BE9A0.toInt()
        private const val COLOR_BLOCKED = 0xFFFFB199.toInt()

        fun newInstance(): ControlFragment = ControlFragment()
    }
}
