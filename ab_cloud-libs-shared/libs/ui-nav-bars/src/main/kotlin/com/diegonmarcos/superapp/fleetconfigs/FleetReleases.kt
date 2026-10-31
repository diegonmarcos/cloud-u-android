package com.diegonmarcos.superapp.fleetconfigs

import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/** One entry of the release notes: a commit that changed this app's folder. */
public data class ReleaseNote(val sha: String, val date: String, val title: String)

/** What the app's Cloud Store release holds now: the build commit of the published APK, and the recent notes. */
public data class ReleaseState(val latestSha: String, val latestDate: String, val notes: List<ReleaseNote>, val error: String) {
    /** True when the published APK is the build installed here (shas compared on the shorter one's length). */
    public fun upToDate(installed: String): Boolean =
        latestSha.isNotBlank() && installed.isNotBlank() && installed != "local" &&
            latestSha.startsWith(installed.take(minOf(installed.length, latestSha.length)))
}

/**
 * Configs ▸ General ▸ Update and About ▸ Changelog read the same two public facts, the way Cloud Store does: the `.source`
 * sidecar CI publishes next to every APK on the rolling release (`<apk sha256> <commit sha>`, so the latest build's commit),
 * and the commits that touched the app's folder (the release notes). Plain HTTPS over Android's resolver; one fetch per
 * process unless the user asks again.
 */
public object FleetReleases {
    @Volatile private var cached: ReleaseState? = null

    public fun cached(): ReleaseState? = cached

    /** Blocking: call off the main thread. */
    public fun load(decl: FleetConfigsDecl, force: Boolean = false): ReleaseState {
        if (!force) cached?.let { return it }
        val u = decl.update
        val asset = u.asset(Build.SUPPORTED_ABIS.toList())
        var err = ""
        val latest = if (asset.isBlank()) { err = "This app declares no release asset."; "" } else runCatching {
            get("https://github.com/${u.repo}/releases/download/${u.tag}/$asset.source").trim().split(Regex("\\s+")).getOrNull(1).orEmpty()
        }.getOrElse { err = "Could not reach the release: ${it.message ?: it.javaClass.simpleName}"; "" }
        val notes = if (u.appDir.isBlank()) emptyList() else runCatching {
            parseNotes(get("https://api.github.com/repos/${u.repo}/commits?path=${u.appDir}&per_page=10"))
        }.getOrElse { if (err.isEmpty()) err = "Could not load the release notes: ${it.message ?: it.javaClass.simpleName}"; emptyList() }
        val date = notes.firstOrNull { latest.isNotBlank() && it.sha.startsWith(latest.take(12)) }?.date.orEmpty()
        return ReleaseState(latest, date, notes, err).also { cached = it }
    }

    public fun parseNotes(json: String): List<ReleaseNote> {
        val a = JSONArray(json)
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val c = o.optJSONObject("commit") ?: return@mapNotNull null
            val title = c.optString("message").lineSequence().firstOrNull().orEmpty().trim()
            val date = c.optJSONObject("committer")?.optString("date").orEmpty().take(10)
            ReleaseNote(o.optString("sha").take(12), date, title)
        }.filter { it.title.isNotBlank() }
    }

    /** Update: Cloud Store's Phone page (installed apps and their updates); its release page when the Store is not installed. */
    public fun openStore(ctx: Context, decl: FleetConfigsDecl): Boolean {
        val i = Intent("${decl.storePkg}.OPEN").setPackage(decl.storePkg).putExtra("tab", "phone").putExtra("package", ctx.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (decl.storePkg.isNotBlank() && runCatching { ctx.startActivity(i) }.isSuccess) return true
        return runCatching {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/${decl.update.repo}/releases/tag/${decl.update.tag}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 10000; c.instanceFollowRedirects = true
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "cloud-fleet-configs")
        try {
            if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }
}
