package com.diegonmarcos.clouddrive.storage

import com.diegonmarcos.cloudlib.rclone.unison.UnisonConfig
import org.json.JSONArray
import org.json.JSONObject

/**
 * Fsync > Folders Sync: the Unison pairs the USER makes. The APK ships only Unison's policy (data/drive-sync-rules.json
 * `unison`: the defaults and the shared ignore rules, no pair); the pairs live in one JSON text the phone keeps (and
 * Cloud Account owns a copy of, as its `unison_profiles` store). [compose] lays the user's pairs over the shipped
 * policy and hands the result to libs:rclone's [UnisonConfig.parse], so a pair is validated by the one parser the
 * engine trusts and a bad pair is a sentence, never a half-valid run. Pure.
 */
object UnisonPairs {

    /** What the add form collects. One of [host] (an SSH peer, mesh name) or [localPeer] (another folder) is set. */
    data class Draft(
        val name: String, val rootA: String, val host: String = "", val user: String = "", val port: Int = 22, val remotePath: String = "",
        val localPeer: String = "", val scheduleMinutes: Int? = null, val prefer: String? = null,
    )

    fun slug(name: String): String = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).ifBlank { "pair" }

    private fun pairs(userText: String): JSONArray =
        runCatching { JSONObject(userText.ifBlank { "{}" }).optJSONArray("pairs") }.getOrNull() ?: JSONArray()

    private fun list(userText: String): List<JSONObject> = pairs(userText).let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }

    private fun text(items: List<JSONObject>): String = JSONObject().put("pairs", JSONArray(items)).toString(2)

    fun entry(d: Draft, id: String): JSONObject = JSONObject().put("id", id).put("name", d.name).put("enabled", true).put("root_a", d.rootA.trim().trim('/')).also { o ->
        o.put("root_b", if (d.host.isNotBlank()) JSONObject().put("host", d.host.trim()).put("user", d.user.trim()).put("port", d.port).put("path", d.remotePath.trim()) else JSONObject().put("path", d.localPeer.trim()))
        d.scheduleMinutes?.let { o.put("schedule_minutes", it) }
        d.prefer?.let { o.put("prefer", it) }
    }

    /** The shipped policy (defaults, ignore) with [userText]'s pairs in place of whatever pairs it carried. */
    fun compose(policy: String, userText: String): String {
        val base = runCatching { JSONObject(policy.ifBlank { "{}" }) }.getOrDefault(JSONObject())
        base.remove("pairs")
        return base.put("pairs", pairs(userText)).toString()
    }

    fun parse(policy: String, userText: String, storageRoot: String): UnisonConfig.Declared =
        runCatching { UnisonConfig.parse(compose(policy, userText), storageRoot) }
            .getOrElse { UnisonConfig.Declared(emptyList(), listOf("the unison pairs are not valid JSON: ${it.message}")) }

    /** Adds the pair when the parser accepts it; otherwise a failure carrying the parser's own sentence. */
    fun add(userText: String, d: Draft, policy: String, storageRoot: String): Result<String> {
        val taken = list(userText).map { it.optString("id") }.toSet()
        val base = slug(d.name)
        val id = generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base-$it" }.first { it !in taken }
        val next = list(userText) + entry(d, id)
        val parsed = parse(policy, text(next), storageRoot)
        if (parsed.pairs.none { it.id == id }) return Result.failure(IllegalArgumentException(parsed.problems.firstOrNull { "'$id'" in it } ?: "the pair was not accepted"))
        return Result.success(text(next))
    }

    fun remove(userText: String, id: String): String = text(list(userText).filter { it.optString("id") != id })
}
