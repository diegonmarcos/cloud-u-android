package com.diegonmarcos.superapp.modelcatalogue

import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * The two things [ModelCatalogueRepository] needs from its host, and nothing more: a GET that
 * answers the body of a 2xx (null for anything else, a failure included: the models API needs no
 * key, so no header is ever passed), and a small keyed store for the last prices.
 */
fun interface CatalogueFetch {
    fun get(url: String): String?
}

interface CatalogueStore {
    data class Entry(val body: String, val at: Long)

    fun get(key: String): Entry?
    fun put(key: String, body: String, at: Long)
}

/**
 * [CatalogueStore] as one JSON file per key under [dir] ({"at","body"}, named by the key's SHA-256):
 * byte-for-byte the layout Cloud Search's search-core Cache wrote before the catalogue was shared,
 * so the prices a phone already cached survive the move.
 */
class FileCatalogueStore(private val dir: File) : CatalogueStore {
    override fun put(key: String, body: String, at: Long) {
        dir.mkdirs()
        File(dir, name(key)).writeText(JSONObject().put("at", at).put("body", body).toString())
    }

    override fun get(key: String): CatalogueStore.Entry? = runCatching {
        val o = JSONObject(File(dir, name(key)).readText())
        CatalogueStore.Entry(o.getString("body"), o.getLong("at"))
    }.getOrNull()

    private fun name(key: String): String =
        MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) } + ".json"
}
