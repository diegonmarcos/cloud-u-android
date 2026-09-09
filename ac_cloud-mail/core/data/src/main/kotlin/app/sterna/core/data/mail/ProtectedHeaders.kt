package app.sterna.core.data.mail

import app.sterna.core.imap.MimeParser

/** RFC 9788 protected headers: which subject a decrypted PGP/MIME message is shown under (#128).
 *  A non-blank `Subject` in [entity] is the subject; anything else keeps [outerSubject], since an
 *  empty one would replace a readable cover subject with a blank title. [entity] is ISO-8859-1. */
internal fun decryptedSubject(outerSubject: String?, entity: String): String? =
    MimeParser.decodedHeaderOf(entity, "Subject")?.takeIf { it.isNotBlank() } ?: outerSubject
