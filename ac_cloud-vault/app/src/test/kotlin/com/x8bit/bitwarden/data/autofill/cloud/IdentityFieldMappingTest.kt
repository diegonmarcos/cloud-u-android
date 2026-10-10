package com.x8bit.bitwarden.data.autofill.cloud

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Identity item → field values: Bitwarden's standard passport / licence / SSN / name / address
 * fields, and the custom fields ("DNI", "Support number", "Valid until", ...) for everything the
 * Identity type has no field for. Every value is an obvious fake.
 */
class IdentityFieldMappingTest {

    private fun valueOf(field: IdentityField, values: IdentityValues): String? =
        IdentityFieldMapping.valueFor(field, values)

    private val spanishId = IdentityValues(
        firstName = "Testy",
        lastName = "Fakeson Example",
        address1 = "Calle Falsa 1",
        city = "Sampletown",
        postalCode = "00000",
        country = "ES",
        customFields = listOf(
            "Document type" to "DNI",
            "DNI" to "FAKE-DNI-0001",
            "Support number" to "FAKE-SUP-0001",
            "Valid until" to "2099-01-01",
            "Issuing country" to "ES",
            "Notes flag" to "",
        ),
    )

    @Test
    fun `standard Identity fields fill passport, licence and SSN fields`() {
        val values = IdentityValues(
            passportNumber = "FAKE-PASS-0001",
            licenseNumber = "FAKE-LIC-0001",
            ssn = "FAKE-SSN-0001",
        )
        assertEquals("FAKE-PASS-0001", valueOf(IdentityField.PASSPORT_NUMBER, values))
        assertEquals("FAKE-LIC-0001", valueOf(IdentityField.LICENSE_NUMBER, values))
        assertEquals("FAKE-SSN-0001", valueOf(IdentityField.SSN, values))
        // A generic "document number" takes the passport when that is all the item holds.
        assertEquals("FAKE-PASS-0001", valueOf(IdentityField.DOCUMENT_NUMBER, values))
    }

    @Test
    fun `custom fields fill what the Identity type lacks`() {
        assertEquals("FAKE-DNI-0001", valueOf(IdentityField.DNI, spanishId))
        assertEquals("FAKE-DNI-0001", valueOf(IdentityField.NIF, spanishId))
        assertEquals("FAKE-DNI-0001", valueOf(IdentityField.NATIONAL_ID, spanishId))
        assertEquals("FAKE-DNI-0001", valueOf(IdentityField.DOCUMENT_NUMBER, spanishId))
        assertEquals("FAKE-SUP-0001", valueOf(IdentityField.SUPPORT_NUMBER, spanishId))
        assertEquals("2099-01-01", valueOf(IdentityField.VALID_UNTIL, spanishId))
        assertEquals("2099-01-01", valueOf(IdentityField.VALID_UNTIL_ON_PAGE, spanishId))
        assertEquals("ES", valueOf(IdentityField.ISSUING_COUNTRY, spanishId))
        // The item holds no NIE, passport or CPF: those fields are left alone.
        assertNull(valueOf(IdentityField.NIE, spanishId))
        assertNull(valueOf(IdentityField.PASSPORT_NUMBER, spanishId))
        assertNull(valueOf(IdentityField.CPF, spanishId))
    }

    @Test
    fun `custom field names match without case, accents or punctuation`() {
        val values = IdentityValues(
            customFields = listOf(
                "número de soporte" to "FAKE-SUP-0002",
                "GÜLTIG BIS" to "2098-12-31",
                "Ausweisnummer" to "FAKE-AUSWEIS-0002",
            ),
        )
        assertEquals("FAKE-SUP-0002", valueOf(IdentityField.SUPPORT_NUMBER, values))
        assertEquals("2098-12-31", valueOf(IdentityField.VALID_UNTIL, values))
        assertEquals("FAKE-AUSWEIS-0002", valueOf(IdentityField.PERSONALAUSWEIS, values))
    }

    @Test
    fun `a DNI field falls back to the NIE, a NIE field never takes a DNI`() {
        val nieOnly = IdentityValues(customFields = listOf("NIE" to "FAKE-NIE-0001"))
        assertEquals("FAKE-NIE-0001", valueOf(IdentityField.DNI, nieOnly))
        assertEquals("FAKE-NIE-0001", valueOf(IdentityField.NIF, nieOnly))
        assertNull(valueOf(IdentityField.NIE, spanishId))
    }

    @Test
    fun `the issuing country never comes from the home address`() {
        val values = IdentityValues(country = "DE", passportNumber = "FAKE-PASS-0003")
        assertNull(valueOf(IdentityField.ISSUING_COUNTRY, values))
        assertEquals("DE", valueOf(IdentityField.COUNTRY, values))
    }

    @Test
    fun `names and address come from the standard fields`() {
        assertEquals("Testy", valueOf(IdentityField.FIRST_NAME, spanishId))
        assertEquals("Fakeson Example", valueOf(IdentityField.LAST_NAME, spanishId))
        assertEquals("Testy Fakeson Example", valueOf(IdentityField.FULL_NAME, spanishId))
        assertEquals("Calle Falsa 1", valueOf(IdentityField.ADDRESS_1, spanishId))
        assertEquals("Sampletown", valueOf(IdentityField.CITY, spanishId))
        assertEquals("00000", valueOf(IdentityField.POSTAL_CODE, spanishId))
        assertNull(valueOf(IdentityField.MIDDLE_NAME, spanishId))
        assertNull(valueOf(IdentityField.FULL_NAME, IdentityValues()))
    }

    @Test
    fun `what the add-identity screen writes is what the fill reads`() {
        val roundTrip = mapOf(
            "DNI" to IdentityField.DNI,
            "NIE" to IdentityField.NIE,
            "NIF" to IdentityField.NIF,
            "TIE" to IdentityField.RESIDENCE_PERMIT,
            "Residence permit" to IdentityField.RESIDENCE_PERMIT,
            "Personalausweis" to IdentityField.PERSONALAUSWEIS,
            "CPF" to IdentityField.CPF,
            "RG" to IdentityField.RG,
            "ID card" to IdentityField.NATIONAL_ID,
        )
        roundTrip.forEach { (label, field) ->
            val kind = IdentityDocumentKind.fromLabel(label)
            val name = requireNotNull(kind.customFieldName) { "$label is stored in a custom field" }
            val values = IdentityValues(customFields = listOf(name to "FAKE-0000"))
            assertEquals("FAKE-0000", valueOf(field, values), "$label → $name → $field")
        }
        assertEquals(IdentityDocumentKind.PASSPORT, IdentityDocumentKind.fromLabel("Passport"))
        assertEquals(IdentityDocumentKind.PASSPORT, IdentityDocumentKind.fromLabel("Reisepass"))
        assertEquals(
            IdentityDocumentKind.DRIVING_LICENCE,
            IdentityDocumentKind.fromLabel("Driving licence"),
        )
        assertEquals(IdentityDocumentKind.SSN, IdentityDocumentKind.fromLabel("SSN"))
        assertEquals(IdentityDocumentKind.OTHER, IdentityDocumentKind.fromLabel(null))
    }

    @Test
    fun `values never reach a log line`() {
        assertEquals("IdentityValues(redacted)", spanishId.toString())
    }
}
