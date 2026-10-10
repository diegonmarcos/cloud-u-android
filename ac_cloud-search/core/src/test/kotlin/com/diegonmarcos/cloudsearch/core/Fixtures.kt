package com.diegonmarcos.cloudsearch.core

import org.json.JSONObject
import java.io.File

/** This app's own build.json (core/build.gradle passes its path) and the saved real responses. */
object Fixtures {
    val buildJson: JSONObject by lazy {
        // core/build.gradle passes the path to the test task AND to PIT's minion JVM.
        val path = System.getProperty("cloudsearch.buildJson") ?: error("core/build.gradle sets cloudsearch.buildJson")
        JSONObject(File(path).readText())
    }

    /** build.json::search, with build.json::agents (the agents catalogue) under search.agents as Decl.kt puts it, as a fresh object a test can edit. */
    fun searchJson(): JSONObject = SearchConfig.withAgents(buildJson.getJSONObject("search"), buildJson.optJSONObject("agents"))

    val cfg: SearchConfig by lazy { SearchConfig.parse(searchJson()) }

    fun text(name: String): String =
        (Fixtures::class.java.getResource("/fixtures/$name") ?: error("missing fixture $name")).readText(Charsets.UTF_8)
}

/** #913 the repository root (the engines' sources sit beside this app). */
val repoRoot: File by lazy { File(System.getProperty("cloudsearch.repoRoot") ?: error("core/build.gradle sets cloudsearch.repoRoot")) }
