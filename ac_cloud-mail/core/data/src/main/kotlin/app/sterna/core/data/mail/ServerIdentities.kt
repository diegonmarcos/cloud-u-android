package app.sterna.core.data.mail

import app.sterna.core.data.account.StoredIdentity
import app.sterna.core.jmap.model.Identity

/** What one `Identity/get` answer becomes in `StoredAccount.serverIdentities`. An identity with no
 *  address is dropped: it can be neither shown nor sent from, and would take a picker row that
 *  selects to nothing. A legacy signature with raw HTML in the plain-text field is split. */
internal fun storedServerIdentities(identities: List<Identity>): List<StoredIdentity> =
    identities
        .filter { it.email.isNotBlank() }
        .map {
            StoredIdentity(
                id = it.id,
                name = it.name.orEmpty(),
                email = it.email,
                signature = it.textSignature.orEmpty(),
                signatureHtml = it.htmlSignature.orEmpty(),
            ).withSplitSignature()
        }
