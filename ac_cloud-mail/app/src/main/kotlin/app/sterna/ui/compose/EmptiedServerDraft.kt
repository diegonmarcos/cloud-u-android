package app.sterna.ui.compose

/*
 * Emptying a draft this composer opened STRAIGHT OFF THE SERVER (#69 × #63), kept out of
 * `ComposeViewModel` so a JVM test can run it, for `EmptiedLocalDraft.kt`'s reason.
 */

/**
 * Is the draft on the server KEPT by this emptying? The twin of [emptiedLocalDraftKeepsServerCopy],
 */
fun emptiedServerDraftIsKept(bodyIsLossy: Boolean, addressingIsProvenEmpty: Boolean): Boolean =
    bodyIsLossy || !addressingIsProvenEmpty

/**
 * Whose credentials expunge the SERVER draft this composer opened, once emptied and saved: the account
 */
fun <T : Any> credentialsDestroyingEmptiedServerDraft(
    openedUnderAccountId: String?,
    @Suppress("UNUSED_PARAMETER") composingAsAccountId: String?,
    lookup: (String) -> T?,
): T? = openedUnderAccountId?.let(lookup)
