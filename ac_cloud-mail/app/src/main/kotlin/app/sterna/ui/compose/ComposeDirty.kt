package app.sterna.ui.compose

import app.sterna.core.data.text.RichBody

/**
 * The unsaved-changes verdict, pulled out of the composable so it can be unit-tested (#70/#94).
 */
internal object ComposeDirty {
    private fun addressSet(value: String): Set<String> =
        recipientTokens(value).map { it.trim().lowercase() }.toSet()

    fun isDirty(
        onlyCopy: Boolean,
        to: String,
        initialTo: String,
        cc: String,
        initialCc: String,
        bcc: String,
        initialBcc: String,
        subject: String,
        initialSubject: String,
        body: RichBody,
        initialBody: RichBody,
        requestReceipt: Boolean,
        initialRequestReceipt: Boolean,
        attachmentsTouched: Boolean,
        /** The markup differs from the one the body opened with, though the text may not. */
        htmlEdited: Boolean = false,
    ): Boolean =
        // An undone send lives only on this screen: guard it even untouched, closing destroys it.
        onlyCopy ||
            addressSet(to) != addressSet(initialTo) ||
            addressSet(cc) != addressSet(initialCc) ||
            addressSet(bcc) != addressSet(initialBcc) ||
            subject != initialSubject ||
            body != initialBody ||
            requestReceipt != initialRequestReceipt ||
            attachmentsTouched ||
            htmlEdited
}

/**
 * Whether the body's markup was changed since it opened. False while there is no baseline yet (the
 * page has not reported), so an empty new composer is never "edited" by the page settling.
 */
internal fun htmlIsDirty(html: String, baseline: String): Boolean = baseline.isNotEmpty() && html != baseline
