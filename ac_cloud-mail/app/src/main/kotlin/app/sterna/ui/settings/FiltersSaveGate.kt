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

    /** Nothing unseen is at stake — write. */
    WRITE,
}

/**
 * Whether a Save on the filters screen may write straight away, or must ask first. Save regenerates
 */
internal fun filtersSaveStep(scriptUnreadable: Boolean): FiltersSaveStep =
    if (scriptUnreadable) FiltersSaveStep.CONFIRM_OVERWRITE else FiltersSaveStep.WRITE
