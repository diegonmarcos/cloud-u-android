package app.sterna.core.data.account

import app.sterna.core.data.text.htmlToText
import app.sterna.core.data.text.looksLikeHtml
import kotlinx.serialization.Serializable

/** A sending identity: "from" name + address + signature. Stored twice — [signature] plain
 *  text, [signatureHtml] optional HTML — as JSON (not Room), so new fields default cleanly. */
@Serializable
data class StoredIdentity(
    val id: String,
    val name: String,
    val email: String,
    val signature: String = "",
    /** Rich version of [signature], sent as-is in the html alternative. Empty = plain text only. */
    val signatureHtml: String = "",
) {
    /** "Name <email>" or just the address when unnamed. */
    fun display(): String = if (name.isBlank()) email else "$name <$email>"

    /** Splits a legacy signature holding raw HTML in [signature] (pre-1.3.13 "Import HTML")
     *  into [signatureHtml] + flattened text. Idempotent otherwise. */
    fun withSplitSignature(): StoredIdentity =
        if (signatureHtml.isBlank() && looksLikeHtml(signature)) {
            copy(signature = htmlToText(signature), signatureHtml = signature)
        } else {
            this
        }
}

/** Every address that IS the user on this account, plus its login. [username] is added even
 *  when identities name it: a linked sub-account resolves identities through its LOGIN (#31). */
fun accountAddresses(identities: List<StoredIdentity>, username: String?): List<String> =
    identities.map { it.email } + listOfNotNull(username)
