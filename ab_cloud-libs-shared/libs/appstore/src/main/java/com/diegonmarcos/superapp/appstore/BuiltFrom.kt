package com.diegonmarcos.superapp.appstore

import com.diegonmarcos.superapp.updater.Fleet

/**
 * #820 THE one reader of "which commit was this APK built from".
 *
 * Two places carry it, both written by CI from the same checkout:
 *  - AVAILABLE: the release's `<asset>.source` sidecar (cloud-android-publish-gate.sh
 *    `stamp`): line 1 = source-identity digest, line 2 = the 40-hex commit (#817).
 *    A legacy one-line sidecar predates line 2 and names no commit.
 *  - INSTALLED: the versionName every app's build.gradle bakes as
 *    "<version> (sha-<8 hex>)" from GITHUB_SHA / `git rev-parse` — the same
 *    declaration that bakes BuildConfig.GIT_SHORT_SHA, which each app's About shows.
 *
 * Anything that does not parse is "unknown" — never a guess.
 */
object BuiltFrom {
    const val UNKNOWN = "unknown"
    private const val SHORT = 8
    private val FULL = Regex("^[0-9a-f]{40}$")
    private val IN_VERSION = Regex("""\(sha-([0-9a-f]{7,40})\)""")

    /** Line 2 of a `.source` sidecar, lower-cased, when it is 40 hex; else null. */
    fun commitFromSidecar(text: String?): String? {
        val line2 = text?.lines()?.getOrNull(1)?.trim()?.lowercase() ?: return null
        return line2.takeIf { FULL.matches(it) }
    }

    /** The sha a build baked into its versionName, or null. */
    fun shaFromVersionName(versionName: String?): String? =
        versionName?.let { IN_VERSION.find(it.lowercase())?.groupValues?.get(1) }

    /** Short form for display; [UNKNOWN] for null/blank. */
    fun short(sha: String?): String =
        sha?.trim()?.takeIf { it.isNotEmpty() }?.take(SHORT) ?: UNKNOWN

    /** True only when both are known and one is a prefix of the other. */
    fun same(a: String?, b: String?): Boolean {
        if (a.isNullOrBlank() || b.isNullOrBlank()) return false
        val x = a.lowercase(); val y = b.lowercase()
        return x.startsWith(y) || y.startsWith(x)
    }

    private val GH_REPO = Regex("""^https://github\.com/([^/]+/[^/]+)/""")

    /** Commit page for a known sha in the repo [releaseUrl] lives in, else null. */
    fun commitUrl(releaseUrl: String, sha: String?): String? {
        if (sha.isNullOrBlank()) return null
        val repo = GH_REPO.find(releaseUrl)?.groupValues?.get(1) ?: return null
        return "https://github.com/$repo/commit/$sha"
    }

    /** Network: GET `<abiReleaseUrl>.source` and read line 2. Null on any failure. */
    fun fetchReleaseCommit(app: Fleet.App): String? = runCatching {
        if (app.releaseUrl.isBlank()) return null
        val c = java.net.URL(app.abiReleaseUrl + ".source").openConnection() as java.net.HttpURLConnection
        c.instanceFollowRedirects = true
        c.connectTimeout = 10_000
        c.readTimeout = 10_000
        val body = if (c.responseCode in 200..299) c.inputStream.bufferedReader().use { it.readText() } else null
        c.disconnect()
        commitFromSidecar(body)
    }.getOrNull()
}
