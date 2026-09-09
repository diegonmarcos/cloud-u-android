package app.sterna.core.data.mail

import app.sterna.core.data.account.MailProtocol

/** Whether the in-memory raw-source cache may answer this read. It is keyed `accountId:emailId`
 *  and holds no numbering, so on IMAP a hit answers "the octets we last read for that uid" and
 *  would sail past the SELECT that opposes a frozen numbering, leaving a cross-account move with a
 *  permanent duplicate (#189). So on IMAP any [FrozenNumbering.Frozen] skips it, null included. */
internal fun <T : Any> rawSourceFromCache(
    protocol: MailProtocol,
    frozen: FrozenNumbering,
    cached: () -> T?,
): T? = if (protocol == MailProtocol.IMAP && frozen is FrozenNumbering.Frozen) null else cached()
