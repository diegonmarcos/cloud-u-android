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

    /** #804 the last batch report is PERSISTED: a batch that ends by updating
     *  the SuperApp kills the process that held it in a field (#784's was lost
     *  on exactly that restart). commit(): the next thing may be that kill. */
    private const val BATCH_PREFS = "store_batch"

    fun lastBatch(ctx: Context): JSONObject? = runCatching {
        ctx.getSharedPreferences(BATCH_PREFS, Context.MODE_PRIVATE).getString("last", null)?.let { JSONObject(it) }
    }.getOrNull()

    @android.annotation.SuppressLint("ApplySharedPref")
    private fun keepBatch(ctx: Context, b: JSONObject) {
        ctx.getSharedPreferences(BATCH_PREFS, Context.MODE_PRIVATE).edit().putString("last", b.toString()).commit()
    }

    fun register(ctx: Context) {
        if (registered) return
        registered = true
        val app = ctx.applicationContext
        AppDebugServer.route("store", listOf(
            AppDebugServer.Op("cache", "verify=1 (optional: re-hash every file)",
                "cached APKs: file, bytes, partial, pkg, versionCode, sha256, shaState"),
            AppDebugServer.Op("stage", "pkg=<applicationId or fleet id>&remote=1 (optional: ask the network too)",
                "the row's stage: stage id, text, verbs, failedAt"),
            AppDebugServer.Op("download", "pkg=…",
                "stage 1 in the background; poll stage. An EXTERNAL pkg walks its declared ladder " +
                "(vendor, F-Droid, play-anon = Google Play's own servers) and stages the verified APK(s)"),
            AppDebugServer.Op("install", "pkg=…",
                "download (if nothing installable is cached) → install → clear, in the background, " +
                "stopping at the first stage that fails; poll stage"),
            AppDebugServer.Op("clear", "pkg=…", "stage 3: delete this app's cached APK(s)"),
            AppDebugServer.Op("auto", "run=1 (optional: start / resume it now) · pkg=… (old alias of install)",
                "#804 the auto chain: phase (refresh/download/install/clear/done), queue with each package's " +
                "status, current package, last error — persisted, so it survives the app restarting"),
            AppDebugServer.Op("downloadAll", "dryRun=1 (plan only) · offline=1 (no network)",
                "fetch every update / missing entry into the cache, install nothing; per-app report"),
            AppDebugServer.Op("updateAll", "dryRun=1 (plan only) · offline=1 (no network)",
                "install every cached newer build (no network), full chain for the rest online; per-app report"),
            AppDebugServer.Op("batch", "", "the report of the last real downloadAll / updateAll"),
            AppDebugServer.Op("feeds", "load=1 (optional: re-read every feed now)",
                "#841 Commits / CI-CD feeds: proxy + public url, bearer set (yes/no, never the value), and the " +
                "leg that last served each (proxy | github-fallback | github | failed) with the proxy's error"),
            AppDebugServer.Op("import", "POST body = an app inventory (#565 Store ▸ Phone export, e.g. the vault's apps/<device>-apps.json) · run=1 (optional: install the direct ones)",
                "Store ▸ Phone's import from a terminal: the plan against THIS phone (installed / direct = vendor or F-Droid rung / store = needs its store / manual), package names only; run=1 installs the direct ones in the background through the Phone page's own installMissing. Fleet apps are the Cloud page's and never installed here."),
            AppDebugServer.Op("progress", "",
                "the Store bar's line now: app, version, stage, bytes/total, %, batch position, next, error"),
        )) { op, q -> route(app, op, q) }
        // #792 /api/fleet/endpoints — the Apps Mesh catalogue, off the same probe
        // the page runs. fleet/peers and fleet/wake are AppDebugServer's own and
        // are matched before this group is consulted.
        AppDebugServer.route("fleet", listOf(
            AppDebugServer.Op("endpoints", "filter=${AppsMesh.FILTERS.joinToString("|")}, wake=1|0",
                "every fleet member: assigned + actual 127.0.0.1 port, membership, running/reachable, engine " +
                "links with their state, shares and its own /api/docs endpoints — one JSON catalogue of the " +
                "whole fleet, `filter` keeping what the Apps Mesh chip of that id shows, with every chip's " +
                "count (probes; wake=1, the default, wakes stopped members first and can take ~15 s; wake=0 " +
                "only looks, as the page does)"),
            AppDebugServer.Op("controls", "",
                "#809 the Apps Mesh page's typed controls as drawn: each {scope, id, type page|action|filter, " +
                "label, icon, color}, the group captions and the ids each page handles — what the page draws, " +
                "readable without a screen"),
        )) { op, q ->
            when (op) {
                "endpoints" -> endpoints(app, q["filter"] ?: "all", q["wake"] != "0").toString()
                "controls" -> AppsMesh.controlsJson(AppsMesh.load(app)).toString()
                else -> null
            }
        }
    }

    /** #793 an unknown filter is an error, not "all": a typo must not look like a full answer. */
    private fun endpoints(ctx: Context, filter: String, wake: Boolean): JSONObject {
        if (filter !in AppsMesh.FILTERS) return JSONObject().put("ok", false)
            .put("error", "unknown filter '$filter'; one of ${AppsMesh.FILTERS.joinToString()}")
        val fleet = Fleet.parse(BuildConfig.CONSTELLATION_FLEET_B64)
        val fleetJson = JSONObject(String(android.util.Base64.decode(BuildConfig.CONSTELLATION_FLEET_B64, android.util.Base64.DEFAULT)))
        val links = StoreMesh.links(fleetJson)
        val live = StoreMesh.probe(ctx, fleet, links, wake)
        val mesh = AppsMesh.meshAddresses()
        return AppsMesh.catalogue(fleet, live, AppsMesh.exposure(AppsMesh.load(ctx), mesh), mesh, links, filter).put("ok", true)
    }

    private fun route(ctx: Context, op: String, q: Map<String, String>): String? = when (op) {
        "cache" -> cache(ctx, q["verify"] == "1").toString()
        "downloadAll", "updateAll" -> batch(ctx, op, q["dryRun"] == "1", q["offline"] != "1" && StoreStages.isOnline(ctx)).toString()
        "batch" -> (lastBatch(ctx) ?: JSONObject().put("ok", true).put("batch", JSONObject.NULL)).toString()
        "auto" -> if (q["pkg"].isNullOrEmpty()) auto(ctx, q["run"] == "1").toString() else verb(ctx, q)
        "progress" -> progress().toString()
        "import" -> importInventory(ctx, q["_body"].orEmpty(), q["run"] == "1").toString()
        "feeds" -> feeds(ctx, q["load"] == "1").toString()
        "stage", "download", "install", "clear" -> verb(ctx, q, op)
        else -> null
    }

    /** Store ▸ Phone import, headless: [StorePhoneFragment.importText]'s plan, and [StoreImport.installMissing] on run. */
    private fun importInventory(ctx: Context, body: String, run: Boolean): JSONObject {
        val app = ctx.applicationContext
        val wanted = runCatching { AppInventory.parse(body) }.getOrElse {
            return JSONObject().put("ok", false).put("error", "not an app inventory: ${it.message}")
        }
        val pm = app.packageManager
        val installed = wanted.map { it.pkg }.filter { runCatching { pm.getPackageInfo(it, 0) }.isSuccess }.toSet()
        val sources = PhoneAppActions.sources(app)
        val plan = AppInventory.plan(wanted, installed, AppInventory.fleetPackages(), sources)
        fun pkgs(l: List<AppInventory.Entry>) = JSONArray(l.map { it.pkg })
        val out = JSONObject().put("ok", true)
            .put("counts", JSONObject().put("installed", plan.installed.size).put("ours", plan.ours.size)
                .put("direct", plan.direct.size).put("store", plan.store.size).put("manual", plan.manual.size))
            .put("direct", pkgs(plan.direct)).put("manual", pkgs(plan.manual))
            .put("store", JSONArray(plan.store.map { JSONObject().put("pkg", it.entry.pkg).put("store", it.label) }))
        if (run && plan.direct.isNotEmpty()) {
            thread(name = "store-import-api") { StoreImport.installMissing(app, PhoneAppActions.resolver(sources), plan) }
            out.put("started", "installing ${plan.direct.size} direct apps in the background; poll /api/store/progress")
        }
        return out
    }

    /** #841 which leg (fleet git-proxy or GitHub fallback) served each feed. */
    private fun feeds(ctx: Context, load: Boolean): JSONObject {
        val feeds = FeedViewer.feeds(ctx)
        if (load) feeds.forEach { f -> runCatching { FeedViewer.load(f) } }
        return FeedViewer.servedJson(feeds)
    }

    private fun verb(ctx: Context, q: Map<String, String>, op: String = "auto"): String {
        val key = q["pkg"].orEmpty()
        val app = Fleet.parse(BuildConfig.CONSTELLATION_FLEET_B64)
            .firstOrNull { it.pkg == key || it.id == key || it.altId == key }
        return if (app == null) external(ctx, key, op).toString()
        else verb(ctx, app, op, q["remote"] == "1").toString()
    }

    /** Last external download/install result per package, for the poll. */
    private val externalResult = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * An EXTERNAL app (not a fleet member): walk its declared ladder exactly as
     * Store ▸ Phone's Install does — vendor, F-Droid, then Google Play's own
     * servers through the play-anon rung. download = stage only (fetch +
     * verify, nothing installed); install/auto = stage then commit. Answers at
     * once; poll the same op (or /api/store/progress) for the outcome.
     */
    private fun external(ctx: Context, pkg: String, op: String): JSONObject {
        val cfg = PhoneAppActions.resolver(PhoneAppActions.sources(ctx))
        val app = SourceResolver.resolve(cfg, pkg)
        val out = JSONObject().put("pkg", pkg).put("op", op).put("declared", app.declared)
            .put("ladder", JSONArray(app.sources.map { it.kind }))
        app.unresolved?.let { out.put("unresolved", it) }
        if (app.direct.isEmpty()) return out.put("ok", false)
            .put("error", app.unresolved ?: "no rung this store can fetch from for '$pkg'")
        if (op == "download" || op == "install" || op == "auto") externalResult[pkg] = "running"
        when (op) {
            "download" -> thread(name = "store-api-xdownload-$pkg") {
                externalResult[pkg] = runCatching { ExternalInstall.stage(ctx, cfg, app) }
                    .fold({ "staged: $it" }, { "failed: ${it.message}" })
            }
            "install", "auto" -> thread(name = "store-api-xinstall-$pkg") {
                externalResult[pkg] = ExternalInstall.run(ctx, cfg, app)?.let { "failed: $it" } ?: "committed"
            }
        }
        if (op == "download" || op == "install" || op == "auto") Thread.sleep(150)
        return out.put("ok", true).put("last", externalResult[pkg] ?: "running")
    }

    /** #804 [StoreAuto.json]; run=1 starts (or resumes) the chain in the
     *  background, ungated by Wi-Fi — the caller asked. */
    private fun auto(ctx: Context, run: Boolean): JSONObject {
        if (run) thread(name = "store-api-auto") {
            StoreAuto.run(ctx, Fleet.parse(BuildConfig.CONSTELLATION_FLEET_B64), StoreAuto.TRIGGER_API)
        }
        if (run) Thread.sleep(150)
        return StoreAuto.json(ctx)
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
        thread(name = "store-api-$op") { keepBatch(ctx, json(run())) }
        return JSONObject().put("ok", true).put("op", op).put("started", true).put("online", online)
            .put("poll", "store/batch")
    }

    /** #785 [StoreStages.progress] — the very object the Store bar draws. */
    fun progress(): JSONObject {
        val p = StoreStages.progress() ?: return JSONObject().put("ok", true).put("active", false)
        return JSONObject().put("ok", true).put("active", true).put("text", p.text)
            .put("id", p.appId).put("pkg", p.pkg).put("app", p.app).put("version", p.version)
            .put("stage", p.stage).put("bytes", p.bytes).put("totalBytes", p.totalBytes).put("percent", p.percent)
            .put("index", p.index).put("count", p.count).put("next", p.next ?: JSONObject.NULL)
            .put("failed", p.failed).put("error", if (p.failed) p.detail else JSONObject.NULL)
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
