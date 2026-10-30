package com.diegonmarcos.clouddrive.configs

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * #1333 build.json::ui.general, parsed (pure, JVM-tested): the anchor row of Configs > General and the Perms list.
 * Nothing here names a permission, a reason or a settings screen: the declaration does, [GeneralPerms] dispatches.
 */
object GeneralDecl {
    data class Anchor(val id: String, val label: String, val icon: String)

    /** One settings screen to try: a `component` ("pkg/cls") or an `action`, with the package as data / extra. */
    data class IntentSpec(val component: String, val action: String, val packageUri: Boolean, val extraPackage: Boolean)

    data class PermEntry(
        val id: String,
        val label: String,
        val why: String,
        val permissions: List<String>,
        val check: String,
        val grant: String,
        val intents: List<IntentSpec>,
        val appop: String = "",
        val minSdk: Int? = null,
        val maxSdk: Int? = null,
        val oem: String = "",
        val requiresPackage: String = "",
    )

    data class General(val anchors: List<Anchor>, val perms: List<PermEntry>)

    val EMPTY = General(emptyList(), emptyList())

    enum class Status { GRANTED, DENIED, NOT_APPLICABLE, INFO }

    /**
     * The status mapping, pure. [granted] is the probe's answer for the entry's [PermEntry.check] (null when the
     * check is not readable); [requiredPackageInstalled] is only looked at for entries with `requires_package`.
     * Order: out of the SDK window or other manufacturer or missing app = not applicable; a `declared` or `oem` check
     * is info (the OS cannot say more); otherwise granted / denied.
     */
    fun status(e: PermEntry, sdk: Int, manufacturer: String, granted: Boolean?, requiredPackageInstalled: Boolean = true): Status = when {
        e.minSdk != null && sdk < e.minSdk -> Status.NOT_APPLICABLE
        e.maxSdk != null && sdk > e.maxSdk -> Status.NOT_APPLICABLE
        e.oem.isNotBlank() && !e.oem.equals(manufacturer, ignoreCase = true) -> Status.NOT_APPLICABLE
        e.requiresPackage.isNotBlank() && !requiredPackageInstalled -> Status.NOT_APPLICABLE
        e.check == "declared" || e.check == "oem" -> Status.INFO
        granted == true -> Status.GRANTED
        else -> Status.DENIED
    }

    /** `{name}` placeholders (the manifest's `${name}`) resolved against [vars]; unknown ones stay as they are. */
    fun resolve(text: String, vars: Map<String, String>): String =
        Regex("\\{([A-Za-z0-9_]+)\\}").replace(text) { vars[it.groupValues[1]] ?: it.value }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull ?: ""
    private fun JsonObject.strings(k: String) = (this[k] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
    private fun objects(e: JsonElement?) = (e as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()

    fun parse(text: String): General {
        val o = (if (text.isBlank()) null else runCatching { json.parseToJsonElement(text) }.getOrNull()) as? JsonObject ?: return EMPTY
        val anchors = objects(o["anchors"]).map { Anchor(it.str("id"), it.str("label"), it.str("icon")) }.filter { it.id.isNotBlank() }
        val perms = objects(o["perms"]).map { p ->
            PermEntry(
                id = p.str("id"), label = p.str("label"), why = p.str("why"), permissions = p.strings("permissions"),
                check = p.str("check"), grant = p.str("grant").ifBlank { "none" },
                intents = objects(p["intents"]).map { i ->
                    IntentSpec(i.str("component"), i.str("action"), (i["package_uri"] as? JsonPrimitive)?.booleanOrNull ?: false, (i["extra_package"] as? JsonPrimitive)?.booleanOrNull ?: false)
                },
                appop = p.str("appop"), minSdk = (p["min_sdk"] as? JsonPrimitive)?.intOrNull, maxSdk = (p["max_sdk"] as? JsonPrimitive)?.intOrNull,
                oem = p.str("oem"), requiresPackage = p.str("requires_package"),
            )
        }.filter { it.id.isNotBlank() }
        return General(anchors, perms)
    }
}
