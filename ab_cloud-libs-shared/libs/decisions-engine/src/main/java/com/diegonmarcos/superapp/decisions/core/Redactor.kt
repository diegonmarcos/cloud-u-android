package com.diegonmarcos.superapp.decisions.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Everything that becomes a Decisions request's `state` passes through here first, so a secret a caller
 * forgot to strip never leaves the phone:
 *  - keys named in redact.drop_keys (message and page bodies, raw payloads, attachments) are removed;
 *  - the VALUE of a key whose name looks secret (token, key, password...) is masked, which is how an
 *    environment-style object is handled without knowing its keys;
 *  - every string goes through the declared patterns (private keys, API keys, JWTs, bearer headers...).
 *
 * A state that does not fit redact.max_chars is not truncated and sent: a model can only clear what it
 * can see, so the caller gets `state_too_large` and keeps today's behaviour.
 */
class Redactor(private val rules: RedactRules) {

    class Cleaned(val state: Any?, val masked: Int, val dropped: Int)

    fun clean(state: Any?): Cleaned {
        val c = Counter()
        return Cleaned(walk(state, c), c.masked, c.dropped)
    }

    /** True when the (already cleaned) state serialises within the cap. */
    fun fits(state: Any?): Boolean = state.toString().length <= rules.maxChars

    fun redactString(text: String): String =
        rules.patterns.fold(text) { acc, (re, sub) -> re.replace(acc, sub) }

    private class Counter(var masked: Int = 0, var dropped: Int = 0)

    private fun walk(x: Any?, c: Counter): Any? = when (x) {
        is String -> redactString(x).also { if (it != x) c.masked++ }
        is JSONObject -> JSONObject().also { out ->
            for (k in x.keys()) {
                when {
                    k.lowercase() in rules.dropKeys -> c.dropped++
                    rules.secretKey.containsMatchIn(k) && !x.isNull(k) -> { out.put(k, rules.mask); c.masked++ }
                    else -> out.put(k, walk(x.opt(k), c))
                }
            }
        }
        is JSONArray -> JSONArray().also { out -> for (i in 0 until x.length()) out.put(walk(x.opt(i), c)) }
        else -> x
    }
}
