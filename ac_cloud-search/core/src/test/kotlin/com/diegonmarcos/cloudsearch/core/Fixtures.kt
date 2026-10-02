package com.diegonmarcos.cloudsearch.core

import org.json.JSONObject
import java.io.File

/** This app's own build.json (core/build.gradle passes its path) and the saved real responses. */
object Fixtures {
    val buildJson: JSONObject by lazy {
        val path = System.getProperty("cloudsearch.buildJson") ?: error("core/build.gradle sets cloudsearch.buildJson")
        JSONObject(File(path).readText())
    }

    /** build.json::search as a fresh object, so a test can edit its copy. */
    fun searchJson(): JSONObject = JSONObject(buildJson.getJSONObject("search").toString())

    val cfg: SearchConfig by lazy { SearchConfig.parse(searchJson()) }

    fun text(name: String): String =
        (Fixtures::class.java.getResource("/fixtures/$name") ?: error("missing fixture $name")).readText(Charsets.UTF_8)
}
