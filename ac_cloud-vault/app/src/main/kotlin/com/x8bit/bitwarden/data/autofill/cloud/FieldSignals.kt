package com.x8bit.bitwarden.data.autofill.cloud

/**
 * The plain facts the autofill parser reads off one node of an `AssistStructure`, with every
 * Android type stripped out, so the classification rules below can be pinned by JVM tests that
 * build fake node trees instead of mocking framework classes.
 *
 * @property autofillHints The node's `autofillHints` (Android hints such as "password", or the
 * androidx `HintConstants` such as "smsOTPCode").
 * @property inputType The node's `android.text.InputType` bits.
 * @property idEntry The resource entry name of a native view ("otp_input").
 * @property hint The placeholder text shown in an empty field.
 * @property htmlTag The HTML tag a WebView or browser reported ("input"), null for native views.
 * @property htmlAttributes The HTML attributes a WebView or browser reported, attribute names
 * lower-cased (type, name, id, autocomplete, label, placeholder, ...).
 */
data class FieldSignals(
    val autofillHints: List<String> = emptyList(),
    val inputType: Int = 0,
    val idEntry: String? = null,
    val hint: String? = null,
    val htmlTag: String? = null,
    val htmlAttributes: Map<String, String> = emptyMap(),
)

/**
 * What a field is for, as far as the vault tier is concerned. [NONE] fields are not ours: the
 * response lists them as ignored so the framework stops asking about them.
 */
enum class FieldKind {
    USERNAME,
    EMAIL,
    PASSWORD,
    OTP,
    CARD,

    /**
     * An ID-document field (national ID, passport, driving licence, support number, ...): filled
     * from Identity items, see [IdentityFieldClassifier].
     */
    ID_DOCUMENT,
    NONE,
}

/**
 * `android.text.InputType` constants, copied so this file stays free of Android imports. They
 * are part of the public SDK and cannot change.
 */
internal object InputTypeBits {
    const val MASK_CLASS: Int = 0x0000000f
    const val MASK_VARIATION: Int = 0x00000ff0
    const val CLASS_TEXT: Int = 0x00000001
    const val CLASS_NUMBER: Int = 0x00000002
    const val TEXT_VARIATION_EMAIL_ADDRESS: Int = 0x00000020
    const val TEXT_VARIATION_PASSWORD: Int = 0x00000080
    const val TEXT_VARIATION_VISIBLE_PASSWORD: Int = 0x00000090
    const val TEXT_VARIATION_WEB_EMAIL_ADDRESS: Int = 0x000000d0
    const val TEXT_VARIATION_WEB_PASSWORD: Int = 0x000000e0
    const val NUMBER_VARIATION_PASSWORD: Int = 0x00000010
}
