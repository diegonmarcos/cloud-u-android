package app.sterna.util

/**
 * Lightweight email-format check: a non-empty local part, an `@`, a domain, and a dot-separated
 */
val EMAIL_REGEX = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s.]{2,}$")

/** True when [address] (trimmed) looks like a well-formed email address. See [EMAIL_REGEX]. */
fun isValidEmail(address: String): Boolean = EMAIL_REGEX.matches(address.trim())
