package com.diegonmarcos.superapp.system

import android.app.Activity
import android.content.Context
import com.diegonmarcos.superapp.system.SpecialPerms.Kind

/**
 * First-run ask for the two special permissions ([SpecialPerms]).
 *
 * On the first launch after install, and on later launches while one is still
 * missing, one short step per missing permission: Install unknown apps FIRST
 * (the SuperApp installs the fleet), then Default home. Each step has Skip; a
 * permission skipped [MAX_SKIPS] times is never asked again here (the Configs ›
 * Permissions row stays the way to grant it). Skip counts are kept by the
 * app's existing ask ledger, [PermAskTracker].
 *
 * The decision is [Planner], pure and JVM-tested; the store is a
 * SharedPreferences counter, and the dialog is the platform AlertDialog.
 */
object FirstRunPerms {

    const val MAX_SKIPS = 2
    val ORDER = listOf(Kind.INSTALL_UNKNOWN, Kind.HOME)

    /** The step order and skip rule, independent of Android. */
    class Planner(private val skips: MutableMap<Kind, Int> = mutableMapOf()) {
        private val shown = mutableSetOf<Kind>()

        fun skips(kind: Kind) = skips[kind] ?: 0
        fun dontAskAgain(kind: Kind) = skips(kind) >= MAX_SKIPS

        /** The next step to show this launch: the first missing, still-askable,
         *  not-yet-shown kind in [ORDER]; null = nothing to ask. */
        fun next(granted: (Kind) -> Boolean): Kind? =
            ORDER.firstOrNull { !granted(it) && !dontAskAgain(it) && it !in shown }

        fun markShown(kind: Kind) { shown += kind }
        fun skip(kind: Kind): Int { val n = skips(kind) + 1; skips[kind] = n; return n }
        fun newLaunch() = shown.clear()
    }


    @Volatile private var planner: Planner? = null
    @Volatile private var showing = false

    private fun planner(ctx: Context): Planner = planner ?: synchronized(this) {
        planner ?: run {
            // The skip counts live in the app's existing ask ledger (PermAskTracker), not a new store.
            val t = PermAskTracker(ctx)
            Planner(Kind.values().associateWith { t.skips(it.name) }.toMutableMap()).also { planner = it }
        }
    }

    /** Called from the shell's onResume: shows the next missing step, at most once per kind per process. */
    fun maybeAsk(activity: Activity) {
        if (showing || activity.isFinishing) return
        val p = planner(activity)
        val f = SpecialPerms.facts(activity)
        val kind = p.next { SpecialPerms.granted(it, f) } ?: return
        p.markShown(kind)
        showing = true
        val body = when (kind) {
            Kind.INSTALL_UNKNOWN -> "SuperApp downloads and installs the Cloud apps for you. Android needs you to allow it as an install source once."
            Kind.HOME -> "Make SuperApp your default home app so the Home button opens it."
        }
        val last = p.skips(kind) + 1 >= MAX_SKIPS
        val shown = com.diegonmarcos.superapp.extinstall.ComposeAsk.show(activity, SpecialPerms.label(kind), body,
            listOf(
                com.diegonmarcos.superapp.extinstall.ComposeAsk.Choice(if (last) "Skip (don't ask again)" else "Skip") {
                    PermAskTracker(activity).setSkips(kind.name, p.skip(kind))
                    // Skipping one step moves straight on to the next.
                    activity.window?.decorView?.post { maybeAsk(activity) }
                },
                com.diegonmarcos.superapp.extinstall.ComposeAsk.Choice("Allow") { SpecialPerms.request(activity, kind) },
            ),
            dismissable = false,
            onDismiss = { showing = false })
        if (!shown) showing = false
    }
}
