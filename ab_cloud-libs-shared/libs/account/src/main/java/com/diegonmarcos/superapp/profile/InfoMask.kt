package com.diegonmarcos.superapp.profile

import org.json.JSONArray
import org.json.JSONObject

/**
 * Configs ▸ Account ▸ INFOS (#695): one fetched vault section as read-out
 * rows, and the rule that keeps a secret off the screen.
 *
 * The rows come from the DATA — whatever the fetched bundle carries, walked
 * leaf by leaf — never from a list of fields typed here. What changes the
 * drawing is declared in build.json::ui.profile.infos.mask:
 *
 *  • `paths` / `values`: a leaf is MASKED when its full path (`section › key ›
 *    …`, case-insensitive) or its value matches one of these. A masked row
 *    carries only the value's LENGTH: the secret itself never reaches a view,
 *    so no tap, copy or accessibility read can reveal it.
 *  • `collapse_over`: an object or list with more entries than this is ONE row
 *    (its count), not thousands — the keyboard's default clipboard list alone
 *    is 476 clips, and a clip is exactly where a pasted token lives.
 *
 * FAIL-CLOSED. A declaration with no pattern at all, or one pattern that does
 * not compile, masks EVERY leaf: a broken rule must cost readability, never a
 * secret. Pure — the JVM suite runs it on the baked declaration.
 */
class InfoMask(paths: List<String>, values: List<String>, private val collapseOver: Int) {

    /** EMPTY (#713): a declared field, or a leaf, the bundle does not fill. */
    enum class Kind { SHOWN, MASKED, PENDING, COLLAPSED, EMPTY }

    /** One read-out line. [text] is empty for MASKED; [size] is the masked
     *  value's length, or a collapsed container's entry count. */
    data class Row(val path: String, val text: String, val kind: Kind, val size: Int = 0)

    private val pathRes = paths.map { runCatching { Regex(it, RegexOption.IGNORE_CASE) }.getOrNull() }
    private val valueRes = values.map { runCatching { Regex(it) }.getOrNull() }

    /** True when the declaration cannot be trusted to protect anything. */
    val maskAll: Boolean =
        (pathRes.isEmpty() && valueRes.isEmpty()) || pathRes.any { it == null } || valueRes.any { it == null }

    fun hides(fullPath: String, value: String): Boolean =
        maskAll || secretPath(fullPath) || pathRes.any { it!!.containsMatchIn(fullPath) } || valueRes.any { it!!.containsMatchIn(value) }

    /** Every row of [value], the bundle's section [sectionId]. */
    fun rows(sectionId: String, value: Any?): List<Row> {
        val out = mutableListOf<Row>()
        walk(sectionId, value, "", out)
        return out
    }

    /** #713 One section of the schema the vault JSON fills (build.json::ui.profile.infos.schema).
     *  #727 [render] `apps` also lists the section's declared apps; [route] is the page it links to. */
    data class SchemaSection(val id: String, val label: String, val fields: List<String>,
                             val render: String = "", val route: String = "")

    /**
     * #713 EVERY declared field of [section], in declared order — its own rows,
     * through the same mask, when [data] (the bundle's section) fills it, ONE
     * [Kind.EMPTY] row when it does not — then whatever [data] carries under a key
     * no field starts with, so nothing fetched is hidden by the declaration.
     * ponytail: extras are found at the section's top level only; a new key
     * under a declared parent (computers › laptop) shows once its field is declared.
     */
    fun schemaRows(section: SchemaSection, data: Any?): List<Row> {
        val out = mutableListOf<Row>()
        for (field in section.fields) {
            val v = field.split(SEP).fold(data) { cur, seg -> (cur as? JSONObject)?.opt(seg) }
            if (unfilled(v)) out += Row(field, "", Kind.EMPTY) else walk(section.id, v, field, out)
        }
        val tops = section.fields.map { it.substringBefore(SEP) }.toSet()
        (data as? JSONObject)?.let { o -> o.keys().forEach { k -> if (k !in tops) walk(section.id, o.opt(k), k, out) } }
        return out
    }

    private fun unfilled(v: Any?): Boolean =
        v == null || v == JSONObject.NULL || (v is String && v.isBlank()) ||
            (v is JSONObject && v.length() == 0) || (v is JSONArray && v.length() == 0)

    private fun walk(section: String, v: Any?, path: String, out: MutableList<Row>) {
        when {
            v is JSONObject && v.optBoolean("pending") ->
                out += Row(path, "${v.optString("source")} · ${v.optString("reason")}", Kind.PENDING)
            v is JSONObject && v.length() > collapseOver -> out += Row(path, "", Kind.COLLAPSED, v.length())
            v is JSONArray && v.length() > collapseOver -> out += Row(path, "", Kind.COLLAPSED, v.length())
            v is JSONObject && v.length() > 0 -> v.keys().forEach { k -> walk(section, v.opt(k), join(path, k), out) }
            v is JSONArray && v.length() > 0 -> (0 until v.length()).forEach { i -> walk(section, v.opt(i), join(path, "[$i]"), out) }
            unfilled(v) -> out += Row(path, "", Kind.EMPTY)
            else -> {
                val text = v.toString()
                out += if (hides(join(section, path), text)) Row(path, "", Kind.MASKED, text.length)
                       else Row(path, text, Kind.SHOWN)
            }
        }
    }

    companion object {
        const val SEP = " › "

        fun join(path: String, key: String) = if (path.isEmpty()) key else "$path$SEP$key"

        /** build.json::ui.profile.infos.mask; a missing block is the fail-closed, mask-all rule. */
        fun parse(o: JSONObject?): InfoMask {
            val m = o ?: JSONObject()
            fun list(key: String): List<String> = m.optJSONArray(key)?.let { a ->
                (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }
            }.orEmpty()
            return InfoMask(list("paths"), list("values"), m.optInt("collapse_over", 12))
        }

        /** #713 build.json::ui.profile.infos.schema.sections; a missing or broken block is no sections. */
        fun parseSchema(o: JSONObject?): List<SchemaSection> {
            val arr = o?.optJSONArray("sections") ?: return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                val s = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = s.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val f = s.optJSONArray("fields") ?: JSONArray()
                SchemaSection(id, s.optString("label").ifBlank { id },
                    (0 until f.length()).map { f.optString(it) }.filter { it.isNotBlank() },
                    s.optString("render"), s.optString("route"))
            }
        }

        /**
         * #727 EVERY section Infos draws, none dropped: the declared [schema] in its
         * order, then any section the fetch's own schema names ([fetched], id to
         * label), then any top-level key [bundle] carries that neither names —
         * `_…` bookkeeping and `schema_version` aside. A section the vault adds
         * before this build learns it is still a card.
         */
        fun sectionsFor(schema: List<SchemaSection>, fetched: List<Pair<String, String>>, bundle: JSONObject?): List<SchemaSection> {
            val out = LinkedHashMap<String, SchemaSection>()
            schema.forEach { out[it.id] = it }
            fetched.forEach { (id, label) -> out.getOrPut(id) { SchemaSection(id, label, emptyList()) } }
            bundle?.keys()?.forEach { k ->
                if (!k.startsWith("_") && k != "schema_version") out.getOrPut(k) { SchemaSection(k, k, emptyList()) }
            }
            return out.values.toList()
        }

        private val baked: JSONObject? by lazy {
            runCatching {
                JSONObject(String(android.util.Base64.decode(
                    com.diegonmarcos.superapp.account.BuildConfig.UI_PROFILE_INFOS_B64, android.util.Base64.NO_WRAP)))
            }.getOrNull()
        }

        /** #783 A path the fleet manifest classes `secret` (AccountFleet.isSecret), set when the
         *  Account model loads: a credential stays masked even when its key looks harmless. */
        @Volatile var secretPath: (String) -> Boolean = { false }

        /** The baked declaration (UI_PROFILE_INFOS_B64). */
        val declared: InfoMask by lazy { parse(baked?.optJSONObject("mask")) }

        /** The baked schema (UI_PROFILE_INFOS_B64 `schema`). */
        val schema: List<SchemaSection> by lazy { parseSchema(baked?.optJSONObject("schema")) }
    }
}
