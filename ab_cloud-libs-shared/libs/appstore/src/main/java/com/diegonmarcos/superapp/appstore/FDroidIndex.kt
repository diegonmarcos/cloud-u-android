package com.diegonmarcos.superapp.appstore

import android.content.Context
import android.os.Build
import android.util.JsonReader
import com.diegonmarcos.superapp.updater.UpdateProgress
import com.diegonmarcos.superapp.updater.apk.VerifiedApk
import com.diegonmarcos.superapp.updater.source.Download
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.security.MessageDigest
import java.util.jar.JarFile

/**
 * #571 — the official F-Droid repo as an install source, the honest way:
 *
 *  1. `index-v1.jar` is downloaded through the fleet's [Download] and opened
 *     as a VERIFYING [JarFile]. Its `index-v1.json` entry is read to the end
 *     (a JAR's signatures are only checked once an entry is fully read) and
 *     its signer certificate's SHA-256 must equal the `cert_sha256` the
 *     declaration pins. An unsigned entry, or one signed by anyone else,
 *     throws and deletes the jar. Nothing below runs on unverified bytes.
 *  2. The index is STREAMED with [JsonReader] — 14 MB of JSON parsed into an
 *     object tree is an OOM on a phone — for exactly two facts: the app's
 *     `suggestedVersionCode`, and its `packages[pkg]` versions with their
 *     `hash` (sha256), `apkName` and `nativecode` ABIs.
 *  3. The suggested version's APK for this device's ABI is downloaded and
 *     verified by [VerifiedApk.byDigest] against the signed index's hash.
 *
 * [suggested] is the badge probe only: the unsigned `api/v1/packages` answer,
 * cheap, and never used to choose bytes.
 */
object FDroidIndex {

    class Version(val apkName: String, val sha256: String, val versionCode: Long, val versionName: String, val nativecode: List<String>)
    class Entry(val suggestedVersionCode: Long, val versions: List<Version>)

    /** (versionName, versionCode) the api suggests, or null when the api has no such package. */
    fun suggested(cfg: JSONObject, pkg: String): Pair<String?, Long?>? {
        val o = SourceResolver.getJson(cfg.getString("api").replace("{pkg}", pkg)) ?: return null
        val code = o.optString("suggestedVersionCode").toLongOrNull() ?: return null
        val arr = o.optJSONArray("packages")
        val name = (0 until (arr?.length() ?: 0)).map { arr!!.getJSONObject(it) }
            .firstOrNull { it.optString("versionCode").toLongOrNull() == code }?.optString("versionName")
        return name to code
    }

    /** Blocking: the suggested version of [pkg] for this device, verified against the signed index. */
    fun fetch(ctx: Context, cfg: JSONObject, pkg: String): VerifiedApk {
        val repo = cfg.getString("repo")
        val index = verifiedIndex(ctx, repo + cfg.getString("index"), cfg.getString("index_entry"), cfg.getString("cert_sha256"))
        val entry = lookup(index.inputStream(), pkg) ?: error("$pkg is not in the F-Droid index")
        val abis = Build.SUPPORTED_ABIS.toList()
        val v = entry.versions.filter { it.versionCode == entry.suggestedVersionCode }
            .firstOrNull { it.nativecode.isEmpty() || it.nativecode.any { a -> a in abis } }
            ?: error("$pkg: suggested versionCode ${entry.suggestedVersionCode} has no APK for ${abis.joinToString()}")
        val target = File(ctx.cacheDir, "external-$pkg.apk")
        UpdateProgress.update(UpdateProgress.State.Downloading(0, 0L, -1L))
        Download.toFile(url = repo + v.apkName, target = target, shouldCancel = { UpdateProgress.cancelRequested }) { written, total ->
            val pct = if (total > 0) ((written * 100) / total).toInt().coerceIn(0, 100) else 0
            UpdateProgress.update(UpdateProgress.State.Downloading(pct, written, total))
        }
        return VerifiedApk.byDigest(target, v.sha256) ?: run {
            Download.discard(target)
            error("F-Droid ${v.apkName}: digest mismatch against the signed index")
        }
    }

    /** The bytes of [entryName] out of the repo's signed index, verified. */
    internal fun verifiedIndex(ctx: Context, url: String, entryName: String, certSha256: String): ByteArray {
        val jar = File(ctx.cacheDir, "fdroid-index-v1.jar")
        // ponytail: the jar is reused for a day; the signature is re-checked on
        // every read, so a stale cache can only be OLD, never forged. Upgrade
        // path if index-v1 is retired: entry.jar → index-v2.json (61 MB).
        if (!jar.isFile || System.currentTimeMillis() - jar.lastModified() > INDEX_MAX_AGE_MS) {
            UpdateProgress.update(UpdateProgress.State.CheckingManifest)
            Download.toFile(url = url, target = jar, shouldCancel = { UpdateProgress.cancelRequested })
        }
        return try { verifiedEntry(jar, entryName, certSha256) } catch (t: Throwable) { jar.delete(); throw t }
    }

    /** The entry's bytes, only if it is signed by exactly the pinned certificate. */
    fun verifiedEntry(jar: File, entryName: String, certSha256: String): ByteArray {
        JarFile(jar, true).use { jf ->
            val e = jf.getJarEntry(entryName) ?: error("$entryName missing from ${jar.name}")
            // Fully read FIRST: JarFile checks the signature digest at end of stream.
            val bytes = jf.getInputStream(e).use { it.readBytes() }
            val signers = e.codeSigners ?: error("${jar.name}: $entryName is not signed")
            val fingerprints = signers.flatMap { it.signerCertPath.certificates }.map { sha256Hex(it.encoded) }
            if (certSha256.lowercase() !in fingerprints)
                error("${jar.name}: signed by ${fingerprints.joinToString()} — not the pinned F-Droid certificate")
            return bytes
        }
    }

    /** Streamed lookup of one package in an index-v1.json. Null = not in the index. */
    fun lookup(input: InputStream, pkg: String): Entry? {
        var suggested: Long? = null
        var versions: List<Version>? = null
        JsonReader(InputStreamReader(input, Charsets.UTF_8)).use { r ->
            r.beginObject()
            while (r.hasNext()) when (r.nextName()) {
                "apps" -> { r.beginArray(); while (r.hasNext()) appHead(r)?.let { (p, s) -> if (p == pkg) suggested = s }; r.endArray() }
                "packages" -> { r.beginObject(); while (r.hasNext()) { if (r.nextName() == pkg) versions = versionsOf(r) else r.skipValue() }; r.endObject() }
                else -> r.skipValue()
            }
            r.endObject()
        }
        val s = suggested ?: return null
        return Entry(s, versions.orEmpty())
    }

    private fun appHead(r: JsonReader): Pair<String, Long>? {
        var p: String? = null; var s: Long? = null
        r.beginObject()
        while (r.hasNext()) when (r.nextName()) {
            "packageName" -> p = r.nextString()
            "suggestedVersionCode" -> s = r.nextString().toLongOrNull()
            else -> r.skipValue()
        }
        r.endObject()
        return if (p != null && s != null) p to s else null
    }

    private fun versionsOf(r: JsonReader): List<Version> {
        val out = mutableListOf<Version>()
        r.beginArray()
        while (r.hasNext()) {
            var apk = ""; var hash = ""; var hashType = ""; var code = 0L; var name = ""; val abis = mutableListOf<String>()
            r.beginObject()
            while (r.hasNext()) when (r.nextName()) {
                "apkName" -> apk = r.nextString()
                "hash" -> hash = r.nextString()
                "hashType" -> hashType = r.nextString()
                "versionCode" -> code = r.nextString().toLongOrNull() ?: 0L
                "versionName" -> name = r.nextString()
                "nativecode" -> { r.beginArray(); while (r.hasNext()) abis += r.nextString(); r.endArray() }
                else -> r.skipValue()
            }
            r.endObject()
            // Only a sha256 hash is a verification; any other hash type is not offered.
            if (apk.isNotEmpty() && hashType == "sha256" && hash.length == 64) out += Version(apk, hash.lowercase(), code, name, abis)
        }
        r.endArray()
        return out
    }

    private fun sha256Hex(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private const val INDEX_MAX_AGE_MS = 24L * 60 * 60 * 1000
}
