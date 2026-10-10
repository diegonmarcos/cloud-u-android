package com.x8bit.bitwarden.data.autofill.cloud

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * ID-document fields across EN / ES / DE / PT, the fill-only name and address fields, and the
 * fields that only look like IDs ("User ID", "Apple ID", a card's expiry date).
 */
class IdentityFieldClassifierTest {

    private fun web(vararg attrs: Pair<String, String>) =
        FieldSignals(htmlTag = "input", htmlAttributes = mapOf("type" to "text") + attrs.toMap())

    private fun native(idEntry: String? = null, hint: String? = null) =
        FieldSignals(inputType = InputTypeBits.CLASS_TEXT, idEntry = idEntry, hint = hint)

    private fun assertField(expected: IdentityField, signals: FieldSignals) {
        assertEquals(expected, IdentityFieldClassifier.classify(signals), "for $signals")
    }

    private fun assertNotOurs(signals: FieldSignals) {
        assertNull(IdentityFieldClassifier.classify(signals), "for $signals")
    }

    @Test
    fun `Spanish document fields`() {
        assertField(IdentityField.DNI, web("name" to "dni"))
        assertField(IdentityField.DNI, web("label" to "Número de DNI"))
        assertField(IdentityField.DNI, web("label" to "DNI / NIE"))
        assertField(IdentityField.NIE, web("id" to "numeroNie"))
        assertField(IdentityField.NIF, web("placeholder" to "NIF"))
        assertField(IdentityField.SUPPORT_NUMBER, web("label" to "Número de soporte"))
        assertField(IdentityField.SUPPORT_NUMBER, web("name" to "num_soporte"))
        assertField(IdentityField.DOCUMENT_NUMBER, web("label" to "Número de documento"))
        assertField(IdentityField.PASSPORT_NUMBER, web("label" to "Pasaporte"))
        assertField(IdentityField.VALID_UNTIL, web("label" to "Fecha de caducidad del DNI"))
        assertField(IdentityField.ISSUING_COUNTRY, web("label" to "País de expedición"))
        assertField(IdentityField.RESIDENCE_PERMIT, web("label" to "Tarjeta de residencia"))
    }

    @Test
    fun `German document fields`() {
        assertField(IdentityField.PERSONALAUSWEIS, web("label" to "Personalausweisnummer"))
        assertField(IdentityField.PERSONALAUSWEIS, web("name" to "ausweisnummer"))
        assertField(IdentityField.PASSPORT_NUMBER, web("label" to "Reisepass"))
        assertField(IdentityField.PASSPORT_NUMBER, web("label" to "Passnummer"))
        assertField(IdentityField.PASSPORT_NUMBER, web("label" to "Pass-Nr."))
        assertField(
            IdentityField.VALID_UNTIL,
            web("name" to "ausweis_gueltig_bis", "label" to "Gültig bis"),
        )
        assertField(IdentityField.SUPPORT_NUMBER, web("label" to "Zugangsnummer (CAN)"))
        assertField(IdentityField.SUPPORT_NUMBER, web("label" to "CAN"))
        assertField(IdentityField.ISSUING_COUNTRY, web("label" to "Ausstellungsland"))
        assertField(IdentityField.RESIDENCE_PERMIT, web("label" to "Aufenthaltstitel"))
    }

    @Test
    fun `English document fields`() {
        assertField(IdentityField.PASSPORT_NUMBER, web("label" to "Passport number"))
        assertField(IdentityField.PASSPORT_NUMBER, web("name" to "passportNumber"))
        assertField(IdentityField.NATIONAL_ID, web("label" to "ID number"))
        assertField(IdentityField.NATIONAL_ID, web("label" to "National ID"))
        assertField(IdentityField.NATIONAL_ID, web("label" to "ID card number"))
        assertField(IdentityField.DOCUMENT_NUMBER, web("label" to "Document number"))
        assertField(IdentityField.LICENSE_NUMBER, web("label" to "Driver's license number"))
        assertField(IdentityField.SSN, web("label" to "SSN"))
        assertField(
            IdentityField.VALID_UNTIL,
            web("id" to "passport_valid_until", "label" to "Valid until"),
        )
        assertField(IdentityField.ISSUING_COUNTRY, web("label" to "Issuing country"))
        assertField(IdentityField.ISSUING_COUNTRY, web("label" to "Passport issuing country"))
    }

    @Test
    fun `Portuguese document fields`() {
        assertField(IdentityField.CPF, web("name" to "cpf"))
        assertField(IdentityField.RG, web("label" to "RG"))
        assertField(IdentityField.RG, web("label" to "Número da identidade"))
        assertField(IdentityField.PASSPORT_NUMBER, web("label" to "Passaporte"))
        assertField(IdentityField.VALID_UNTIL, web("label" to "Data de validade do passaporte"))
        assertField(IdentityField.ISSUING_COUNTRY, web("label" to "País emissor"))
    }

    @Test
    fun `native views and non-standard autofill hints`() {
        assertField(IdentityField.DNI, native(idEntry = "dni_input"))
        assertField(IdentityField.PASSPORT_NUMBER, native(hint = "Passport number"))
        assertField(
            IdentityField.PASSPORT_NUMBER,
            FieldSignals(inputType = InputTypeBits.CLASS_TEXT, autofillHints = listOf("passportNumber")),
        )
    }

    @Test
    fun `an expiry date that names no document is fill-only`() {
        assertField(IdentityField.VALID_UNTIL_ON_PAGE, web("label" to "Fecha de caducidad"))
        assertField(IdentityField.VALID_UNTIL_ON_PAGE, web("label" to "Validade"))
        assertFalse(IdentityField.VALID_UNTIL_ON_PAGE.isDocument)
        assertTrue(IdentityField.VALID_UNTIL.isDocument)
    }

    @Test
    fun `fields that only look like IDs are not ours`() {
        assertNotOurs(web("label" to "User ID"))
        assertNotOurs(web("name" to "user_id"))
        assertNotOurs(web("name" to "userId"))
        assertNotOurs(web("label" to "Apple ID"))
        assertNotOurs(web("label" to "Login ID"))
        assertNotOurs(web("label" to "Customer ID number"))
        assertNotOurs(web("label" to "Order ID"))
        assertNotOurs(web("label" to "ID"))
        assertNotOurs(web("name" to "id"))
        assertNotOurs(web("label" to "Vehicle identification number"))
        assertNotOurs(web("label" to "Número de cliente"))
        assertNotOurs(web("label" to "Promo code"))
        assertNotOurs(native(idEntry = "search_box"))
    }

    @Test
    fun `secrets and card fields are never ID fields, whatever their name says`() {
        assertNotOurs(web("type" to "password", "name" to "dni"))
        assertNotOurs(web("label" to "PIN del DNIe"))
        assertNotOurs(web("label" to "Contraseña del DNI"))
        assertNotOurs(web("type" to "email", "name" to "dni"))
        assertNotOurs(web("autocomplete" to "username", "name" to "dni"))
        assertNotOurs(web("autocomplete" to "cc-exp", "label" to "Fecha de caducidad"))
        assertNotOurs(web("label" to "Fecha de caducidad de la tarjeta"))
        assertNotOurs(web("label" to "Card expiry date"))
        assertNotOurs(web("label" to "Gültig bis", "name" to "kreditkarte_gueltig"))
        assertNotOurs(web("label" to "Card number"))
        assertNotOurs(FieldSignals(autofillHints = listOf("password"), inputType = 1))
        assertNotOurs(
            FieldSignals(
                inputType = InputTypeBits.CLASS_NUMBER or InputTypeBits.NUMBER_VARIATION_PASSWORD,
                idEntry = "passport_pin",
            ),
        )
        // Not an input at all.
        assertNotOurs(FieldSignals(htmlTag = "div", htmlAttributes = mapOf("id" to "dni")))
        assertNotOurs(FieldSignals(idEntry = "dni_label"))
    }

    @Test
    fun `names and address are fill-only fields`() {
        val cases = listOf(
            web("autocomplete" to "given-name") to IdentityField.FIRST_NAME,
            web("autocomplete" to "family-name") to IdentityField.LAST_NAME,
            web("autocomplete" to "shipping postal-code") to IdentityField.POSTAL_CODE,
            web("label" to "Apellidos") to IdentityField.LAST_NAME,
            web("label" to "Vorname") to IdentityField.FIRST_NAME,
            web("label" to "Nome completo") to IdentityField.FULL_NAME,
            web("label" to "Postleitzahl") to IdentityField.POSTAL_CODE,
            web("name" to "postal_code") to IdentityField.POSTAL_CODE,
            web("label" to "Código postal") to IdentityField.POSTAL_CODE,
            web("label" to "ZIP code") to IdentityField.POSTAL_CODE,
            native(idEntry = "postal_code") to IdentityField.POSTAL_CODE,
            web("label" to "Ciudad") to IdentityField.CITY,
            web("label" to "País") to IdentityField.COUNTRY,
            FieldSignals(autofillHints = listOf("personGivenName"), inputType = 1) to
                IdentityField.FIRST_NAME,
        )
        cases.forEach { (signals, expected) ->
            assertField(expected, signals)
            assertFalse(expected.isDocument, "$expected must be fill-only")
        }
        // Not a name: a username, a company, a split Spanish surname, a phone country code.
        assertNotOurs(web("label" to "Nombre de usuario"))
        assertNotOurs(web("label" to "Company name"))
        assertNotOurs(web("label" to "Primer apellido"))
        assertNotOurs(web("label" to "Country code", "type" to "text"))
        assertNotOurs(web("label" to "Prefijo del país"))
    }

    @Test
    fun `the vault classifier reports document fields before card and username words`() {
        assertEquals(FieldKind.ID_DOCUMENT, CloudFieldClassifier.classify(web("label" to "Número de DNI")))
        assertEquals(FieldKind.ID_DOCUMENT, CloudFieldClassifier.classify(web("label" to "ID card number")))
        assertEquals(FieldKind.ID_DOCUMENT, CloudFieldClassifier.classify(web("name" to "passportNumber")))
        // "ID" in an unrelated username field stays a username.
        assertEquals(FieldKind.USERNAME, CloudFieldClassifier.classify(web("name" to "user_id")))
        assertEquals(FieldKind.USERNAME, CloudFieldClassifier.classify(web("name" to "login_id")))
        // A name field is no document: the vault classifier has nothing to say about it.
        assertEquals(FieldKind.NONE, CloudFieldClassifier.classify(web("autocomplete" to "given-name")))
        assertTrue(IdentityFieldClassifier.isDocumentField(web("label" to "Reisepass")))
        assertFalse(IdentityFieldClassifier.isDocumentField(web("label" to "Vorname")))
    }

    @Test
    fun `normalize folds accents, eszett and camel case`() {
        assertEquals("numero de soporte", IdentityFieldClassifier.normalize("Número de soporte"))
        assertEquals("gultig bis", IdentityFieldClassifier.normalize("Gültig bis"))
        assertEquals("strasse", IdentityFieldClassifier.normalize("Straße"))
        assertEquals("passport number", IdentityFieldClassifier.normalize("passportNumber"))
        assertEquals("idcard number", IdentityFieldClassifier.normalize("ID card number"))
    }
}
