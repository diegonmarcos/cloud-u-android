package com.diegonmarcos.clouddrive.storage

import org.json.JSONArray
import org.json.JSONObject

/**
 * The Mounts > Rclone page's `rclone config`, as data. Everything the wizard offers comes from rclone itself:
 * the backend list and each backend's option schema are the JSON of `rclone config providers` (parsed by
 * [parse]); nothing here names a backend. [steps] orders the questions the way rclone's terminal flow asks them
 * (required first-class options in schema order, advanced ones behind a toggle, options of another provider
 * variant skipped), and [createArgs] / [updateArgs] / [deleteArgs] are the argv of the real `rclone config` verbs.
 * Pure, so the JVM suite exercises exactly what the phone runs. No value is logged here: callers hold the secrets.
 */
object RcloneProviders {

    data class Example(val value: String, val help: String, val provider: String)

    data class Option(
        val name: String,
        val help: String,
        val default: String,
        val required: Boolean,
        val advanced: Boolean,
        val password: Boolean,
        val sensitive: Boolean,
        val exclusive: Boolean,
        val hiddenInConfigurator: Boolean,
        val type: String,
        val provider: String,
        val examples: List<Example>,
    ) {
        val isBool: Boolean get() = type == "bool"
        /** First line of the help: rclone prints the rest as a paragraph, a phone shows the line and a "more" fold. */
        val title: String get() = help.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
    }

    data class Provider(val name: String, val description: String, val options: List<Option>, val hidden: Boolean) {
        /** A backend that signs in through a browser (rclone's oauth helper adds a `token` option to every one of them). */
        val oauth: Boolean get() = options.any { it.name == "token" }
        /** Google Drive's own extras (shared drives, service account): the schema says so by naming the options. */
        val hasServiceAccount: Boolean get() = options.any { it.name == "service_account_credentials" }
        val hasSharedDrives: Boolean get() = options.any { it.name == "team_drive" }
    }

    /** rclone's own bit for "do not offer in the configurator" (fs.OptionHideConfigurator = 1 << 1). */
    private const val HIDE_CONFIGURATOR = 2

    /** The options the oauth helper owns: the wizard's browser step writes them, no question asks for them. */
    val OAUTH_OWNED = setOf("token", "auth_url", "token_url")

    /** Parse `rclone config providers`; a text that is not that JSON is an empty list, never a made-up one. */
    fun parse(json: String): List<Provider> {
        val arr = runCatching { JSONArray(json.trim()) }.getOrNull() ?: return emptyList()
        val out = ArrayList<Provider>()
        for (i in 0 until arr.length()) {
            val p = arr.optJSONObject(i) ?: continue
            val name = p.optString("Name")
            if (name.isBlank()) continue
            val opts = p.optJSONArray("Options") ?: JSONArray()
            val options = (0 until opts.length()).mapNotNull { opts.optJSONObject(it)?.let(::option) }
            out += Provider(name, p.optString("Description"), options, p.optBoolean("Hide", false))
        }
        return out.sortedBy { it.name }
    }

    private fun option(o: JSONObject): Option {
        val ex = o.optJSONArray("Examples") ?: JSONArray()
        val examples = (0 until ex.length()).mapNotNull { ex.optJSONObject(it) }.map { Example(it.optString("Value"), it.optString("Help"), it.optString("Provider")) }
        val default = when {
            o.has("DefaultStr") && !o.isNull("DefaultStr") -> o.optString("DefaultStr")
            o.has("Default") && !o.isNull("Default") -> o.opt("Default").toString()
            else -> ""
        }
        return Option(
            name = o.optString("Name"), help = o.optString("Help"), default = default,
            required = o.optBoolean("Required", false), advanced = o.optBoolean("Advanced", false),
            password = o.optBoolean("IsPassword", false), sensitive = o.optBoolean("Sensitive", false),
            exclusive = o.optBoolean("Exclusive", false),
            hiddenInConfigurator = (o.optInt("Hide", 0) and HIDE_CONFIGURATOR) != 0,
            type = o.optString("Type"), provider = o.optString("Provider"), examples = examples,
        )
    }

    /** rclone's `Provider` field on an option or example: empty = always, `A,B` = only for those, `!A,B` = for all but those. */
    fun applies(rule: String, chosen: String): Boolean {
        if (rule.isBlank()) return true
        val negate = rule.startsWith("!")
        val hit = rule.removePrefix("!").split(',').map { it.trim() }.contains(chosen)
        return if (negate) !hit else hit
    }

    /** The examples that apply to the provider variant chosen so far. */
    fun examples(option: Option, values: Map<String, String>): List<Example> =
        option.examples.filter { applies(it.provider, values["provider"].orEmpty()) }

    /**
     * The questions, in rclone's order, for [provider] given the [values] answered so far (a `provider` answer such as
     * S3's flavour changes which later options apply). Options the oauth helper owns and ones rclone hides from its own
     * configurator are never asked.
     */
    fun steps(provider: Provider, values: Map<String, String>, advanced: Boolean): List<Option> =
        provider.options.filter {
            it.name !in OAUTH_OWNED && !it.hiddenInConfigurator && (advanced || !it.advanced) && applies(it.provider, values["provider"].orEmpty())
        }

    /** A remote name rclone accepts: letters, digits, `_ - . + @ space`, not starting with `-` or space, not ending with space. */
    fun validName(name: String): Boolean =
        name.isNotEmpty() && Regex("[\\p{L}\\p{N}_.+@ -]+").matches(name) && !name.startsWith("-") && !name.startsWith(" ") && !name.endsWith(" ")

    /** An option key as rclone.conf spells it (lower-case snake): refuses anything that could become a flag or a second argument. */
    fun validKey(key: String): Boolean = Regex("[a-z0-9_]+").matches(key)

    private fun pairs(values: Map<String, String>): List<String> =
        values.filter { (k, v) -> validKey(k) && v.isNotEmpty() }.map { (k, v) -> "$k=$v" }

    /** `rclone config providers` */
    fun providersArgs(): List<String> = listOf("config", "providers")

    /** `rclone config create <name> <type> k=v...`: non-interactive, so a missing answer is an error, never a hidden prompt. */
    fun createArgs(name: String, type: String, values: Map<String, String>): List<String> {
        require(validName(name)) { "remote name '$name' is not one rclone accepts" }
        require(validKey(type)) { "backend '$type' is not a backend name" }
        return listOf("config", "create", name, type) + pairs(values) + "--non-interactive"
    }

    /** `rclone config update <name> k=v...` */
    fun updateArgs(name: String, values: Map<String, String>): List<String> {
        require(validName(name)) { "remote name '$name' is not one rclone accepts" }
        return listOf("config", "update", name) + pairs(values) + "--non-interactive"
    }

    fun showArgs(name: String): List<String> = listOf("config", "show", name)
    fun deleteArgs(name: String): List<String> = listOf("config", "delete", name)
    fun lsdArgs(name: String): List<String> = listOf("lsd", "$name:")
    fun aboutArgs(name: String): List<String> = listOf("about", "$name:", "--json")
    /** The Google Drive backend command that lists the account's shared drives (`rclone backend drives remote:`). */
    fun drivesArgs(name: String): List<String> = listOf("backend", "drives", "$name:")

    /**
     * `rclone authorize <backend>`: rclone's own browser flow. It serves a callback on 127.0.0.1:53682 (this phone), prints
     * the sign-in URL and, once the user approved, the token JSON between two marker lines ([token]).
     */
    fun authorizeArgs(type: String, clientId: String = "", clientSecret: String = ""): List<String> {
        require(validKey(type)) { "backend '$type' is not a backend name" }
        val base = listOf("authorize", type, "--auth-no-open-browser")
        return if (clientId.isNotBlank() && clientSecret.isNotBlank()) listOf("authorize", type, clientId, clientSecret, "--auth-no-open-browser") else base
    }

    private val URL_LINE = Regex("https?://127\\.0\\.0\\.1:\\d+/auth\\S*")

    /** The sign-in URL in rclone's output, or null. */
    fun authUrl(lines: List<String>): String? = lines.asSequence().mapNotNull { URL_LINE.find(it)?.value }.firstOrNull()

    /** The token JSON `rclone authorize` prints between "Paste the following into your remote machine --->" and "<---End paste". */
    fun token(output: String): String? {
        val lines = output.lines()
        val start = lines.indexOfFirst { it.contains("Paste the following") }
        val end = lines.indexOfFirst { it.contains("End paste") }
        val body = if (start >= 0 && end > start) lines.subList(start + 1, end).joinToString("").trim() else return null
        return body.takeIf { runCatching { JSONObject(it).has("access_token") }.getOrDefault(false) }
    }

    /** Lines of `rclone lsd`: `   -1 2024-01-01 00:00:00        -1 name` -> name. */
    fun lsd(output: String): List<String> = output.lines().mapNotNull { l ->
        val t = l.trim()
        if (t.isEmpty()) null else Regex("^-?\\d+\\s+\\S+\\s+\\S+\\s+-?\\d+\\s+(.*)$").find(t)?.groupValues?.get(1)
    }

    data class About(val total: Long?, val used: Long?, val free: Long?, val trashed: Long?)

    /** `rclone about --json`: the numbers the backend reports; a field it does not know is null, never zero. */
    fun about(json: String): About? {
        val o = runCatching { JSONObject(json.trim()) }.getOrNull() ?: return null
        fun n(k: String): Long? = if (o.has(k) && !o.isNull(k)) o.optLong(k) else null
        return About(n("total"), n("used"), n("free"), n("trashed"))
    }

    data class SharedDrive(val id: String, val name: String)

    /** `rclone backend drives`: a JSON array of {id, name, kind}. */
    fun drives(json: String): List<SharedDrive> {
        val arr = runCatching { JSONArray(json.trim()) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.filter { it.optString("id").isNotBlank() }.map { SharedDrive(it.optString("id"), it.optString("name")) }
    }

    /** Keys whose value must never be drawn or logged: rclone's own password/token/secret fields. */
    fun masked(key: String): Boolean = key == "token" || key.contains("pass") || key.contains("secret") || key.contains("key") || key.contains("credentials") || key.contains("auth")
}
