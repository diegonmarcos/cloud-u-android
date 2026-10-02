package com.diegonmarcos.superapp.appstore

import android.content.Context
import com.diegonmarcos.superapp.devtools.AppDebugServer
import com.diegonmarcos.superapp.updater.Fleet
import com.diegonmarcos.superapp.updater.apk.VerifiedApk
import com.diegonmarcos.superapp.updater.cache.ApkCache
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * #774 `/api/store/<verb>` — the Store's three stages, verifiable with the screen
 * locked. Registered on the fleet's ONE debug server ([AppDebugServer.route]:
 * loopback, token-gated, listed in /api/docs); no second transport.
 *
 * Every verb goes through [StoreStages] — the same calls the row's buttons make
 * — so a green here is a statement about the Store, not about a parallel path.
 * download/install/auto run on their own thread and return at once (a 400 MB
 * fetch must not hold a socket); poll `stage` for the outcome. #784 adds the
 * batch verbs: downloadAll / updateAll answer a dry run (`dryRun=1`) in place,
 * and a real run goes to the background with its per-app report under `batch`.
 * `offline=1` takes the network away, as the batch would see it. No URL, token or
 * secret is ever in a response: names, sizes, digests and sentences only.
 */
object StoreDebugApi {

    @Volatile private var registered = false
    @Volatile private var last: JSONObject? = null

    fun register(ctx: Context) {
        if (registered) return
        registered = true
        val app = ctx.applicationContext
        AppDebugServer.route("store", listOf(
            AppDebugServer.Op("cache", "verify=1 (optional: re-hash every file)",
                "cached APKs: file, bytes, partial, pkg, versionCode, sha256, shaState"),
            AppDebugServer.Op("stage", "pkg=<applicationId or fleet id>&remote=1 (optional: ask the network too)",
                "the row's stage: stage id, text, verbs, failedAt"),
            AppDebugServer.Op("download", "pkg=…", "stage 1 in the background; poll stage"),
            AppDebugServer.Op("install", "pkg=…",
                "download (if nothing installable is cached) → install → clear, in the background, " +
                "stopping at the first stage that fails; poll stage"),
            AppDebugServer.Op("clear", "pkg=…", "stage 3: delete this app's cached APK(s)"),
            AppDebugServer.Op("auto", "pkg=…", "same as install (kept for old callers)"),
            AppDebugServer.Op("downloadAll", "dryRun=1 (plan only) · offline=1 (no network)",
                "fetch every update / missing entry into the cache, install nothing; per-app report"),
            AppDebugServer.Op("updateAll", "dryRun=1 (plan only) · offline=1 (no network)",
                "install every cached newer build (no network), full chain for the rest online; per-app report"),
            AppDebugServer.Op("batch", "", "the report of the last real downloadAll / updateAll"),
        )) { op, q -> route(app, op, q) }
    }

    private fun route(ctx: Context, op: String, q: Map<String, String>): String? = when (op) {
        "cache" -> cache(ctx, q["verify"] == "1").toString()
        "downloadAll", "updateAll" -> batch(ctx, op, q["dryRun"] == "1", q["offline"] != "1" && StoreStages.isOnline(ctx)).toString()
        "batch" -> (last ?: JSONObject().put("ok", true).put("batch", JSONObject.NULL)).toString()
        "stage", "download", "install", "clear", "auto" -> {
            val key = q["pkg"].orEmpty()
            val app = Fleet.parse(BuildConfig.CONSTELLATION_FLEET_B64)
                .firstOrNull { it.pkg == key || it.id == key || it.altId == key }
            if (app == null) JSONObject().put("ok", false).put("error", "no fleet entry for pkg='$key'").toString()
            else verb(ctx, app, op, q["remote"] == "1").toString()
        }
        else -> null
    }

    private fun verb(ctx: Context, app: Fleet.App, op: String, remote: Boolean): JSONObject {
        when (op) {
            "download" -> thread(name = "store-api-download-${app.id}") { StoreStages.download(ctx, app) }
            "install", "auto" -> thread(name = "store-api-install-${app.id}") { StoreStages.install(ctx, app) }
            "clear" -> StoreStages.clear(ctx, app)
        }
        // A verb thread may not have marked itself busy yet; a short settle makes
        // the immediate answer the stage it just entered rather than the one before.
        if (op == "download" || op == "install" || op == "auto") Thread.sleep(150)
        val s = StoreStages.stage(ctx, app, if (remote) runCatching { Fleet.status(ctx, app) }.getOrNull() else null)
        return JSONObject().put("ok", true).put("pkg", app.pkg).put("id", app.id).put("op", op)
            .put("stage", s.id).put("text", s.text).put("verbs", JSONArray(s.actions))
            .put("failedAt", s.failedAt ?: JSONObject.NULL)
            .put("cached", s.cached?.let { entry(it, verify = false) } ?: JSONObject.NULL)
    }

    private fun batch(ctx: Context, op: String, dryRun: Boolean, online: Boolean): JSONObject {
        val fleet = Fleet.parse(BuildConfig.CONSTELLATION_FLEET_B64)
        fun run() = if (op == "downloadAll") StoreStages.downloadAll(ctx, fleet, online, dryRun)
                    else StoreStages.updateAll(ctx, fleet, online, dryRun)
        if (dryRun) return json(run())
        thread(name = "store-api-$op") { last = json(run()) }
        return JSONObject().put("ok", true).put("op", op).put("started", true).put("online", online)
            .put("poll", "store/batch")
    }

    private fun json(b: StoreStages.Batch): JSONObject = JSONObject().put("ok", true).put("op", b.op)
        .put("dryRun", b.dryRun).put("online", b.online).put("summary", b.summary)
        .put("needBytes", b.needBytes).put("roomBytes", b.roomBytes)
        .put("apps", JSONArray(b.outcomes.map {
            JSONObject().put("id", it.app.id).put("pkg", it.app.pkg).put("result", it.result)
                .put("text", it.text).put("failedAt", it.failedAt ?: JSONObject.NULL)
        }))

    private fun cache(ctx: Context, verify: Boolean): JSONObject {
        val entries = ApkCache.entries(ctx)
        return JSONObject().put("ok", true)
            .put("dir", ApkCache.dir(ctx).name)
            .put("totalBytes", entries.sumOf { it.bytes })
            .put("entries", JSONArray(entries.map { entry(it, verify) }))
    }

    private fun entry(e: ApkCache.Entry, verify: Boolean): JSONObject {
        val r = e.record
        val shaState = when {
            e.partial -> "partial"
            r == null -> "no-record"
            !verify -> "recorded"
            VerifiedApk.byDigest(e.file, r.sha256) != null -> "verified"
            else -> "MISMATCH"
        }
        return JSONObject().put("file", e.file.name).put("bytes", e.bytes).put("partial", e.partial)
            .put("pkg", r?.pkg ?: JSONObject.NULL).put("versionCode", r?.versionCode ?: JSONObject.NULL)
            .put("sha256", r?.sha256 ?: JSONObject.NULL).put("shaState", shaState)
            .put("modifiedAt", e.modifiedAt)
    }
}
