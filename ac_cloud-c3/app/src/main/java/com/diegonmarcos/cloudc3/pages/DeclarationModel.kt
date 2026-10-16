package com.diegonmarcos.cloudc3.pages

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The More page's content as data: a container's declaration (data/declarations.json, derived by
 * gen_infra_slice.py from the consolidated build.json / compose / caddy declaration) cut into readable sections, plus
 * the raw JSON. Environment variables appear as NAMES only; the file never carries a value.
 */
object DeclarationModel {

    data class Section(val title: String, val lines: List<Pair<String, String>>)

    private val pretty = Json { prettyPrint = true; prettyPrintIndent = "  " }

    fun raw(decl: JsonObject): String = pretty.encodeToString(JsonElement.serializer(), decl)

    private fun JsonObject.text(k: String): String = when (val v = this[k]) {
        null, JsonNull -> ""
        is JsonPrimitive -> v.contentOrNull.orEmpty()
        else -> v.toString()
    }

    private fun JsonObject.list(k: String): List<String> = (this[k] as? JsonArray).orEmpty().map { (it as? JsonPrimitive)?.contentOrNull ?: it.toString() }
    private fun JsonObject.obj(k: String): JsonObject = this[k] as? JsonObject ?: JsonObject(emptyMap())

    fun sections(d: JsonObject): List<Section> {
        val vm = d.obj("vm")
        val ports = d.obj("ports")
        val env = d.obj("env")
        val routes = d.obj("routes")
        val res = d["resources"] as? JsonObject
        fun l(vararg p: Pair<String, String>) = p.filter { it.second.isNotBlank() }
        return listOf(
            Section("Overview", l("Container" to d.text("container"), "Service" to d.text("service"),
                "Category" to d.text("category"), "Description" to d.text("description"), "Source folder" to d.text("folder"))),
            Section("VM", l("Name" to vm.text("name"), "Direct address" to vm.text("addr"), "Mesh IP" to vm.text("wg_ip"), "Public IP" to vm.text("ip"))),
            Section("Image and runtime", l("Image" to d.text("image"), "Read-only root" to d.text("read_only"),
                "Restart policy" to d.text("restart_policy"), "Network mode" to d.text("network_mode"))),
            Section("Ports", l("Container port" to ports.text("container"), "Port env var" to ports.text("env"), "Protocol" to ports.text("protocol")) +
                (ports["extra"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let { e -> "Extra ${e.text("port")}" to listOf(e.text("protocol"), e.text("service")).filter { s -> s.isNotBlank() }.joinToString(" ") } }),
            Section("Volumes", d.list("volumes").map { "Mount" to it }),
            Section("Environment (names only, never values)", l("Env file" to env.text("env_file")) +
                env.list("names").map { "Variable" to it } + env.list("secret_names").map { "Secret (name only)" to it }),
            Section("Resources", res?.entries?.map { it.key to it.value.toString().trim('"') }.orEmpty()),
            Section("Health check", l("Command" to d.text("healthcheck")) + (d["monitoring"] as? JsonObject)?.entries?.map { "Monitoring ${it.key}" to it.value.toString().trim('"') }.orEmpty()),
            Section("Routes and auth", l("Public URL" to routes.text("public_url"), "Domain" to routes.text("domain"), "Base path" to routes.text("base_path"),
                "Auth tier" to routes.text("auth_tier"), "Declared auth" to routes.text("declared_auth"), "Mesh-only (wg_only)" to routes.text("wg_only"),
                "Private URL" to routes.text("private_url"), "Private DNS" to routes.text("dns"))),
            Section("Dependencies", d.list("depends_on").map { "Depends on" to it }),
        ).filter { it.lines.isNotEmpty() }
    }
}
