package com.diegonmarcos.cloudme

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Agenda — the one section that reads live data with no other app in front of
 * it. Both tabs are this fragment in two modes, because an event and a task
 * are the same row with a different date field and the two lists would
 * otherwise be one class copied twice.
 *
 * Both come from the cal ENGINE (Cloud-Lib-Cal.apk, reached through
 * [CalEngineClient]; this app no longer compiles libs:cal): events from its
 * cache of the ICS subscriptions, tasks from its CalDAV VTODO mirror — the
 * same engine and the same data Cloud Agenda shows. The engine answers from
 * its cache, never the network, but the bind itself waits on the main thread,
 * so the rows are fetched on a worker and drawn when they arrive.
 *
 * Nothing on screen is invented: with no account connected both lists are
 * empty and say so, and an engine that is missing or too old for this build
 * is a line naming the Store, never an empty list.
 */
class AgendaFragment : Fragment() {

    private val mode: String get() = arguments?.getString(ARG_MODE) ?: MODE_EVENTS

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val ctx = inflater.context
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 16), dp(ctx, 12), dp(ctx, 16), dp(ctx, 28))
        }
        val scroll = ScrollView(ctx).apply {
            isFillViewport = true
            setBackgroundColor(ContextCompat.getColor(ctx, R.color.me_bg))
            addView(col)
        }
        load(col)
        return scroll
    }

    // ── the engine ───────────────────────────────────────────────────

    /** Ask the engine on a worker, then draw on the main thread — or say why there is nothing to draw. */
    private fun load(col: LinearLayout) {
        val engine = CalEngineClient(requireContext())
        val todos = mode == MODE_TODOS
        Thread {
            val why = engine.check()
            val rows = if (why is CalEngineClient.Check.Ready) runCatching {
                if (todos) engine.todos()
                else System.currentTimeMillis().let { now -> engine.events(now, now + HORIZON_DAYS * 24L * 60L * 60L * 1000L) }
            } else null
            col.post {
                if (!isAdded) return@post
                val ctx = col.context
                val result = rows?.getOrNull()
                col.removeAllViews()
                when {
                    why is CalEngineClient.Check.NotInstalled -> col.addView(emptyState(ctx,
                        getString(R.string.cal_engine_missing_title), getString(R.string.cal_engine_missing, why.pkg)))
                    why is CalEngineClient.Check.TooOld -> col.addView(emptyState(ctx,
                        getString(R.string.cal_engine_old_title), getString(R.string.cal_engine_old, why.pkg, why.found, why.needed)))
                    result != null && todos -> renderTodos(ctx, col, result)
                    result != null -> renderEvents(ctx, col, result, engine)
                    else -> col.addView(emptyState(ctx,
                        getString(R.string.cal_engine_failed_title), rows?.exceptionOrNull()?.message.orEmpty()))
                }
            }
        }.apply { isDaemon = true }.start()
    }

    // ── events ───────────────────────────────────────────────────────

    private fun renderEvents(ctx: Context, col: LinearLayout, events: JSONArray, engine: CalEngineClient) {
        if (events.length() == 0) {
            col.addView(emptyState(ctx, "No events yet",
                "Agenda renders from the calendar engine's cache, never from the network, so the " +
                "tab opens instantly. On a fresh install that cache is empty until the engine has " +
                "fetched its subscriptions once — that first fetch is running now if this is the " +
                "first time you have opened the tab."))
            syncOnce(col, engine)
            return
        }

        var lastDay = ""
        for (i in 0 until events.length()) {
            val e = events.optJSONObject(i) ?: continue
            val start = e.optLong("start")
            // One heading per day rather than a date on every row: a day with
            // four events reads as a block, which is what an agenda is for.
            val day = DAY_FMT.format(Date(start))
            if (day != lastDay) { col.addView(heading(ctx, day)); lastDay = day }
            col.addView(row(
                ctx,
                title = e.optString("title").ifBlank { "(untitled)" },
                detail = e.optString("location"),
                trailing = if (e.optBoolean("allDay")) "all day" else TIME_FMT.format(Date(start)),
                accent = 0xFF7E57C2.toInt(),
            ))
        }
    }

    // ── todos ────────────────────────────────────────────────────────

    private fun renderTodos(ctx: Context, col: LinearLayout, todos: JSONArray) {
        // COMPLETED and CANCELLED are history; an open list that shows them is
        // a list you stop reading.
        val open = (0 until todos.length()).mapNotNull { todos.optJSONObject(it) }
            .filter { it.optString("status") != "COMPLETED" && it.optString("status") != "CANCELLED" }
            .sortedWith(compareBy({ due(it) ?: Long.MAX_VALUE }, { it.optString("summary") }))

        if (open.isEmpty()) {
            col.addView(emptyState(ctx, "No open tasks",
                "Tasks are the CalDAV VTODOs the calendar engine mirrors locally. Buro paperwork and health " +
                "follow-ups both land here as long as they are filed in the same account — the " +
                "split the user asked for is a category on the task, not a second list."))
            return
        }

        val overdue = open.filter { (due(it) ?: Long.MAX_VALUE) < System.currentTimeMillis() }
        if (overdue.isNotEmpty()) {
            col.addView(heading(ctx, "Overdue"))
            overdue.forEach { col.addView(todoRow(ctx, it, 0xFFEF5350.toInt())) }
        }
        val rest = open - overdue.toSet()
        if (rest.isNotEmpty()) {
            col.addView(heading(ctx, "Open"))
            rest.forEach { col.addView(todoRow(ctx, it, 0xFF26A69A.toInt())) }
        }
    }

    /** The engine's task row (CalEngine.todoJson): `due` is epoch millis as a string, "" when unset. */
    private fun due(t: JSONObject): Long? = t.optString("due").toLongOrNull()

    private fun todoRow(ctx: Context, t: JSONObject, accent: Int): View = row(
        ctx,
        title = t.optString("summary").ifBlank { "(untitled)" },
        detail = t.optString("description").lineSequence().firstOrNull().orEmpty(),
        trailing = due(t)?.let { DAY_FMT.format(Date(it)) } ?: "",
        accent = accent,
        progress = t.optInt("percentComplete", -1).takeIf { it >= 0 },
    )

    // ── view helpers ─────────────────────────────────────────────────

    private fun row(
        ctx: Context,
        title: String,
        detail: String,
        trailing: String,
        accent: Int,
        progress: Int? = null,
    ): View {
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(ctx, 14).toFloat()
                setColor(ContextCompat.getColor(ctx, R.color.me_surface))
                setStroke(dp(ctx, 1), ContextCompat.getColor(ctx, R.color.me_outline))
            }
            setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(ctx, 8) }
        }

        val head = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(View(ctx).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(accent) }
            layoutParams = LinearLayout.LayoutParams(dp(ctx, 10), dp(ctx, 10)).apply { rightMargin = dp(ctx, 10) }
        })
        head.addView(TextView(ctx).apply {
            text = title
            setTextColor(ContextCompat.getColor(ctx, R.color.me_text))
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (trailing.isNotBlank()) head.addView(TextView(ctx).apply {
            text = trailing
            setTextColor(ContextCompat.getColor(ctx, R.color.me_text_dim))
            textSize = 12f
        })
        card.addView(head)

        if (detail.isNotBlank()) card.addView(TextView(ctx).apply {
            text = detail
            setTextColor(ContextCompat.getColor(ctx, R.color.me_text_dim))
            textSize = 13f
            maxLines = 2
            setPadding(dp(ctx, 20), dp(ctx, 4), 0, 0)
        })

        if (progress != null && progress > 0) card.addView(TextView(ctx).apply {
            text = "$progress% done"
            setTextColor(accent)
            textSize = 12f
            setPadding(dp(ctx, 20), dp(ctx, 4), 0, 0)
        })

        return card
    }

    private fun heading(ctx: Context, text: String) = TextView(ctx).apply {
        this.text = text
        setTextColor(ContextCompat.getColor(ctx, R.color.me_text))
        textSize = 16f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(ctx, 16), 0, dp(ctx, 2))
    }

    private fun emptyState(ctx: Context, title: String, body: String) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            cornerRadius = dp(ctx, 14).toFloat()
            setColor(ContextCompat.getColor(ctx, R.color.me_surface))
            setStroke(dp(ctx, 1), ContextCompat.getColor(ctx, R.color.me_outline))
        }
        setPadding(dp(ctx, 14), dp(ctx, 14), dp(ctx, 14), dp(ctx, 14))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(ctx, 10) }
        addView(TextView(ctx).apply {
            text = title
            setTextColor(ContextCompat.getColor(ctx, R.color.me_text))
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
        })
        addView(TextView(ctx).apply {
            text = body
            setTextColor(ContextCompat.getColor(ctx, R.color.me_text_dim))
            textSize = 13f
            setLineSpacing(dp(ctx, 3).toFloat(), 1f)
            setPadding(0, dp(ctx, 6), 0, 0)
        })
    }

    private fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    /**
     * First-open fetch. The engine's sync is blocking and networked, so it never
     * runs on the way to drawing a frame — the cache is rendered first and the
     * fetch only fills it for next time, re-rendering in place if the fragment
     * is still on screen.
     *
     * Once per process, guarded rather than checked-then-set: two tab switches
     * in quick succession would otherwise both see an empty cache and start
     * their own fetch of the same feeds.
     */
    private fun syncOnce(col: LinearLayout, engine: CalEngineClient) {
        if (!syncStarted.compareAndSet(false, true)) return
        Thread {
            runCatching { engine.sync() }
            col.post { if (isAdded) load(col) }
        }.apply { isDaemon = true }.start()
    }

    companion object {
        const val MODE_EVENTS = "events"
        const val MODE_TODOS = "todos"

        private const val ARG_MODE = "mode"
        private const val HORIZON_DAYS = 30

        /** Process-wide: the cache is shared by both tabs and by every
         *  fragment instance, so the fetch belongs to the process, not to a
         *  view that is recreated on every rotation. */
        private val syncStarted = AtomicBoolean(false)

        private val DAY_FMT = SimpleDateFormat("EEE d MMM", Locale.getDefault())
        private val TIME_FMT = SimpleDateFormat("HH:mm", Locale.getDefault())

        fun newInstance(mode: String): AgendaFragment = AgendaFragment().apply {
            arguments = Bundle().apply { putString(ARG_MODE, mode) }
        }
    }
}
