package com.diegonmarcos.superapp.appstore

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import com.diegonmarcos.superapp.updater.Fleet
import com.diegonmarcos.superapp.updater.FleetIdentity
import com.diegonmarcos.superapp.updater.UpdateProgress
import com.diegonmarcos.superapp.updater.Updater
import com.diegonmarcos.superapp.updater.cache.ApkCache
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * #804 THE AUTO CHAIN — one deterministic machine for "Auto update ON":
 *
 *  1. refresh  — ask the remote about every fleet entry and write the queue:
 *                updates for everything, missing LIBS too ([Fleet.Mode.AUTO]),
 *                any installable cached build; libs first, apps after, the host
 *                (this app) last;
 *  2. download — Download ALL of it into the cache before installing anything
 *                ([StoreStages.download]: cache hit first, Range-resumable,
 *                sha-verified), never starting what [StoreStages.room] says will
 *                not fit;
 *  3. install  — in queue order, through [StoreStages.install] (verify → install
 *                → clear on proof), held to [installBudget] when there is no
 *                privileged channel; the host goes to its own self-updater last;
 *  4. clear    — reap every cache whose bytes are now installed.
 *
 * It used to be [Fleet.autoPass]'s batch, which capped the DOWNLOADS at three
 * per pass without a privileged channel, ran only at app start and every six
 * hours (joining Wi-Fi did nothing), drew nothing, and kept its progress in
 * memory — the owner's "Wi-Fi, Auto update ON, updates pending, the Store does
 * nothing", and the #784 batch report lost when the SuperApp restarted itself.
 *
 * The state is PERSISTED after every transition (prefs, commit()): a chain
 * killed mid-download or mid-install — by Android, or by installing this very
 * app — resumes at the phase and package it stopped at on the next trigger,
 * without re-running the refresh. A package that fails is marked failed with
 * the stage and the reason, and the chain moves on to the next one.
 *
 * Triggers: app start and the periodic job (ConstellationWorker / UpdateWorker,
 * through [Fleet.autoChain]), an unmetered network appearing ([attach]), and a
 * Store refresh. Gating (identity, the toggle, Wi-Fi only) stays with the
 * workers, which own it; [run] itself only does the work.
 *
 * ponytail: downloads run one at a time — "bounded concurrency" of 1. The
 * progress model ([UpdateProgress.state] / [UpdateProgress.job]) is one global
 * that Fleet.download publishes into, so parallel fetches would scramble the
 * very bar #785 made readable. Raise it when progress becomes per-job.
 */
object StoreAuto {

    private const val TAG = "StoreAuto"
    private const val PREFS = "store_auto"
    private const val KEY = "state"

    const val IDLE = "idle"
    const val REFRESH = "refresh"
    const val DOWNLOAD = "download"
    const val INSTALL = "install"
    const val CLEAR = "clear"
    const val DONE = "done"

    /** The four phases in order. A persisted phase in here is a chain to resume. */
    val PHASES = listOf(REFRESH, DOWNLOAD, INSTALL, CLEAR)

    // Per-package status, in the order a package moves through them.
    const val QUEUED = "queued"
    const val DOWNLOADING = "downloading"
    const val DOWNLOADED = "downloaded"
    const val INSTALLING = "installing"
    const val INSTALLED = "installed"
    /** Handed to PackageInstaller, waiting for the user's tap. */
    const val PENDING = "pending"
    const val FAILED = "failed"
    /** The user cancelled this one's install sheet before (#774): not re-prompted. */
    const val HELD = "held"
    /** The host's own update, given to its self-updater after everything else. */
    const val HANDED = "handed"

    const val HOST = "host"

    const val TRIGGER_APP_START = "app_start"
    const val TRIGGER_PERIODIC = "periodic"
    const val TRIGGER_WIFI = "wifi"
    const val TRIGGER_STORE_REFRESH = "store_refresh"
    const val TRIGGER_API = "api"

    /** UpdateWorker's owner name: it updates the host itself right after the chain. */
    const val UPDATER_OWNER = "Updater/Worker"

    /** A finished chain is the answer for this long to any automatic trigger
     *  (the two periodic workers and a flapping Wi-Fi all ask the same thing);
     *  a Store refresh and the API always run a fresh one. */
    private const val FRESH_MS = 5L * 60L * 1000L

    class Item(
        val id: String,
        /** lib | app | host — the install order. */
        val kind: String,
        var status: String,
        /** What the refresh saw: update | missing | cached. Kept so a restarted
         *  chain installs against the same remote answer without asking again. */
        val remote: String,
        val version: String,
        val digest: String,
        val bytes: Long,
        val source: String,
        var failedAt: String? = null,
        var error: String? = null,
        /** The installed "versionCode/lastUpdateTime" when its install started:
         *  after a restart, a different value is the device saying it landed. */
        var before: String? = null,
    )

    class State(
        var phase: String,
        val trigger: String,
        val startedAt: Long,
        var updatedAt: Long = startedAt,
        var finishedAt: Long = 0L,
        var current: String? = null,
        var lastError: String? = null,
        val queue: MutableList<Item> = ArrayList(),
    ) {
        fun count(status: String) = queue.count { it.status == status }
        val summary: String get() = queue.groupingBy { it.status }.eachCount().entries
            .joinToString(" · ") { "${it.value} ${it.key}" }.ifEmpty { "nothing to do" }
    }

    // ── seams: the production default is the only one shipped ──────────────

    /** The remote answer per package. */
    @Volatile var remote: (Context, Fleet.App) -> Fleet.State = Fleet::status

    /** Installs one unattended pass may start ([Fleet.unattendedInstallBudget]). */
    @Volatile var installBudget: (Context) -> Int = Fleet::unattendedInstallBudget

    /** The host's own update, last: its self-updater (UpdateWorker). */
    @Volatile var selfUpdate: (Context) -> Unit = { Updater.start(it) }

    /** #812 the Store badge: told how many updates still wait after every
     *  chain (0 clears it). The SuperApp's StoreBadge sets it. */
    @Volatile var onPending: (Context, Int) -> Unit = { _, _ -> }

    /** Updates still waiting on the device after [s]: everything not landed. */
    fun pending(s: State): Int = s.queue.count { it.status != INSTALLED && it.status != HANDED }

    /** Called right after a package's transition is persisted, before its work
     *  starts — a test kills the "process" here to prove the resume. */
    @Volatile var checkpoint: (phase: String, id: String) -> Unit = { _, _ -> }

    private val running = AtomicBoolean(false)
    @Volatile private var live: State? = null

    /** #857 the phase of the running chain, else of the persisted one. */
    fun phaseNow(ctx: Context): String? = (live ?: load(ctx))?.phase

    /** #857 a chain is running in this process right now. */
    fun isRunning(): Boolean = running.get()
    @Volatile private var scanned = 0
    @Volatile private var scanTotal = 0

    /**
     * Run (or resume) the chain. Blocking — call off the main thread. One at a
     * time per process: a second trigger while one runs is already being
     * served, and gets the running chain's state back.
     * [selfAfter] false when the caller updates the host itself right after
     * (UpdateWorker), true when the chain has to hand it over.
     */
    fun run(ctx: Context, apps: List<Fleet.App>, trigger: String, selfAfter: Boolean = true): State {
        if (!running.compareAndSet(false, true)) {
            Log.i(TAG, "$trigger: the auto chain is already running — this trigger is already being served")
            return live ?: load(ctx) ?: State(IDLE, trigger, System.currentTimeMillis())
        }
        try {
            // #903 the chain holds the dataSync foreground service for its whole run.
            com.diegonmarcos.superapp.updater.BatchForeground.begin(ctx)
            val prev = load(ctx)
            val now = System.currentTimeMillis()
            val s = when {
                prev != null && prev.phase in PHASES -> {
                    Log.i(TAG, "$trigger: resuming the '${prev.trigger}' chain at phase ${prev.phase} " +
                               "(${prev.summary}) — nothing already done is redone")
                    prev
                }
                prev != null && prev.phase == DONE && trigger != TRIGGER_STORE_REFRESH &&
                    trigger != TRIGGER_API && now - prev.finishedAt in 0 until FRESH_MS -> {
                    Log.i(TAG, "$trigger: a chain finished ${(now - prev.finishedAt) / 1000}s ago " +
                               "(${prev.summary}) — that is this trigger's answer")
                    return prev
                }
                else -> State(REFRESH, trigger, now)
            }
            live = s
            // A Cancel stops THAT batch; the next trigger is the "resume later"
            // (#812). Left armed, one Cancel would stop every chain after it.
            UpdateProgress.beginDownload()
            val byId = apps.associateBy { it.id }
            // Each phase moves s.phase on only when it completed; one that
            // stops (cancel, install budget) leaves it, and the rest wait for
            // the next trigger, which resumes exactly there.
            //
            // #812 ROUNDS: what does not fit in the real free storage stays
            // queued while what was downloaded is installed and cleared — then
            // the chain goes round again for the rest (rootfs-sized items end
            // up one at a time). A round that lands nothing ends the loop and
            // the leftovers fail with the real numbers instead of thrashing.
            var rounds = 0
            while (true) {
                val landedBefore = s.count(INSTALLED)
                if (s.phase == REFRESH) refresh(ctx, s, apps)
                if (s.phase == DOWNLOAD) download(ctx, s, byId)
                if (s.phase == INSTALL) install(ctx, s, byId)
                if (s.phase != CLEAR) break
                val deferred = s.queue.filter { it.kind != HOST && it.status == QUEUED }
                if (deferred.isNotEmpty() && s.count(INSTALLED) > landedBefore && rounds++ < s.queue.size) {
                    reap(ctx, s, byId)
                    s.phase = DOWNLOAD
                    save(ctx, s)
                    continue
                }
                deferred.forEach { fail(it, DOWNLOAD, it.error ?: "not downloaded") }
                clear(ctx, s, byId, selfAfter)
                break
            }
            Log.i(TAG, "$trigger: chain at ${s.phase} — ${s.summary}" + (s.lastError?.let { " — $it" } ?: ""))
            return s
        } finally {
            live = null
            running.set(false)
            UpdateProgress.republish()
            com.diegonmarcos.superapp.updater.BatchForeground.end(ctx)
        }
    }

    /** [Fleet.autoChain]: the workers' pass, as the chain. */
    fun pass(ctx: Context, apps: List<Fleet.App>, owner: String): Fleet.Pass {
        val s = run(ctx, apps, owner.substringAfter(':', TRIGGER_PERIODIC), selfAfter = !owner.startsWith(UPDATER_OWNER))
        val work = s.queue.filter { it.kind != HOST }
        // #894 what the pass did, filed under the pass's ONE summary alert (Fleet.autoPass opens and closes it).
        val byId = apps.associateBy { it.id }
        for (i in work) {
            val pkg = byId[i.id]?.pkg ?: continue
            val build = i.digest.ifEmpty { i.version }
            when (i.status) {
                INSTALLED -> com.diegonmarcos.superapp.updater.PassLedgerStore.record(ctx,
                    com.diegonmarcos.superapp.updater.PassLedger.Outcome.INSTALLED, pkg, build)
                PENDING -> com.diegonmarcos.superapp.updater.PassLedgerStore.record(ctx,
                    com.diegonmarcos.superapp.updater.PassLedger.Outcome.NEEDS_TAP, pkg, build)
                FAILED, HELD -> com.diegonmarcos.superapp.updater.PassLedgerStore.record(ctx,
                    com.diegonmarcos.superapp.updater.PassLedger.Outcome.FAILED, pkg, build, i.error.orEmpty())
            }
        }
        val waiting = work.count { it.status == PENDING || it.status == DOWNLOADED || it.status == INSTALLING }
        return Fleet.Pass(
            acted = s.count(INSTALLED),
            considered = work.count { it.status != INSTALLED },
            batched = work.size,
            silent = waiting == 0,
            channel = null,
            reason = "auto chain (${s.trigger}) at ${s.phase}: ${s.summary}" + (s.lastError?.let { " — $it" } ?: ""),
        )
    }

    // ── the four phases ──────────────────────────────────────────────────────

    private fun refresh(ctx: Context, s: State, apps: List<Fleet.App>) {
        save(ctx, s)
        val todo = apps.filter { !it.blocked }
        scanTotal = todo.size
        scanned = 0
        for (app in todo) {
            scanned++
            UpdateProgress.republish()
            val r = try { remote(ctx, app) } catch (e: Exception) { Fleet.State.Error(e.message ?: e.javaClass.simpleName) }
            if (app.pkg == ctx.packageName) {
                if (r is Fleet.State.UpdateAvailable) s.queue += itemOf(app, HOST, r)
                continue
            }
            val held = Fleet.heldAtInstall(ctx, app)
            val wanted = r is Fleet.State.UpdateAvailable || (r is Fleet.State.Missing && app.kind == "lib") ||
                StoreStages.actionableFor(ctx, app, r) != null
            when {
                r is Fleet.State.Error -> s.queue += itemOf(app, app.kind, null).also { fail(it, REFRESH, r.message) }
                !wanted -> {}
                held != null -> s.queue += itemOf(app, app.kind, r).also {
                    it.status = HELD; it.failedAt = INSTALL
                    it.error = "the last install did not finish (${held.message}) — Install it from its row"
                }
                else -> s.queue += itemOf(app, app.kind, r)
            }
        }
        // Libs (the engines apps bind to) before apps, the host last. Stable,
        // so the fleet's own order holds inside each group.
        s.queue.sortBy { rank(it.kind) }
        s.phase = DOWNLOAD
        save(ctx, s)
    }

    private fun download(ctx: Context, s: State, byId: Map<String, Fleet.App>) {
        val go = s.queue.filter { it.kind != HOST && (it.status == QUEUED || it.status == DOWNLOADING) }
        for ((n, item) in go.withIndex()) {
            if (UpdateProgress.cancelRequested) return stop(ctx, s, "cancelled by the user — the rest resume on the next pass")
            val app = byId[item.id]
            if (app == null) { fail(item, DOWNLOAD, "no longer in the fleet"); save(ctx, s); continue }
            val r = remoteOf(item)
            // Killed after the bytes landed: the cache already holds it.
            if (StoreStages.actionableFor(ctx, app, r) != null) { item.status = DOWNLOADED; item.failedAt = null; item.error = null; save(ctx, s); continue }
            val want = (item.bytes - StoreStages.partialBytes(ctx, app)).coerceAtLeast(0)
            val room = StoreStages.room(ctx)
            if (want > room) {
                // #812 Not a failure yet: it stays queued for the next round,
                // after what is downloaded has been installed and cleared.
                item.error = "needs ${mb(want)}, ${mb(room)} usable (${ApkCache.roomText(ctx)}) — " +
                    "waits for the downloaded ones to install and clear"
                item.failedAt = DOWNLOAD
                s.lastError = "${item.id}: ${item.error}"
                save(ctx, s); continue
            }
            item.status = DOWNLOADING
            s.current = item.id
            save(ctx, s)
            checkpoint(DOWNLOAD, item.id)
            StoreStages.beginNext(UpdateProgress.Job(app.id, app.pkg, app.label, UpdateProgress.STAGE_DOWNLOADING,
                item.version, n + 1, go.size, go.getOrNull(n + 1)?.let { byId[it.id]?.label }))
            val st = try { StoreStages.download(ctx, app) } catch (e: Exception) { null }
            if (UpdateProgress.cancelRequested && StoreStages.actionableFor(ctx, app, r) == null) {
                // #812 Cancel mid-download: not a failure. Its .part stays for
                // the Range resume, and it stays queued for the next trigger.
                item.status = QUEUED
                return stop(ctx, s, "cancelled by the user — ${item.id} resumes from its partial on the next pass")
            }
            if (StoreStages.actionableFor(ctx, app, r) != null) {
                item.status = DOWNLOADED; item.failedAt = null; item.error = null
            } else fail(item, st?.failedAt ?: DOWNLOAD, st?.text ?: "download stopped")
            s.current = null
            save(ctx, s)
        }
        UpdateProgress.endBatch()
        s.phase = INSTALL
        save(ctx, s)
    }

    private fun install(ctx: Context, s: State, byId: Map<String, Fleet.App>) {
        val go = s.queue.filter { it.kind != HOST && (it.status == DOWNLOADED || it.status == INSTALLING) }
        val budget = installBudget(ctx)
        var started = 0
        for ((n, item) in go.withIndex()) {
            if (UpdateProgress.cancelRequested) return stop(ctx, s, "cancelled by the user — the rest resume on the next pass")
            val app = byId[item.id]
            if (app == null) { fail(item, INSTALL, "no longer in the fleet"); save(ctx, s); continue }
            // Killed mid-install: the device is the only witness that counts.
            if (item.status == INSTALLING) {
                val now = code(ctx, app)
                if (now != null && now != item.before) { landed(item); save(ctx, s); continue }
            }
            val held = Fleet.heldAtInstall(ctx, app)
            if (held != null) {
                item.status = HELD; item.failedAt = INSTALL; item.error = "install did not finish: ${held.message}"
                save(ctx, s); continue
            }
            if (started >= budget) return stop(ctx, s, "${go.size - n} install(s) wait for the next pass: no " +
                "privileged install channel, and this pass may open $budget install session(s)")
            item.before = code(ctx, app)
            item.status = INSTALLING
            s.current = item.id
            save(ctx, s)
            checkpoint(INSTALL, item.id)
            StoreStages.beginNext(UpdateProgress.Job(app.id, app.pkg, app.label, UpdateProgress.STAGE_VERIFYING,
                item.version, n + 1, go.size, go.getOrNull(n + 1)?.let { byId[it.id]?.label }))
            val st = try { StoreStages.install(ctx, app, remoteOf(item)) } catch (e: Exception) {
                StoreStages.Stage("error", e.message ?: e.javaClass.simpleName, emptyList(), failedAt = StoreStages.INSTALL)
            }
            started++
            val after = code(ctx, app)
            when {
                after != null && after != item.before -> landed(item)
                st.failedAt != null -> fail(item, st.failedAt, st.text)
                // The device already runs the published bytes: nothing to hand over.
                st.id == "installed" -> landed(item)
                else -> { item.status = PENDING; item.error = "handed to the installer — confirm it on screen" }
            }
            s.current = null
            save(ctx, s)
        }
        UpdateProgress.endBatch()
        s.phase = CLEAR
        s.lastError = null
        save(ctx, s)
    }

    /** stage() reaps a cache whose bytes ARE the installed APK — the same
     *  proof-only rule as every other Clear. */
    private fun reap(ctx: Context, s: State, byId: Map<String, Fleet.App>) {
        for (item in s.queue) if (item.kind != HOST) byId[item.id]?.let { StoreStages.stage(ctx, it) }
    }

    private fun clear(ctx: Context, s: State, byId: Map<String, Fleet.App>, selfAfter: Boolean) {
        reap(ctx, s, byId)
        val failed = s.queue.filter { it.status == FAILED || it.status == HELD }
        s.lastError = failed.firstOrNull()?.let { "${it.id}: ${it.status} at ${it.failedAt} — ${it.error}" }
        StoreStages.finishBatch(failed.mapNotNull { i ->
            byId[i.id]?.let { StoreStages.Outcome(it, StoreStages.FAILED, i.error.orEmpty(), i.failedAt) }
        })
        val host = s.queue.firstOrNull { it.kind == HOST && it.status == QUEUED }
        host?.apply {
            status = HANDED
            error = if (selfAfter) "handed to the self-updater" else "the self-updater runs next"
        }
        s.phase = DONE
        s.current = null
        s.finishedAt = System.currentTimeMillis()
        // Persisted BEFORE the host update: installing this app kills this process.
        save(ctx, s)
        runCatching { onPending(ctx, pending(s)) }
        if (host != null && selfAfter) runCatching { selfUpdate(ctx) }
            .onFailure { Log.w(TAG, "self-update hand-off failed: ${it.message}") }
    }

    private fun stop(ctx: Context, s: State, why: String) {
        s.lastError = why
        s.current = null
        UpdateProgress.endBatch()
        save(ctx, s)
    }

    private fun fail(i: Item, at: String, why: String) { i.status = FAILED; i.failedAt = at; i.error = why }

    private fun landed(i: Item) { i.status = INSTALLED; i.failedAt = null; i.error = null }

    private fun rank(kind: String) = when (kind) { "lib" -> 0; HOST -> 2; else -> 1 }

    private fun code(ctx: Context, app: Fleet.App): String? =
        StoreStages.installedCode(ctx, app)?.let { "${it.first}/${it.second}" }

    private fun mb(b: Long) = FleetIdentity.human(b)

    private fun itemOf(app: Fleet.App, kind: String, r: Fleet.State?): Item = when (r) {
        is Fleet.State.UpdateAvailable ->
            Item(app.id, kind, QUEUED, "update", r.versionName.orEmpty(), r.remoteDigest12, r.bytes, r.source)
        is Fleet.State.Missing -> Item(app.id, kind, QUEUED, "missing", "", "", r.bytes, "")
        else -> Item(app.id, kind, QUEUED, "cached", "", "", 0L, "")
    }

    private fun remoteOf(i: Item): Fleet.State? = when (i.remote) {
        "update" -> Fleet.State.UpdateAvailable(i.version.ifEmpty { null }, i.digest, i.bytes, source = i.source)
        "missing" -> Fleet.State.Missing(i.bytes)
        else -> null
    }

    // ── what the Store bar and /api/store/auto read ──────────────────────────

    /** "Auto ▸ 2/4 download all" while a chain runs in this process, else null. */
    fun label(): String? {
        val s = live ?: return null
        return "Auto ▸ " + when (s.phase) {
            REFRESH -> "1/4 checking $scanned of $scanTotal"
            DOWNLOAD -> "2/4 download all"
            INSTALL -> "3/4 install"
            CLEAR -> "4/4 clear"
            else -> return null
        }
    }

    /** `/api/store/auto`: phase, queue, current package, last error — the
     *  persisted state, so it reads the same after the app restarted. */
    fun json(ctx: Context): JSONObject {
        val run = running.get()
        val s = load(ctx) ?: return JSONObject().put("ok", true).put("running", run).put("phase", IDLE)
            .put("queue", JSONArray())
        return toJson(s).put("ok", true).put("running", run).put("summary", s.summary)
            .put("pending", pending(s))
            .put("room", runCatching { roomJson(ctx) }.getOrDefault(JSONObject()))
            .put("resumable", !run && s.phase in PHASES).put("label", label() ?: JSONObject.NULL)
    }

    /** #812 the real numbers behind any "no room": free storage, reserve,
     *  the derived bound, what the cache holds and what may still be written. */
    fun roomJson(ctx: Context): JSONObject {
        val free = ApkCache.freeBytes(ctx); val cached = ApkCache.totalBytes(ctx)
        return JSONObject().put("freeBytes", free).put("cachedBytes", cached)
            .put("reserveBytes", com.diegonmarcos.superapp.updater.BuildConfig.APK_CACHE_RESERVE_BYTES)
            .put("boundBytes", ApkCache.boundOf(free, cached)).put("usableBytes", StoreStages.room(ctx))
            .put("text", ApkCache.roomText(ctx))
    }

    fun load(ctx: Context): State? = runCatching {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)?.let { fromJson(JSONObject(it)) }
    }.getOrNull()

    /** commit(), not apply(): the next thing may be this process dying. */
    @SuppressLint("ApplySharedPref")
    private fun save(ctx: Context, s: State) {
        s.updatedAt = System.currentTimeMillis()
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, toJson(s).toString()).commit()
        UpdateProgress.republish()
    }

    private fun toJson(s: State): JSONObject = JSONObject().put("phase", s.phase).put("trigger", s.trigger)
        .put("startedAt", s.startedAt).put("updatedAt", s.updatedAt).put("finishedAt", s.finishedAt)
        .put("current", s.current ?: JSONObject.NULL).put("lastError", s.lastError ?: JSONObject.NULL)
        .put("queue", JSONArray(s.queue.map { i ->
            JSONObject().put("id", i.id).put("kind", i.kind).put("result", i.status).put("remote", i.remote)
                .put("version", i.version).put("digest", i.digest).put("bytes", i.bytes).put("source", i.source)
                .put("failedAt", i.failedAt ?: JSONObject.NULL).put("error", i.error ?: JSONObject.NULL)
                .put("before", i.before ?: JSONObject.NULL)
        }))

    private fun JSONObject.str(k: String): String? = if (isNull(k)) null else optString(k)

    private fun fromJson(o: JSONObject): State {
        val q = o.optJSONArray("queue") ?: JSONArray()
        return State(o.getString("phase"), o.optString("trigger"), o.optLong("startedAt"), o.optLong("updatedAt"),
            o.optLong("finishedAt"), o.str("current"), o.str("lastError"),
            MutableList(q.length()) { n ->
                val i = q.getJSONObject(n)
                Item(i.getString("id"), i.optString("kind"), i.getString("result"), i.optString("remote"),
                    i.optString("version"), i.optString("digest"), i.optLong("bytes"), i.optString("source"),
                    i.str("failedAt"), i.str("error"), i.str("before"))
            })
    }

    // ── the Wi-Fi trigger ────────────────────────────────────────────────────

    /** True when an unmetered network has just APPEARED: metered (or none) →
     *  unmetered. The first reading of a process is not an edge — app start is
     *  its own trigger. */
    fun wifiEdge(was: Boolean?, now: Boolean): Boolean = now && was == false

    private val watching = AtomicBoolean(false)
    @Volatile private var lastUnmetered: Boolean? = null

    /**
     * Make the chain THE unattended pass ([Fleet.autoChain]) and start the
     * Wi-Fi trigger: [kick] runs when the default network turns unmetered. The
     * ACTIVE network on purpose, the one AutoUpdatePrefs judges by — on a
     * permanently-VPN'd phone it is the VPN, which inherits NOT_METERED from
     * the Wi-Fi under it. Idempotent.
     */
    fun attach(ctx: Context, kick: (Context, String) -> Unit) {
        val app = ctx.applicationContext
        Fleet.autoChain = { c, apps, owner -> pass(c, apps, owner) }
        if (!watching.compareAndSet(false, true)) return
        runCatching {
            app.getSystemService(ConnectivityManager::class.java)!!
                .registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                    override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                        val now = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                        val was = lastUnmetered
                        lastUnmetered = now
                        if (wifiEdge(was, now)) {
                            Log.i(TAG, "an unmetered network appeared — kicking the auto chain")
                            kick(app, TRIGGER_WIFI)
                        }
                    }

                    override fun onLost(network: Network) { lastUnmetered = false }
                })
        }.onFailure {
            watching.set(false)
            Log.w(TAG, "no Wi-Fi trigger (network callback refused): ${it.message}")
        }
    }
}
