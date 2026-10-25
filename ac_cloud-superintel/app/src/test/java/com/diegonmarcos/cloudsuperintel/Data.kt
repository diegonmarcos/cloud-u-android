package com.diegonmarcos.cloudsuperintel

import org.json.JSONObject
import java.io.File

/** The real declared data of the app (Gradle runs a module's tests from the module dir), never a copy of it. */
object Data {
    fun asset(name: String): String = File("src/main/assets/si/$name").readText()
    fun buildJson(): JSONObject = JSONObject(File("../build.json").readText())

    /** The page ids build.json::ui declares for one section. */
    fun pages(section: String): List<String> {
        val secs = buildJson().getJSONObject("ui").getJSONArray("sections")
        val s = (0 until secs.length()).map { secs.getJSONObject(it) }.first { it.getString("id") == section }
        val p = s.getJSONArray("pages")
        return (0 until p.length()).map { p.getJSONObject(it).getString("id") }
    }
}
