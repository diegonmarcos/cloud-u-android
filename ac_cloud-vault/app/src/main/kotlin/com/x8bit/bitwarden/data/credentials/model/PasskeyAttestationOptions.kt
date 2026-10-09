package com.x8bit.bitwarden.data.credentials.model

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

/**
 * Models FIDO 2 credential creation request options received from a Relying Party (RP).
 *
 * Parsing is deliberately lenient: WebAuthn only requires `challenge`, `rp` and `user.id`
 * (`rp.id`, `rp.name`, `user.displayName`, `authenticatorSelection` and `pubKeyCredParams` are all
 * optional or defaulted), and browsers forward sites' options as they receive them. A strict
 * model made such a request unparseable, which surfaced as "relying party cannot be identified".
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class PasskeyAttestationOptions(
    @EncodeDefault
    @SerialName("authenticatorSelection")
    val authenticatorSelection: AuthenticatorSelectionCriteria = AuthenticatorSelectionCriteria(),
    @SerialName("challenge")
    val challenge: String,
    // WebAuthn spells it `excludeCredentials`; `excludedCredentials` is the name this model used
    // before and is still accepted when reading.
    @JsonNames("excludedCredentials")
    @SerialName("excludeCredentials")
    val excludeCredentials: List<PublicKeyCredentialDescriptor> = emptyList(),
    @EncodeDefault
    @SerialName("pubKeyCredParams")
    val pubKeyCredParams: List<PublicKeyCredentialParameters> = DEFAULT_PUB_KEY_CRED_PARAMS,
    @SerialName("rp")
    val relyingParty: PublicKeyCredentialRpEntity = PublicKeyCredentialRpEntity(),
    @SerialName("user")
    val user: PublicKeyCredentialUserEntity,
) {

    /**
     * Represents criteria that must be respected when selecting a credential.
     */
    @Serializable
    data class AuthenticatorSelectionCriteria(
        @SerialName("authenticatorAttachment")
        val authenticatorAttachment: AuthenticatorAttachment? = null,
        @SerialName("residentKey")
        val residentKeyRequirement: ResidentKeyRequirement? = null,
        @SerialName("userVerification")
        val userVerification: UserVerificationRequirement = UserVerificationRequirement.PREFERRED,
    ) {
        /**
         * Enum class representing the types of attachments associated with selection criteria.
         */
        @Serializable
        enum class AuthenticatorAttachment {
            @SerialName("platform")
            PLATFORM,

            @SerialName("cross_platform")
            CROSS_PLATFORM,
        }

        /**
         * Enum class indicating the type of authentication expected by the selection criteria.
         */
        @Serializable
        enum class ResidentKeyRequirement {
            /**
             * Resident keys are preferred during selection, if supported.
             */
            @SerialName("preferred")
            PREFERRED,

            /**
             * Resident keys are required during selection.
             */
            @SerialName("required")
            REQUIRED,
        }
    }

    /**
     * Represents parameters for a credential in the creation options.
     */
    @Serializable
    data class PublicKeyCredentialParameters(
        @SerialName("type")
        val type: String,
        @SerialName("alg")
        val alg: Double,
    )

    /**
     * Represents the RP associated with the credential options.
     */
    @Serializable
    data class PublicKeyCredentialRpEntity(
        @SerialName("name")
        val name: String? = null,
        @SerialName("id")
        val id: String? = null,
    )

    /**
     * Represents the user associated with teh credential options.
     */
    @Serializable
    data class PublicKeyCredentialUserEntity(
        @SerialName("name")
        val name: String? = null,
        @SerialName("id")
        val id: String,
        @SerialName("displayName")
        val displayName: String? = null,
    )
}

/**
 * The algorithms WebAuthn Level 3 says to assume when a relying party sends no `pubKeyCredParams`:
 * ES256 (-7) and RS256 (-257).
 */
private val DEFAULT_PUB_KEY_CRED_PARAMS = listOf(
    PasskeyAttestationOptions.PublicKeyCredentialParameters(type = "public-key", alg = -7.0),
    PasskeyAttestationOptions.PublicKeyCredentialParameters(type = "public-key", alg = -257.0),
)
