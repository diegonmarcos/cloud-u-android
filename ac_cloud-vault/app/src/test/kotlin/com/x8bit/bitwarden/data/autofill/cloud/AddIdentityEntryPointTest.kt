package com.x8bit.bitwarden.data.autofill.cloud

import android.content.Intent
import android.os.Bundle
import com.x8bit.bitwarden.data.autofill.model.AutofillSaveItem
import com.x8bit.bitwarden.ui.vault.feature.addedit.VaultAddEditState
import com.x8bit.bitwarden.ui.vault.feature.addedit.util.toDefaultAddTypeContent
import com.x8bit.bitwarden.ui.vault.feature.addedit.util.toVaultItemCipherType
import com.x8bit.bitwarden.ui.vault.model.VaultItemCipherType
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The add-identity entry point: only a caller with the fleet signature permission gets in, the
 * extras prefill the new-Identity screen and are removed from the intent, the document reaches the
 * main screen once and only in memory, and the screen shows exactly what the fill reads back.
 * Every value is an obvious fake.
 */
class AddIdentityEntryPointTest {

    private val extras = mapOf(
        AddIdentityRequest.EXTRA_TYPE to "DNI",
        AddIdentityRequest.EXTRA_NUMBER to "  FAKE-DNI-0001 ",
        AddIdentityRequest.EXTRA_SUPPORT to "FAKE-SUP-0001",
        AddIdentityRequest.EXTRA_ISSUING_COUNTRY to "es",
        AddIdentityRequest.EXTRA_VALID_UNTIL to "2099-01-01",
        AddIdentityRequest.EXTRA_FIRST_NAME to "Testy",
        AddIdentityRequest.EXTRA_LAST_NAME to "Fakeson Example",
    )

    private fun intentWith(values: Map<String, String>): Intent = mockk {
        every { getStringExtra(any()) } answers { values[firstArg()] }
        every { removeExtra(any()) } just runs
        every { replaceExtras(isNull<Bundle>()) } returns this@mockk
    }

    private fun verifyCleared(intent: Intent) {
        AddIdentityRequest.EXTRAS.forEach { key -> verify { intent.removeExtra(key) } }
        verify { intent.replaceExtras(isNull<Bundle>()) }
    }

    // ── extras ────────────────────────────────────────────────────────

    @Test
    fun `the extras prefill the request and are removed from the intent`() {
        val intent = intentWith(extras)

        val item = AddIdentityRequest.readAndClear(intent)

        assertEquals(
            AutofillSaveItem.Identity(
                documentType = "DNI",
                documentNumber = "FAKE-DNI-0001",
                supportNumber = "FAKE-SUP-0001",
                issuingCountry = "es",
                validUntil = "2099-01-01",
                firstName = "Testy",
                lastName = "Fakeson Example",
            ),
            item,
        )
        verifyCleared(intent)
    }

    @Test
    fun `an empty request prefills nothing and is still cleared`() {
        val intent = intentWith(mapOf(AddIdentityRequest.EXTRA_TYPE to "DNI"))

        assertNull(AddIdentityRequest.readAndClear(intent))
        verifyCleared(intent)
    }

    @Test
    fun `unreadable extras are dropped and cleared`() {
        val intent: Intent = mockk {
            every { getStringExtra(any()) } throws IllegalStateException("bad parcel")
            every { removeExtra(any()) } just runs
            every { replaceExtras(isNull<Bundle>()) } returns this@mockk
        }

        assertNull(AddIdentityRequest.readAndClear(intent))
        verifyCleared(intent)
    }

    @Test
    fun `an oversized value is cut, never trusted`() {
        val item = AddIdentityRequest.read { key ->
            if (key == AddIdentityRequest.EXTRA_NUMBER) "F".repeat(10_000) else null
        }
        assertEquals(128, item?.documentNumber?.length)
    }

    @Test
    fun `a request never prints its values`() {
        val item = AddIdentityRequest.read { extras[it] }
        assertFalse(item.toString().contains("FAKE-DNI"), item.toString())
    }

    // ── who may call ──────────────────────────────────────────────────

    @Test
    fun `a caller without the permission is refused`() {
        val unknown = -1
        val fleetApp = 10_123
        val otherApp = 10_456
        val holders = setOf(fleetApp)
        fun allowed(uid: Int) = AddIdentityCallerPolicy.isAllowed(
            launchedFromUid = uid,
            unknownUid = unknown,
            uidHoldsPermission = { it in holders },
        )

        assertTrue(allowed(fleetApp))
        assertFalse(allowed(otherApp))
        // Identity not shared: only the framework knows the caller, and its check passed.
        assertTrue(allowed(unknown))
    }

    @Test
    fun `the activity is exported only behind the fleet signature permission`() {
        val manifest = DocumentBuilderFactory
            .newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(File("src/main/AndroidManifest.xml"))
        val android = "http://schemas.android.com/apk/res/android"
        fun elements(tag: String): List<Element> = manifest
            .getElementsByTagName(tag)
            .let { list -> (0 until list.length).map { list.item(it) as Element } }

        val permission = elements("permission")
            .single { it.getAttributeNS(android, "name") == AddIdentityRequest.PERMISSION }
        assertEquals("signature", permission.getAttributeNS(android, "protectionLevel"))

        val activity = elements("activity")
            .single { it.getAttributeNS(android, "name") == ".AddIdentityActivity" }
        assertEquals("true", activity.getAttributeNS(android, "exported"))
        assertEquals(AddIdentityRequest.PERMISSION, activity.getAttributeNS(android, "permission"))
        val actions = activity
            .getElementsByTagName("action")
            .let { list -> (0 until list.length).map { (list.item(it) as Element).getAttributeNS(android, "name") } }
        assertEquals(listOf(AddIdentityRequest.ACTION), actions)
    }

    // ── hand-over to the main screen ──────────────────────────────────

    @Test
    fun `the document reaches the main screen once, only for the relay intent`() {
        val item = AutofillSaveItem.Identity(documentType = "DNI", documentNumber = "FAKE-DNI-0002")
        val otherIntent: Intent = mockk {
            every { getBooleanExtra(PendingAddIdentity.EXTRA_RELAY, false) } returns false
        }
        val relayIntent: Intent = mockk {
            every { getBooleanExtra(PendingAddIdentity.EXTRA_RELAY, false) } returns true
            every { removeExtra(PendingAddIdentity.EXTRA_RELAY) } just runs
        }

        PendingAddIdentity.offer(item)
        assertNull(otherIntent.takeRelayedAddIdentityOrNull())
        assertEquals(item, relayIntent.takeRelayedAddIdentityOrNull())
        assertNull(relayIntent.takeRelayedAddIdentityOrNull())
    }

    // ── the new-Identity screen ───────────────────────────────────────

    private fun VaultAddEditState.ViewState.Content.customFields(): Map<String, Pair<String, String>> =
        common.customFieldData.associate { custom ->
            when (custom) {
                is VaultAddEditState.Custom.TextField -> custom.name to ("text" to custom.value)
                is VaultAddEditState.Custom.HiddenField -> custom.name to ("hidden" to custom.value)
                else -> custom.name to ("other" to "")
            }
        }

    @Test
    fun `a DNI opens the Identity screen with its number, support and expiry in custom fields`() {
        val item = requireNotNull(AddIdentityRequest.read { extras[it] })
        assertEquals(VaultItemCipherType.IDENTITY, item.toVaultItemCipherType())

        val content = item.toDefaultAddTypeContent(isIndividualVaultDisabled = false)

        assertEquals("DNI (ES)", content.common.name)
        val identity = content.type as VaultAddEditState.ViewState.Content.ItemType.Identity
        assertEquals("Testy", identity.firstName)
        assertEquals("Fakeson Example", identity.lastName)
        assertEquals("", identity.passportNumber)
        assertEquals(
            mapOf(
                "Document type" to ("text" to "DNI"),
                "DNI" to ("hidden" to "FAKE-DNI-0001"),
                "Support number" to ("hidden" to "FAKE-SUP-0001"),
                "Issuing country" to ("text" to "ES"),
                "Valid until" to ("text" to "2099-01-01"),
            ),
            content.customFields(),
        )

        // What the screen will save is what an ID field reads back.
        val values = IdentityValues(
            customFields = content.customFields().map { (name, value) -> name to value.second },
        )
        assertEquals("FAKE-DNI-0001", IdentityFieldMapping.valueFor(IdentityField.DNI, values))
        assertEquals("FAKE-SUP-0001", IdentityFieldMapping.valueFor(IdentityField.SUPPORT_NUMBER, values))
        assertEquals("2099-01-01", IdentityFieldMapping.valueFor(IdentityField.VALID_UNTIL, values))
        assertEquals("ES", IdentityFieldMapping.valueFor(IdentityField.ISSUING_COUNTRY, values))
    }

    @Test
    fun `a passport goes into the Identity type's own passport field`() {
        val item = AutofillSaveItem.Identity(
            documentType = "Reisepass",
            documentNumber = "FAKE-PASS-0001",
            issuingCountry = "DE",
        )

        val content = item.toDefaultAddTypeContent(isIndividualVaultDisabled = false)

        val identity = content.type as VaultAddEditState.ViewState.Content.ItemType.Identity
        assertEquals("FAKE-PASS-0001", identity.passportNumber)
        assertEquals(setOf("Document type", "Issuing country"), content.customFields().keys)
    }
}
