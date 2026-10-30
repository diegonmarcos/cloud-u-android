package com.diegonmarcos.superapp.launcher

import com.diegonmarcos.superapp.BuildConfig
import android.util.Base64
import org.json.JSONObject

/**
 * Where a Cloud > Lnktree page entry (a `kind: entry` stack panel) leads. Holds no URL: every address is read from a
 * declared source, so it cannot drift from it.
 *   - `site`     the label of a link in data/linktree.json (the portal snapshot), read by [AppUrls.siteUrl]
 *   - `registry` a code of build.json ui.app_urls.registry_sites (the `webapp` of that node of projects-repos.json)
 *   - `repo`     a repo of the fleet under ui.app_urls.repo_base, with an optional `path` inside it
 * Nothing resolved (or the entry is `planned`) means null: the entry is drawn greyed and does nothing.
 */
object LnktreeTargets {
    fun resolve(site: String, registry: String, repo: String, path: String, linktree: String, appUrls: String): String? {
        if (site.isNotEmpty()) return AppUrls.siteUrl(site, linktree)
        val cfg = runCatching { JSONObject(appUrls) }.getOrDefault(JSONObject())
        if (registry.isNotEmpty()) return cfg.optJSONObject("registry_sites")?.optString(registry)?.takeIf { it.isNotBlank() }
        if (repo.isNotEmpty()) {
            val base = cfg.optString("repo_base")
            if (base.isBlank()) return null
            return base + repo + if (path.isNotEmpty()) "/tree/main/$path" else ""
        }
        return null
    }

    fun of(panel: Sections.StackPanel): String? {
        if (panel.planned) return null
        return resolve(panel.site, panel.registry, panel.repo, panel.path,
            String(Base64.decode(BuildConfig.LINKTREE_JSON_B64, Base64.NO_WRAP)),
            String(Base64.decode(BuildConfig.UI_APP_URLS_B64, Base64.NO_WRAP)))
    }
}
