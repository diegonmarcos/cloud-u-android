package com.x8bit.bitwarden.data.autofill.cloud

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Field rules against fake nodes shaped like what WebView / Chrome / native EditTexts report.
 * No real form data anywhere: only field descriptions.
 */
class CloudFieldClassifierTest {

    private fun web(vararg attrs: Pair<String, String>) =
        FieldSignals(htmlTag = "input", htmlAttributes = attrs.toMap())

    private fun native(inputType: Int, idEntry: String? = null, hint: String? = null) =
        FieldSignals(inputType = inputType, idEntry = idEntry, hint = hint)

    // ── one-time codes ────────────────────────────────────────────────

    @Test
    fun `androidx SMS and 2FA-app OTP hints are OTP fields`() {
        assertEquals(FieldKind.OTP, CloudFieldClassifier.classify(FieldSignals(autofillHints = listOf("smsOTPCode"))))
        assertEquals(FieldKind.OTP, CloudFieldClassifier.classify(FieldSignals(autofillHints = listOf("smsOTPCode3"))))
        assertEquals(FieldKind.OTP, CloudFieldClassifier.classify(FieldSignals(autofillHints = listOf("2faAppOTPCode"))))
    }

    @Test
    fun `HTML autocomplete one-time-code is an OTP field`() {
        assertEquals(FieldKind.OTP, CloudFieldClassifier.classify(web("type" to "text", "autocomplete" to "one-time-code")))
    }

    @Test
    fun `OTP wording beats the number-password input type banks use for SMS codes`() {
        val numberPassword = InputTypeBits.CLASS_NUMBER or InputTypeBits.NUMBER_VARIATION_PASSWORD
        assertEquals(FieldKind.OTP, CloudFieldClassifier.classify(native(numberPassword, idEntry = "otp_input")))
        assertEquals(FieldKind.OTP, CloudFieldClassifier.classify(native(numberPassword, hint = "Verification code")))
    }

    @Test
    fun `camel case and separators are split before matching`() {
        assertEquals(FieldKind.OTP, CloudFieldClassifier.classify(web("name" to "loginOtpCode")))
        assertEquals(FieldKind.OTP, CloudFieldClassifier.classify(web("id" to "two-factor-token")))
        assertEquals(FieldKind.OTP, CloudFieldClassifier.classify(web("placeholder" to "Enter your one time passcode")))
    }

    @Test
    fun `words that only contain the letters o-t-p are not OTP fields`() {
        // "footprint" and "hotpot" contain "otp" as letters but not as a word.
        assertEquals(FieldKind.NONE, CloudFieldClassifier.classify(web("name" to "footprint")))
        assertEquals(FieldKind.NONE, CloudFieldClassifier.classify(web("name" to "hotpot_size")))
    }

    @Test
    fun `a short numeric code field is an OTP field`() {
        assertEquals(
            FieldKind.OTP,
            CloudFieldClassifier.classify(web("name" to "code", "inputmode" to "numeric", "maxlength" to "6")),
        )
    }

    @Test
    fun `card, postcode, promo and PIN codes are not OTP fields`() {
        val notOtp = listOf("security code", "card code", "postal code", "promo code", "pin code", "zip code")
        notOtp.forEach { label ->
            val kind = CloudFieldClassifier.classify(web("label" to label, "inputmode" to "numeric", "maxlength" to "5"))
            assertTrue(kind != FieldKind.OTP, "'$label' must not be an OTP field, was $kind")
        }
    }

    // ── passwords ─────────────────────────────────────────────────────

    @Test
    fun `every password input type variation is a password`() {
        val types = listOf(
            InputTypeBits.CLASS_TEXT or InputTypeBits.TEXT_VARIATION_PASSWORD,
            InputTypeBits.CLASS_TEXT or InputTypeBits.TEXT_VARIATION_VISIBLE_PASSWORD,
            InputTypeBits.CLASS_TEXT or InputTypeBits.TEXT_VARIATION_WEB_PASSWORD,
            InputTypeBits.CLASS_NUMBER or InputTypeBits.NUMBER_VARIATION_PASSWORD,
        )
        types.forEach { type ->
            assertTrue(CloudFieldClassifier.isPasswordInputType(type), "0x${type.toString(16)}")
            assertEquals(FieldKind.PASSWORD, CloudFieldClassifier.classify(native(type)))
        }
    }

    @Test
    fun `plain text and plain number input types are not passwords`() {
        assertFalse(CloudFieldClassifier.isPasswordInputType(InputTypeBits.CLASS_TEXT))
        assertFalse(CloudFieldClassifier.isPasswordInputType(InputTypeBits.CLASS_NUMBER))
        // Email variation shares bits with the password variations; it must not read as one.
        assertFalse(
            CloudFieldClassifier.isPasswordInputType(InputTypeBits.CLASS_TEXT or InputTypeBits.TEXT_VARIATION_WEB_EMAIL_ADDRESS),
        )
    }

    @Test
    fun `HTML autocomplete current-password and new-password are passwords`() {
        assertEquals(FieldKind.PASSWORD, CloudFieldClassifier.classify(web("autocomplete" to "current-password")))
        assertEquals(FieldKind.PASSWORD, CloudFieldClassifier.classify(web("autocomplete" to "section-login new-password")))
        assertEquals(FieldKind.PASSWORD, CloudFieldClassifier.classify(web("type" to "password", "name" to "pw")))
    }

    // ── usernames and email ───────────────────────────────────────────

    @Test
    fun `autocomplete username and email, and the email input types`() {
        assertEquals(FieldKind.USERNAME, CloudFieldClassifier.classify(web("autocomplete" to "username")))
        assertEquals(FieldKind.EMAIL, CloudFieldClassifier.classify(web("autocomplete" to "email")))
        assertEquals(FieldKind.EMAIL, CloudFieldClassifier.classify(web("type" to "email")))
        assertEquals(
            FieldKind.EMAIL,
            CloudFieldClassifier.classify(native(InputTypeBits.CLASS_TEXT or InputTypeBits.TEXT_VARIATION_EMAIL_ADDRESS)),
        )
        assertEquals(FieldKind.EMAIL, CloudFieldClassifier.classify(FieldSignals(autofillHints = listOf("emailAddress"))))
    }

    @Test
    fun `id and name wording is the last resort for usernames`() {
        assertEquals(FieldKind.USERNAME, CloudFieldClassifier.classify(native(InputTypeBits.CLASS_TEXT, idEntry = "login_id")))
        assertEquals(FieldKind.USERNAME, CloudFieldClassifier.classify(web("name" to "user_name")))
        // "user" next to something else is a profile field, not a login.
        assertEquals(FieldKind.NONE, CloudFieldClassifier.classify(web("name" to "user_first_name")))
    }

    // ── fields that are not ours ──────────────────────────────────────

    @Test
    fun `search boxes are never ours even when they mention a login word`() {
        assertEquals(FieldKind.NONE, CloudFieldClassifier.classify(web("name" to "search_username")))
        assertEquals(FieldKind.NONE, CloudFieldClassifier.classify(web("type" to "search", "name" to "q")))
    }

    @Test
    fun `non-input nodes are never classified from their wording`() {
        // A label or button that says "Password" is not a field.
        assertEquals(FieldKind.NONE, CloudFieldClassifier.classify(FieldSignals(idEntry = "password_label")))
        assertEquals(FieldKind.NONE, CloudFieldClassifier.classify(FieldSignals(htmlTag = "div", htmlAttributes = mapOf("id" to "password"))))
    }

    @Test
    fun `card fields are reported as card, never as login or OTP`() {
        assertEquals(FieldKind.CARD, CloudFieldClassifier.classify(FieldSignals(autofillHints = listOf("creditCardNumber"))))
        assertEquals(FieldKind.CARD, CloudFieldClassifier.classify(web("autocomplete" to "cc-csc")))
        assertEquals(FieldKind.CARD, CloudFieldClassifier.classify(web("name" to "card_number")))
    }

    // ── a whole login form, as a WebView reports it ──────────────────

    @Test
    fun `a WebView login page classifies field by field`() {
        val page = listOf(
            web("type" to "email", "name" to "identifier", "autocomplete" to "username"),
            web("type" to "password", "name" to "pass", "autocomplete" to "current-password"),
            web("type" to "checkbox", "name" to "remember"),
            web("type" to "search", "name" to "site_search"),
        )
        assertEquals(
            listOf(FieldKind.USERNAME, FieldKind.PASSWORD, FieldKind.NONE, FieldKind.NONE),
            page.map(CloudFieldClassifier::classify),
        )
    }

    @Test
    fun `tokenize splits camel case, digits and separators`() {
        assertEquals(listOf("login", "otp", "code"), CloudFieldClassifier.tokenize("loginOtpCode"))
        assertEquals(listOf("two", "factor", "2", "step"), CloudFieldClassifier.tokenize("two_factor 2-step"))
    }
}
