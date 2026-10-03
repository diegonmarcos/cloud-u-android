package com.diegonmarcos.cloudbrowser

import com.diegonmarcos.superapp.browser.BrowserAutofillMatch
import com.diegonmarcos.superapp.browser.BrowserProfile
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #802 I6 the autofill profile: vault mapping, importers, card numbers dropped, field matching. */
class BrowserProfileTest {

    private val vaultAbout = JSONObject("""{"profile":{"name":"Ada Lovelace","email":"ada@example.test","website":"ada.example"},
        "addresses":[{"label":"home","first_name":"Ada","middle_name":"K","last_name":"Lovelace","full_name":"Ada K Lovelace",
          "email":"ada@example.test","phone":"+49 30 1","street_name":"Main St","street_number":"5","apartment":"2",
          "city":"Berlin","state":"BE","zip":"10115","country":"DE"},
         {"label":"work","street_name":"Work Rd","street_number":"9","city":"Hamburg","zip":"20095","country":"DE"}]}""")

    private val vault = BrowserProfile.fromVaultBundle(vaultAbout.getJSONObject("profile"), vaultAbout.getJSONArray("addresses"))

    @Test
    fun `the vault bundle's about maps to identity and addresses`() {
        assertEquals("Ada", vault.identity.first)
        assertEquals("Lovelace", vault.identity.last)
        assertEquals("Ada Lovelace", vault.identity.full)
        assertEquals("+49 30 1", vault.identity.phone)
        assertEquals(2, vault.addresses.size)
        assertEquals("Main St 5", vault.addresses[0].line1)
        assertEquals("10115", vault.addresses[0].zip)
    }

    @Test
    fun `masked shows initials and counts, never a value`() {
        val m = vault.masked().toString()
        assertTrue(m.contains("\"initials\":\"AL\""))
        assertFalse("no name", m.contains("Lovelace"))
        assertFalse("no street", m.contains("Main St"))
        assertFalse("no email", m.contains("ada@"))
        assertEquals(2, vault.masked().getInt("addresses"))
    }

    @Test
    fun `a CSV export imports by header, quoted fields included`() {
        val p = BrowserProfile.parse(null, "Full Name,Street Address,City,Postal Code,Country\n" +
            "\"Lovelace, Ada\",\"1 \"\"Quoted\"\" St\",Berlin,10115,DE\nBob,2 Road,Paris,75001,FR\n")
        assertEquals(2, p.addresses.size)
        assertEquals("Lovelace, Ada", p.addresses[0].full)
        assertEquals("1 \"Quoted\" St", p.addresses[0].street)
        assertEquals("75001", p.addresses[1].zip)
    }

    @Test
    fun `card numbers and codes are dropped at parse, whatever the format`() {
        val bw = """{"items":[{"type":4,"name":"Me","identity":{"firstName":"Ada","lastName":"L","city":"Berlin","address1":"Main St 5"}},
            {"type":3,"name":"Visa","card":{"cardholderName":"Ada L","number":"4111 1111 1111 1234","code":"999","expMonth":"7","expYear":"2030","brand":"Visa"}}]}"""
        val p = BrowserProfile.parse("bitwarden", bw)
        assertEquals("1234", p.cards.single().last4)
        val stored = p.toJson().toString()
        assertFalse("no full number", stored.contains("4111"))
        assertFalse("no security code", stored.contains("999"))
        val ff = BrowserProfile.parse(null, """{"addresses":[{"given-name":"Ada","postal-code":"10115"}],"creditCards":[{"cc-name":"Ada","cc-number":"5500000000000004","cc-exp-month":1,"cc-exp-year":2031}]}""")
        assertEquals("0004", ff.cards.single().last4)
        assertFalse(ff.toJson().toString().contains("5500000000"))
        assertEquals("10115", ff.addresses.single().zip)
    }

    @Test
    fun `merge keeps one copy of an address and lets the newer identity win`() {
        val again = vault.merge(BrowserProfile.fromNative(vault.toJson()))
        assertEquals(2, again.addresses.size)
        assertEquals(vault, BrowserProfile.fromNative(vault.toJson()))
    }

    private fun field(vararg kv: Pair<String, String>) = JSONObject().also { o -> kv.forEach { o.put(it.first, it.second) } }

    @Test
    fun `fields are matched by autocomplete, type and words`() {
        assertEquals("first", BrowserAutofillMatch.kind(field("autocomplete" to "shipping given-name")))
        assertEquals("email", BrowserAutofillMatch.kind(field("type" to "email", "name" to "x")))
        assertEquals("zip", BrowserAutofillMatch.kind(field("name" to "billing_postcode")))
        assertEquals("email", BrowserAutofillMatch.kind(field("label" to "Email address")))
        assertEquals("apartment", BrowserAutofillMatch.kind(field("label" to "Address line 2")))
        assertEquals("line1", BrowserAutofillMatch.kind(field("name" to "street")))
        assertEquals("Main St 5", BrowserAutofillMatch.value("line1", vault))
        assertEquals("Work Rd 9", BrowserAutofillMatch.value("line1", vault, 1))
    }

    @Test
    fun `passwords and card fields are never filled`() {
        assertNull(BrowserAutofillMatch.kind(field("type" to "password", "name" to "email")))
        assertNull(BrowserAutofillMatch.kind(field("autocomplete" to "cc-number")))
        assertNull(BrowserAutofillMatch.kind(field("name" to "card_name")))
        assertNull(BrowserAutofillMatch.kind(field("label" to "CVV")))
        assertNull(BrowserAutofillMatch.kind(field("type" to "hidden", "name" to "email")))
        val plan = BrowserAutofillMatch.plan(JSONArray().put(field("type" to "password")).put(field("name" to "email").put("i", 1)))
        assertEquals(mapOf(1 to "email"), plan)
    }
}
