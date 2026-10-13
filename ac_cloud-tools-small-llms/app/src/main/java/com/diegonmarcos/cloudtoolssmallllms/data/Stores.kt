package com.diegonmarcos.cloudtoolssmallllms.data

import android.content.Context
import com.diegonmarcos.cloudtoolssmallllms.core.Ledger
import org.json.JSONArray
import org.json.JSONObject

/** One line of the chat as it is stored and drawn. [kind]: user, answer, clarify or error. */
data class Msg(
    val id: Long, val kind: String, val text: String, val at: Long,
    val task: String = "", val model: String = "", val cost: Double? = null,
    val routerModel: String = "", val routerCost: Double = 0.0, val confidence: Double = 0.0,
    val candidates: List<String> = emptyList(), val request: String = "",
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("kind", kind).put("text", text).put("at", at)
        .put("task", task).put("model", model).put("cost", cost ?: JSONObject.NULL).put("router_model", routerModel)
        .put("router_cost", routerCost).put("confidence", confidence).put("candidates", JSONArray(candidates)).put("request", request)

    companion object {
        const val USER = "user"
        const val ANSWER = "answer"
        const val CLARIFY = "clarify"
        const val ERROR = "error"

        fun fromJson(o: JSONObject): Msg = Msg(
            o.optLong("id"), o.optString("kind"), o.optString("text"), o.optLong("at"), o.optString("task"), o.optString("model"),
            o.opt("cost").let { if (it is Number) it.toDouble() else null }, o.optString("router_model"), o.optDouble("router_cost", 0.0),
            o.optDouble("confidence", 0.0),
            o.optJSONArray("candidates")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty(), o.optString("request"),
        )
    }
}

/**
 * Everything this app keeps that is not a secret, in SharedPreferences [PREFS] (fleet manifest
 * store, class content / config): the chat, the cost ledger, the router choice, the per-task
 * worker overrides and the last read of Jev's availability. The token is NOT here (SecretPrefs).
 */
class Stores(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun messages(): List<Msg> = runCatching {
        val a = JSONArray(prefs.getString(MESSAGES, "[]"))
        (0 until a.length()).map { Msg.fromJson(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    fun saveMessages(list: List<Msg>) {
        prefs.edit().putString(MESSAGES, JSONArray(list.takeLast(MAX_MESSAGES).map { it.toJson() }).toString()).apply()
    }

    fun ledger(): Ledger = Ledger.fromJson(prefs.getString(LEDGER, null))

    fun saveLedger(l: Ledger) {
        prefs.edit().putString(LEDGER, l.toJson()).apply()
    }

    var router: String
        get() = prefs.getString(ROUTER, "").orEmpty()
        set(v) { prefs.edit().putString(ROUTER, v).apply() }

    fun workers(): Map<String, String> = runCatching {
        val o = JSONObject(prefs.getString(WORKERS, "{}") ?: "{}")
        o.keys().asSequence().associateWith { o.getString(it) }
    }.getOrDefault(emptyMap())

    fun saveWorkers(m: Map<String, String>) {
        prefs.edit().putString(WORKERS, JSONObject(m).toString()).apply()
    }

    /** The last read of the decision catalogue: listed (null = never read), its $/1M input, when. */
    fun jev(): Triple<Boolean?, Double?, Long> {
        if (!prefs.contains(JEV_LISTED)) return Triple(null, null, 0L)
        val price = prefs.getFloat(JEV_PRICE, -1f).toDouble().takeIf { it >= 0 }
        return Triple(prefs.getBoolean(JEV_LISTED, false), price, prefs.getLong(JEV_AT, 0L))
    }

    fun saveJev(listed: Boolean, price: Double?, at: Long) {
        prefs.edit().putBoolean(JEV_LISTED, listed).putFloat(JEV_PRICE, (price ?: -1.0).toFloat()).putLong(JEV_AT, at).apply()
    }

    companion object {
        const val PREFS = "cloud_tools_small_llms"
        private const val MESSAGES = "messages"
        private const val LEDGER = "ledger"
        private const val ROUTER = "router"
        private const val WORKERS = "workers"
        private const val JEV_LISTED = "jev_listed"
        private const val JEV_PRICE = "jev_price_in"
        private const val JEV_AT = "jev_checked_at"
        private const val MAX_MESSAGES = 300
    }
}
