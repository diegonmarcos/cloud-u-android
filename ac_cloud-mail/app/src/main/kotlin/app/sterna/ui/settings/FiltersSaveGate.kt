package app.sterna.ui.settings

/**
 * When the filters screen may offer its Save button (#34). Save regenerates the script and makes it
 */
internal fun filtersSaveEnabled(saving: Boolean, dirty: Boolean, rulesNotRunning: Boolean): Boolean =
    !saving && (dirty || rulesNotRunning)

/** What a Save gesture on the filters screen must do next. */
internal enum class FiltersSaveStep {
    /** Ask before writing: this save replaces a script nobody has read. */
    CONFIRM_OVERWRITE,

    /** Ask before writing: a DIFFERENT script is filtering this account's mail right now, and the
     *  save switches it off by activating ours. */
    CONFIRM_TAKEOVER,

    /** Nothing unseen is at stake — write. */
    WRITE,
}

/**
 * Whether a Save on the filters screen may write straight away, or must ask first. Save regenerates
 * the `sterna` script from the list on screen and makes it the account's ACTIVE one, so both
 * questions here are about content the owner is not looking at.
 *
 * [foreignActive] used to reach the write unasked (#209), and that was the expensive half: with a
 * foreign script running and this screen listing nothing, the very first rule the owner added
 * activated a one-rule script and stopped the one holding their real filters. A save that
 * deactivates somebody else's working script is a decision, not a side effect.
 */
internal fun filtersSaveStep(scriptUnreadable: Boolean, foreignActive: Boolean): FiltersSaveStep = when {
    // Ours-but-unreadable first: it names the sharper loss (content replaced, not just switched
    // off), and the rules read raises foreignActive alongside it for the same script.
    scriptUnreadable -> FiltersSaveStep.CONFIRM_OVERWRITE
    foreignActive -> FiltersSaveStep.CONFIRM_TAKEOVER
    else -> FiltersSaveStep.WRITE
}
