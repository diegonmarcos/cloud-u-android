package app.sterna.core.data.filter

import app.sterna.core.data.mail.FilterRulesState

/**
 * "File future mail from [address] into [trashFolder], already read."
 */
fun blockRule(address: String, trashFolder: String): FilterRule = FilterRule(
    name = address,
    enabled = true,
    field = RuleField.FROM,
    match = RuleMatch.IS,
    value = address,
    moveTo = trashFolder,
    markRead = true,
)

/**
 * Whether [rules] already send [address] away — a FROM/IS rule on the same address, case ignored,
 */
fun alreadyBlocked(rules: List<FilterRule>, address: String): Boolean = rules.any {
    it.field == RuleField.FROM && it.match == RuleMatch.IS && it.value.equals(address, ignoreCase = true)
}

/**
 * Whether a block rule may target [address] at all — asked before anything the account's script
 */
fun blockableSender(address: String, ownAddresses: List<String>): Boolean {
    val target = address.trim()
    if (target.isEmpty()) return false
    return ownAddresses.none { it.trim().equals(target, ignoreCase = true) }
}

/**
 * [loaded] plus the new rule — ADDED, never substituted. `MailRepository.saveFilterRules`
 */
fun withBlockRule(loaded: List<FilterRule>, address: String, trashFolder: String): List<FilterRule> =
    if (alreadyBlocked(loaded, address)) loaded else loaded + blockRule(address, trashFolder)

/** What [addBlockRule] did. */
enum class BlockOutcome {
    /** The script now carries the rule, on top of everything it already carried. */
    ADDED,

    /** A FROM/IS rule for this address was already there; nothing was written. */
    ALREADY_PRESENT,

    /** The rules could not be read, or the save was refused. NOTHING was written. */
    FAILED,
}

/**
 * Add the block rule for [address] to the account's server-side script: read the rules, then write
 */
suspend fun addBlockRule(
    address: String,
    trashFolder: String,
    load: suspend () -> FilterRulesState,
    save: suspend (List<FilterRule>) -> Unit,
): BlockOutcome {
    val state = runCatching { load() }.getOrNull()
    if (state !is FilterRulesState.Loaded) return BlockOutcome.FAILED
    // The duplicate check answers BEFORE the foreign-script refusal, and the order is the message.
    // Neither branch writes, so they differ only in what the screen says: on an address the script
    // already handles, nothing went wrong.
    if (alreadyBlocked(state.rules, address)) return BlockOutcome.ALREADY_PRESENT
    if (state.foreignActiveScript) return BlockOutcome.FAILED
    return runCatching { save(withBlockRule(state.rules, address, trashFolder)) }
        .fold(onSuccess = { BlockOutcome.ADDED }, onFailure = { BlockOutcome.FAILED })
}
