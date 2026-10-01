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

    enum class Kind { SHOWN, MASKED, PENDING, COLLAPSED }

    /** One read-out line. [text] is empty for MASKED; [size] is the masked
     *  value's length, or a collapsed container's entry count. */
    data class Row(val path: String, val text: String, val kind: Kind, val size: Int = 0)

    private val pathRes = paths.map { runCatching { Regex(it, RegexOption.IGNORE_CASE) }.getOrNull() }
    private val valueRes = values.map { runCatching { Regex(it) }.getOrNull() }

    /** True when the declaration cannot be trusted to protect anything. */
    val maskAll: Boolean =
        (pathRes.isEmpty() && valueRes.isEmpty()) || pathRes.any { it == null } || valueRes.any { it == null }

    fun hides(fullPath: String, value: String): Boolean =
        maskAll || pathRes.any { it!!.containsMatchIn(fullPath) } || valueRes.any { it!!.containsMatchIn(value) }

    /** Every row of [value], the bundle's section [sectionId]. */
    fun rows(sectionId: String, value: Any?): List<Row> {
        val out = mutableListOf<Row>()
        walk(sectionId, value, "", out)
        return out
    }

    private fun walk(section: String, v: Any?, path: String, out: MutableList<Row>) {
        when {
            v is JSONObject && v.optBoolean("pending") ->
                out += Row(path, "${v.optString("source")} · ${v.optString("reason")}", Kind.PENDING)
            v is JSONObject && v.length() > collapseOver -> out += Row(path, "", Kind.COLLAPSED, v.length())
            v is JSONArray && v.length() > collapseOver -> out += Row(path, "", Kind.COLLAPSED, v.length())
            v is JSONObject && v.length() > 0 -> v.keys().forEach { k -> walk(section, v.opt(k), join(path, k), out) }
            v is JSONArray && v.length() > 0 -> (0 until v.length()).forEach { i -> walk(section, v.opt(i), join(path, "[$i]"), out) }
            else -> {
                val text = if (v == null || v == JSONObject.NULL) "null" else v.toString()
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

        /** The baked declaration (UI_PROFILE_INFOS_B64). */
        val declared: InfoMask by lazy {
            parse(runCatching {
                JSONObject(String(android.util.Base64.decode(
                    com.diegonmarcos.superapp.BuildConfig.UI_PROFILE_INFOS_B64, android.util.Base64.NO_WRAP)))
                    .optJSONObject("mask")
            }.getOrNull())
        }
    }
}
