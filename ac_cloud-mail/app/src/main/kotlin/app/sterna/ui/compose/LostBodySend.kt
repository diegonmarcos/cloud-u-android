package app.sterna.ui.compose

/*
 * Rules for sending, saving or closing a composer whose body was lost, kept out of
 * `ComposeViewModel` so a JVM test can run them: nothing in this module can instantiate an
 */

/**
 * May this composer send THIS body? Refused only where both terms hold: the text was lost AND
 */
internal fun composerMaySendBody(bodyWasLost: Boolean, body: String): Boolean =
    !(bodyWasLost && body.isBlank())

/**
 * What a refused send has to say, which differs by route. The wording is a decision, so it is made
 * here and turned into an `R.string` by the caller.
 */
internal enum class LostBodyWording {
    /**
     * A draft is stored behind this screen, so the text is not gone: leave and open it again.
     */
    REOPEN_DRAFT,

    /** Nothing is stored anywhere: this composer never held a draft, so retyping is all there is. */
    RETYPE,
}

/**
 * Whether this send is refused for a lost body, and what to tell the user. `null` means the send
 */
internal fun lostBodySendWording(
    bodyWasLost: Boolean,
    body: String,
    holdsStoredDraft: Boolean,
): LostBodyWording? = when {
    composerMaySendBody(bodyWasLost, body) -> null
    holdsStoredDraft -> LostBodyWording.REOPEN_DRAFT
    else -> LostBodyWording.RETYPE
}

/**
 * Would THIS save destroy the queued row — the only store a save can take away?
 */
internal fun lostBodySaveDestroysQueuedRow(
    bodyWasLost: Boolean,
    body: String,
    holdsQueuedRow: Boolean,
): Boolean = holdsQueuedRow && !composerMaySendBody(bodyWasLost, body)

/**
 * Does CLOSING this composer park the queued row rather than give it back to the queue? When true,
 */
internal fun closingParksQueuedRow(bodyWasLost: Boolean, body: String): Boolean =
    !composerMaySendBody(bodyWasLost, body)
