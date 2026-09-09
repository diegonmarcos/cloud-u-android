package app.sterna.core.data.filter

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Compiles structured [FilterRule]s to a Sieve script and reads them back.
 */
object SieveCodec {
    const val SCRIPT_NAME = "sterna"

    /**
     * **The Sieve emitted below is a compatibility surface. Changing it is expensive, and bumping
     */
    private const val MARKER = "# STERNA-RULES-V1:"
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val serializer = ListSerializer(FilterRule.serializer())

    /**
     * Build the Sieve script (with embedded JSON metadata) for [rules].
     */
    fun generate(rules: List<FilterRule>): String {
        val sb = StringBuilder()
        sb.append(MARKER).append(' ').append(json.encodeToString(serializer, rules)).append('\n')

        val active = rules.filter { it.enabled && it.value.isNotBlank() }
        val requires = buildList {
            if (active.any { it.moveTo != null }) add("fileinto")
            if (active.any { it.markRead || it.flag }) add("imap4flags")
        }
        if (requires.isNotEmpty()) {
            sb.append("require [").append(requires.joinToString(", ") { "\"$it\"" }).append("];\n")
        }
        sb.append('\n')
        for (rule in active) sb.append(ruleToSieve(rule)).append('\n')
        return sb.toString()
    }

    /**
     * The rules a script carries, or **null when this script cannot be read as ours**: content, but
     */
    fun parseRulesOrNull(script: String): List<FilterRule>? {
        if (script.isBlank()) return emptyList()
        val line = script.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith(MARKER) } ?: return null
        val payload = line.removePrefix(MARKER).trim()
        return runCatching { json.decodeFromString(serializer, payload) }.getOrNull()
    }

    /**
     * Parse the rule list out of a script's JSON metadata comment (empty if none). Only for callers
     */
    fun parseRules(script: String): List<FilterRule> = parseRulesOrNull(script) ?: emptyList()

    /**
     * [script] reduced to the Sieve it runs: its marker line removed whole, blanks normalised — the
     */
    fun sieveBody(script: String): String {
        val lines = script.replace("\r\n", "\n").replace('\r', '\n').lines()
        val marker = lines.indexOfFirst { it.trim().startsWith(MARKER) }
        return lines.filterIndexed { i, _ -> i != marker }
            .joinToString("\n") { it.trimEnd(' ', '\t') }
            .trim()
    }

    private fun ruleToSieve(rule: FilterRule): String {
        val matchTag = if (rule.match == RuleMatch.IS) ":is" else ":contains"
        val value = escape(rule.value)
        val test = when (rule.field) {
            RuleField.SUBJECT -> "header $matchTag \"subject\" \"$value\""
            RuleField.FROM -> "address $matchTag \"from\" \"$value\""
            RuleField.TO -> "address $matchTag \"to\" \"$value\""
            RuleField.CC -> "address $matchTag \"cc\" \"$value\""
        }
        val body = StringBuilder()
        // Flags must be set before fileinto/keep so the filed copy carries them.
        if (rule.markRead) body.append("    addflag \"\\\\Seen\";\n")
        if (rule.flag) body.append("    addflag \"\\\\Flagged\";\n")
        if (rule.moveTo != null) body.append("    fileinto \"${escape(rule.moveTo)}\";\n")
        return "if $test {\n$body}"
    }

    /** Escape a value for a Sieve quoted-string (backslash and double-quote). */
    private fun escape(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")
}
