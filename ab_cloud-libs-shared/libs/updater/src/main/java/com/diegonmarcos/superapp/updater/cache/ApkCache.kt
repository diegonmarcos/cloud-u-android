package com.diegonmarcos.superapp.updater.cache

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.diegonmarcos.superapp.updater.BuildConfig
import com.diegonmarcos.superapp.updater.apk.ApkIntegrity
import java.io.File
import java.security.MessageDigest

/**
 * #625 — THE APK CACHE. Where a downloaded APK lives, and the ONE rule that may
 * delete it.
 *
 * ## Why this file exists: the cache was `cacheDir`
 *
 * Every download in this module and in libs:appstore landed in
 * `context.cacheDir`. That directory is, by the platform's own contract,
 * RECLAIMABLE: Android deletes from it under storage pressure without asking
 * anyone, and Settings ▸ Storage ▸ Clear cache wipes it outright. A 267 MB
 * artifact therefore really did disappear between "downloaded" and "installed",
 * repeatedly — which is indistinguishable, from outside, from the store
 * deleting its own download on purpose, and is exactly how it was reported.
 *
 * There was no delete-on-failure bug to find. The deleting process was the
 * operating system, doing precisely what `cacheDir` means. So the fix is not a
 * guard on a call site; it is moving the bytes somewhere the OS will not
 * reclaim and making THIS object the only thing that removes them.
 *
 * [dir] is `noBackupFilesDir/<declared>`: app-private, persistent, and outside
 * the backup set (uploading a fleet of APKs to a cloud backup is not a thing
 * anyone asked for). The name, the size bound and the eviction policy are
 * declared in `build.json::release.apk_cache` and reach here through
 * BuildConfig — there is no constant in this file to disagree with them.
 *
 * ## Retention: what "properly installed" is allowed to mean
 *
 * The previous rule was "the PackageInstaller broadcast said STATUS_SUCCESS, so
 * delete". That is a statement about a message, not about the device. This one
 * asks the device, and it is careful about what it can actually prove:
 *
 * - PROVEN, by hashing the file on disk: it is byte-for-byte the artifact that
 *   was downloaded and identified ([Record.sha256], recorded at download time).
 * - PROVEN, by hashing the INSTALLED PACKAGE: [installedSha256] hashes the APK
 *   Android is actually running, reached through `ApplicationInfo.sourceDir`,
 *   and that digest must equal the cached artifact's. This is THE rule. An
 *   earlier revision of this file claimed "an app cannot read another package's
 *   base.apk without root, so nothing here can hash it" and settled for
 *   versionCode + signing certificate instead. That claim is wrong: `/data/app`
 *   denies LISTING but still permits traversal to a path the platform itself
 *   handed us, and `base.apk` is world-readable — which is how every APK
 *   extractor on the phone works unrooted. The heuristic it justified is also
 *   too weak to act on: a REBUILT same-version APK has a different digest and
 *   an identical versionCode and certificate, and #517/#518 are a whole history
 *   of versionCode confusion in this fleet.
 * - FALLBACK, named as one: when [installedSha256] cannot read those bytes (a
 *   split-APK install, a path that will not open), the old versionCode +
 *   certificate comparison decides, and the reason says out loud that the
 *   evidence is weaker.
 *
 * The digest is read from the DEVICE, never from [Record.sha256] on both sides
 * — comparing the cache against itself would pass unconditionally, which is the
 * hollow shape of this check and the one the guard mutates for.
 *
 * Everything short of that keeps the file. A declined dialog, a failure, a
 * missing record, a file that no longer hashes to what was downloaded: all of
 * them are [Retention.Kept], with the reason, because the cost of keeping bytes
 * is disk and the cost of dropping them is the whole download again. Note which
 * way the fallbacks fail: every "cannot tell" ends in a keep, so even if the
 * platform someday stops handing over those bytes, the cost is disk, not a
 * 267 MB download.
 */
object ApkCache {

    private const val TAG = "Updater/ApkCache"

    /** Sidecar suffix. One line per fact, so reading it cannot fail halfway
     *  into a half-parsed record the way a JSON blob can. */
    private const val RECORD_SUFFIX = ".record"

    /** The module's own segment under no_backup. Structural, not policy: the
     *  DECLARED name ([BuildConfig.APK_CACHE_DIR]) lives inside it, so
     *  updater_apk_paths.xml can export this parent generically and the
     *  declaration stays the only place the cache is named. */
    private const val OWNER_SEGMENT = "updater"

    /** The cache directory, created on demand.
     *
     *  NOT cacheDir. See the class header — cacheDir is the bug. */
    fun dir(ctx: Context): File =
        File(File(ctx.noBackupFilesDir, OWNER_SEGMENT), BuildConfig.APK_CACHE_DIR).apply { mkdirs() }

    /** A cache slot named [name]. Callers name artifacts; this owns WHERE. */
    fun file(ctx: Context, name: String): File = File(dir(ctx), name)

    /**
     * Is [f] a file THIS cache owns?
     *
     * The install-result receiver deletes by a path that arrived in a broadcast
     * extra, so it must never delete something merely because it was named.
     * This is the check that used to read `f.parentFile != context.cacheDir`,
     * moved here so it follows the directory instead of silently ceasing to
     * match it — a comparison left pointing at cacheDir would have made every
     * reap a no-op, and a reap that stops happening has no symptom at all
     * beyond a cache that grows forever.
     */
    fun isOurs(ctx: Context, f: File): Boolean = runCatching {
        f.isFile && f.canonicalFile.parentFile == dir(ctx).canonicalFile
    }.getOrDefault(false)

    // ── the download record ──────────────────────────────────────────────

    /** What was downloaded, written beside it the moment it verified. */
    class Record(val pkg: String, val versionCode: Long, val sha256: String, val cert: String?)

    /**
     * Record [apk]'s identity and digest, and bring the cache back under its
     * declared bound. Returns the record, or null when the file will not parse
     * as an APK (in which case nothing is deleted — an unidentifiable file is
     * kept and reported, never silently dropped).
     *
     * Called from the two download chokepoints, so every artifact that reaches
     * an installer has a record and every retention decision below has
     * something to compare against.
     */
    fun keep(ctx: Context, apk: File): Record? {
        if (!isOurs(ctx, apk)) {
            Log.w(TAG, "not recording ${apk.absolutePath}: outside ${dir(ctx)}")
            return null
        }
        val id = ApkIntegrity.identify(ctx, apk)
        if (id == null) {
            Log.w(TAG, "no record for ${apk.name}: its manifest will not parse, so nothing " +
                "here can ever prove it was installed — it is kept, not reaped")
            return null
        }
        val rec = Record(id.pkg, id.versionCode, ApkIntegrity.sha256(apk), archiveCert(ctx, apk))
        runCatching {
            recordFile(apk).writeText(
                listOf(rec.pkg, rec.versionCode.toString(), rec.sha256, rec.cert ?: "").joinToString("\n"))
        }.onFailure { Log.w(TAG, "could not write the record for ${apk.name}: ${it.message}") }
        Log.i(TAG, "cached ${apk.name}: ${rec.pkg} versionCode ${rec.versionCode} " +
            "sha256 ${rec.sha256.take(12)}… (${apk.length()} B)")
        pruneStale(ctx, rec.pkg, rec.versionCode, except = apk)
        evict(ctx)
        return rec
    }

    /**
     * #774 Drop every cached build of [pkg] OLDER than [versionCode] — called
     * when a newer one is cached ([keep]) or installed (the receiver's success).
     * An older build of the same package can never be installed over a newer
     * one, so it is pure disk; the cache is keyed pkg + versionCode + sha256 and
     * this is the "stale" half of that key. Never touches [except], never a
     * file without a record (nothing can prove what it is).
     */
    fun pruneStale(ctx: Context, pkg: String, versionCode: Long, except: File? = null): List<String> =
        entries(ctx).filter { e ->
            e.file != except && e.record != null && e.record.pkg == pkg && e.record.versionCode < versionCode
        }.mapNotNull { e ->
            drop(e.file)
            if (e.file.exists()) null else e.file.name.also {
                Log.i(TAG, "pruned stale $it: $pkg versionCode ${e.record?.versionCode} < $versionCode")
            }
        }

    // ── #774 the last stage outcome, per package ─────────────────────────

    /** Stage names a note can carry — the Store row's own vocabulary. */
    const val STAGE_DOWNLOAD = "download"
    const val STAGE_INSTALL = "install"

    /** Why the last Download or Install of [pkg] did not finish. Persisted, so
     *  a cancel from the system install sheet still reads as "cancelled" on the
     *  row after the process is gone — the receiver that learns it outlives
     *  nothing else. Never a URL or a token: callers pass their own sentence. */
    class Note(val stage: String, val message: String, val at: Long)

    private fun notes(ctx: Context) =
        ctx.getSharedPreferences("updater_apk_cache_notes", Context.MODE_PRIVATE)

    fun note(ctx: Context, pkg: String, stage: String, message: String) {
        if (pkg.isBlank()) return
        notes(ctx).edit().putString(pkg, "$stage\n${System.currentTimeMillis()}\n$message").apply()
    }

    fun noteOf(ctx: Context, pkg: String): Note? = runCatching {
        val parts = notes(ctx).getString(pkg, null)?.split('\n', limit = 3) ?: return null
        Note(parts[0], parts[2], parts[1].toLong())
    }.getOrNull()

    fun clearNote(ctx: Context, pkg: String) {
        if (pkg.isNotBlank()) notes(ctx).edit().remove(pkg).apply()
    }

    /** The record written by [keep], or null when there is none. */
    fun record(apk: File): Record? = runCatching {
        val lines = recordFile(apk).readLines()
        if (lines.size < 3) return null
        Record(lines[0], lines[1].toLong(), lines[2], lines.getOrNull(3)?.ifBlank { null })
    }.getOrNull()

    /** Drop [apk] and its record. For bytes already KNOWN BAD (a failed digest,
     *  a stale downgrade, the wrong package) — the callers that already deleted
     *  those, now also dropping the sidecar so it cannot outlive its file. */
    fun drop(apk: File) {
        apk.delete()
        recordFile(apk).delete()
        File(apk.parentFile, apk.name + ".part").delete()
    }

    private fun recordFile(apk: File) = File(apk.parentFile, apk.name + RECORD_SUFFIX)

    // ── retention ────────────────────────────────────────────────────────

    /** The outcome of a retention decision, with the reason either way. Never a
     *  bare Boolean: "it was not deleted" and "it was not deleted BECAUSE the
     *  install is not actually on the device" are different facts, and only one
     *  of them is worth telling somebody. */
    sealed class Retention(val reason: String) {
        class Reaped(val name: String, val freedBytes: Long, reason: String) : Retention(reason)
        class Kept(reason: String) : Retention(reason)
    }

    /**
     * The retention DECISION, taken without touching a byte. [Decision.proof]
     * non-null means the install is proven and the file is free to go; null
     * means keep, and [Decision.reason] says why either way.
     *
     * Separate from [reapIfInstalled] so the Store can tell the user what a
     * clear WOULD free before they tap it, using this exact rule rather than a
     * second, looser copy of it.
     */
    class Decision(val proof: String?, val reason: String)

    fun decide(ctx: Context, apk: File): Decision {
        fun keep(why: String) = Decision(null, why)
        if (!isOurs(ctx, apk))
            return keep("${apk.name} is not a file this cache owns — nothing touched")
        val rec = record(apk)
            ?: return keep("${apk.name} has no download record, so nothing can be " +
                "compared against what is installed — kept")
        val onDisk = runCatching { ApkIntegrity.sha256(apk) }.getOrNull()
        if (onDisk != rec.sha256)
            return keep("${apk.name} no longer hashes to the artifact that was " +
                "downloaded (${onDisk?.take(12) ?: "unreadable"}… vs ${rec.sha256.take(12)}…) — " +
                "kept, and NOT treated as installed")
        val installed = installedInfo(ctx, rec.pkg)
            ?: return keep("${rec.pkg} is not installed on this device, whatever the " +
                "installer reported — ${apk.name} kept for the retry")
        val code = versionCodeOf(installed)
        if (code != rec.versionCode)
            return keep("${rec.pkg} is installed at versionCode $code, not the cached " +
                "${rec.versionCode} — something else installed it, so ${apk.name} is kept")
        // THE RULE. Hash the APK the device is actually running and compare it to
        // the artifact that was downloaded. Read from the INSTALLED PACKAGE —
        // passing rec.sha256 in here would be the cache agreeing with itself.
        val installedSha = installedSha256(ctx, rec.pkg)
        if (installedSha != null) {
            if (installedSha != rec.sha256)
                return keep("${rec.pkg} at versionCode $code is installed from DIFFERENT bytes " +
                    "than the cached artifact (installed ${installedSha.take(12)}… vs cached " +
                    "${rec.sha256.take(12)}…) — ${apk.name} kept, because a matching versionCode " +
                    "is not a matching build")
            return Decision("the installed package's own APK hashes to ${rec.sha256.take(12)}…, " +
                "byte-for-byte the artifact that was downloaded",
                "${apk.name} is redundant: ${rec.pkg} versionCode $code is installed from exactly " +
                    "these bytes")
        }
        val installedCert = certOf(installed)
        if (rec.cert != null && installedCert != null && rec.cert != installedCert)
            return keep("${rec.pkg} at versionCode $code is signed by a DIFFERENT " +
                "certificate than the cached artifact — ${apk.name} kept, and this is worth " +
                "looking at")
        val weaker = "the installed APK's own bytes could not be read (split install, or the " +
            "path would not open), so this rests on " +
            (if (rec.cert != null && installedCert != null)
                "package, versionCode and signing certificate matching"
             else "package and versionCode matching only") +
            " — weaker evidence than a digest, said out loud"
        return Decision(weaker, "${apk.name} looks redundant on weaker evidence: $weaker")
    }

    /**
     * sha256 of the APK Android is ACTUALLY running for [pkg], or null when
     * those bytes cannot be read — which is a "cannot tell", never a match.
     *
     * `ApplicationInfo.sourceDir` is the platform's own path to the installed
     * `base.apk`. `/data/app` refuses to be LISTED but still allows traversal to
     * a name you were given, and `base.apk` is world-readable, so this needs no
     * root. A split install has no single file to compare against a single
     * downloaded APK, so it returns null rather than hashing the base alone.
     */
    fun installedSha256(ctx: Context, pkg: String): String? = runCatching {
        val ai = ctx.packageManager.getApplicationInfo(pkg, 0)
        if (!ai.splitSourceDirs.isNullOrEmpty()) return null
        val f = File(ai.sourceDir ?: return null)
        if (f.isFile && f.canRead()) ApkIntegrity.sha256(f) else null
    }.getOrNull()

    /**
     * Delete [apk] IF AND ONLY IF the cached artifact is provably the thing now
     * installed. See the class header for exactly what "provably" covers.
     *
     * Best-effort about deleting, strict about deciding: a failed delete costs
     * disk, a wrong decision costs the user a download.
     */
    fun reapIfInstalled(ctx: Context, apk: File): Retention {
        val d = decide(ctx, apk)
        val proof = d.proof ?: return Retention.Kept(d.reason)
        val rec = record(apk) ?: return Retention.Kept(d.reason)
        val size = apk.length()
        drop(apk)
        val gone = !apk.exists()
        val why = "${rec.pkg} versionCode ${rec.versionCode}: $proof, and ${apk.name} still " +
            "hashed to the bytes that were downloaded"
        Log.i(TAG, (if (gone) "reaped" else "could not delete") + " ${apk.name} " +
            "(${size / 1_000_000} MB) — $why")
        return if (gone) Retention.Reaped(apk.name, size, why)
        else Retention.Kept("${apk.name} could not be deleted (filesystem said no) — $why")
    }

    // ── listing, eviction, manual clear ──────────────────────────────────

    /** One cached artifact as the Store shows it. [record] null = unverified:
     *  a `.part`, or a file whose sidecar is gone. */
    class Entry(val file: File, val record: Record?, val bytes: Long, val modifiedAt: Long) {
        val partial: Boolean get() = file.name.endsWith(".part")
    }

    fun entries(ctx: Context): List<Entry> =
        (dir(ctx).listFiles() ?: emptyArray())
            .filter { it.isFile && !it.name.endsWith(RECORD_SUFFIX) }
            .map { Entry(it, record(it), it.length(), it.lastModified()) }
            .sortedBy { it.file.name }

    fun totalBytes(ctx: Context): Long = entries(ctx).sumOf { it.bytes }

    /** What one eviction pass did. [unverified] is the part that must never be
     *  silent: files nobody could vouch for, deleted only because the bound
     *  left no choice. */
    class Eviction(val freedBytes: Long, val deleted: List<String>, val unverified: List<String>)

    /**
     * Bring the cache under [BuildConfig.APK_CACHE_MAX_BYTES], by the declared
     * [BuildConfig.APK_CACHE_EVICT] policy.
     *
     * `superseded-then-oldest`: an entry whose package also has a NEWER cached
     * build goes first — it can never be installed again, so it is free to
     * lose. Then oldest by mtime. UNVERIFIED entries (no record, or a `.part`)
     * are LAST, not first: a partial download is the one thing resume exists to
     * protect, and dropping it is the behaviour this ticket is about. When the
     * bound still forces one out, it is named in the log and in the returned
     * [Eviction.unverified] — a cache may delete to stay inside its bound, but
     * it may not do it quietly.
     */
    fun evict(ctx: Context): Eviction {
        val max = BuildConfig.APK_CACHE_MAX_BYTES
        var total = totalBytes(ctx)
        if (total <= max) return Eviction(0, emptyList(), emptyList())
        val all = entries(ctx)
        val newestByPkg = all.mapNotNull { it.record }
            .groupBy { it.pkg }.mapValues { (_, v) -> v.maxOf { it.versionCode } }
        fun superseded(e: Entry) =
            e.record != null && (newestByPkg[e.record.pkg] ?: 0L) > e.record.versionCode
        val order = when (BuildConfig.APK_CACHE_EVICT) {
            "superseded-then-oldest" -> all.sortedWith(
                compareBy({ if (it.record == null) 2 else if (superseded(it)) 0 else 1 },
                          { it.modifiedAt }))
            // An unrecognised policy is not a reason to do nothing (the bound
            // is real) and not a reason to invent one silently either.
            else -> {
                Log.w(TAG, "unknown eviction policy '${BuildConfig.APK_CACHE_EVICT}' declared in " +
                    "build.json::release.apk_cache — falling back to oldest-first and saying so")
                all.sortedBy { it.modifiedAt }
            }
        }
        val freed = ArrayList<String>()
        val unverified = ArrayList<String>()
        var bytes = 0L
        for (e in order) {
            if (total <= max) break
            val why = if (e.record == null) "UNVERIFIED (no download record)"
                      else if (superseded(e)) "superseded by a newer cached build of ${e.record.pkg}"
                      else "oldest"
            val size = e.bytes
            drop(e.file)
            if (e.file.exists()) continue
            total -= size; bytes += size
            freed += e.file.name
            if (e.record == null) unverified += e.file.name
            Log.w(TAG, "evicted ${e.file.name} (${size / 1_000_000} MB, $why) — the cache was " +
                "over its declared ${max / 1_000_000} MB bound")
        }
        return Eviction(bytes, freed, unverified)
    }

    /**
     * What a manual clear would do, measured off the files that are there now
     * and decided by [decide] — the SAME rule the reap uses, so the button
     * cannot promise one thing and do another. [redundantBytes] is free to take;
     * [keptBytes] is the only copy of something whose install is not proven, and
     * the Store has to say so before it takes it.
     */
    class Plan(val redundant: List<Entry>, val kept: List<Entry>) {
        val redundantBytes: Long get() = redundant.sumOf { it.bytes }
        val keptBytes: Long get() = kept.sumOf { it.bytes }
    }

    fun plan(ctx: Context): Plan {
        val (redundant, kept) = entries(ctx).partition { decide(ctx, it.file).proof != null }
        return Plan(redundant, kept)
    }

    /**
     * The safe half of the manual clear: drop every cached APK whose install is
     * PROVEN, and leave the rest alone. This is what the button does first, so a
     * tap can never be the thing that loses a download — #625 must not come back
     * through the manual door.
     */
    fun clearRedundant(ctx: Context): Eviction {
        var bytes = 0L
        val names = ArrayList<String>()
        for (e in plan(ctx).redundant) {
            val size = e.bytes
            val r = reapIfInstalled(ctx, e.file)
            if (r is Retention.Reaped) { bytes += size; names += e.file.name }
            else Log.i(TAG, "left ${e.file.name} in place: ${r.reason}")
        }
        Log.i(TAG, "cleared the provably-installed half of the apk cache: " +
            "${names.size} file(s), ${bytes / 1_000_000} MB")
        return Eviction(bytes, names, emptyList())
    }

    /** The user's own "clear cache". Deletes everything, reports what went.
     *  Reachable only after [clearRedundant] has already taken the free bytes
     *  and the user has been told, in counts and megabytes, that what is left is
     *  the only copy. */
    fun clear(ctx: Context): Eviction {
        val all = entries(ctx)
        var bytes = 0L
        val names = ArrayList<String>()
        val unverified = ArrayList<String>()
        for (e in all) {
            val size = e.bytes
            drop(e.file)
            if (e.file.exists()) continue
            bytes += size; names += e.file.name
            if (e.record == null) unverified += e.file.name
        }
        Log.i(TAG, "cleared the apk cache on request: ${names.size} file(s), ${bytes / 1_000_000} MB")
        return Eviction(bytes, names, unverified)
    }

    // ── platform facts ───────────────────────────────────────────────────

    private fun installedInfo(ctx: Context, pkg: String): PackageInfo? = runCatching {
        ctx.packageManager.getPackageInfo(pkg, signingFlags())
    }.getOrNull() ?: runCatching { ctx.packageManager.getPackageInfo(pkg, 0) }.getOrNull()

    private fun versionCodeOf(pi: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pi.longVersionCode
        else @Suppress("DEPRECATION") pi.versionCode.toLong()

    private fun signingFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES
        else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES

    /** sha256 of the first signing certificate, or null when this API level or
     *  this file will not give one up. Null is "cannot tell", and the caller
     *  says so rather than reading it as a match. */
    private fun certOf(pi: PackageInfo): String? = runCatching {
        val sigs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            pi.signingInfo?.apkContentsSigners
        else @Suppress("DEPRECATION") pi.signatures
        sigs?.firstOrNull()?.toByteArray()?.let { hex(MessageDigest.getInstance("SHA-256").digest(it)) }
    }.getOrNull()

    private fun archiveCert(ctx: Context, apk: File): String? = runCatching {
        @Suppress("DEPRECATION")
        ctx.packageManager.getPackageArchiveInfo(apk.absolutePath, signingFlags())?.let { certOf(it) }
    }.getOrNull()

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
}
