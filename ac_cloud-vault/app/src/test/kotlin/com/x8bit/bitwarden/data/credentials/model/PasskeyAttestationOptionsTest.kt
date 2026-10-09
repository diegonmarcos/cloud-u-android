package com.x8bit.bitwarden.data.credentials.model

import com.bitwarden.core.di.CoreModule
import com.x8bit.bitwarden.data.credentials.sanitizer.PasskeyAttestationOptionsSanitizerImpl
import io.mockk.mockk
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PasskeyAttestationOptionsTest {

    private val json: Json = CoreModule.providesJson(buildInfoManager = mockk(relaxed = true))

    @Test
    fun `options without rp id, rp name, user displayName, authenticatorSelection and params parse`() {
        val options = json.decodeFromString<PasskeyAttestationOptions>(SPARSE_OPTIONS_JSON)

        assertEquals(null, options.relyingParty.id)
        assertEquals(null, options.relyingParty.name)
        assertEquals(null, options.user.displayName)
        assertEquals(
            PasskeyAttestationOptions.AuthenticatorSelectionCriteria(),
            options.authenticatorSelection,
        )
        assertEquals(listOf(-7.0, -257.0), options.pubKeyCredParams.map { it.alg })
    }

    @Test
    fun `unknown resident key and attachment values do not make the options unparseable`() {
        val options = json.decodeFromString<PasskeyAttestationOptions>(
            SPARSE_OPTIONS_JSON.replace(
                "\"challenge\"",
                "\"authenticatorSelection\": {\"residentKey\": \"discouraged\"," +
                    " \"authenticatorAttachment\": \"hybrid\"}, \"challenge\"",
            ),
        )

        assertEquals("abc", options.challenge)
    }

    /**
     * Golden: the exact request JSON that is handed to the SDK for a site that sends the bare
     * minimum. The SDK builds clientDataJSON from this plus the origin chosen by the manager.
     */
    @Test
    fun `sparse options are completed into the golden request JSON for the SDK`() {
        val parsed = json.decodeFromString<PasskeyAttestationOptions>(SPARSE_OPTIONS_JSON)
        val withRpId = parsed.copy(
            relyingParty = parsed.relyingParty.copy(id = "www.squarespace.com"),
        )

        val result = json.encodeToString(PasskeyAttestationOptionsSanitizerImpl.sanitize(withRpId))

        assertEquals(GOLDEN_SDK_REQUEST_JSON, result)
    }
}

private const val SPARSE_OPTIONS_JSON = """
{
  "challenge": "abc",
  "rp": {},
  "user": { "id": "dXNlcg", "name": "jane@example.com" }
}
"""

private const val GOLDEN_SDK_REQUEST_JSON =
    "{\"authenticatorSelection\":{},\"challenge\":\"abc\"," +
        "\"pubKeyCredParams\":[{\"type\":\"public-key\",\"alg\":-7.0}," +
        "{\"type\":\"public-key\",\"alg\":-257.0}]," +
        "\"rp\":{\"name\":\"www.squarespace.com\",\"id\":\"www.squarespace.com\"}," +
        "\"user\":{\"name\":\"jane@example.com\",\"id\":\"dXNlcg\"," +
        "\"displayName\":\"jane@example.com\"}}"
