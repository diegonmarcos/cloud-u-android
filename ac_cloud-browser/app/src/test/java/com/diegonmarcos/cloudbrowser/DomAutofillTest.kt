package com.diegonmarcos.cloudbrowser

import com.diegonmarcos.superapp.autofill.AddressFormat
import com.diegonmarcos.superapp.autofill.AddressType
import com.diegonmarcos.superapp.autofill.AutofillAddress
import com.diegonmarcos.superapp.autofill.AutofillCandidates
import com.diegonmarcos.superapp.autofill.AutofillContact
import com.diegonmarcos.superapp.autofill.AutofillProfile
import com.diegonmarcos.superapp.autofill.ContactKind
import com.diegonmarcos.superapp.autofill.Fields
import com.diegonmarcos.superapp.autofill.FillTarget
import com.diegonmarcos.superapp.autofill.SiteRule
import com.diegonmarcos.superapp.autofill.Snippet
import com.diegonmarcos.superapp.browser.BrowserClearCategories
import com.diegonmarcos.superapp.browser.BrowserProfile
import com.diegonmarcos.superapp.browser.BrowserStorage
import com.diegonmarcos.superapp.browser.DomAutofillPlan
import com.diegonmarcos.superapp.browser.PlanField
import com.diegonmarcos.superapp.browser.ProfileAddress
import com.diegonmarcos.superapp.browser.ProfileIdentity
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tier 2 DOM autofill, the host's pure half (a0_docs/eng-specs/autofill-3-tier.md): the chip's words,
 * which value each field gets per country, the profile picker order (site rule, TLD, default), the save
 * proposal, the per-host engine config, and the clear-data categories. The page engine itself runs under
 * node in test/test-browser-dom-autofill.sh. Every fixture is an obvious fake.
 */
class DomAutofillTest {
    private fun addr(country: String, vararg f: Pair<String, String>, type: String = AddressType.HOME, default: Boolean = true) =
        AutofillAddress(type = type, isDefault = default, fields = mapOf(*f) + ("country" to country))

    private val spain = AutofillProfile(id = 1, label = "Testy - Spain-A", isDefault = true,
        fields = mapOf("given_name" to "Testy", "family_name" to "Fakeson", "family_name2" to "Example", "nationality" to "ES"),
        addresses = listOf(addr("ES", "street" to "Calle Falsa", "house_number" to "1", "floor_door" to "P04 0001", "postal_code" to "00000", "city" to "Sampletown")),
        contacts = listOf(AutofillContact(kind = ContactKind.EMAIL, value = "testy@example.invalid", isDefault = true),
            AutofillContact(kind = ContactKind.TEL, value = "+00 900 000 000", type = "landline"),
            AutofillContact(kind = ContactKind.TEL, value = "+00 600 000 000", type = "mobile")))
    private val germany = AutofillProfile(id = 2, label = "Testy - Germany-A", fields = mapOf("given_name" to "Testy", "family_name" to "Fakeson"),
        addresses = listOf(
            addr("DE", "co_line" to "Erika Example", "street" to "Musterstraße", "house_number" to "1", "postal_code" to "00000", "city" to "Musterstadt", type = AddressType.POSTAL),
            addr("DE", "street" to "Packstation", "house_number" to "000", "postal_code" to "00000", "city" to "Musterstadt", type = AddressType.POST_OFFICE, default = false)),
        contacts = listOf(AutofillContact(kind = ContactKind.EMAIL, value = "de@example.invalid")))
    private val brazil = AutofillProfile(id = 3, label = "Testy - Brazil", fields = mapOf("given_name" to "Testy", "family_name" to "Fakeson"),
        addresses = listOf(addr("BR", "street" to "Rua Exemplo", "house_number" to "100", "complement" to "apto 00", "neighborhood" to "Centro Falso",
            "postal_code" to "00000-000", "city" to "Cidade Exemplo", "state" to "XX")))
    private val all = listOf(spain, germany, brazil)

    // ── per-country formats ─────────────────────────────────────────────

    @Test fun address_lines_per_country() {
        val es = FillTarget(spain, spain.addresses[0])
        assertEquals("Calle Falsa, 1", es.value("address-line1"))
        assertEquals("P04 0001", es.value("address-line2"))
        assertEquals("Fakeson Example", es.value("family-name"))
        assertEquals("Fakeson", es.value("x-family-name-1")); assertEquals("Example", es.value("x-family-name-2"))
        assertEquals("España", es.value("country-name"))
        val de = FillTarget(germany, germany.addresses[0])
        assertEquals("Musterstraße 1", de.value("address-line1"))
        assertEquals("c/o Erika Example", de.value("address-line2"))
        assertEquals("with its own c/o field the line stays clean", "", de.value("address-line2", coSeparate = true))
        assertEquals("c/o Erika Example", de.value("x-co"))
        assertEquals("c/o Erika Example\nMusterstraße 1", de.value("street-address"))
        val br = FillTarget(brazil, brazil.addresses[0])
        assertEquals("Rua Exemplo, 100", br.value("address-line1"))
        assertEquals("apto 00", br.value("address-line2"))
        assertEquals("Centro Falso", br.value("address-level3"))
        assertEquals("00000-000", br.value("postal-code"))
        assertEquals("100 Main St", AddressFormat.line1("US", "Main St", "100"))
    }

    @Test fun phones_prefer_the_default_then_mobile() {
        assertEquals("+00 600 000 000", FillTarget(spain, null).value("tel"))
        assertEquals(listOf("+00 600 000 000", "+00 900 000 000"), AutofillCandidates.forKey("tel", listOf(spain)))
    }

    // ── picker: site rule, TLD, default ─────────────────────────────────

    @Test fun picker_order_follows_site_rule_then_country_then_default() {
        assertEquals("Testy - Spain-A", AutofillCandidates.defaultProfileFor("shop.example", emptyList(), all)!!.label)
        assertEquals("a .de site prefers the profile with a DE address", "Testy - Germany-A", AutofillCandidates.defaultProfileFor("www.laden.de", emptyList(), all)!!.label)
        val rule = SiteRule(9, "shop.example", fieldKey = Fields.PROFILE, literalValue = "testy - brazil")
        assertEquals("Testy - Brazil", AutofillCandidates.defaultProfileFor("checkout.shop.example", listOf(rule), all)!!.label)
        val targets = DomAutofillPlan.targets("www.laden.de", listOf("address-line1", "postal-code"), emptyList(), all)
        assertEquals(listOf("Testy - Germany-A · Postal", "Testy - Germany-A · Post office", "Testy - Spain-A · Home", "Testy - Brazil · Home"),
            targets.map { it.title })
        assertEquals("Fill address: Testy - Germany-A · Postal ▾", DomAutofillPlan.chipText("address", targets, false))
    }

    @Test fun incognito_chip_names_no_profile_and_snippets_need_a_pick() {
        val t = DomAutofillPlan.targets("shop.example", listOf("email"), emptyList(), all)
        assertEquals("Autofill…", DomAutofillPlan.chipText("contact", t, true))
        assertNull("no profile, no chip", DomAutofillPlan.chipText("address", emptyList(), false))
        assertEquals("Insert snippet ▾", DomAutofillPlan.chipText("snippet", emptyList(), false, snippets = 2))
        assertNull(DomAutofillPlan.chipText("snippet", emptyList(), false, snippets = 0))
    }

    // ── values ──────────────────────────────────────────────────────────

    @Test fun values_fill_a_split_street_form() {
        val plan = listOf(PlanField(0, "given-name"), PlanField(1, "x-family-name-1"), PlanField(2, "x-family-name-2"), PlanField(3, "x-street"),
            PlanField(4, "x-house-number"), PlanField(5, "x-floor-door"), PlanField(6, "postal-code"), PlanField(7, "x-nationality"), PlanField(8, "organization"))
        val v = DomAutofillPlan.values(plan, FillTarget(spain, spain.addresses[0]))
        assertEquals(mapOf(0 to "Testy", 1 to "Fakeson", 2 to "Example", 3 to "Calle Falsa", 4 to "1", 5 to "P04 0001", 6 to "00000", 7 to "ES"), v)
    }

    @Test fun a_secret_or_id_key_never_gets_a_value_whatever_asked() {
        val v = DomAutofillPlan.values(listOf(PlanField(0, "current-password"), PlanField(1, "one-time-code"), PlanField(2, "cc-number"),
            PlanField(3, "username"), PlanField(4, "new-password", literal = "fake"), PlanField(5, "x-id-number"), PlanField(6, "x-snippet")), FillTarget(spain, null))
        assertTrue(v.isEmpty())
        listOf("current-password", "new-password", "one-time-code", "cc-number", "cc-csc", "username", "x-id-dni").forEach { assertTrue(it, Fields.isSecretToken(it)) }
        listOf("email", "tel", "name", "postal-code", "x-house-number").forEach { assertFalse(it, Fields.isSecretToken(it)) }
    }

    @Test fun a_rule_literal_wins_and_a_snippet_goes_only_into_its_field() {
        assertEquals(mapOf(7 to "Newsletter"), DomAutofillPlan.values(listOf(PlanField(7, "literal", literal = "Newsletter"), PlanField(8, "unknown-key")), FillTarget(spain, null)))
        val s = Snippet(1, "About me", "Line one 🙂\nLine two")
        assertEquals(mapOf(3 to "Line one 🙂\nLine two"), DomAutofillPlan.snippetValues(listOf(PlanField(3, "x-snippet"), PlanField(4, "email")), s))
        assertTrue(DomAutofillPlan.snippetValues(listOf(PlanField(4, "email")), s).isEmpty())
    }

    @Test fun fields_parse_the_engine_report() {
        val a = JSONArray().put(JSONObject().put("id", 3).put("key", "email").put("literal", ""))
            .put(JSONObject().put("id", -1).put("key", "tel")).put(JSONObject().put("id", 5).put("key", ""))
        assertEquals(listOf(PlanField(3, "email", "")), DomAutofillPlan.fields(a))
    }

    // ── save proposal ───────────────────────────────────────────────────

    @Test fun submitted_address_goes_under_the_sites_profile_unless_known() {
        val offer = DomAutofillPlan.proposal(mapOf("x-street" to "Otra Calle", "x-house-number" to "9", "postal-code" to "99999",
            "address-level2" to "Elsewhere", "current-password" to "pw-zz"), all, spain)!!
        assertEquals(1L, offer.profileId)
        assertEquals("Otra Calle", offer.address["street"]); assertEquals("9", offer.address["house_number"])
        assertFalse(offer.address.fields.values.any { it.contains("pw-zz") })
        assertNull("same place is not offered again", DomAutofillPlan.proposal(mapOf("x-street" to "calle  falsa", "x-house-number" to "1",
            "postal-code" to "00000", "country" to "es"), all, spain))
        assertNull("a contact form is not an address", DomAutofillPlan.proposal(mapOf("email" to "x@example.invalid", "name" to "X"), all, spain))
        val fresh = DomAutofillPlan.proposal(mapOf("name" to "Fakey Person", "street-address" to "9 Other Road\nUnit 9", "postal-code" to "99999",
            "email" to "fakey@example.invalid"), emptyList(), null)!!
        assertNull(fresh.profileId)
        assertEquals("Fakey", fresh.profile["given_name"]); assertEquals("Person", fresh.profile["family_name"])
        assertEquals("Unit 9", fresh.address["complement"])
        assertEquals("fakey@example.invalid", fresh.profile.contacts.single().value)
    }

    @Test fun legacy_browser_profile_still_fills() {
        val legacy = DomAutofillPlan.legacy(BrowserProfile(ProfileIdentity(first = "Testy", last = "McTestface", email = "testy@example.invalid"),
            listOf(ProfileAddress(label = "Old", street = "Example Street", number = "1", zip = "00000", city = "Sampletown"))))
        val p = legacy.single()
        assertEquals("testy@example.invalid", FillTarget(p, null).value("email"))
        assertEquals("Example Street 1", FillTarget(p, p.addresses.single()).value("address-line1"))
        assertTrue(DomAutofillPlan.legacy(BrowserProfile()).isEmpty())
    }

    @Test fun engine_config_carries_only_this_hosts_field_rules() {
        val rules = listOf(
            SiteRule(1, "shop.example", "form.checkout", "#plz", "postal-code"),
            SiteRule(2, "other.example", "", "#x", "email"),
            SiteRule(3, "shop.example", "", "#off", "email", enabled = false),
            SiteRule(4, "example", "", "#y", "tel"),
            SiteRule(5, "shop.example", fieldKey = Fields.PROFILE, literalValue = "Testy - Brazil"),
        )
        val c = DomAutofillPlan.config("www.shop.example", rules, incognito = true)
        assertTrue(c.getBoolean("incognito"))
        val sel = (0 until c.getJSONArray("rules").length()).map { c.getJSONArray("rules").getJSONObject(it).getString("field") }
        assertEquals("most specific first; disabled, foreign and profile rules stay out of the page", listOf("#plz", "#y"), sel)
    }

    // ── clear-data categories (3.1): autofill data is never a cookie or site-data box ─

    @Test fun no_cookie_or_site_data_clear_reaches_autofill_data() {
        val storageIds = BrowserStorage.ITEMS.map { it.first }
        val clearBoxes = listOf("history", "cookies", "cache", "storage", "previews", "downloads")
        (storageIds + clearBoxes + BrowserClearCategories.SITE_BOXES.map { it.first }).forEach { id ->
            val cat = BrowserClearCategories.of(id)
            assertTrue("$id is cookies or site data, got $cat", cat == BrowserClearCategories.COOKIES || cat == BrowserClearCategories.SITE_DATA)
        }
        assertEquals(BrowserClearCategories.AUTOFILL, BrowserClearCategories.of(BrowserClearCategories.AUTOFILL_LOCAL))
        assertFalse("autofill is never pre-ticked", BrowserClearCategories.AUTOFILL_LOCAL in
            BrowserClearCategories.defaults(storageIds + BrowserClearCategories.AUTOFILL_LOCAL))
        assertEquals(setOf(BrowserClearCategories.SITE_COOKIES, BrowserClearCategories.SITE_STORAGE),
            BrowserClearCategories.defaults(BrowserClearCategories.SITE_BOXES.map { it.first }))
    }

    @Test fun per_site_clear_touches_only_that_site() {
        val o = BrowserClearCategories.siteOrigins("shop.example")
        assertEquals(listOf("https://shop.example", "http://shop.example", "https://www.shop.example", "http://www.shop.example"), o)
        assertTrue(o.none { it.contains("other.example") || it.endsWith("://example") })
        assertTrue(BrowserClearCategories.siteOrigins("").isEmpty())
        assertNotNull(BrowserClearCategories.siteOrigins("www.shop.example").firstOrNull { it == "https://shop.example" })
    }
}
