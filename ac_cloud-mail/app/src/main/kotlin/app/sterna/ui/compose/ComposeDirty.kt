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
    ): Boolean =
        // An undone send lives only on this screen: guard it even untouched, closing destroys it.
        onlyCopy ||
            addressSet(to) != addressSet(initialTo) ||
            addressSet(cc) != addressSet(initialCc) ||
            addressSet(bcc) != addressSet(initialBcc) ||
            subject != initialSubject ||
            body != initialBody ||
            requestReceipt != initialRequestReceipt ||
            attachmentsTouched
}
