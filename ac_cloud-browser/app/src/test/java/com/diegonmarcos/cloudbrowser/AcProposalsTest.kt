package com.diegonmarcos.cloudbrowser

import com.diegonmarcos.superapp.browser.AcAddress
import com.diegonmarcos.superapp.browser.AcCard
import com.diegonmarcos.superapp.browser.AcCardFormat
import com.diegonmarcos.superapp.browser.AcEmail
import com.diegonmarcos.superapp.browser.AcFieldSignals
import com.diegonmarcos.superapp.browser.AcLogin
import com.diegonmarcos.superapp.browser.AcMemoryEntry
import com.diegonmarcos.superapp.browser.AcName
import com.diegonmarcos.superapp.browser.AcOffer
import com.diegonmarcos.superapp.browser.AcProfile
import com.diegonmarcos.superapp.browser.AcProposal
import com.diegonmarcos.superapp.browser.AcProposals
import com.diegonmarcos.superapp.browser.AcTokens
import com.diegonmarcos.superapp.browser.AcUnlockWindow
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #900 what a submitted form could add to the stores, and the small pure helpers around the fill. */
class AcProposalsTest {

    private val all = AcOffer(password = true, card = true, profileData = true, siteMemory = true)

    private fun field(id: Int, v: String, type: String = "text", name: String = "", label: String = "", ac: String = "", fw: Boolean = false) =
        JSONObject().put("id", id).put("v", v).put("type", type).put("name", name).put("label", label).put("autocomplete", ac).put("fw", fw)

    private fun submit(vararg f: JSONObject, site: String = "https://www.example.com/x", profiles: List<AcProfile> = emptyList(), logins: List<AcLogin> = emptyList(),
                       cards: List<AcCard> = emptyList(), memory: List<AcMemoryEntry> = emptyList(), offer: AcOffer = all, never: Set<String> = emptySet()) =
        AcProposals.fromSubmission(AcProposals.parse(JSONArray(f.toList())), site, "form1", profiles, logins, cards, memory, offer, never)

    @Test
    fun `a sign-in with a new password offers to save it, keyed on the registrable domain`() {
        val p = submit(field(0, "ana", name = "email", type = "email"), field(1, "s3cret!", type = "password", name = "password"))
        val pw = p.single() as AcProposal.Password
        assertEquals("example.com", pw.site); assertEquals("ana", pw.username); assertNull(pw.existingId)
    }

    @Test
    fun `a changed password offers an update, the same one nothing, a never-here site nothing, and the setting off nothing`() {
        val saved = listOf(AcLogin("l1", "example.com", "ana", "old"))
        val login = arrayOf(field(0, "ANA", name = "user"), field(1, "new-pw", type = "password"))
        assertEquals("l1", (submit(*login, logins = saved).single() as AcProposal.Password).existingId)
        assertTrue(submit(field(0, "ana", name = "user"), field(1, "old", type = "password"), logins = saved).isEmpty())
        assertTrue(submit(*login, never = setOf("example.com")).isEmpty())
        assertTrue(submit(*login, offer = all.copy(password = false)).isEmpty())
    }

    @Test
    fun `what Cloud Vault filled is not offered for saving again`() {
        assertTrue(submit(field(0, "ana", name = "user", fw = true), field(1, "vault-pw", type = "password", fw = true)).isEmpty())
    }

    @Test
    fun `a sign-up form's new password is offered, with the email as the user name`() {
        val p = submit(field(0, "ana@example.test", type = "email"), field(1, "Pw-123456", type = "password", ac = "new-password"), field(2, "Pw-123456", type = "password", ac = "new-password"))
        val pw = p.filterIsInstance<AcProposal.Password>().single()
        assertEquals("ana@example.test", pw.username)
    }

    @Test
    fun `a checkout card is offered once, only when it passes the Luhn check, with its expiry parsed and no code kept`() {
        val f = arrayOf(field(0, "4111 1111 1111 1111", name = "cardnumber"), field(1, "05/29", label = "Expiry date MM/YY"), field(2, "Ana G", label = "Name on card"), field(3, "123", label = "CVV"))
        val c = (submit(*f).single() as AcProposal.CardSave).card
        assertEquals("4111 1111 1111 1111", c.number); assertEquals("05", c.expMonth); assertEquals("2029", c.expYear); assertEquals("Ana G", c.holder)
        assertEquals("the security code is not saved from a submit", "", c.cvc)
        assertTrue("already saved", submit(*f, cards = listOf(AcCard(number = "4111111111111111"))).none { it is AcProposal.CardSave })
        assertTrue("fails Luhn", submit(field(0, "4111 1111 1111 1112", name = "cardnumber")).none { it is AcProposal.CardSave })
        assertTrue(submit(*f, offer = all.copy(card = false)).none { it is AcProposal.CardSave })
    }

    @Test
    fun `luhn and expiry parsing`() {
        assertTrue(AcProposals.luhn("4111111111111111")); assertTrue(AcProposals.luhn("5500 0000 0000 0004")); assertTrue(AcProposals.luhn("3782-822463-10005"))
        assertFalse(AcProposals.luhn("4111111111111112")); assertFalse(AcProposals.luhn("1234")); assertFalse(AcProposals.luhn("4111-1111-1111-111x"))
        assertEquals("05" to "2029", AcProposals.parseExpiry("05/29")); assertEquals("05" to "2029", AcProposals.parseExpiry("5 / 2029"))
        assertEquals("12" to "2031", AcProposals.parseExpiry("1231")); assertEquals("01" to "2030", AcProposals.parseExpiry("2030-01"))
        assertNull(AcProposals.parseExpiry("13/29")); assertNull(AcProposals.parseExpiry("soon"))
    }

    @Test
    fun `a checkout with a new name, address, phone and email offers each as a record for a profile`() {
        val p = submit(field(0, "Ana", ac = "given-name"), field(1, "Garcia", ac = "family-name"), field(2, "Calle Falsa 1", ac = "address-line1"),
            field(3, "28001", ac = "postal-code"), field(4, "Madrid", ac = "address-level2"), field(5, "+34 600", type = "tel"), field(6, "ana@example.test", type = "email"))
        val groups = p.filterIsInstance<AcProposal.ProfileRecord>().map { it.group }
        assertEquals(listOf(AcTokens.G_NAME, AcTokens.G_ADDRESS, AcTokens.G_PHONE, AcTokens.G_EMAIL), groups)
        val addr = p.filterIsInstance<AcProposal.ProfileRecord>().first { it.address != null }.address!!
        assertEquals("Madrid", addr.city); assertEquals("28001", addr.postal)
    }

    @Test
    fun `details a profile already holds are not offered again`() {
        val have = AcProfile(id = "p", names = listOf(AcName(given = "Ana", family = "Garcia")), phones = listOf(com.diegonmarcos.superapp.browser.AcPhone(value = "+34600")),
            emails = listOf(AcEmail(value = "ANA@example.test")), addresses = listOf(AcAddress(street = "Calle Falsa 1", postal = "28001")))
        val p = submit(field(0, "ana", ac = "given-name"), field(1, "GARCIA", ac = "family-name"), field(2, "+34 600", type = "tel"), field(3, "ana@example.test", type = "email"),
            field(4, "Calle Falsa 1", ac = "address-line1"), field(5, "28001", ac = "postal-code"), profiles = listOf(have))
        assertTrue(p.joinToString { it.title }, p.isEmpty())
    }

    @Test
    fun `no profile data is offered from a sign-in form, and the setting off offers none`() {
        assertTrue(submit(field(0, "Ana", ac = "given-name"), field(1, "pw", type = "password"), offer = all.copy(password = false)).none { it is AcProposal.ProfileRecord })
        assertTrue(submit(field(0, "Ana", ac = "given-name"), field(1, "Garcia", ac = "family-name"), offer = all.copy(profileData = false)).none { it is AcProposal.ProfileRecord })
    }

    @Test
    fun `a customer number is offered for this site only, once, and never from a search or password box`() {
        val f = arrayOf(field(0, "K-48213", name = "kundennr", label = "Kundennummer"), field(1, "shoes", type = "search", name = "q"))
        val p = submit(*f).single() as AcProposal.SiteValue
        assertEquals("example.com", p.entry.origin); assertEquals("kundennr", p.entry.fieldKey); assertEquals("K-48213", p.entry.value)
        assertTrue(submit(*f, memory = listOf(p.entry)).isEmpty())
        assertTrue(submit(*f, offer = all.copy(siteMemory = false)).isEmpty())
    }

    @Test
    fun `a sign-in form's identity box is the user name whatever it is called, but a sign-up's is not`() {
        val login = AcProposals.parse(JSONArray(listOf(field(0, "ana@example.test", type = "email", label = "Email"), field(1, "pw", type = "password"))))
        assertEquals(AcTokens.USERNAME, login[0].cls.token)
        val signup = AcProposals.parse(JSONArray(listOf(field(0, "ana@example.test", type = "email"), field(1, "pw", ac = "new-password", type = "password"))))
        assertEquals(AcTokens.EMAIL, signup[0].cls.token)
        val long = AcProposals.parse(JSONArray((0..5).map { field(it, "v", name = "f$it") } + field(9, "pw", type = "password")))
        assertTrue("a long form with a password is not a sign-in", long.none { it.cls.token == AcTokens.USERNAME })
    }

    @Test
    fun `adding a record to a profile keeps the rest and the defaults`() {
        val base = AcProfile(id = "p", names = listOf(AcName(given = "Ana", isDefault = true)))
        val rec = AcProposal.ProfileRecord(AcTokens.G_NAME, name = AcName(given = "Anita"))
        val next = AcProposals.addTo(base, rec)
        assertEquals(listOf("Ana", "Anita"), next.names.map { it.given }); assertEquals(1, next.names.count { it.isDefault }); assertTrue(next.names.first().isDefault)
    }

    @Test
    fun `a generated password has all four character classes and is not repeated`() {
        val a = AcProposals.generatePassword(); val b = AcProposals.generatePassword()
        assertEquals(20, a.length); assertFalse(a == b)
        assertTrue(a.any { it.isLowerCase() } && a.any { it.isUpperCase() } && a.any { it.isDigit() } && a.any { !it.isLetterOrDigit() })
    }

    @Test
    fun `card parts are written the way the box asks`() {
        val c = AcCard(holder = "Ana G", number = "4111111111111111", expMonth = "05", expYear = "2029", cvc = "123")
        assertEquals("4111 1111 1111 1111", AcCardFormat.value(c, AcTokens.CC_NUMBER))
        assertEquals("05/29", AcCardFormat.value(c, AcTokens.CC_EXP))
        assertEquals("05 / 2029", AcCardFormat.value(c, AcTokens.CC_EXP, AcFieldSignals(placeholder = "MM / YYYY")))
        assertEquals("05/2029", AcCardFormat.value(c, AcTokens.CC_EXP, AcFieldSignals(maxLength = 7)))
        assertEquals("29", AcCardFormat.value(c, AcTokens.CC_EXP_YEAR, AcFieldSignals(maxLength = 2)))
        assertEquals("2029", AcCardFormat.value(c, AcTokens.CC_EXP_YEAR))
        assertEquals("123", AcCardFormat.value(c, AcTokens.CC_CSC)); assertEquals("Visa", AcCardFormat.value(c, AcTokens.CC_TYPE))
        assertNull(AcCardFormat.value(c.copy(cvc = ""), AcTokens.CC_CSC)); assertNull(AcCardFormat.value(c, AcTokens.EMAIL))
    }

    @Test
    fun `one unlock opens a short window and no longer`() {
        val w = AcUnlockWindow(graceMs = 1000)
        assertFalse(w.isOpen(0)); w.open(100)
        assertTrue(w.isOpen(100)); assertTrue(w.isOpen(1099)); assertFalse(w.isOpen(1100)); assertFalse("time going backwards is not open", w.isOpen(50))
        w.open(2000); w.lock(); assertFalse(w.isOpen(2001))
    }
}
