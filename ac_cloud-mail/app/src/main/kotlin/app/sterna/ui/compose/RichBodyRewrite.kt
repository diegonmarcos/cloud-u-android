package app.sterna.ui.compose

import app.sterna.core.data.text.RichBody
import app.sterna.core.data.text.Span
import app.sterna.core.data.text.remapAfterEdit

/**
 * Applies a signature [rewrite] (a swap or an insertion, see `SignatureChange`) to a rich body
 */
fun rewriteRichBody(rich: RichBody, caret: Int, rewrite: (String) -> String?): RichBody? =
    rewrite(rich.text)?.let { remapAfterEdit(rich, it, Span(caret, caret), Span(caret, caret), null) }
