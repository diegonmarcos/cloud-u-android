package com.diegonmarcos.superapp.launcher

import android.content.Context
import android.content.pm.PackageManager
import android.util.Base64
import com.diegonmarcos.superapp.BuildConfig
import com.diegonmarcos.superapp.appstore.PhoneAppActions
import org.json.JSONArray
import org.json.JSONObject

/**
 * #572 — the long-press menu's URLs section. THE derivation: this file and
 * build.json::ui.app_urls (whose `_doc_app_urls` is the contract) are the only
 * places a URL is made, and neither holds one — every host comes from a
 * declaration, every scheme from `app_urls`. test-menu-urls-declarative.sh
 * fails on a literal URL here or in [AppLongPressMenu].
 *
 * Nothing derivable = no [Link] = no row. A URL is never guessed.
 */
object AppUrls {

    class Link(val label: String, val url: String)

    /** Every row the menu draws for [pkg]: our app's endpoints, then the phone-app website. */
    fun of(ctx: Context, pkg: String): List<Link> {
        val cfg = decode(BuildConfig.UI_APP_URLS_B64)
        val pm = ctx.packageManager
        val metaOf = { key: String ->
            runCatching {
                pm.getApplicationInfo(pkg, PackageManager.GET_META_DATA).metaData?.getString(key)
            }.getOrNull()
        }
        val ext = decode(BuildConfig.EXTERNAL_APPS_B64)
        return ours(pkg, ext, cfg,
                decode(BuildConfig.SERVICES_PUBLIC_B64), decode(BuildConfig.SERVICES_PRIVATE_B64)) +
            listOfNotNull(site(pkg, ext, decode(BuildConfig.LINKTREE_JSON_B64))) +
            listOfNotNull(website(pkg, cfg, metaOf, PhoneAppActions.installerOf(ctx, pkg)))
    }

    /** Why [of] drew nothing for [pkg] (call it only then). Two
     *  different answers, never one: a declared kind whose value is missing,
     *  versus no URL kind declared for this app at all (#677). */
    fun absence(@Suppress("UNUSED_PARAMETER") ctx: Context, pkg: String): String =
        explain(pkg, decode(BuildConfig.EXTERNAL_APPS_B64), decode(BuildConfig.LINKTREE_JSON_B64),
            decode(BuildConfig.SERVICES_PUBLIC_B64), decode(BuildConfig.SERVICES_PRIVATE_B64))

    fun explain(pkg: String, externalApps: String, linktree: String, publicSvc: String, privateSvc: String): String {
        val app = entry(pkg, externalApps)
        val service = app?.optString("service").orEmpty()
        val site = app?.optString("site").orEmpty()
        val missing = listOfNotNull(
            service.takeIf { it.isNotEmpty() && arr(publicSvc).none { r -> r.optString("name") == it }
                && arr(privateSvc).none { r -> r.optString("name") == it } }
                ?.let { "service '$it' is declared but no services snapshot row has it" },
            site.takeIf { it.isNotEmpty() && siteUrl(it, linktree) == null }
                ?.let { "site '$it' is declared but linktree.json has no link with that label" },
        )
        return if (missing.isNotEmpty()) "URL missing: " + missing.joinToString("; ")
        else "No URL kind for this app: no service, no published site, no website in its manifest, no known installer page"
    }

    /** #677 THE THIRD SOURCE — a published static site (GitHub Pages and the
     *  like). The external_apps entry names a link LABEL in `site`; the URL is
     *  the one linktree.json (the portal's own snapshot) already carries for
     *  it. Declared once there, referenced here, never synthesised from a
     *  repo name. An unresolved label yields no row. */
    fun site(pkg: String, externalApps: String, linktree: String): Link? {
        val label = entry(pkg, externalApps)?.optString("site").orEmpty()
        if (label.isEmpty()) return null
        return siteUrl(label, linktree)?.let { Link("Site", it) }
    }

    /** The url of the linktree link labelled [label]; null when absent or
     *  when two links share the label with different urls (never guessed). */
    fun siteUrl(label: String, linktree: String): String? {
        val found = mutableSetOf<String>()
        fun walk(o: Any?) {
            when (o) {
                is JSONObject -> {
                    if (o.optString("label") == label) o.optString("url").takeIf { isWebUrl(it) }?.let { found += it }
                    o.keys().forEach { walk(o.opt(it)) }
                }
                is JSONArray -> for (i in 0 until o.length()) walk(o.opt(i))
            }
        }
        runCatching { walk(JSONObject(linktree)) }
        return found.singleOrNull()
    }

    private fun entry(pkg: String, externalApps: String): JSONObject? = arr(externalApps).firstOrNull { app ->
        pkg == app.optString("hub_package") || pkg == app.optString("alt_package") ||
            pkg == app.optString("install_package") ||
            app.optJSONObject("forks")?.let { f -> f.keys().asSequence().any { f.optString(it) == pkg } } == true
    }

    /** Our app → its public and private endpoint, read off the service row its
     *  external_apps entry names. Public first; a service with no public route
     *  yields only the private one. */
    fun ours(pkg: String, externalApps: String, cfg: String, publicSvc: String, privateSvc: String): List<Link> {
        val service = entry(pkg, externalApps)?.optString("service").orEmpty()
        if (service.isEmpty()) return emptyList()
        val c = JSONObject(cfg.ifEmpty { "{}" })
        val pub = arr(publicSvc).firstOrNull { it.optString("name") == service }
        val priv = pub ?: arr(privateSvc).firstOrNull { it.optString("name") == service }
        return listOfNotNull(
            pub?.optString("public_url")?.takeIf { it.isNotEmpty() }
                ?.let { Link("Public", c.optString("public_scheme") + it) },
            priv?.optString("private_dns")?.takeIf { it.isNotEmpty() }
                ?.let { Link("Private", c.optString("private_scheme") + it) },
        )
    }

    /** Phone app → its website: the app's own manifest meta-data first, else the
     *  page of the store that really installed it. Null = nothing declared or found. */
    fun website(pkg: String, cfg: String, metaOf: (String) -> String?, installer: String?): Link? {
        val c = JSONObject(cfg.ifEmpty { "{}" })
        val keys = c.optJSONArray("website_meta_keys") ?: JSONArray()
        for (i in 0 until keys.length()) {
            val v = metaOf(keys.getString(i))?.trim()
            if (v != null && isWebUrl(v)) return Link("Website", v)
        }
        val page = installer?.let { c.optJSONObject("installer_pages")?.optJSONObject(it) } ?: return null
        return Link(page.getString("label"), page.getString("url").replace("{pkg}", pkg))
    }

    private fun isWebUrl(v: String): Boolean = runCatching {
        val u = java.net.URI(v)
        u.scheme?.lowercase() in listOf("http", "https") && !u.host.isNullOrEmpty()
    }.getOrDefault(false)

    private fun decode(b64: String) = String(Base64.decode(b64, Base64.NO_WRAP))

    private fun arr(json: String): List<JSONObject> = runCatching {
        val a = JSONArray(json)
        (0 until a.length()).map { a.getJSONObject(it) }
    }.getOrDefault(emptyList())
}
