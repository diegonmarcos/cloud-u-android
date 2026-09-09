package app.sterna.core.data.filter

/**
 * The name a server that implements the vacation responder as a Sieve script gives that script. Its
 */
const val VACATION_SCRIPT_NAME = "vacation"

/**
 * What the account's Sieve scripts say about the user's filter rules. The default is the "we know
 */
data class FilterScriptStatus(
    /** A script named `sterna` exists on the server. */
    val scriptExists: Boolean = false,
    /** That script is the ACTIVE one. A server keeps a single active script per account. */
    val scriptActive: Boolean = false,
    /** How many rules it carries that actually filter mail (see [enabledRuleCount]). */
    val enabledRuleCount: Int = 0,
    /** A script named [VACATION_SCRIPT_NAME] exists next to it — EXISTENCE, not activity: what
     *  [filterScriptWarning] predicts on. */
    val vacationScriptExists: Boolean = false,
    /** That `vacation` script is the ACTIVE one. Separate from [vacationScriptExists] because only
     *  this one is about the present: "saving will stop your auto-reply" is false on an account
     *  whose active script is a third one. */
    val vacationScriptActive: Boolean = false,
    /** Some script OTHER than `sterna` is the active one — so a save, which activates Sterna's
     *  script, switches that one off. */
    val foreignActive: Boolean = false,
)

/** What a screen must tell the user about the state of their filter rules, or null for nothing. */
enum class FilterScriptWarning {
    /**
     * The rules exist but are NOT running: the script that carries them is not the active one.
     */
    RULES_NOT_RUNNING,

    /** The rules ARE running, but this server materialises the responder as a Sieve script, so
     *  turning it on will make that script the active one and stop the rules. */
    RESPONDER_WILL_SUSPEND_RULES,
}

/**
 * Which warning (if any) the vacation and filter screens must carry. The order of the guards is the
 */
fun filterScriptWarning(
    scriptExists: Boolean,
    scriptActive: Boolean,
    enabledRuleCount: Int,
    vacationScriptExists: Boolean,
    responderEnabled: Boolean?,
): FilterScriptWarning? = when {
    !scriptExists -> null
    enabledRuleCount == 0 -> null
    !scriptActive -> FilterScriptWarning.RULES_NOT_RUNNING
    vacationScriptExists && responderEnabled == false -> FilterScriptWarning.RESPONDER_WILL_SUSPEND_RULES
    else -> null
}

/**
 * The warning to show after a status read that may have failed.
 */
fun refreshedFilterWarning(
    previous: FilterScriptWarning?,
    status: FilterScriptStatus?,
    responderEnabled: Boolean?,
): FilterScriptWarning? = if (status == null) {
    previous
} else {
    filterScriptWarning(
        scriptExists = status.scriptExists,
        scriptActive = status.scriptActive,
        enabledRuleCount = status.enabledRuleCount,
        vacationScriptExists = status.vacationScriptExists,
        responderEnabled = responderEnabled,
    )
}

/** Which "another script is active" line the filters screen must carry, or null for none. */
enum class ForeignScriptNotice {
    /** Some other script is active. Whose, and what it does, is unknown — so nothing is claimed. */
    ANOTHER_SCRIPT,

    /** The `vacation` script IS the active one: what Save costs is the auto-reply. */
    STOPS_AUTO_REPLY,

    /** This account has a `sterna` script nobody could parse: what a save would do here is replace
     *  content no one has read. */
    UNREADABLE_SCRIPT,
}

/**
 * Which foreign-script line the filters screen shows above its Save button. Save does not merely
 */
fun foreignScriptNotice(
    scriptUnreadable: Boolean,
    foreignActive: Boolean,
    vacationScriptActive: Boolean,
): ForeignScriptNotice? = when {
    scriptUnreadable -> ForeignScriptNotice.UNREADABLE_SCRIPT
    !foreignActive -> null
    vacationScriptActive -> ForeignScriptNotice.STOPS_AUTO_REPLY
    else -> ForeignScriptNotice.ANOTHER_SCRIPT
}

/**
 * Whether the filters screen prints "No rules yet. Add one…". An empty rule list has three causes
 * and only ONE of them is that sentence:
 *
 *  - nothing is filtering this account — the sentence is true, and the invitation is right;
 *  - this app's own script exists and could not be parsed ([scriptUnreadable]);
 *  - another script is filtering the account right now ([foreignActive]) and this app cannot
 *    express it as rules.
 *
 * The last one is #209. Zero rules HERE was printed as zero rules ON THE SERVER, next to a line
 * saying another script was active — the app told the owner their working filters did not exist,
 * and invited them to start from nothing.
 */
fun showsNoRulesNote(ruleCount: Int, scriptUnreadable: Boolean, foreignActive: Boolean): Boolean =
    ruleCount == 0 && !scriptUnreadable && !foreignActive

/** Which sentence the RESPONDER screen puts at its head, or null for none. */
enum class VacationFilterLine {
    /** The rules are not running, and the way to put them back is named. */
    RULES_NOT_RUNNING_WITH_REMEDY,

    /** The rules are not running. The fact alone: the remedy would undo this very screen. */
    RULES_NOT_RUNNING_FACT,

    /** Switching the responder on will suspend the rules. */
    RESPONDER_WILL_SUSPEND_RULES,
}

/**
 * Which of the two "not running" sentences the responder screen shows. The remedy — "you can put
 */
fun vacationFilterLine(warning: FilterScriptWarning?, responderEnabled: Boolean): VacationFilterLine? =
    when (warning) {
        null -> null
        FilterScriptWarning.RESPONDER_WILL_SUSPEND_RULES -> VacationFilterLine.RESPONDER_WILL_SUSPEND_RULES
        FilterScriptWarning.RULES_NOT_RUNNING ->
            if (responderEnabled) {
                VacationFilterLine.RULES_NOT_RUNNING_FACT
            } else {
                VacationFilterLine.RULES_NOT_RUNNING_WITH_REMEDY
            }
    }

/**
 * Whether [warning] is the statement of fact — the rules are on the server and not running. A
 */
fun rulesAreNotRunning(warning: FilterScriptWarning?): Boolean = warning == FilterScriptWarning.RULES_NOT_RUNNING

/**
 * How many of [rules] actually filter mail: enabled, and with something to match on. Same predicate
 * as the one [SieveCodec.generate] emits Sieve for.
 */
fun enabledRuleCount(rules: List<FilterRule>): Int = rules.count { it.enabled && it.value.isNotBlank() }
