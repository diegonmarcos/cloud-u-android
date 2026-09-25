package com.diegonmarcos.superapp.appstore

import android.content.Context
import androidx.core.content.pm.PackageInfoCompat
import com.diegonmarcos.superapp.updater.AbiUpdateTag
import com.diegonmarcos.superapp.updater.Fleet
import com.diegonmarcos.superapp.updater.UpdateProgress
import com.diegonmarcos.superapp.updater.VersionOrder
import com.diegonmarcos.superapp.updater.apk.VerifiedApk
import com.diegonmarcos.superapp.updater.source.Download
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * #571 WE ARE THE STORE — the ORDERED install sources of an external app, read
 * from the `resolver` block of the one install-source map ([PhoneAppActions.SOURCES_ASSET]).
 *
 * Three rungs, always in this order: the vendor's own direct-APK endpoint, the
 * official F-Droid repo ([FDroidIndex]: signed index, per-version sha256), and
 * the Google Play deep-link. Play publishes NO APK URL, so its rung carries
 * nothing but its kind and the row shows a 'needs Play' badge — a URL on a Play
 * rung is a fake, and [source] refuses to parse one. An app the map does not
 * declare still resolves, to the Play rung alone, so every package a vault
 * export or an import names gets a row and an honest answer.
 *
 * Fleet apps are never resolved here: they install through the constellation
 * manifest and the updater's own release path ([FleetInstall]).
 *
 * [check] is the version probe behind the row badges, one rung at a time:
 * a vendor feed (versionCode, or a version name compared numerically), the
 * F-Droid api's suggestedVersionCode, or nothing for Play. [fetch] is the
 * download: the same [Download] the fleet uses, verified by the vendor's
 * digest when it publishes one and by structure otherwise, and always checked
 * to be the package it was asked for before it goes near an installer.
 */
object SourceResolver {

    const val KIND_VENDOR = "vendor"
    const val KIND_FDROID = "fdroid"
    const val KIND_PLAY = "play"

    /** One rung of an app's ladder. */
    sealed class Source(val kind: String) {
        class Vendor(
            /** URL template — `{version}` from the feed, `{asset}` from [abis]. Null when [apkKey] names it. */
            val apk: String?,
            val abis: Map<String, String>,
            val feed: String?,
            val versionName: String?,
            val versionCode: String?,
            val apkKey: String?,
            val sha256Key: String?,
            /** Sidecar URL template: bare hex or the sha256sum(1) form. */
            val sha256: String?,
            val versionRe: Regex?,
        ) : Source(KIND_VENDOR)
        object FDroid : Source(KIND_FDROID)
        object Play : Source(KIND_PLAY)
    }

    class External(val pkg: String, val label: String, val sources: List<Source>, val declared: Boolean) {
        /** The rungs this store can download from itself. */
        val direct: List<Source> get() = sources.filter { it !is Source.Play }
        /** Nothing but Play: the badge, and no Install of our own. */
        val needsPlay: Boolean get() = direct.isEmpty()
        val hasPlay: Boolean get() = sources.any { it is Source.Play }

        /** The shape [Fleet.commit] takes, so an external APK goes through the
         *  fleet's install channels — the ONE installer — and nothing else. */
        fun asFleetApp() = Fleet.App(
            id = pkg, label = label, pkg = pkg, altId = null, registry = "", namespace = "", image = "",
            tag = "", asset = "", assets = emptyMap(), releaseUrl = "", repoUrl = "", ghcrPage = "",
            blocked = false, kind = "app",
        )
    }

    class Config(
        val order: List<String>,
        val fdroid: JSONObject,
        /** The installer package whose store page is the Play rung — looked up in the #564 map, never named in code. */
        val playInstaller: String,
        val apps: Map<String, External>,
    )

    fun config(sources: JSONObject): Config {
        val r = sources.getJSONObject("resolver")
        val order = r.getJSONArray("order").let { a -> (0 until a.length()).map { a.getString(it) } }
        val apps = r.getJSONObject("apps")
        val parsed = apps.keys().asSequence().sorted().associateWith { pkg -> external(pkg, apps.getJSONObject(pkg), order) }
        return Config(order, r.getJSONObject("fdroid"), r.getJSONObject("play").getString("installer"), parsed)
    }

    /** The declared ladder, or the Play rung alone for a package the map does not know. */
    fun resolve(cfg: Config, pkg: String): External =
        cfg.apps[pkg] ?: External(pkg, pkg, listOf(Source.Play), declared = false)

    private fun external(pkg: String, o: JSONObject, order: List<String>): External {
        val arr = o.getJSONArray("sources")
        val list = (0 until arr.length()).map { i -> source(arr.getJSONObject(i)) }
        // The ladder must be a subsequence of `order`: a Play rung ABOVE a
        // direct one would send the user to a store this store replaces.
        val ranks = list.map { order.indexOf(it.kind) }
        require(list.isNotEmpty() && ranks.all { it >= 0 } && ranks == ranks.sorted() && ranks.distinct().size == ranks.size) {
            "$pkg: sources ${list.map { it.kind }} are not a subsequence of $order"
        }
        return External(pkg, o.optString("label").ifEmpty { pkg }, list, declared = true)
    }

    private fun source(o: JSONObject): Source = when (val kind = o.getString("kind")) {
        KIND_FDROID -> Source.FDroid
        KIND_PLAY -> {
            // Google Play has no public APK URL. A Play rung that names one is
            // a lie about where the bytes come from, so it does not parse.
            require(o.length() == 1) { "a play source carries nothing but its kind: $o" }
            Source.Play
        }
        KIND_VENDOR -> {
            val abis = o.optJSONObject("abis")?.let { m -> m.keys().asSequence().associateWith { m.getString(it) } }.orEmpty()
            val apk = o.optString("apk").ifEmpty { null }
            val apkKey = o.optString("apk_key").ifEmpty { null }
            require(apk != null || apkKey != null) { "vendor source names no apk: $o" }
            require(apk == null || apk.startsWith("https://")) { "vendor apk is not https: $apk" }
            require(apkKey == null || o.has("feed")) { "apk_key needs a feed: $o" }
            Source.Vendor(
                apk = apk, abis = abis,
                feed = o.optString("feed").ifEmpty { null },
                versionName = o.optString("version_name").ifEmpty { null },
                versionCode = o.optString("version_code").ifEmpty { null },
                apkKey = apkKey,
                sha256Key = o.optString("sha256_key").ifEmpty { null },
                sha256 = o.optString("sha256").ifEmpty { null },
                versionRe = o.optString("version_re").ifEmpty { null }?.let { Regex(it) },
            )
        }
        else -> error("unknown source kind $kind")
    }

    // ── version probe ────────────────────────────────────────────────────

    enum class Note { NONE, NO_FEED, PLAY_MANAGES, UNDECLARED, NOT_COMPARABLE }

    /** What a row shows. [via] is the rung that answered (a [Source.kind], or "fleet"). */
    sealed class Check(val via: String?) {
        class NotInstalled(via: String?, val needsPlay: Boolean) : Check(via)
        class Installed(val versionName: String, val versionCode: Long, via: String?, val note: Note) : Check(via)
        class UpdateAvailable(val versionName: String, val remote: String, via: String) : Check(via)
        class Unknown(val versionName: String?, val reason: String) : Check(null)
    }

    const val VIA_FLEET = "fleet"

    /** The fleet's own verdict in the row's vocabulary, so both kinds paint alike. */
    fun ofFleet(s: Fleet.State): Check = when (s) {
        is Fleet.State.Installed -> Check.Installed(s.versionName, s.versionCode, VIA_FLEET, Note.NONE)
        is Fleet.State.UpdateAvailable -> Check.UpdateAvailable(s.versionName ?: "", s.remoteDigest12, VIA_FLEET)
        is Fleet.State.Missing -> Check.NotInstalled(VIA_FLEET, needsPlay = false)
        is Fleet.State.Blocked -> Check.Unknown(null, "unpublished")
        is Fleet.State.Error -> Check.Unknown(null, s.message)
    }

    /** versionName/versionCode of [pkg] as installed, or null. */
    fun installed(ctx: Context, pkg: String): Pair<String, Long>? = runCatching {
        val pi = ctx.packageManager.getPackageInfo(pkg, 0)
        (pi.versionName ?: "") to PackageInfoCompat.getLongVersionCode(pi)
    }.getOrNull()

    /** What the remote rung says is current. Null = this rung has no feed. */
    class Remote(val name: String?, val code: Long?)

    /** Local facts only — what a row shows before Check all has run. */
    fun local(ctx: Context, app: External): Check {
        val have = installed(ctx, app.pkg) ?: return Check.NotInstalled(app.sources.first().kind, app.needsPlay)
        return Check.Installed(have.first, have.second, null, if (app.declared) Note.NONE else Note.UNDECLARED)
    }

    /** Blocking probe: the first rung that has an opinion decides. */
    fun check(ctx: Context, cfg: Config, app: External): Check {
        val have = installed(ctx, app.pkg) ?: return Check.NotInstalled(app.sources.first().kind, app.needsPlay)
        val (name, code) = have
        if (!app.declared) return Check.Installed(name, code, null, Note.UNDECLARED)
        for (src in app.sources) {
            if (src is Source.Play) return Check.Installed(name, code, src.kind, Note.PLAY_MANAGES)
            val probed = try { remoteVersion(cfg, app, src) } catch (t: Throwable) {
                return Check.Unknown(name, "${src.kind}: ${t.message ?: t.javaClass.simpleName}")
            }
            val remote = probed ?: return Check.Installed(name, code, src.kind, Note.NO_FEED)
            return when (VersionOrder.compare(remote.code, code)) {
                VersionOrder.Order.NEWER -> Check.UpdateAvailable(name, remote.name ?: remote.code.toString(), src.kind)
                VersionOrder.Order.SAME, VersionOrder.Order.OLDER -> Check.Installed(name, code, src.kind, Note.NONE)
                VersionOrder.Order.UNKNOWN -> {
                    val c = compareNames(remote.name, name)
                    when {
                        c == null -> Check.Installed(name, code, src.kind, Note.NOT_COMPARABLE)
                        c > 0 -> Check.UpdateAvailable(name, remote.name.orEmpty(), src.kind)
                        else -> Check.Installed(name, code, src.kind, Note.NONE)
                    }
                }
            }
        }
        return Check.Installed(name, code, null, Note.NO_FEED)
    }

    /**
     * Dotted-numeric compare of two version NAMES: the leading `\d+(\.\d+)*`
     * of each, component by component, missing components read as 0. Null
     * when either has no numeric prefix — "not comparable" is an answer, and
     * it must never be rounded to "up to date".
     */
    fun compareNames(remote: String?, installed: String?): Int? {
        fun parts(s: String?): List<Long>? =
            Regex("^(\\d+(?:\\.\\d+)*)").find(s?.trim().orEmpty())?.groupValues?.get(1)?.split('.')?.map { it.toLong() }
        val a = parts(remote) ?: return null
        val b = parts(installed) ?: return null
        for (i in 0 until maxOf(a.size, b.size)) {
            val d = (a.getOrNull(i) ?: 0L).compareTo(b.getOrNull(i) ?: 0L)
            if (d != 0) return d
        }
        return 0
    }

    private fun remoteVersion(cfg: Config, app: External, src: Source): Remote? = when (src) {
        is Source.Vendor -> src.feed?.let { url ->
            val feed = getJson(url) ?: error("feed $url answered 404")
            val name = (path(feed, src.versionName) as? String)?.let { normalise(src, it) }
            val code = path(feed, src.versionCode)?.toString()?.toLongOrNull()
            Remote(name, code)
        }
        is Source.FDroid -> FDroidIndex.suggested(cfg.fdroid, app.pkg)?.let { Remote(it.first, it.second) }
            ?: error("${app.pkg} is not in the F-Droid api")
        is Source.Play -> null
    }

    private fun normalise(src: Source.Vendor, raw: String): String =
        src.versionRe?.find(raw)?.groupValues?.getOrNull(1) ?: raw

    /** `a.b.c` walked into nested JSON objects. Null for a null/blank path. */
    fun path(o: JSONObject, p: String?): Any? {
        if (p.isNullOrBlank()) return null
        var cur: Any? = o
        for (k in p.split('.')) cur = (cur as? JSONObject)?.opt(k) ?: return null
        return cur
    }

    // ── download ─────────────────────────────────────────────────────────

    /** Blocking. The verified, identity-checked APK from [src], or a throw naming why. */
    fun fetch(ctx: Context, cfg: Config, app: External, src: Source): VerifiedApk = when (src) {
        is Source.Vendor -> fetchVendor(ctx, app, src)
        is Source.FDroid -> identity(ctx, app.pkg, FDroidIndex.fetch(ctx, cfg.fdroid, app.pkg))
        is Source.Play -> error("${app.label} publishes no APK outside Google Play")
    }

    private fun fetchVendor(ctx: Context, app: External, src: Source.Vendor): VerifiedApk {
        UpdateProgress.update(UpdateProgress.State.CheckingManifest)
        val feed = src.feed?.let { getJson(it) ?: error("feed $it answered 404") }
        val version = feed?.let { path(it, src.versionName) as? String }?.let { normalise(src, it) }
        val asset = if (src.abis.isEmpty()) "" else AbiUpdateTag.currentFrom(src.abis, src.abis.values.first())
        fun fill(t: String) = t.replace("{version}", version.orEmpty()).replace("{asset}", asset)
        val url = src.apkKey?.let { k -> feed?.let { path(it, k) as? String } }
            ?: src.apk?.let { fill(it) }
            ?: error("${app.pkg}: the feed names no APK")
        require(url.startsWith("https://")) { "refusing a non-https APK: $url" }
        val digest = src.sha256Key?.let { k -> feed?.let { path(it, k) as? String } }
            ?: src.sha256?.let { sidecar(fill(it)) }
        val target = File(ctx.cacheDir, "external-${app.pkg}.apk")
        UpdateProgress.update(UpdateProgress.State.Downloading(0, 0L, -1L))
        Download.toFile(url = url, target = target, shouldCancel = { UpdateProgress.cancelRequested }) { written, total ->
            val pct = if (total > 0) ((written * 100) / total).toInt().coerceIn(0, 100) else 0
            UpdateProgress.update(UpdateProgress.State.Downloading(pct, written, total))
        }
        // A vendor that publishes a digest gets the fleet's strongest check; one
        // that does not gets the structural floor and an honest evidence line.
        val verified = (if (digest != null) VerifiedApk.byDigest(target, digest) else VerifiedApk.structural(target))
            ?: run {
                Download.discard(target)
                error("${app.label}: the vendor APK failed verification" + (digest?.let { " against $it" } ?: ""))
            }
        return identity(ctx, app.pkg, verified)
    }

    /** The bytes must BE the package asked for: a vendor URL that serves
     *  something else never reaches an installer. */
    private fun identity(ctx: Context, pkg: String, apk: VerifiedApk): VerifiedApk {
        val id = Fleet.candidateIdentity(ctx, apk.file) ?: return apk
        if (id.pkg != pkg) {
            apk.file.delete()
            error("downloaded ${id.pkg}, wanted $pkg — discarded")
        }
        return apk
    }

    private fun sidecar(url: String): String? = runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = true; c.connectTimeout = TIMEOUT_MS; c.readTimeout = TIMEOUT_MS
        val body = if (c.responseCode in 200..299) c.inputStream.bufferedReader().use { it.readText() } else null
        c.disconnect()
        body?.trim()?.substringBefore(' ')?.trim()?.lowercase()
            ?.takeIf { s -> s.length == 64 && s.all { it in '0'..'9' || it in 'a'..'f' } }
    }.getOrNull()

    /** GET [url] as JSON. Null on 404 (the thing does not exist); throws on anything else. */
    fun getJson(url: String): JSONObject? {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.instanceFollowRedirects = true; c.connectTimeout = TIMEOUT_MS; c.readTimeout = TIMEOUT_MS
            c.setRequestProperty("Accept", "application/json")
            val code = c.responseCode
            if (code == 404) return null
            if (code !in 200..299) error("HTTP $code from $url")
            return JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        } finally { c.disconnect() }
    }

    private const val TIMEOUT_MS = 15_000
}
