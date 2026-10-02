package com.diegonmarcos.cloudsearch

import android.util.Base64
import com.diegonmarcos.cloudsearch.core.SearchConfig

/**
 * build.json::search, decoded once: app/build.gradle bakes it into BuildConfig as base64 JSON and
 * core's SearchConfig parses and validates it. This is the only reader of that blob.
 */
object Decl {
    val config: SearchConfig by lazy {
        SearchConfig.parse(String(Base64.decode(BuildConfig.SEARCH_CONFIG_B64, Base64.DEFAULT), Charsets.UTF_8))
    }
}
