package app.sterna.core.data.account

import app.sterna.core.data.text.sanitiseReceivedHtml

/**
 * A stored HTML signature reduced to the markup that goes on the wire: trimmed, then through the one
 * sanitisation policy. The send path, [StoredSignature.renderableHtml] and the signature editor's live
 * preview all call this, so there is no second pipeline for a preview to disagree with.
 */
fun signatureWireHtml(html: String): String = sanitiseReceivedHtml(html.trim())
