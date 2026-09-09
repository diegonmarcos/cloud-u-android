package app.sterna.core.data.filter

import app.sterna.core.data.mail.FilterRulesState

/**
 * The ACTIVE Sieve script that this app did not write, as the server holds it.
 */
data class ForeignScript(
    /** The name the server lists it under. Save does not delete it; it stops being the active one. */
    val name: String,
    /**
     * Its text, or null when the script list named it and its body could not be fetched. Null and
     * "" are kept apart deliberately: an unknown body must never read as an empty script, because
     * an empty script is the one case where replacing it costs nothing.
     */
    val body: String? = null,
)

/**
 * What reading an account's server-side filters answers — the decision behind
 * `MailRepository.loadFilterRules`, in one place so it can be executed by a test.
 *
 * [foreignScript] is the script and its content TOGETHER rather than a bare "someone else is
 * active" flag. The flag alone is what shipped the bug this signature closes (#209): the screen
 * knew another script was filtering the account's mail, had its blobId in hand, never downloaded
 * it, and drew "No rules yet. Add one…" over a mailbox that was being filtered. A caller cannot
 * now claim the first fact without carrying the second.
 */
fun loadedFilterRules(sternaScript: String?, foreignScript: ForeignScript?): FilterRulesState.Loaded {
    val parsed = sternaScript?.let { SieveCodec.parseRulesOrNull(it) }
    val unreadable = sternaScript != null &&
        (parsed == null || holdsForeignSieve(sternaScript, parsed))
    return FilterRulesState.Loaded(
        rules = parsed.orEmpty(),
        foreignActiveScript = foreignScript != null || unreadable,
        scriptUnreadable = unreadable,
        foreignScript = foreignScript,
    )
}

/**
 * Whether [script] holds Sieve this app did not write, its rules having been read as [parsed].
 */
internal fun holdsForeignSieve(script: String, parsed: List<FilterRule>): Boolean {
    if (script.isBlank()) return false
    return SieveCodec.sieveBody(script) != SieveCodec.sieveBody(SieveCodec.generate(parsed))
}
