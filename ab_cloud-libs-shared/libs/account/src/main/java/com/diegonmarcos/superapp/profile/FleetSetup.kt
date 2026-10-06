package com.diegonmarcos.superapp.profile

import android.content.Context
import com.diegonmarcos.superapp.appstore.StoreStages
import com.diegonmarcos.superapp.fleetconfig.FleetPolicy
import com.diegonmarcos.superapp.fleetconfig.SetupContract
import org.json.JSONArray
import org.json.JSONObject

/**
 * #873 FLEET SETUP: Cloud Account pushes what it holds into every installed fleet app through the setup
 * contract ([SetupContract], `<package>.fleetsetup`), per app and in the manifest's order (the SuperApp first):
 *
 *     describe  ->  apply (per declared store, atomic, per-key result)  ->  export (read back and compare)
 *
 * and says ✓/✗ per app, naming the failing key. A step that fails stops that app only; [run] with `only` retries
 * one app. What is pushed is [SetupPlan]'s: the declared copy's `settings` and the imported configs, routed by the
 * manifest's declarations. Values are compared, never printed: an outcome carries key names and reasons only.
 */
object FleetSetup {

    /** One call to an app's setup endpoint; Android's is [SetupContract.call], the JVM suite hands in a fake. */
    fun interface Transport { fun call(pkg: String, method: String, store: String?, body: JSONObject?): SetupContract.Reply }

    enum class State { DONE, FAILED, NOT_INSTALLED, NO_CONTRACT, NOTHING }

    data class KeyOutcome(val store: String, val file: String, val key: String, val ok: Boolean, val why: String = "")

    data class AppOutcome(val id: String, val pkg: String, val state: State, val keys: List<KeyOutcome>, val note: String) {
        val ok: Boolean get() = state == State.DONE || state == State.NOTHING
        val failing: KeyOutcome? get() = keys.firstOrNull { !it.ok }
        /** `✓ me: 3 keys` / `✗ me: mail_jmap_prefs.password: no grant` -- the one line the page shows. */
        fun line(): String = when {
            ok -> "✓ $id: ${keys.size} key(s)" + note.takeIf { it.isNotBlank() }.let { if (it == null) "" else " · $it" }
            failing != null -> "✗ $id: ${failing!!.store}.${failing!!.key}: ${failing!!.why}"
            else -> "✗ $id: $note"
        }
    }

    /** One app: describe -> apply each declared store -> export to verify. Never throws. */
    fun runApp(app: SetupPlan.AppPlan, installed: Boolean, t: Transport): AppOutcome {
        val id = app.app.id; val pkg = app.app.pkg
        if (!installed) return AppOutcome(id, pkg, State.NOT_INSTALLED, emptyList(), "not installed")
        if (app.items.isEmpty()) return AppOutcome(id, pkg, State.NOTHING, emptyList(), "nothing for this app")
        val d = when (val r = t.call(pkg, SetupContract.METHOD_DESCRIBE, null, null)) {
            is SetupContract.Reply.Ok -> r.json
            is SetupContract.Reply.Unreachable -> return AppOutcome(id, pkg, State.NO_CONTRACT, emptyList(), "no setup endpoint (${r.why.take(80)}): update $id")
            is SetupContract.Reply.Refused -> return AppOutcome(id, pkg, State.NO_CONTRACT, emptyList(), "describe refused: ${r.why.take(80)}")
        }
        val served = HashMap<String, Boolean>()
        d.optJSONArray("stores")?.let { a -> for (i in 0 until a.length()) a.getJSONObject(i).let { served[it.getString("name")] = it.optBoolean("served") } }
        val out = ArrayList<KeyOutcome>()
        for ((sf, items) in app.groups()) {
            val (store, file) = sf
            fun fail(why: String) = items.forEach { out += KeyOutcome(store, file, it.key, false, why) }
            when (served[store]) {
                null -> { fail("not declared by this build of $id"); continue }
                false -> { fail("declared, but the app registers no handler for it"); continue }
                else -> {}
            }
            val values = JSONObject(); val types = JSONObject()
            for (i in items) { values.put(i.key, i.value); i.type?.let { types.put(i.key, it) } }
            if (types.length() > 0) values.put(FleetPolicy.TYPES, types)
            val ar = when (val r = t.call(pkg, SetupContract.METHOD_APPLY, store, JSONObject().put("file", file).put("values", values))) {
                is SetupContract.Reply.Ok -> r.json
                is SetupContract.Reply.Unreachable -> { fail(r.why.take(80)); continue }
                is SetupContract.Reply.Refused -> { fail(r.why.take(80)); continue }
            }
            val per = ar.optJSONObject("keys") ?: JSONObject()
            if (!ar.optBoolean("ok")) {
                items.forEach { i -> per.optJSONObject(i.key).let { k -> out += KeyOutcome(store, file, i.key, false, k?.optString("why")?.takeIf { it.isNotEmpty() } ?: ar.optString("why", "refused")) } }
                continue
            }
            // verify: read it back through the same contract
            val back = when (val r = t.call(pkg, SetupContract.METHOD_EXPORT, store, null)) {
                is SetupContract.Reply.Ok -> r.json.optJSONObject("stores")?.optJSONObject(store)?.optJSONObject(file)
                else -> null
            }
            for (i in items) out += when {
                back == null -> KeyOutcome(store, file, i.key, false, "applied, but the read-back failed")
                !same(back.opt(i.key), i.value) -> KeyOutcome(store, file, i.key, false, "applied, but the app reads back a different value")
                else -> KeyOutcome(store, file, i.key, true)
            }
        }
        val bad = out.count { !it.ok }
        return AppOutcome(id, pkg, if (bad == 0) State.DONE else State.FAILED, out, if (bad == 0) "" else "$bad key(s) failed")
    }

    fun same(a: Any?, b: Any?): Boolean = when {
        a == null || b == null -> false
        a is Number && b is Number -> a.toDouble() == b.toDouble()
        a is JSONArray && b is JSONArray -> a.toString() == b.toString()
        else -> a.toString() == b.toString()
    }

    /** The whole fleet, in plan order; [only] retries those apps alone. Calls [each] as each app finishes. */
    fun run(plan: SetupPlan.Plan, installed: (String) -> Boolean, t: Transport, only: Set<String>? = null, each: (AppOutcome) -> Unit = {}): List<AppOutcome> =
        plan.apps.filter { only == null || it.app.id in only }.map { p -> runApp(p, installed(p.app.pkg), t).also(each) }

    // ── Android ──────────────────────────────────────────────────────────

    fun transport(ctx: Context) = Transport { pkg, method, store, body -> SetupContract.call(ctx, pkg, method, store, body) }

    fun installed(ctx: Context, pkg: String) = pkg == ctx.packageName || runCatching { ctx.packageManager.getPackageInfo(pkg, 0) }.isSuccess

    /** What this phone would push: the Connections, the declared copy [declared] and the Configs section, by the baked manifest. */
    fun plan(ctx: Context, configs: String?, declared: JSONObject?, appConfigs: JSONObject? = null): SetupPlan.Plan =
        SetupPlan.build(AccountFleet.manifest(ctx), configs?.takeIf { it.isNotBlank() }?.let { runCatching { JSONObject(it) }.getOrNull() }, declared, appConfigs)

    /** Installs [appId] through the Store's own stages; true when it is installed afterwards. BLOCKS. */
    fun install(ctx: Context, appId: String): Boolean {
        val app = AccountFleet.fleetApps().firstOrNull { it.id == appId } ?: return false
        runCatching { StoreStages.auto(ctx, app) }
        return installed(ctx, app.pkg)
    }
}
