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
 * - PROVEN, by hashing: the file on disk is byte-for-byte the artifact that was
 *   downloaded and identified ([Record.sha256], recorded at download time).
 * - PROVEN, by PackageManager: the installed package has the SAME package name,
 *   the SAME versionCode and the SAME signing certificate as that artifact's own
 *   manifest.
 * - NOT PROVEN, and deliberately not claimed: that the installed APK is
 *   byte-identical to the cached one. An app cannot read another package's
 *   base.apk without root, so nothing here can hash it. A different build that
 *   shared the package name, versionCode AND signing certificate would satisfy
 *   the checks above — for THIS fleet that means a rebuild of the same
 *   version_code, which the ship engine's wall-clock version_code makes
 *   vanishingly unlikely, but it is a heuristic and it is named as one.
 *
 * Everything short of that keeps the file. A declined dialog, a failure, a
 * missing record, a file that no longer hashes to what was downloaded: all of
 * them are [Retention.Kept], with the reason, because the cost of keeping bytes
 * is disk and the cost of dropping them is the whole download again.
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
        evict(ctx)
        return rec
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
     * Delete [apk] IF AND ONLY IF the cached artifact is provably the thing now
     * installed. See the class header for exactly what "provably" covers.
     *
     * Best-effort about deleting, strict about deciding: a failed delete costs
     * disk, a wrong decision costs the user a download.
     */
    fun reapIfInstalled(ctx: Context, apk: File): Retention {
        if (!isOurs(ctx, apk))
            return Retention.Kept("${apk.name} is not a file this cache owns — nothing touched")
        val rec = record(apk)
            ?: return Retention.Kept("${apk.name} has no download record, so nothing can be " +
                "compared against what is installed — kept")
        val onDisk = runCatching { ApkIntegrity.sha256(apk) }.getOrNull()
        if (onDisk != rec.sha256)
            return Retention.Kept("${apk.name} no longer hashes to the artifact that was " +
                "downloaded (${onDisk?.take(12) ?: "unreadable"}… vs ${rec.sha256.take(12)}…) — " +
                "kept, and NOT treated as installed")
        val installed = installedInfo(ctx, rec.pkg)
            ?: return Retention.Kept("${rec.pkg} is not installed on this device, whatever the " +
                "installer reported — ${apk.name} kept for the retry")
        val code = versionCodeOf(installed)
        if (code != rec.versionCode)
            return Retention.Kept("${rec.pkg} is installed at versionCode $code, not the cached " +
                "${rec.versionCode} — something else installed it, so ${apk.name} is kept")
        val installedCert = certOf(installed)
        if (rec.cert != null && installedCert != null && rec.cert != installedCert)
            return Retention.Kept("${rec.pkg} at versionCode $code is signed by a DIFFERENT " +
                "certificate than the cached artifact — ${apk.name} kept, and this is worth " +
                "looking at")
        val proof = if (rec.cert != null && installedCert != null)
            "package, versionCode and signing certificate all match"
        else
            "package and versionCode match (no signing certificate available on this API level " +
                "to compare — weaker evidence, said out loud)"
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

    /** The user's own "clear cache". Deletes everything, reports what went. */
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
