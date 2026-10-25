package com.diegonmarcos.cloudsuperintel

import org.json.JSONObject

/** One SI persona as assets/si/personas.json declares it. */
data class Persona(
    val id: String,
    val label: String,
    val icon: String,
    val role: String,
    val system: String,
    val suggestions: List<String>,
    val agent: String,
    val models: List<String>,
    val web: Boolean,
    val tools: List<String>,
    val router: Boolean,
)

/** The personas, parsed from data: Kotlin names none of them. */
object Personas {
    const val ASSET = "si/personas.json"

    fun parse(text: String): List<Persona> {
        val a = JSONObject(text).getJSONArray("personas")
        return (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
            fun list(k: String) = o.optJSONArray(k)?.let { x -> (0 until x.length()).map { x.getString(it) } } ?: emptyList()
            Persona(
                o.getString("id"), o.getString("label"), o.getString("icon"), o.optString("role"), o.getString("system"),
                list("suggestions"), o.optString("agent"), list("models"), o.optBoolean("web"), list("tools"), o.optBoolean("router"),
            )
        }
    }

    /** The system prompt a persona sends; the router's {{personas}} becomes one "label: role" line per other persona. */
    fun system(p: Persona, all: List<Persona>): String =
        p.system.replace("{{personas}}", all.filter { it.id != p.id }.joinToString("\n") { "- ${it.label}: ${it.role}" })

    /** The first model of [p]'s preference the gateway lists; null = leave the user's pick. */
    fun preferredModel(p: Persona, listed: List<String>): String? = p.models.firstOrNull { it in listed }
}
