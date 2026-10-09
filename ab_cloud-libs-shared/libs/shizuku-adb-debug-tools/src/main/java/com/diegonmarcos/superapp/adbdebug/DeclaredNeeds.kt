package com.diegonmarcos.superapp.adbdebug

import org.json.JSONObject

/** One thing an app declares it needs the privileged channel for. */
data class Need(val id: String, val label: String, val why: String, val commands: List<String>)

/**
 * "Declared needs": what THIS app says it uses the channel for, read from its own
 * build.json::privileged_channel.needs[] (baked into BuildConfig by this module's build.gradle, the same
 * consumer-wins path as the local-server port). Nothing is typed into the page: add a need to the app's
 * build.json and it shows here; an app that declares none shows none.
 */
object DeclaredNeeds {

    /** [json] is the whole `privileged_channel` object (or null/blank/garbage = no needs). */
    fun parse(json: String?): List<Need> {
        if (json.isNullOrBlank()) return emptyList()
        val arr = runCatching { JSONObject(json).optJSONArray("needs") }.getOrNull() ?: return emptyList()
        val seen = HashSet<String>()
        val out = ArrayList<Need>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id").trim()
            if (id.isEmpty() || !seen.add(id)) continue
            val cmds = o.optJSONArray("commands")
            out += Need(
                id = id,
                label = o.optString("label").ifBlank { id },
                why = o.optString("why"),
                commands = if (cmds == null) emptyList() else (0 until cmds.length()).map { cmds.optString(it) }.filter { it.isNotBlank() },
            )
        }
        return out
    }

    fun decode(b64: String): String? =
        if (b64.isBlank()) null else runCatching { String(java.util.Base64.getDecoder().decode(b64), Charsets.UTF_8) }.getOrNull()

    /** This build's declared needs (BuildConfig.CHANNEL_NEEDS_B64). */
    fun current(): List<Need> = parse(decode(BuildConfig.CHANNEL_NEEDS_B64))
}
