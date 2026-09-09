package app.sterna.core.data.mail

/** A message could not be brought back from the server: offline, destroyed server-side, or its
 *  folder renumbered (#159). The wording stays technical and English: a bug report quotes it. */
class MessageUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)
