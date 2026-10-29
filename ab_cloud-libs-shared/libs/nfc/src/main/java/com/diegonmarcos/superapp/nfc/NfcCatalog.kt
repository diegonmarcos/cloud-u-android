package com.diegonmarcos.superapp.nfc

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class NxpVersion(val version: String, val name: String, val userPages: Int)

/** The declared NFC vocabulary (assets/nfc/nfc-catalog.json, #1264). Unknown values are spelled raw, never an error. */
class NfcCatalog(
    val icManufacturers: Map<Int, String>,
    val sak: Map<Int, String>,
    val nxpVersions: List<NxpVersion>,
    val iso15693Manufacturers: Map<Int, String>,
) {
    fun manufacturer(uid0: Int): String = icManufacturers[uid0] ?: "Unknown (0x%02X)".format(uid0)
    fun iso15693(code: Int): String = iso15693Manufacturers[code] ?: "Unknown (0x%02X)".format(code)
    fun sakName(s: Int): String = sak[s] ?: "SAK 0x%02X".format(s)
    fun version(v: ByteArray): NxpVersion? = Bytes.hex(v).let { h -> nxpVersions.firstOrNull { it.version.equals(h, true) } }

    companion object {
        const val ASSET = "nfc/nfc-catalog.json"

        fun load(ctx: Context): NfcCatalog = parse(ctx.assets.open(ASSET).bufferedReader().use { it.readText() })

        fun parse(text: String): NfcCatalog {
            val o = JSONObject(text)
            fun hexMap(key: String): Map<Int, String> {
                val m = o.optJSONObject(key) ?: return emptyMap()
                return m.keys().asSequence().filterNot { it.startsWith("_") }.associate { (it.toIntOrNull(16) ?: error("bad hex key $it in $key")) to m.getString(it) }
            }
            fun strMap(key: String): Map<String, String> {
                val m = o.optJSONObject(key) ?: return emptyMap()
                return m.keys().asSequence().filterNot { it.startsWith("_") }.associateWith { m.getString(it) }
            }
            fun rows(key: String): List<JSONObject> = o.optJSONArray(key).let { a: JSONArray? -> if (a == null) emptyList() else List(a.length()) { a.getJSONObject(it) } }
            return NfcCatalog(
                icManufacturers = hexMap("ic_manufacturers"),
                sak = hexMap("sak"),
                nxpVersions = rows("nxp_versions").map { NxpVersion(it.getString("version").uppercase(), it.getString("name"), it.getInt("user_pages")) },
                iso15693Manufacturers = hexMap("iso15693_manufacturers"),
            )
        }
    }
}
