package app.sterna.core.data.mail

import app.sterna.core.jmap.model.Identity

/** Which server identity a JMAP submission is created under, and whether it must carry an explicit
 *  `envelope` (RFC 8621 §7.5) so the SMTP `MAIL FROM` matches the `From:` the reader sees. An
 *  identity typed by hand exists only on the phone, and the envelope the server would derive from
 *  the fallback identity leaves the message with a mismatch DMARC fails (#172). */
object SubmissionIdentity {

    /** The identity to submit under, and the envelope's `mailFrom` — null when the server derives it. */
    data class Choice(val identity: Identity, val envelopeMailFrom: String?)

    /** Null when the account has no server identity at all. [onBehalf] never gets an envelope: on
     *  a delegated account (#31) the mismatch is deliberate and verified against Stalwart. The
     *  match trims and folds case, as `resolvedIdentities` dedupes on `trim().lowercase()`. */
    fun choose(fromEmail: String?, serverIdentities: List<Identity>, onBehalf: Boolean): Choice? {
        val chosen = fromEmail?.trim().orEmpty()
        // An empty chosen address matches nothing, deliberately: `Identity.email` defaults to "",
        // so a bare `equals` would let an addressless identity displace the first one.
        val matched = if (chosen.isEmpty()) null
        else serverIdentities.firstOrNull { it.email.trim().equals(chosen, true) }
        val identity = matched ?: serverIdentities.firstOrNull() ?: return null
        val needsEnvelope = !onBehalf && chosen.isNotEmpty() && matched == null
        return Choice(identity = identity, envelopeMailFrom = if (needsEnvelope) chosen else null)
    }
}
