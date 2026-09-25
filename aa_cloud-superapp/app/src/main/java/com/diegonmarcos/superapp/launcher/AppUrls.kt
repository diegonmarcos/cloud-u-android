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
        return ours(pkg, decode(BuildConfig.EXTERNAL_APPS_B64), cfg,
                decode(BuildConfig.SERVICES_PUBLIC_B64), decode(BuildConfig.SERVICES_PRIVATE_B64)) +
            listOfNotNull(website(pkg, cfg, metaOf, PhoneAppActions.installerOf(ctx, pkg)))
    }

    /** Our app → its public and private endpoint, read off the service row its
     *  external_apps entry names. Public first; a service with no public route
     *  yields only the private one. */
    fun ours(pkg: String, externalApps: String, cfg: String, publicSvc: String, privateSvc: String): List<Link> {
        val service = arr(externalApps).firstOrNull { app ->
            pkg == app.optString("hub_package") || pkg == app.optString("alt_package") ||
                pkg == app.optString("install_package") ||
                app.optJSONObject("forks")?.let { f -> f.keys().asSequence().any { f.optString(it) == pkg } } == true
        }?.optString("service").orEmpty()
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
