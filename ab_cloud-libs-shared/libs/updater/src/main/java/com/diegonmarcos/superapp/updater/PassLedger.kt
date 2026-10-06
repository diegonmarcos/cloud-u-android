package com.diegonmarcos.superapp.updater

import android.content.Context
import com.diegonmarcos.superapp.core.FleetAlerts

/**
 * #894 One alert per unattended pass, not one per app.
 *
 * An unattended fleet pass used to leave the phone with a notification for
 * every app it touched: "Installed ✓ <pkg> installed successfully" when a late
 * session result landed, "Install is waiting on a hidden confirmation" for each
 * app that needed a tap, the same failure again on every later pass. The pass
 * now keeps a LEDGER of what happened to each package and raises ONE summary
 * ("2 updated, 1 needs a tap") that replaces itself as results arrive, and is
 * not raised again while it says the same thing.
 *
 * Entries are `pkg@build` (failures add `#reason`), so "the same
 * failure" means the same package, the same build and the same reason.
 *
 * [State] and the functions on [PassLedger] are pure (JVM-tested by
 * PassLedgerTest); [PassLedgerStore] is the SharedPreferences + FleetAlerts
 * shell around them.
 */
object PassLedger {

    enum class Outcome { INSTALLED, NEEDS_TAP, FAILED }

    /** Results landing later than this after the pass began are not part of it. */
    const val WINDOW_MS = 30 * 60_000L

    /** [raised] is the [signature] of the summary last put on screen. */
    data class State(
        val installed: Set<String> = emptySet(),
        val needTap: Set<String> = emptySet(),
        val failed: Set<String> = emptySet(),
        val startedAt: Long = 0L,
        val raised: String = "",
        /** The pass is still working: results are filed but the summary waits for its end. */
        val running: Boolean = false,
    ) {
        val empty: Boolean get() = installed.isEmpty() && needTap.isEmpty() && failed.isEmpty()
    }

    fun entry(pkg: String, version: String) = "$pkg@$version"
    private fun pkgOf(entry: String) = entry.substringBefore('@')
    private fun reasonKey(reason: String) = reason.trim().replace(Regex("\\s+"), " ").take(80)

    /** A new pass: forget its outcomes, remember what was last shown. */
    fun begin(s: State, now: Long) = State(startedAt = now, raised = s.raised, running = true)

    fun end(s: State) = s.copy(running = false)

    fun inWindow(s: State, now: Long) = s.startedAt > 0 && now - s.startedAt in 0..WINDOW_MS

    /** Folds one result in. A package is in exactly one bucket: its latest outcome. */
    fun record(s: State, outcome: Outcome, pkg: String, version: String, reason: String = ""): State {
        val e = entry(pkg, version)
        val installed = s.installed.filterNot { pkgOf(it) == pkg }.toSet()
        val needTap = s.needTap.filterNot { pkgOf(it) == pkg }.toSet()
        val failed = s.failed.filterNot { pkgOf(it) == pkg }.toSet()
        return when (outcome) {
            Outcome.INSTALLED -> s.copy(installed = installed + e, needTap = needTap, failed = failed)
            Outcome.NEEDS_TAP -> s.copy(installed = installed, needTap = needTap + e, failed = failed)
            Outcome.FAILED -> s.copy(installed = installed, needTap = needTap, failed = failed + "$e#${reasonKey(reason)}")
        }
    }

    /** "2 updated, 1 needs a tap, 1 failed"; null when the pass did nothing worth a line. */
    fun title(s: State): String? {
        val parts = buildList {
            if (s.installed.isNotEmpty()) add("${s.installed.size} updated")
            if (s.needTap.isNotEmpty()) add("${s.needTap.size} ${if (s.needTap.size == 1) "needs" else "need"} a tap")
            if (s.failed.isNotEmpty()) add("${s.failed.size} failed")
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(", ")
    }

    fun text(s: State, storeName: String): String {
        val names = (s.needTap + s.failed).map { pkgOf(it).substringAfterLast('.') }.distinct().sorted()
        return (if (names.isEmpty()) "" else names.joinToString(", ") + ". ") + "Open $storeName"
    }

    fun signature(s: State): String =
        listOf(s.installed, s.needTap, s.failed).joinToString("|") { it.sorted().joinToString(",") }

    /** The summary is (re)raised only when it differs from the one last shown. */
    fun shouldRaise(s: State): Boolean = !s.empty && signature(s) != s.raised
    fun shouldWithdraw(s: State): Boolean = s.empty && s.raised.isNotEmpty()
}

/** Where the host says a tap goes and what the store is called (see [PassLedgerStore]). */
object UpdaterHost {
    /** FleetAlerts deep link of the pass summary and of a result alert's tap. */
    @Volatile var alertLink: String = "page:config/store-cloud"
    @Volatile var storeName: String = "the Store"
}

object PassLedgerStore {
    private const val PREFS = "pass_ledger"
    private const val KEY = "updater:pass_summary"

    private fun load(ctx: Context): PassLedger.State {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return PassLedger.State(
            installed = p.getStringSet("installed", emptySet()).orEmpty().toSet(),
            needTap = p.getStringSet("need_tap", emptySet()).orEmpty().toSet(),
            failed = p.getStringSet("failed", emptySet()).orEmpty().toSet(),
            startedAt = p.getLong("started_at", 0L),
            raised = p.getString("raised", "").orEmpty(),
            running = p.getBoolean("running", false),
        )
    }

    private fun save(ctx: Context, s: PassLedger.State) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putStringSet("installed", s.installed).putStringSet("need_tap", s.needTap)
            .putStringSet("failed", s.failed).putLong("started_at", s.startedAt)
            .putString("raised", s.raised).putBoolean("running", s.running).commit()
    }

    @Synchronized fun begin(ctx: Context) = runCatching {
        save(ctx, PassLedger.begin(load(ctx), System.currentTimeMillis()))
    }

    /** The pass finished: show its one summary. Late session results update it afterwards. */
    @Synchronized fun end(ctx: Context) {
        runCatching { save(ctx, PassLedger.end(load(ctx))) }
        flush(ctx)
    }

    /**
     * Files one result under the running pass and refreshes its summary.
     * False when no pass is running: the caller then treats it as a stand-alone
     * result (a manual install), which never raises a success alert.
     */
    @Synchronized fun record(ctx: Context, outcome: PassLedger.Outcome, pkg: String, version: String, reason: String = ""): Boolean =
        runCatching {
            val now = System.currentTimeMillis()
            val s = load(ctx)
            if (!PassLedger.inWindow(s, now)) return false
            save(ctx, PassLedger.record(s, outcome, pkg, version, reason))
            if (!s.running) flush(ctx)
            true
        }.getOrDefault(false)

    /** Raises, replaces or withdraws the single summary alert. */
    @Synchronized fun flush(ctx: Context) {
        runCatching {
            val s = load(ctx)
            when {
                PassLedger.shouldRaise(s) -> {
                    FleetAlerts.raise(ctx, FleetAlerts.Alert(
                        title = PassLedger.title(s) ?: return,
                        text = PassLedger.text(s, UpdaterHost.storeName),
                        severity = if (s.failed.isNotEmpty()) FleetAlerts.WARN else FleetAlerts.INFO,
                        deepLink = UpdaterHost.alertLink, dedupeKey = KEY))
                    save(ctx, s.copy(raised = PassLedger.signature(s)))
                }
                PassLedger.shouldWithdraw(s) -> {
                    FleetAlerts.withdraw(ctx, KEY)
                    save(ctx, s.copy(raised = ""))
                }
            }
        }
    }

    /** First time this exact failure (pkg, build, reason) is seen outside a pass? Remembers it. */
    @Synchronized fun firstTimeFailure(ctx: Context, pkg: String, version: String, reason: String): Boolean = runCatching {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val sig = PassLedger.record(PassLedger.State(), PassLedger.Outcome.FAILED, pkg, version, reason).failed.first()
        if (p.getString("last:$pkg", null) == sig) false else { p.edit().putString("last:$pkg", sig).commit(); true }
    }.getOrDefault(true)

    /** A package that installed clears its remembered failure, so a later one is news again. */
    @Synchronized fun clearFailure(ctx: Context, pkg: String) {
        runCatching { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("last:$pkg").commit() }
    }
}
