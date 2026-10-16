package com.diegonmarcos.cloudbrowser

import com.diegonmarcos.superapp.browser.AcFieldClassifier
import com.diegonmarcos.superapp.browser.AcFieldSignals
import com.diegonmarcos.superapp.browser.AcKind
import com.diegonmarcos.superapp.browser.AcTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #900 the field classifier: autocomplete attribute / type / name / id / label / placeholder to a WHATWG token and a kind. */
class AcFieldClassifierTest {

    private fun c(s: AcFieldSignals) = AcFieldClassifier.classify(s)
    private fun tok(s: AcFieldSignals) = c(s).token

    @Test
    fun `the autocomplete attribute wins over everything, the last known token of the list`() {
        assertEquals(AcTokens.GIVEN, tok(AcFieldSignals(autocomplete = "shipping given-name", name = "phone")))
        assertEquals(AcTokens.POSTAL, tok(AcFieldSignals(autocomplete = "section-a billing postal-code", label = "Name")))
        assertEquals(AcTokens.TEL, tok(AcFieldSignals(autocomplete = "tel-national")))
        assertEquals(AcTokens.COUNTRY, tok(AcFieldSignals(autocomplete = "country-name")))
        assertEquals(AcTokens.LINE1, tok(AcFieldSignals(autocomplete = "address-line1", name = "city")))
        assertEquals(AcKind.PROFILE, c(AcFieldSignals(autocomplete = "family-name")).kind)
    }

    @Test
    fun `autocomplete secrets are secrets`() {
        assertEquals(AcKind.PASSWORD, c(AcFieldSignals(autocomplete = "current-password", type = "text")).kind)
        assertEquals(AcKind.NEW_PASSWORD, c(AcFieldSignals(autocomplete = "new-password", type = "password")).kind)
        assertEquals(AcKind.OTP, c(AcFieldSignals(autocomplete = "one-time-code")).kind)
        assertEquals(AcKind.LOGIN_USER, c(AcFieldSignals(autocomplete = "username")).kind)
        for (t in listOf("cc-number", "cc-exp", "cc-exp-month", "cc-exp-year", "cc-csc", "cc-name", "cc-type"))
            assertEquals(t, tok(AcFieldSignals(autocomplete = t))); 
        assertEquals(AcKind.CARD, c(AcFieldSignals(autocomplete = "cc-number")).kind)
        assertTrue(c(AcFieldSignals(autocomplete = "cc-csc")).isSecret)
    }

    @Test
    fun `a password box is a secret whatever it is called, new when it says so`() {
        assertEquals(AcKind.PASSWORD, c(AcFieldSignals(type = "password", name = "pet", label = "Name of your first pet")).kind)
        assertEquals(AcKind.NEW_PASSWORD, c(AcFieldSignals(type = "password", name = "pw2", label = "Confirm password")).kind)
        assertEquals(AcKind.NEW_PASSWORD, c(AcFieldSignals(type = "password", label = "Nueva contrasena")).kind)
        assertEquals(AcTokens.CURRENT_PASSWORD, tok(AcFieldSignals(type = "password", name = "password")))
    }

    @Test
    fun `type email and tel`() {
        assertEquals(AcTokens.EMAIL, tok(AcFieldSignals(type = "email", name = "x")))
        assertEquals(AcTokens.TEL, tok(AcFieldSignals(type = "tel", name = "x")))
    }

    @Test
    fun `names ids and labels in english spanish portuguese french german`() {
        assertEquals(AcTokens.GIVEN, tok(AcFieldSignals(name = "firstName")))
        assertEquals(AcTokens.GIVEN, tok(AcFieldSignals(label = "Vorname")))
        assertEquals(AcTokens.GIVEN, tok(AcFieldSignals(label = "Prénom")))
        assertEquals(AcTokens.FAMILY, tok(AcFieldSignals(id = "lastname")))
        assertEquals(AcTokens.FAMILY, tok(AcFieldSignals(label = "Apellidos")))
        assertEquals(AcTokens.FAMILY, tok(AcFieldSignals(label = "Nachname")))
        assertEquals(AcTokens.NAME, tok(AcFieldSignals(label = "Full name")))
        assertEquals(AcTokens.NAME, tok(AcFieldSignals(placeholder = "Nome completo")))
        assertEquals(AcTokens.LINE1, tok(AcFieldSignals(label = "Straße")))
        assertEquals(AcTokens.LINE1, tok(AcFieldSignals(name = "street_address")))
        assertEquals(AcTokens.LINE1, tok(AcFieldSignals(label = "Dirección")))
        assertEquals(AcTokens.LINE2, tok(AcFieldSignals(label = "Apartment, suite, etc.")))
        assertEquals(AcTokens.POSTAL, tok(AcFieldSignals(label = "Código postal")))
        assertEquals(AcTokens.POSTAL, tok(AcFieldSignals(name = "zip")))
        assertEquals(AcTokens.POSTAL, tok(AcFieldSignals(label = "PLZ")))
        assertEquals(AcTokens.LEVEL2, tok(AcFieldSignals(label = "Cidade")))
        assertEquals(AcTokens.LEVEL2, tok(AcFieldSignals(name = "city")))
        assertEquals(AcTokens.LEVEL1, tok(AcFieldSignals(label = "State / Province")))
        assertEquals(AcTokens.COUNTRY, tok(AcFieldSignals(label = "País")))
        assertEquals(AcTokens.TEL, tok(AcFieldSignals(label = "Teléfono móvil")))
        assertEquals(AcTokens.EMAIL, tok(AcFieldSignals(label = "Correo electrónico")))
        assertEquals(AcTokens.ORGANIZATION, tok(AcFieldSignals(label = "Company")))
        assertEquals(AcTokens.BDAY, tok(AcFieldSignals(label = "Fecha de nacimiento")))
        assertEquals(AcTokens.X_NUMBER, tok(AcFieldSignals(label = "Hausnummer")))
    }

    @Test
    fun `postal code is not mistaken for a one-time code, a verification code is`() {
        assertEquals(AcTokens.POSTAL, tok(AcFieldSignals(label = "Postal code", maxLength = 5, inputMode = "numeric")))
        assertEquals(AcKind.OTP, c(AcFieldSignals(label = "Verification code")).kind)
        assertEquals(AcKind.OTP, c(AcFieldSignals(name = "code", label = "Code", maxLength = 6, inputMode = "numeric")).kind)
        assertEquals(AcKind.OTP, c(AcFieldSignals(label = "Código de verificación")).kind)
    }

    @Test
    fun `card fields by words`() {
        assertEquals(AcTokens.CC_NUMBER, tok(AcFieldSignals(name = "cardnumber")))
        assertEquals(AcTokens.CC_NUMBER, tok(AcFieldSignals(label = "Número de tarjeta")))
        assertEquals(AcTokens.CC_CSC, tok(AcFieldSignals(label = "CVV")))
        assertEquals(AcTokens.CC_CSC, tok(AcFieldSignals(label = "Sicherheitscode")))
        assertEquals(AcTokens.CC_EXP, tok(AcFieldSignals(label = "Expiry date MM/YY")))
        assertEquals(AcTokens.CC_NAME, tok(AcFieldSignals(label = "Name on card")))
        assertEquals(AcKind.CARD, c(AcFieldSignals(label = "Card number")).kind)
    }

    @Test
    fun `documents`() {
        assertEquals(AcTokens.X_PASSPORT, tok(AcFieldSignals(label = "Passport number")))
        assertEquals(AcTokens.X_PASSPORT, tok(AcFieldSignals(label = "Número de pasaporte")))
        assertEquals(AcTokens.X_NATIONAL_ID, tok(AcFieldSignals(label = "DNI")))
        assertEquals(AcTokens.X_NATIONAL_ID, tok(AcFieldSignals(label = "Personalausweis")))
        assertEquals(AcTokens.X_TAX_ID, tok(AcFieldSignals(label = "Steuer-ID")))
        assertEquals(AcTokens.X_RESIDENCE, tok(AcFieldSignals(label = "Residence permit number")))
        assertEquals(AcTokens.X_ISSUING, tok(AcFieldSignals(label = "Issuing country")))
        assertEquals(AcTokens.X_CITIZENSHIP, tok(AcFieldSignals(label = "Citizenship")))
    }

    @Test
    fun `sign-in boxes by words, and the person's NAME is not a user name`() {
        assertEquals(AcKind.LOGIN_USER, c(AcFieldSignals(label = "User name")).kind)
        assertEquals(AcKind.LOGIN_USER, c(AcFieldSignals(label = "Nombre de usuario")).kind)
        assertEquals(AcKind.LOGIN_USER, c(AcFieldSignals(name = "login")).kind)
        assertEquals(AcTokens.NAME, tok(AcFieldSignals(label = "Your name")))
    }

    @Test
    fun `search boxes, captchas, buttons and plain numbers are never filled or remembered`() {
        assertEquals(AcKind.IGNORE, c(AcFieldSignals(type = "search", name = "q")).kind)
        assertEquals(AcKind.IGNORE, c(AcFieldSignals(name = "q")).kind)
        assertEquals(AcKind.IGNORE, c(AcFieldSignals(label = "Enter the captcha")).kind)
        assertEquals(AcKind.IGNORE, c(AcFieldSignals(type = "submit")).kind)
        assertEquals(AcKind.IGNORE, c(AcFieldSignals(type = "hidden", name = "email")).kind)
        assertEquals(AcKind.IGNORE, c(AcFieldSignals(type = "checkbox", name = "tel")).kind)
        assertEquals(AcKind.IGNORE, c(AcFieldSignals(type = "number", name = "qty")).kind)
        assertEquals(AcKind.IGNORE, c(AcFieldSignals(label = "Promo code")).kind)
    }

    @Test
    fun `an unrecognised text box is a candidate for the per-site memory`() {
        assertEquals(AcKind.SITE, c(AcFieldSignals(label = "Customer number", name = "kundennr")).kind)
        assertEquals(AcKind.SITE, c(AcFieldSignals(label = "Member ID")).kind)
        assertFalse(c(AcFieldSignals(label = "Member ID")).isSecret)
    }

    @Test
    fun `accents and camel case are folded`() {
        assertEquals(AcTokens.FAMILY, tok(AcFieldSignals(name = "FamilyName")))
        assertEquals(AcTokens.GIVEN, tok(AcFieldSignals(name = "givenName")))
        assertEquals("cafe", AcFieldClassifier.norm("Café"))
        assertEquals("strasse", AcFieldClassifier.norm("Straße"))
    }
}
