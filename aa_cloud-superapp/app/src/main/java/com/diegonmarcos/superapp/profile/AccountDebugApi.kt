package com.diegonmarcos.superapp.profile

import android.content.Context
import com.diegonmarcos.superapp.devtools.AppDebugServer
import com.diegonmarcos.superapp.devtools.AppDebugServer.Op
import org.json.JSONArray
import org.json.JSONObject

/**
 * #778 `/api/account/…` on the fleet debug API (libs:devtools: loopback + fleet token), so the
 * architect tests Configs ▸ Account on the phone with curl. Reads and actions call [AccountModel] —
 * the very functions the tabs' buttons call. GET-only server, so every action is a GET with its
 * verb as the op (the "POST-equivalents").
 *
 * NO SECRET LEAVES HERE: values pass the Infos mask ([InfoMask.declared]) or are not printed at
 * all (runtime and drift answer paths, kinds and counts); a masked field cannot be set through
 * [edit]; the upload uses the gh engine's token in-process and prints only the commit.
 */
object AccountDebugApi {

    fun register(ctx: Context) {
        val app = ctx.applicationContext
        AppDebugServer.route("account", listOf(
            Op("tabs", "", "the declared tab strip and drift pairs"),
            Op("profiles", "", "the local declared copy (else the server file) per topic, through the mask"),
            Op("runtime", "", "the last runtime snapshot: per-app status, observed paths"),
            Op("drift", "", "file metadata, per-pair counts, per-app drifted paths, three-way classes"),
            Op("refresh", "", "read every app now and store R"),
            Op("populate", "from=runtime|server", "populate the local copy (unsaved)"),
            Op("edit", "path=&value=", "set one unmasked field of the local copy (unsaved)"),
            Op("save", "", "write the local copy"),
            Op("sync", "dir=push|pull|discard&path=|app=|all=1", "server→runtime, runtime→declared, or discard L"),
            Op("upload", "dry=1", "commit the saved local copy with the gh engine's token (dry=1: the plan only)"),
            Op("erase", "confirm=1", "GDPR: erase the contact card on this device and ask the profile-sync server to drop its copy"),
        )) { op, q -> runCatching { handle(app, op, q)?.toString() }.getOrElse { JSONObject().put("error", it.message).toString() } }
    }

    fun handle(ctx: Context, op: String, q: Map<String, String>): JSONObject? {
        val m = AccountModel.get(ctx)
        return when (op) {
            "", "tabs" -> JSONObject()
                .put("tabs", JSONArray(AccountModel.tabs().map { JSONObject().put("id", it.id).put("label", it.label) }))
                .put("pairs", JSONArray(AccountModel.pairs().map { JSONObject().put("id", it.id).put("a", it.a.name).put("b", it.b.name) }))
            "profiles" -> profiles(m)
            "runtime" -> m.runtime()?.let { r -> JSONObject().put("meta", r.meta.json().put("intact", r.intact)).put("apps", r.apps ?: JSONObject()) }
                ?: JSONObject().put("meta", JSONObject.NULL)
            "drift" -> m.report()
            "refresh" -> done(m.refreshRuntime(), m)
            "populate" -> done(if (q["from"] == "server") m.populateFromServer() else if (q["from"] == "runtime") m.populateFromRuntime()
                               else "✗ from=runtime|server", m)
            "edit" -> {
                val path = q["path"].orEmpty(); val value = q["value"].orEmpty()
                done(if (path.isBlank() || InfoMask.declared.hides(path, value)) "✗ not an editable field (blank or masked)" else m.edit(path, value), m)
            }
            "save" -> done(m.save(), m)
            "sync" -> {
                val paths = target(m, q, if (q["dir"] == "push") AccountStore.Slot.S else AccountStore.Slot.L)
                done(when (q["dir"]) {
                    "push" -> m.pushServerToRuntime(paths)
                    "pull" -> m.pullRuntimeToLocal(paths)
                    "discard" -> m.discardLocal()
                    else -> "✗ dir=push|pull|discard"
                }, m)
            }
            "upload" -> {
                val device = VaultCockpit.selectedDevice(ctx)
                if (q["dry"] == "1") {
                    val plan = m.uploadPlan(device) ?: return JSONObject().put("result", "✗ no saved local copy")
                    JSONObject().put("repo", plan.first.repo).put("path", plan.first.path).put("branch", plan.first.branch)
                        .put("bytes", plan.second.size).put("message", plan.third)
                } else {
                    val token = AccountModel.ghHost()?.let { GhEngine(ctx).token(it) }
                    done(if (token == null) "✗ the gh engine holds no GitHub token — sign in on Connect ▸ GitHub ▸ WebAuth" else m.upload(token, device), m)
                }
            }
            // #781 the contact card form (and its Erase button) left Runtime; the erasure stays reachable here.
            "erase" -> {
                if (q["confirm"] != "1") return JSONObject().put("result", "✗ confirm=1 — this erases the contact card here and on the server")
                val done = java.util.concurrent.LinkedBlockingQueue<String>()
                ProfileSync.forgetMe(ctx) { done.put(it) }
                JSONObject().put("result", done.poll(30, java.util.concurrent.TimeUnit.SECONDS) ?: "✗ no answer in 30 s — the erase may still complete")
            }
            else -> null
        }
    }

    /** Which paths a sync names: `path=` one, `app=` that app's drift against R, `all=1` every drift against R
     *  — R compared with [side] (S for a push, L for a pull). */
    private fun target(m: AccountModel, q: Map<String, String>, side: AccountStore.Slot): List<String> {
        q["path"]?.takeIf { it.isNotBlank() }?.let { return listOf(it) }
        val p = AccountModel.FilePair("", side, AccountStore.Slot.R)
        val fields = m.diff(p)
        return when {
            !q["app"].isNullOrBlank() -> AccountDrift.drifted(fields, q["app"]!!)
            q["all"] == "1" -> AccountDrift.drifted(fields)
            else -> emptyList()
        }
    }

    private fun done(line: String, m: AccountModel) = JSONObject().put("result", line).put("local_unsaved", m.dirty)

    private fun profiles(m: AccountModel): JSONObject {
        val shown = m.shown()
        val topics = JSONArray()
        for (section in InfoMask.sectionsFor(InfoMask.schema, emptyList(), shown)) {
            val rows = InfoMask.declared.schemaRows(section, shown?.opt(section.id))
            topics.put(JSONObject().put("id", section.id).put("label", section.label)
                .put("fields", section.fields.size)
                .put("filled", rows.count { it.kind != InfoMask.Kind.EMPTY })
                .put("empty", rows.count { it.kind == InfoMask.Kind.EMPTY })
                .put("masked", rows.count { it.kind == InfoMask.Kind.MASKED })
                .put("rows", JSONArray(rows.map { r ->
                    JSONObject().put("path", r.path).put("kind", r.kind.name.lowercase()).apply {
                        if (r.kind == InfoMask.Kind.SHOWN) put("text", r.text.take(120)) else put("size", r.size)
                    }
                })))
        }
        val file = if (m.local != null) m.savedLocal() else m.server()
        return JSONObject().put("showing", if (m.local != null) "L" else if (shown != null) "S" else JSONObject.NULL)
            .put("meta", file?.meta?.json() ?: JSONObject.NULL).put("local_unsaved", m.dirty).put("topics", topics)
    }
}
