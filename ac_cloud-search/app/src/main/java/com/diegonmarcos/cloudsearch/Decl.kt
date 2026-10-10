package com.diegonmarcos.cloudsearch

import android.util.Base64
import com.diegonmarcos.cloudsearch.core.SearchConfig
import org.json.JSONObject

/**
 * build.json::search, decoded once: app/build.gradle bakes it into BuildConfig as base64 JSON and
 * core's SearchConfig parses and validates it. This is the only reader of that blob. #913b the agents
 * catalogue (build.json::agents) is baked apart (AGENTS_B64) and put back under search.agents first.
 */
object Decl {
    val config: SearchConfig by lazy {
        SearchConfig.parse(SearchConfig.withAgents(JSONObject(text(BuildConfig.SEARCH_CONFIG_B64)), JSONObject(text(BuildConfig.AGENTS_B64))))
    }

    private fun text(b64: String) = String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8)
}
