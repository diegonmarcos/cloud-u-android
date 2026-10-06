package com.diegonmarcos.cloudstore.web

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** One constellation row, read from aa_cloud-superapp/data/constellation-fleet.json. */
data class FleetApp(
    val id: String,
    val label: String,
    val kind: String,
    val version: String,
    /** The release link the Android store installs from; here it is the install action. */
    val releaseUrl: String,
    val blocked: Boolean,
)

private fun JsonObject.str(key: String): String = this[key]?.jsonPrimitive?.contentOrNull ?: ""

/** Apps first (what a phone installs), then the libs; blocked rows are not offered. */
fun parseFleet(text: String): List<FleetApp> {
    val apps = Json.parseToJsonElement(text).jsonObject["apps"]?.jsonArray ?: JsonArray(emptyList())
    return apps.map { it.jsonObject }
        .map {
            FleetApp(
                id = it.str("id"), label = it.str("label"), kind = it.str("kind"),
                version = it.str("version_name"), releaseUrl = it.str("release_url"),
                blocked = it["blocked"]?.jsonPrimitive?.contentOrNull == "true",
            )
        }
        .filter { it.id.isNotEmpty() && !it.blocked }
        .sortedBy { if (it.kind == "app") 0 else 1 }
}
