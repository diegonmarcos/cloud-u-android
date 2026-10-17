package com.diegonmarcos.superapp.appstore

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Environment
import android.os.IBinder
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.diegonmarcos.superapp.uikit.KitAction
import com.diegonmarcos.superapp.uikit.KitDensity
import com.diegonmarcos.superapp.uikit.KitState
import com.diegonmarcos.superapp.uikit.KitStatusBanner
import com.diegonmarcos.superapp.uikit.KitStep
import com.diegonmarcos.superapp.uikit.KitStepState
import com.diegonmarcos.superapp.uikit.KitStepper
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import com.diegonmarcos.superapp.updater.Fleet
import com.diegonmarcos.superapp.updater.cache.ApkCache
import com.termux.cloud.ICloudExec
import java.io.File
import org.json.JSONObject

/**
 * A fleet app whose new build changes its Linux uid (android:sharedUserId, the
 * cloud-shared-store work) cannot be installed over the old one: Android answers
 * INSTALL_FAILED_UID_CHANGED (legacy -24) — or SHARED_USER_INCOMPATIBLE (-8) /
 * UPDATE_INCOMPATIBLE (-7) — and every retry from the cache fails the same way.
 *
 * So the first such failure turns the row into a ONE-TIME migration instead of a
 * retry: the failure is recorded per package ([Record]), every install path
 * ([StoreStages.install], Update all, the auto chain) refuses that package while
 * the OLD build is still installed ([blocks]), and the row shows [MigrationCard]:
 *   1. back up the home (the terminal tars its own $HOME to the declared path),
 *   2. uninstall the old build (ACTION_DELETE),
 *   3. install the new one (the normal Install, live again once (2) is done),
 *   4. open the app → Restore (its SharedStoreMigration restores and deletes the archive).
 *
 * DECLARED, not hard-coded: aa_cloud-superapp/data/fleet-identity.json, the group's
 * `migration: shared_uid` + `migration_plan` (backup paths read from
 * ac_cloud-termux/build.json, resolved by the libs:appstore bake). cloud-drive's
 * step 2 joins the group and reuses it unchanged.
 */
object SharedUidMigration {
    private const val TAG = "SharedUidMigration"
    const val STAGE = "migrate"
    const val KIND = "shared_uid"
    private const val PREFS = "store_migration"

    const val TAG_CARD = "store-migrate:"
    const val TAG_BACKUP = "store-migrate:backup:"
    const val TAG_UNINSTALL = "store-migrate:uninstall:"
    const val TAG_INSTALL = "store-migrate:install:"
    const val TAG_OPEN = "store-migrate:open:"
    const val TAG_DONE = "store-migrate:done:"

    // ── 1. detection ─────────────────────────────────────────────────────

    /** The installer results that mean "the uid changed": no retry will ever land. */
    enum class Cause(val legacy: Int, val constant: String) {
        UID_CHANGED(-24, "INSTALL_FAILED_UID_CHANGED"),
        SHARED_USER_INCOMPATIBLE(-8, "INSTALL_FAILED_SHARED_USER_INCOMPATIBLE"),
        UPDATE_INCOMPATIBLE(-7, "INSTALL_FAILED_UPDATE_INCOMPATIBLE"),
    }

    /** Pure. [legacy] = EXTRA_LEGACY_STATUS (Int.MIN_VALUE when absent), [message] = the status message or the row's note. */
    fun cause(legacy: Int, message: String?): Cause? =
        Cause.values().firstOrNull { it.legacy == legacy } ?: cause(message)

    /** Pure: the same answer read from a note's text ("… shared user changed … code: legacy=-24"). */
    fun cause(message: String?): Cause? {
        val m = message ?: return null
        Cause.values().firstOrNull { m.contains(it.constant) }?.let { return it }
        Regex("legacy=(-?\\d+)").find(m)?.groupValues?.get(1)?.toIntOrNull()
            ?.let { code -> Cause.values().firstOrNull { it.legacy == code } }?.let { return it }
        return if (m.contains("shared user changed", ignoreCase = true)) Cause.UID_CHANGED else null
    }

    // ── 2. the declared plan ─────────────────────────────────────────────

    /** fleet-identity.json `migration_plan`: where the backup lives, and the order the apps go in. */
    class Plan(val kind: String, val sharedRoot: String, val backupDir: String, val archive: String, val order: List<String>) {
        /** <storage>/<shared_root>/<backup_dir>/<pkg>/<archive> — the path the terminal's restore reads. */
        fun backupPath(pkg: String, storageRoot: String = defaultStorageRoot()): String =
            "${storageRoot.trimEnd('/')}/$sharedRoot/$backupDir/$pkg/$archive"
    }

    fun parsePlan(o: JSONObject?): Plan? {
        o ?: return null
        val root = o.optString("shared_root").takeIf { it.isNotEmpty() && it != "null" } ?: return null
        val order = o.optJSONArray("order")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
        return Plan(o.optString("kind", KIND), root, o.optString("backup_dir", ".cloud-migrate"),
            o.optString("archive", "home.tar.gz"), order)
    }

    /** The plan for [pkg]: its own group's, else the first declared one (a package migrating before it joins a group). */
    fun planFor(reg: FleetIdentity.Registry, pkg: String): Plan? =
        (reg.groups.firstOrNull { pkg in it.members && it.migration == KIND }?.plan
            ?: reg.groups.firstNotNullOfOrNull { g -> g.plan.takeIf { g.migration == KIND } })

    /** [pkg] is a member of a group fleet-identity.json declares `migration: shared_uid` for. */
    fun inMigrationGroup(reg: FleetIdentity.Registry, pkg: String): Boolean =
        reg.groups.any { pkg in it.members && it.migration == KIND }

    /**
     * Pure: does [cause] start the migration for [pkg]? UID_CHANGED and SHARED_USER_INCOMPATIBLE
     * always do (only a uid change raises them). UPDATE_INCOMPATIBLE (-7) is ALSO a plain signing-key
     * mismatch, so it migrates only a package in a declared migration group; anywhere else it is the
     * row's ordinary "signature mismatch" error.
     */
    fun migrates(cause: Cause?, inGroup: Boolean): Boolean = when (cause) {
        null -> false
        Cause.UPDATE_INCOMPATIBLE -> inGroup
        else -> true
    }

    fun migrates(cause: Cause?, pkg: String): Boolean =
        migrates(cause, baked()?.let { inMigrationGroup(it, pkg) } == true)

    private fun baked(): FleetIdentity.Registry? =
        bakedPlans ?: runCatching { FleetIdentity.baked() }.getOrNull()?.also { bakedPlans = it }

    @Volatile private var bakedPlans: FleetIdentity.Registry? = null
    fun plan(pkg: String): Plan? = planFor(baked() ?: return null, pkg)

    fun defaultStorageRoot(): String =
        runCatching { Environment.getExternalStorageDirectory().path }.getOrNull() ?: "/storage/emulated/0"

    // ── 3. state (device prefs, per package) ─────────────────────────────

    /** One migration in progress: what failed, against which installed build ([before], StoreStages.installedKey). */
    class Record(val pkg: String, val cause: Cause, val version: String, val before: String, val since: Long,
                 val done: Boolean = false, val backupStartedAt: Long = 0L) {
        fun copy(done: Boolean = this.done, backupStartedAt: Long = this.backupStartedAt) =
            Record(pkg, cause, version, before, since, done, backupStartedAt)
    }

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun record(ctx: Context, pkg: String): Record? = runCatching {
        val o = JSONObject(prefs(ctx).getString(pkg, null)!!)
        Record(pkg, Cause.valueOf(o.getString("cause")), o.optString("version"), o.getString("before"),
            o.getLong("since"), o.optBoolean("done"), o.optLong("backupStartedAt"))
    }.getOrNull()

    fun save(ctx: Context, r: Record) {
        prefs(ctx).edit().putString(r.pkg, JSONObject().put("cause", r.cause.name).put("version", r.version)
            .put("before", r.before).put("since", r.since).put("done", r.done)
            .put("backupStartedAt", r.backupStartedAt).toString()).commit()
    }

    fun dismiss(ctx: Context, pkg: String) {
        record(ctx, pkg)?.let { save(ctx, it.copy(done = true)) }
    }

    fun backupStarted(ctx: Context, pkg: String, now: Long = System.currentTimeMillis()) {
        record(ctx, pkg)?.let { save(ctx, it.copy(backupStartedAt = now)) }
    }

    /** Pure: while the build that refused the update is still installed, no install may run. */
    fun blocks(r: Record?, installedNow: String): Boolean =
        r != null && !r.done && installedNow.isNotEmpty() && installedNow == r.before

    /** [pkg]'s migration has not got past its uninstall: the old build is still the installed one. */
    fun stillOld(ctx: Context, pkg: String): Boolean {
        val r = record(ctx, pkg) ?: return false
        val now = runCatching {
            @Suppress("DEPRECATION") val pi = ctx.packageManager.getPackageInfo(pkg, 0)
            val code = if (android.os.Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
            "$code/${pi.lastUpdateTime}"   // StoreStages.installedKey's shape
        }.getOrDefault("")
        return blocks(r, now)
    }

    fun blocks(ctx: Context, app: Fleet.App): Boolean =
        blocks(record(ctx, app.pkg), StoreStages.installedKey(ctx, app))

    /**
     * Read the row's install note: the first uid-change failure for this package
     * becomes a [Record] (once — a second identical failure changes nothing). Once
     * the old build is gone the note is history and is cleared, so the row offers
     * a plain Install (step 3). Returns the live record, if any.
     */
    fun observe(ctx: Context, app: Fleet.App): Record? {
        val installed = StoreStages.installedKey(ctx, app)
        var r = record(ctx, app.pkg)
        val note = ApkCache.noteOf(ctx, app.pkg)?.takeIf { it.stage == ApkCache.STAGE_INSTALL }
        val c = cause(note?.message)?.takeIf { migrates(it, app.pkg) }
        if (c != null && installed.isNotEmpty() && (r == null || r.before != installed)) {
            val ver = StoreStages.cachedFor(ctx, app)?.record?.versionCode?.let { "v$it" } ?: ""
            r = Record(app.pkg, c, ver, installed, System.currentTimeMillis())
            save(ctx, r)
            Log.i(TAG, "${app.pkg}: ${c.constant} — install retries stop; the row shows the migration")
        }
        if (r != null && c != null && installed != r.before) ApkCache.clearNote(ctx, app.pkg)
        return r?.takeIf { !it.done }
    }

    // ── 4. the steps ─────────────────────────────────────────────────────

    enum class Step { BACKUP, UNINSTALL, INSTALL, RESTORE }

    /**
     * What is on shared storage for this package: complete archive, or one still being
     * written. [unverified] = this app cannot read shared storage (Cloud Store holds no
     * storage permission), so all it knows is that the backup was started at [modifiedAt].
     */
    class Backup(val bytes: Long, val modifiedAt: Long, val inProgress: Boolean, val unverified: Boolean = false)

    fun backupOf(path: String, startedAt: Long = 0L): Backup? {
        val f = File(path)
        val readable = runCatching {
            generateSequence(f.parentFile) { it.parentFile }.firstOrNull { it.exists() }?.list() != null
        }.getOrDefault(false)
        if (!readable && !f.isFile) return if (startedAt > 0) Backup(0L, startedAt, false, unverified = true) else null
        if (f.isFile) return Backup(f.length(), f.lastModified(), false)
        val part = File("$path.part")
        return if (part.isFile) Backup(part.length(), part.lastModified(), true) else null
    }

    /** Pure: the step the user is on. [installedNow] "" = not installed. */
    fun current(r: Record, installedNow: String, backup: Backup?): Step = when {
        installedNow.isEmpty() -> Step.INSTALL
        installedNow != r.before -> Step.RESTORE
        backup == null || backup.inProgress -> Step.BACKUP
        else -> Step.UNINSTALL
    }

    /** Pure: the package in [order] before [pkg] that still has to go first (termux owns the store), or null. */
    fun waitsFor(order: List<String>, pkg: String, pendingBefore: (String) -> Boolean): String? {
        val i = order.indexOf(pkg)
        if (i <= 0) return null
        return order.subList(0, i).firstOrNull(pendingBefore)
    }

    /**
     * The shell line the OLD build runs, as itself, to tar its own home: written to
     * <archive>.part and renamed only when tar succeeded, so a half-written file is
     * never taken for a backup. Run from $HOME (CLOUD_EXEC's default workdir / RUN_COMMAND_WORKDIR).
     */
    fun backupScript(path: String): String {
        val dir = path.substringBeforeLast('/')
        return "H=\"\${HOME:-\$PWD}\"; mkdir -p ${q(dir)} && cd \"\$H\" && " +
            "tar -czf ${q("$path.part")} . && mv -f ${q("$path.part")} ${q(path)}"
    }

    /** The same, detached, so a large home outlives the binder call that started it. */
    fun detached(script: String): String = "( nohup sh -c ${q(script)} >/dev/null 2>&1 & )"

    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

    /** /data/data/<pkg>/files/{usr,home}: the Termux layout both terminals keep. */
    fun prefixOf(pkg: String) = "/data/data/$pkg/files/usr"
    fun homeOf(pkg: String) = "/data/data/$pkg/files/home"

    /** CLOUD_EXEC (ICloudExec.exec): exe, args, stdin, workdir, timeout — the call the store makes. */
    class ExecCall(val action: String, val executable: String, val args: List<String>, val workdir: String, val timeoutMs: Int)

    fun execCall(pkg: String, path: String): ExecCall =
        ExecCall("$pkg.CLOUD_EXEC", "${prefixOf(pkg)}/bin/sh", listOf("-c", detached(backupScript(path))), homeOf(pkg), 30_000)

    /** Termux's RUN_COMMAND intent (the fallback: a terminal without CLOUD_EXEC). */
    fun runCommandIntent(pkg: String, path: String): Intent = Intent("$pkg.RUN_COMMAND").setPackage(pkg)
        .putExtra("$pkg.RUN_COMMAND_PATH", "${prefixOf(pkg)}/bin/sh")
        .putExtra("$pkg.RUN_COMMAND_ARGUMENTS", arrayOf("-c", backupScript(path)))
        .putExtra("$pkg.RUN_COMMAND_WORKDIR", homeOf(pkg))
        .putExtra("$pkg.RUN_COMMAND_BACKGROUND", true)

    /**
     * Step 1. CLOUD_EXEC first (signature-level, no prompt); then RUN_COMMAND; then,
     * for an app that is not a terminal (cloud-drive backs itself up on launch), its
     * launcher. Returns what happened, for the toast. Blocking: off the main thread.
     */
    fun startBackup(ctx: Context, pkg: String, path: String): String {
        val call = execCall(pkg, path)
        runCatching { if (cloudExec(ctx, call)) return "backup started in $pkg (CLOUD_EXEC)" }
            .onFailure { Log.w(TAG, "CLOUD_EXEC $pkg: ${it.message}") }
        runCatching { if (ctx.startService(runCommandIntent(pkg, path)) != null) return "backup started in $pkg (RUN_COMMAND)" }
            .onFailure { Log.w(TAG, "RUN_COMMAND $pkg: ${it.message}") }
        ctx.packageManager.getLaunchIntentForPackage(pkg)?.let {
            runCatching { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return "opened $pkg — it backs up on launch" }
        }
        return "could not reach $pkg — run in it: " + backupScript(path)
    }

    /** ICloudExec.exec through libs:shared-store's generated interface (the terminal's own AIDL). */
    private fun cloudExec(ctx: Context, call: ExecCall): Boolean {
        val latch = java.util.concurrent.CountDownLatch(1)
        var svc: ICloudExec? = null
        val conn = object : ServiceConnection {
            override fun onServiceConnected(n: ComponentName?, b: IBinder?) { svc = ICloudExec.Stub.asInterface(b); latch.countDown() }
            override fun onServiceDisconnected(n: ComponentName?) { svc = null }
        }
        val pkg = call.action.removeSuffix(".CLOUD_EXEC")
        if (!ctx.bindService(Intent(call.action).setPackage(pkg), conn, Context.BIND_AUTO_CREATE)) return false
        try {
            if (!latch.await(10, java.util.concurrent.TimeUnit.SECONDS)) return false
            val res = (svc ?: return false).exec(call.executable, call.args.toTypedArray(), null, call.workdir, call.timeoutMs)
            return res?.getString("error") == null
        } finally { runCatching { ctx.unbindService(conn) } }
    }

    /** Step 2: Android's own uninstall confirmation. */
    fun uninstallIntent(pkg: String): Intent =
        @Suppress("DEPRECATION") Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun human(b: Long) = com.diegonmarcos.superapp.updater.FleetIdentity.human(b)
    private fun time(t: Long) = java.text.DateFormat.getDateTimeInstance().format(java.util.Date(t))

    /** Pure: the four steps as the dense stepper draws them. */
    fun steps(
        pkg: String, label: String, path: String, step: Step, backup: Backup?, waitsFor: String?,
        onBackup: () -> Unit, onUninstall: () -> Unit, onInstall: () -> Unit, onOpen: () -> Unit,
    ): List<KitStep> {
        fun st(s: Step) = when {
            s.ordinal < step.ordinal -> KitStepState.DONE
            s == step -> KitStepState.RUNNING
            else -> KitStepState.TODO
        }
        val backupDetail = when {
            backup == null -> "Not backed up yet. Tap Back up: $label tars its home to $path"
            backup.inProgress -> "Writing… ${human(backup.bytes)} so far"
            backup.unverified -> "Started ${time(backup.modifiedAt)} — this app cannot read shared storage; check $path in $label before uninstalling"
            else -> "Backed up · ${human(backup.bytes)} · ${time(backup.modifiedAt)} · $path"
        }
        val blocked = waitsFor != null
        return listOf(
            KitStep("backup", "1. Back up the home", backupDetail, st(Step.BACKUP),
                KitAction(if (backup == null) "Back up" else "Again", TAG_BACKUP + pkg, step == Step.BACKUP || step == Step.UNINSTALL, onBackup),
                tag = TAG_BACKUP + pkg + ":step"),
            KitStep("uninstall", "2. Uninstall old version",
                if (blocked) "Finish $waitsFor first — it owns the shared store" else "Android removes the old build and its data",
                st(Step.UNINSTALL), KitAction("Uninstall", TAG_UNINSTALL + pkg, step == Step.UNINSTALL && !blocked, onUninstall),
                tag = TAG_UNINSTALL + pkg + ":step"),
            KitStep("install", "3. Install the new version", "From the cached APK, as a fresh install",
                st(Step.INSTALL), KitAction("Install", TAG_INSTALL + pkg, step == Step.INSTALL, onInstall),
                tag = TAG_INSTALL + pkg + ":step"),
            KitStep("restore", "4. Open app → Restore", "The app offers the restore from the backup on first launch",
                st(Step.RESTORE), KitAction("Open", TAG_OPEN + pkg, step == Step.RESTORE, onOpen),
                tag = TAG_OPEN + pkg + ":step"),
        )
    }
}

/**
 * The one-time migration card on a Store row ([SharedUidMigration]). Pure UI: the
 * caller passes the record, what the device says, and the four actions.
 */
@Composable
fun SharedUidMigrationCard(
    pkg: String, label: String, record: SharedUidMigration.Record, path: String, installedNow: String,
    backup: SharedUidMigration.Backup?, waitsFor: String?,
    onBackup: () -> Unit, onUninstall: () -> Unit, onInstall: () -> Unit, onOpen: () -> Unit, onDone: () -> Unit,
) {
    val p = LocalKitPalette.current
    val step = SharedUidMigration.current(record, installedNow, backup)
    Column(Modifier.fillMaxWidth().testTag(SharedUidMigration.TAG_CARD + pkg), verticalArrangement = Arrangement.spacedBy(KitDensity.small)) {
        KitStatusBanner(
            "$label ${record.version} changes its Linux user (${record.cause.constant}). Android cannot update it in " +
                "place — install retries are stopped. Migrate once:",
            if (step == SharedUidMigration.Step.RESTORE) KitState.OK else KitState.WARN,
            tag = "migrate:$pkg",
            action = if (step == SharedUidMigration.Step.RESTORE) KitAction("Done", SharedUidMigration.TAG_DONE + pkg, true, onDone) else null,
        )
        KitStepper(SharedUidMigration.steps(pkg, label, path, step, backup, waitsFor, onBackup, onUninstall, onInstall, onOpen))
        if (waitsFor != null) Text("Order: $waitsFor before $pkg", color = p.textSecondary, fontSize = KitDensity.caption)
    }
}
