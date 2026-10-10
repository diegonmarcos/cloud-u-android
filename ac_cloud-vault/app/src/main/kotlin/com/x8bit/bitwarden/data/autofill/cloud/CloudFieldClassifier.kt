package com.x8bit.bitwarden.data.autofill.cloud

/**
 * Cloud Vault's field rules, layered on the upstream parser (ViewNodeExtensions): the upstream
 * heuristics keep deciding username / password / card, and this classifier adds what they did
 * not know about and is consulted in this order:
 *
 * 1. explicit `autofillHints` (Android and androidx `HintConstants`, incl. the SMS / 2FA-app OTP
 *    hints and the HTML `one-time-code` token);
 * 2. the HTML `autocomplete` attribute a WebView or browser reports (`current-password`,
 *    `new-password`, `username`, `email`, `one-time-code`, `cc-*`);
 * 3. one-time-code wording in the id, name, label or placeholder (checked before the password
 *    input types, because banks render SMS codes in number-password fields);
 * 4. the input type (text / visible / web / number password, email) and HTML `type`;
 * 5. ID-document wording ([IdentityFieldClassifier]): DNI, NIE, passport, Personalausweis, CPF...;
 * 6. id / name / label / placeholder wording, as a last resort, for input-like nodes only.
 *
 * Pure Kotlin on purpose: [FieldSignals] carries the node's facts, so the rules are tested with
 * fake trees on a plain JVM.
 */
object CloudFieldClassifier {

    private val OTP_HINTS = setOf(
        "smsotpcode",
        "2faappotpcode",
        "otp",
        "totp",
        "onetimecode",
        "one-time-code",
        "one_time_code",
    )
    private const val SMS_OTP_DIGIT_HINT_PREFIX = "smsotpcode"
    private val PASSWORD_HINTS = setOf(
        "password",
        "newpassword",
        "new-password",
        "currentpassword",
        "current-password",
    )
    private val EMAIL_HINTS = setOf("emailaddress", "email")
    private val USERNAME_HINTS = setOf("username", "newusername", "new-username")
    private val CARD_HINT_PREFIXES = listOf("creditcard", "cc-")

    private val IGNORED_TOKENS = setOf("search", "find", "recipient", "query", "q")

    /** Single tokens (after camel-case and separator splitting) that mean a one-time code. */
    private val OTP_TOKENS = setOf(
        "otp", "totp", "hotp", "mfa", "2fa", "tfa", "otpcode", "totpcode", "mfacode",
        "2facode", "smscode", "authcode", "verificationcode", "verifycode", "onetimecode",
        "onetimepassword", "onetimepasscode", "twofactor", "2step", "twostep",
    )

    /** Adjacent token pairs that mean a one-time code. */
    private val OTP_TOKEN_PAIRS = setOf(
        "one" to "time",
        "verification" to "code",
        "verify" to "code",
        "auth" to "code",
        "authentication" to "code",
        "authenticator" to "code",
        "two" to "factor",
        "2" to "step",
        "two" to "step",
        "sms" to "code",
        "login" to "code",
        "confirmation" to "code",
    )

    /** Wording that makes a short numeric "code" field something other than a one-time code. */
    private val NOT_OTP_CONTEXT = setOf(
        "card", "cc", "cvv", "cvc", "csc", "security", "zip", "postal", "post", "promo",
        "coupon", "discount", "referral", "gift", "voucher", "country", "area", "phone", "tel",
        "invite", "invitation", "pin",
    )

    private val PASSWORD_TOKENS = setOf("password", "passwd", "pswd", "pwd", "passwort")
    private val EMAIL_TOKENS = setOf("email", "mail", "emailaddress")
    private val USERNAME_TOKENS = setOf("username", "userid", "login", "loginid", "phone")
    private val USERNAME_TOKEN_PAIRS = setOf("user" to "name", "user" to "id", "login" to "id")
    private val CARD_TOKENS = setOf("cc", "card", "cvv", "cvc", "csc", "ccnumber", "cardnumber")

    /** The HTML attributes that carry a field's meaning (never its value). */
    private val MEANINGFUL_HTML_ATTRIBUTES = listOf(
        "id", "name", "label", "placeholder", "aria-label", "hint", "title",
    )

    private const val OTP_MIN_LENGTH = 4
    private const val OTP_MAX_LENGTH = 8

    /**
     * Classifies one node. [FieldKind.NONE] means "not a field the vault fills".
     */
    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    fun classify(signals: FieldSignals): FieldKind {
        // 1. Explicit autofill hints.
        kindFromAutofillHints(signals.autofillHints)?.let { return it }

        val isInputLike = signals.isInputLike
        if (!isInputLike) return FieldKind.NONE

        // 2. The HTML autocomplete attribute.
        kindFromAutocomplete(signals.htmlAttributes["autocomplete"])?.let { return it }

        val tokens = signals.meaningTokens()
        if (tokens.any { it in IGNORED_TOKENS }) return FieldKind.NONE

        // 3. One-time-code wording, before the password input types.
        if (isOtpWording(tokens) || isShortNumericCode(signals, tokens)) return FieldKind.OTP

        // 4. Input types.
        val htmlType = signals.htmlAttributes["type"]?.lowercase()
        if (isPasswordInputType(signals.inputType) || htmlType == "password") {
            return FieldKind.PASSWORD
        }
        if (isEmailInputType(signals.inputType) || htmlType == "email") return FieldKind.EMAIL

        // 5. ID-document wording (DNI, passport, Personalausweis, CPF, ...), before the card and
        //    username words: an "ID card number" is not a payment card. See IdentityFieldClassifier.
        if (IdentityFieldClassifier.isDocumentField(signals)) return FieldKind.ID_DOCUMENT

        // 6. Wording.
        return when {
            tokens.any { it in CARD_TOKENS } -> FieldKind.CARD
            tokens.any { it in PASSWORD_TOKENS } -> FieldKind.PASSWORD
            tokens.any { it in EMAIL_TOKENS } -> FieldKind.EMAIL
            tokens.any { it in USERNAME_TOKENS } ||
                tokens.zipWithNext().any { it in USERNAME_TOKEN_PAIRS } -> FieldKind.USERNAME
            else -> FieldKind.NONE
        }
    }

    /** True for the password variations of text, visible, web and number input types. */
    fun isPasswordInputType(inputType: Int): Boolean {
        val cls = inputType and InputTypeBits.MASK_CLASS
        val variation = inputType and InputTypeBits.MASK_VARIATION
        return when (cls) {
            InputTypeBits.CLASS_TEXT -> variation == InputTypeBits.TEXT_VARIATION_PASSWORD ||
                variation == InputTypeBits.TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputTypeBits.TEXT_VARIATION_WEB_PASSWORD

            InputTypeBits.CLASS_NUMBER -> variation == InputTypeBits.NUMBER_VARIATION_PASSWORD
            else -> false
        }
    }

    private fun isEmailInputType(inputType: Int): Boolean {
        if (inputType and InputTypeBits.MASK_CLASS != InputTypeBits.CLASS_TEXT) return false
        val variation = inputType and InputTypeBits.MASK_VARIATION
        return variation == InputTypeBits.TEXT_VARIATION_EMAIL_ADDRESS ||
            variation == InputTypeBits.TEXT_VARIATION_WEB_EMAIL_ADDRESS
    }

    @Suppress("ReturnCount")
    private fun kindFromAutofillHints(hints: List<String>): FieldKind? {
        val normalized = hints.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        if (normalized.isEmpty()) return null
        if (normalized.any { it in OTP_HINTS || it.startsWith(SMS_OTP_DIGIT_HINT_PREFIX) }) {
            return FieldKind.OTP
        }
        if (normalized.any { it in PASSWORD_HINTS }) return FieldKind.PASSWORD
        if (normalized.any { it in EMAIL_HINTS }) return FieldKind.EMAIL
        if (normalized.any { it in USERNAME_HINTS }) return FieldKind.USERNAME
        if (normalized.any { hint -> CARD_HINT_PREFIXES.any { hint.startsWith(it) } }) {
            return FieldKind.CARD
        }
        return null
    }

    /** The autocomplete value is a token list ("section-a shipping email"); the last one counts. */
    private fun kindFromAutocomplete(raw: String?): FieldKind? {
        val token = raw
            ?.trim()
            ?.lowercase()
            ?.split(Regex("\\s+"))
            ?.lastOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        return when {
            token == "one-time-code" -> FieldKind.OTP
            token == "current-password" || token == "new-password" -> FieldKind.PASSWORD
            token == "email" -> FieldKind.EMAIL
            token == "username" -> FieldKind.USERNAME
            token.startsWith("cc-") -> FieldKind.CARD
            else -> null
        }
    }

    private fun isOtpWording(tokens: List<String>): Boolean {
        if (tokens.any { it in OTP_TOKENS }) return true
        return tokens.zipWithNext().any { it in OTP_TOKEN_PAIRS }
    }

    /**
     * A field that only says "code" is a one-time code when it is short and numeric and nothing
     * around it says card, postcode, promo, phone or PIN.
     */
    private fun isShortNumericCode(signals: FieldSignals, tokens: List<String>): Boolean {
        if ("code" !in tokens || tokens.any { it in NOT_OTP_CONTEXT }) return false
        val maxLength = signals.htmlAttributes["maxlength"]?.trim()?.toIntOrNull()
        val isNumeric = signals.inputType and InputTypeBits.MASK_CLASS == InputTypeBits.CLASS_NUMBER ||
            signals.htmlAttributes["inputmode"]?.lowercase() == "numeric" ||
            signals.htmlAttributes["type"]?.lowercase() in setOf("number", "tel")
        return isNumeric && (maxLength == null || maxLength in OTP_MIN_LENGTH..OTP_MAX_LENGTH)
    }

    private val FieldSignals.isInputLike: Boolean
        get() = htmlTag?.lowercase() == "input" || (htmlTag == null && inputType != 0)

    /** Every meaning-bearing word of the node, split on separators and camel case. */
    private fun FieldSignals.meaningTokens(): List<String> {
        val sources = buildList {
            idEntry?.let(::add)
            hint?.let(::add)
            MEANINGFUL_HTML_ATTRIBUTES.forEach { key -> htmlAttributes[key]?.let(::add) }
        }
        return sources.flatMap { tokenize(it) }
    }

    /** "loginOtpCode" / "login-otp_code" / "Login OTP code" all become [login, otp, code]. */
    internal fun tokenize(raw: String): List<String> =
        raw
            .replace(Regex("(?<=[a-z0-9])(?=[A-Z])"), " ")
            .lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.isNotEmpty() }
}
