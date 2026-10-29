package com.diegonmarcos.superapp.bluetooth

import android.content.Context
import org.json.JSONObject

/**
 * The declared vendor vocabulary (assets/bluetooth/company-ids.json, #1264): SIG company identifiers, Apple continuity message
 * types and well-known service-data UUIDs. Unknown ids are spelled in hex, never an error.
 */
class CompanyIds(val companies: Map<Int, String>, val appleTypes: Map<Int, String>, val serviceData: Map<String, String>) {
    fun name(id: Int): String = companies[id] ?: "0x%04X".format(id)
    fun known(id: Int): Boolean = id in companies

    /** What an Apple manufacturer payload is, by its continuity type byte; null for another company or an unknown type. */
    fun appleType(company: Int, data: ByteArray): String? =
        if (company == AdvParser.APPLE && data.isNotEmpty()) appleTypes[data[0].toInt() and 0xFF] else null

    fun serviceDataName(uuid: String): String? = Uuids.assigned(uuid)?.let { serviceData[Uuids.short(uuid).lowercase()] }

    companion object {
        const val ASSET = "bluetooth/company-ids.json"

        fun load(ctx: Context): CompanyIds = parse(ctx.assets.open(ASSET).bufferedReader().use { it.readText() })

        fun parse(text: String): CompanyIds {
            val o = JSONObject(text)
            fun hexMap(key: String): Map<Int, String> {
                val m = o.optJSONObject(key) ?: return emptyMap()
                return m.keys().asSequence().associate { (it.toIntOrNull(16) ?: error("bad hex id $it in $key")) to m.getString(it) }
            }
            val sd = o.optJSONObject("service_data")
            return CompanyIds(hexMap("companies"), hexMap("apple_continuity"),
                sd?.keys()?.asSequence()?.associate { it.lowercase() to sd.getString(it) }.orEmpty())
        }
    }
}
