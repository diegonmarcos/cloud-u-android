package com.x8bit.bitwarden.data.autofill.model

/**
 * Autofill hints used to determine what data an input field is associated with.
 */
enum class AutofillHint {
    CARD_CARDHOLDER,
    CARD_EXPIRATION_DATE,
    CARD_EXPIRATION_MONTH,
    CARD_EXPIRATION_YEAR,
    CARD_NUMBER,
    CARD_SECURITY_CODE,
    CARD_BRAND,
    PASSWORD,
    USERNAME,

    /**
     * A one-time code (SMS or authenticator app). Cloud Vault fills it with the login's current
     * TOTP code.
     */
    OTP,
}
