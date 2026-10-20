package com.diegonmarcos.cloudc3

import com.diegonmarcos.cloudc3.pages.FleetModel
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertTrue

/**
 * The Infra tests read THIS repository's own declaration (data/fleet_slice.json) and assert against it, so no VM, IP or
 * device is typed in a test either: a fixture that restated the fleet would be the second copy test-c3-infra.sh T3 forbids.
 * Values a live answer would carry are obviously fake (RFC 5737 addresses, "vm-x").
 */
object FleetFixtures {

    /** The module directory (the one holding build.json), found by walking up from the working directory. */
    fun moduleDir(): File {
        var dir = File("").absoluteFile
        while (dir.parentFile != null && !File(dir, "build.json").isFile) dir = dir.parentFile
        assertTrue("could not locate ac_cloud-c3/build.json from ${File("").absolutePath}", File(dir, "build.json").isFile)
        return dir
    }

    fun sliceText(): String = File(moduleDir(), "data/fleet_slice.json").readText()

    fun slice(): FleetModel.Slice = FleetModel.parse(sliceText())

    fun rawSlice(): JsonObject = Json.parseToJsonElement(sliceText()).jsonObject

    /** Container names of every subgroup the dashboards call Databases: the containers that must NOT appear under Infra. */
    fun dbContainerNames(): Set<String> {
        val root = Json.parseToJsonElement(File(moduleDir(), "data/cloud_services.json").readText()).jsonObject
        val out = mutableSetOf<String>()
        (root["groups"] as JsonArray).forEach { g ->
            (g.jsonObject["subgroups"] as? JsonArray)?.forEach { s ->
                val label = s.jsonObject["label"]!!.jsonPrimitive.content
                if (label == "Databases" || label == "DBs") {
                    (s.jsonObject["containers"] as JsonArray).forEach { out += it.jsonObject["name"]!!.jsonPrimitive.content }
                }
            }
        }
        return out
    }
}
