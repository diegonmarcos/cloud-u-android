package com.diegonmarcos.watchtower

import android.content.Context
import android.os.Bundle
import android.util.Base64
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * WatchTower — analytics over the cloud's delivery pipeline.
 *
 * Watchdog watches what is running. Morpheus decides what runs. WatchTower
 * counts what ran: per repo and per workflow, over a window, how many runs
 * there were, what share of the finished ones went green, and how long ago the
 * last green was.
 *
 * ONE SCREEN, REAL NUMBERS. There is no placeholder here and no mock row. If
 * GitHub answers, the figures are GitHub's; if it does not, the screen says
 * which way it failed. The three states are kept apart on purpose — an empty
 * window, a spent quota and an unreachable network are different facts, and
 * rendering all three as zero is the silent failure aa_cloud-superapp's
 * GitHubFeed was fixed for.
 *
 * THE SAME SUBJECT AS THE SUPERAPP'S ANALYTICS CARD. C3 to Observability
 * carries a card reading the same endpoint with the same anonymous client and
 * the same fifteen-minute TTL; it shows one aggregate line per repo. This is
 * that card's detail view, and the per-WORKFLOW breakdown below is the part the
 * card has no room for. The two cannot disagree about a number without one of
 * them simply being older.
 *
 * NO CREDENTIAL. Deliberate, and the reason this app can exist at all: the
 * anonymous GitHub Actions API is the one analytics-grade source reachable with
 * no token that actually answers. See build.json::_doc_why_not_pub_analytics
 * for why /pub/analytics/ is not it.
 */
class MainActivity : AppCompatActivity() {

    // ── the declaration, as data ───────────────────────────────────────
    // Everything below comes out of build.json via BuildConfig. Nothing about
    // which repos are counted, how wide the window is, or how long an answer is
    // cached is a constant in this file — the same rule ac_c3-morpheus follows
    // for its surfaces. Adding a repo is an edit to build.json.

    private data class RepoRef(val owner: String, val repo: String, val label: String)

    private data class Config(
        val repos: List<RepoRef>,
        val windowRuns: Int,
        val cacheMinutes: Int,
    )

    /** One finished-or-running workflow run, reduced to what gets counted. */
    private data class Run(
        val workflow: String,
        val conclusion: String,
        val status: String,
        val tsMillis: Long,
    )

    /** Why a fetch looks the way it does. OK means GitHub answered, so an
     *  empty list genuinely means "nothing ran in this window". */
    private enum class Status { OK, RATE_LIMITED, UNREACHABLE }

    /** Runs plus the reason they are what they are. [stale] is true when these
     *  came from cache because the live call failed, so cached counts are never
     *  passed off as current. */
    private data class Fetched(
        val runs: List<Run>,
        val status: Status,
        val stale: Boolean,
    )

    /**
     * The counts, for any set of runs.
     *
     * [finished] — NOT [total] — is the denominator, and that distinction is
     * the whole correctness of this screen. A run that is still in progress
     * carries an empty `conclusion`; counting it as "not green" would make the
     * success rate sag every time the fleet is mid-build, which is exactly when
     * somebody opens this app. Queued and running are neither green nor failed:
     * they are not yet anything.
     */
    private data class Stats(
        val total: Int,
        val finished: Int,
        val green: Int,
        val failed: Int,
        val lastGreenMs: Long,
    ) {
        /** Integer percent of FINISHED runs that concluded success. Zero
         *  finished runs is reported by the caller as "no data", never as 0%
         *  — a rate with an empty denominator is not a rate. */
        val greenPct: Int get() = if (finished == 0) 0 else (green * 100) / finished
    }

    private lateinit var cfg: Config
    private lateinit var column: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cfg = parseConfig()

        column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(28))
        }
        setContentView(ScrollView(this).apply { addView(column) })
        load()
    }

    private fun parseConfig(): Config {
        val json = String(Base64.decode(BuildConfig.ANALYTICS_JSON_B64, Base64.NO_WRAP))
        val o = JSONObject(json)
        val arr = o.optJSONArray("repos") ?: JSONArray()
        val repos = (0 until arr.length()).map { i ->
            val r = arr.getJSONObject(i)
            RepoRef(
                owner = r.getString("owner"),
                repo  = r.getString("repo"),
                label = r.optString("label", r.getString("repo")),
            )
        }
        return Config(
            repos        = repos,
            windowRuns   = o.optInt("window_runs", 30),
            cacheMinutes = o.optInt("cache_minutes", 15),
        )
    }

    // ── render ─────────────────────────────────────────────────────────

    private fun load() {
        column.removeAllViews()
        column.addView(title(getString(R.string.app_name)))
        column.addView(caption(getString(R.string.screen_subtitle, cfg.windowRuns)))

        val loading = caption(getString(R.string.loading))
        column.addView(loading)

        lifecycleScope.launch {
            val now = System.currentTimeMillis()
            // Stagger the fan-out. Five repos hitting the anonymous API at once
            // spends five of the hour's sixty requests in one burst; the same
            // trickle aa_cloud-superapp's feeds use.
            val results = cfg.repos.mapIndexed { i, ref ->
                async {
                    delay(i * STAGGER_MS)
                    ref to fetchRuns(ref)
                }
            }.awaitAll()
            column.removeView(loading)

            val everything = results.flatMap { it.second.runs }
            if (everything.isEmpty()) {
                // Report the WORST thing that happened, not the first. A single
                // reachable repo with nothing in its window must not present as
                // a healthy empty fleet when the other four were rate limited.
                val worst = results.map { it.second.status }
                    .firstOrNull { it != Status.OK } ?: Status.OK
                column.addView(caption(errorText(worst)))
                column.addView(refreshButton())
                return@launch
            }
            if (results.any { it.second.stale }) {
                column.addView(caption(getString(R.string.stale_notice)))
            }

            column.addView(header(getString(R.string.fleet_header)))
            column.addView(statRow(null, stats(everything), now))

            for ((ref, fetched) in results) {
                val label = "${ref.label}  ·  ${ref.owner}/${ref.repo}"
                if (fetched.runs.isEmpty()) {
                    column.addView(statLine(label, errorText(fetched.status)))
                    continue
                }
                column.addView(statRow(label, stats(fetched.runs), now))
            }

            renderWorkflows(everything, now)
            column.addView(refreshButton())
        }
    }

    /**
     * Per-workflow breakdown, WORST FIRST — the reason to open this app rather
     * than glance at the card.
     *
     * Ordered by green ratio ascending, then by run count descending, so the
     * workflow that is failing most often and runs most often is the first
     * thing read. Workflows with nothing finished yet sort last rather than
     * first: a workflow with no verdict is not the worst workflow, it is an
     * unknown one, and putting it at the top would bury the real answer.
     */
    private fun renderWorkflows(runs: List<Run>, now: Long) {
        val byWorkflow = runs
            .filter { it.workflow.isNotBlank() }
            .groupBy { it.workflow }
            .mapValues { (_, rs) -> stats(rs) }
        if (byWorkflow.isEmpty()) return

        column.addView(header(getString(R.string.workflows_header)))
        byWorkflow.entries
            .sortedWith(
                compareBy<Map.Entry<String, Stats>> { it.value.finished == 0 }
                    .thenBy { it.value.greenPct }
                    .thenByDescending { it.value.total },
            )
            .forEach { (name, s) ->
                val right = if (s.finished == 0) {
                    getString(R.string.stat_no_green)
                } else {
                    getString(R.string.workflow_row, s.green, s.finished)
                }
                column.addView(statLine(name, right, bad = s.finished > 0 && s.green < s.finished))
            }
    }

    private fun stats(runs: List<Run>): Stats {
        val finished = runs.filter { it.conclusion.isNotBlank() }
        val green = finished.filter { it.conclusion == "success" }
        return Stats(
            total       = runs.size,
            finished    = finished.size,
            green       = green.size,
            // cancelled is NOT a failure. A cancel-in-progress concurrency
            // group cancels the older run on every rapid push, so counting
            // those would report a busy afternoon as an outage.
            failed      = finished.count { it.conclusion == "failure" || it.conclusion == "timed_out" },
            lastGreenMs = green.maxOfOrNull { it.tsMillis } ?: 0L,
        )
    }

    private fun statRow(label: String?, s: Stats, now: Long): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(6) }
            layoutParams = lp
            setBackgroundColor(0x331A0033)
        }
        if (label != null) box.addView(body(label))
        val green = if (s.finished == 0) {
            getString(R.string.stat_no_green)
        } else {
            getString(R.string.stat_success_rate, s.greenPct)
        }
        val last = if (s.lastGreenMs > 0L) {
            getString(R.string.stat_last_green, ago(now - s.lastGreenMs))
        } else {
            getString(R.string.stat_no_green)
        }
        box.addView(caption(
            getString(R.string.stat_runs, s.total) + "  ·  " + green +
                "  ·  " + getString(R.string.stat_failures, s.failed) + "  ·  " + last,
        ))
        return box
    }

    private fun statLine(left: String, right: String, bad: Boolean = false): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(6))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(4) }
            layoutParams = lp
            setBackgroundColor(if (bad) 0x55B91C1C.toInt() else 0x22FFFFFF)
        }
        box.addView(body(left))
        box.addView(caption(right))
        return box
    }

    private fun refreshButton(): View = TextView(this).apply {
        text = getString(R.string.refresh)
        setTextColor(0xFF78C8FF.toInt())
        setTextAppearance(android.R.style.TextAppearance_Material_Button)
        setPadding(dp(10), dp(18), dp(10), dp(10))
        isClickable = true
        isFocusable = true
        setOnClickListener { load() }
    }

    private fun errorText(s: Status): String = when (s) {
        Status.RATE_LIMITED -> getString(R.string.err_rate_limited)
        Status.UNREACHABLE  -> getString(R.string.err_unreachable)
        Status.OK           -> getString(R.string.err_empty)
    }

    // ── the client ─────────────────────────────────────────────────────

    /**
     * One request per repo. `per_page` costs the same as `per_page=1` against
     * the anonymous 60-per-hour budget, so the window is as wide as build.json
     * asks for without costing anything extra.
     *
     * On a failed live call the cached body is still served — a
     * quarter-hour-old count beats a blank screen — but the failure travels
     * with it as [Fetched.stale] so the screen can say so.
     */
    private suspend fun fetchRuns(ref: RepoRef): Fetched {
        val sp = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = "${ref.owner}/${ref.repo}/runs"
        val now = System.currentTimeMillis()
        val ttl = cfg.cacheMinutes * 60_000L

        if (now - sp.getLong("$key.ts", 0L) < ttl) {
            sp.getString("$key.body", null)?.let {
                return Fetched(parseRuns(it), Status.OK, stale = false)
            }
        }
        val url = "https://api.github.com/repos/${ref.owner}/${ref.repo}" +
            "/actions/runs?per_page=${cfg.windowRuns}"
        val fresh = get(url)
        if (fresh.first == null) {
            val cached = sp.getString("$key.body", null)
                ?: return Fetched(emptyList(), fresh.second, stale = false)
            return Fetched(parseRuns(cached), fresh.second, stale = true)
        }
        sp.edit().putLong("$key.ts", now).putString("$key.body", fresh.first).apply()
        return Fetched(parseRuns(fresh.first!!), Status.OK, stale = false)
    }

    private fun parseRuns(body: String): List<Run> = runCatching {
        val arr = JSONObject(body).optJSONArray("workflow_runs") ?: return emptyList()
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Run(
                workflow   = o.optString("name", ""),
                conclusion = o.optString("conclusion", ""),
                status     = o.optString("status", ""),
                tsMillis   = parseIso8601(o.optString("created_at", "")),
            )
        }
    }.getOrDefault(emptyList())

    private suspend fun get(url: String): Pair<String?, Status> = withContext(Dispatchers.IO) {
        runCatching {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5_000
                readTimeout = 5_000
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Accept", "application/vnd.github+json")
            }
            try {
                val code = conn.responseCode
                when {
                    code in 200..299 ->
                        conn.inputStream.bufferedReader().use { it.readText() } to Status.OK
                    // An exhausted quota is a 403 (legacy) or 429 (current)
                    // carrying x-ratelimit-remaining: 0. The same codes WITHOUT
                    // that header are a real permission refusal, which is a
                    // different problem and must not be mislabelled.
                    (code == 403 || code == 429) &&
                        conn.getHeaderField("x-ratelimit-remaining") == "0" ->
                        null to Status.RATE_LIMITED
                    else -> null to Status.UNREACHABLE
                }
            } finally {
                conn.disconnect()
            }
        }.getOrElse { null to Status.UNREACHABLE }
    }

    private fun parseIso8601(s: String): Long =
        runCatching { java.time.Instant.parse(s).toEpochMilli() }.getOrDefault(0L)

    private fun ago(ms: Long): String = when {
        ms < 60_000 -> "just now"
        ms < 3_600_000 -> "${ms / 60_000}m ago"
        ms < 86_400_000 -> "${ms / 3_600_000}h ago"
        else -> "${ms / 86_400_000}d ago"
    }

    // ── small view helpers ─────────────────────────────────────────────

    private fun title(s: String) = TextView(this).apply {
        text = s
        setTextColor(0xFFE9D8FD.toInt())
        setTextAppearance(android.R.style.TextAppearance_Material_Headline)
    }

    private fun header(s: String) = TextView(this).apply {
        text = s
        setTextColor(0xFF78C8FF.toInt())
        setTextAppearance(android.R.style.TextAppearance_Material_Subhead)
        setPadding(0, dp(18), 0, dp(4))
    }

    private fun body(s: String) = TextView(this).apply {
        text = s
        setTextColor(0xFFE9D8FD.toInt())
        setTextAppearance(android.R.style.TextAppearance_Material_Body1)
    }

    private fun caption(s: String) = TextView(this).apply {
        text = s
        setTextColor(0x99FFFFFF.toInt())
        setTextAppearance(android.R.style.TextAppearance_Material_Caption)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val PREFS = "watchtower_cache"
        const val UA = "Diego-WatchTower/1.0 (+https://diegonmarcos.com)"
        const val STAGGER_MS = 120L
    }
}
