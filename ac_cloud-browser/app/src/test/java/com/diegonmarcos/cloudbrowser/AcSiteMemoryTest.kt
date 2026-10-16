package com.diegonmarcos.cloudbrowser

import com.diegonmarcos.superapp.browser.AcClass
import com.diegonmarcos.superapp.browser.AcFieldClassifier
import com.diegonmarcos.superapp.browser.AcFieldSig
import com.diegonmarcos.superapp.browser.AcFieldSignals
import com.diegonmarcos.superapp.browser.AcKind
import com.diegonmarcos.superapp.browser.AcMemoryEntry
import com.diegonmarcos.superapp.browser.AcSiteMemory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #900 per-site memory: values keyed by origin + form signature + field key, offered on that site only. */
class AcSiteMemoryTest {

    private fun e(origin: String, form: String, field: String, value: String, uses: Int = 1, last: Long = 0, id: String = "$origin-$field-$value") =
        AcMemoryEntry(id, origin, form, field, field, value, last, uses)

    @Test
    fun `the origin key is the host without www or port`() {
        assertEquals("shop.example", AcSiteMemory.originKey("https://www.shop.example:8443/cart?x=1"))
        assertEquals("shop.example", AcSiteMemory.originKey("SHOP.example"))
        assertEquals("", AcSiteMemory.originKey("about:blank"))
        assertEquals("localhost", AcSiteMemory.originKey("http://localhost:8000/a"))
    }

    @Test
    fun `a field key is the first of name id autocomplete label and ignores a generated suffix`() {
        assertEquals("kundennr", AcFieldSig(name = "kundennr", id = "x", label = "Y").key())
        assertEquals("customer id", AcFieldSig(id = "Customer_ID").key())
        assertEquals("member", AcFieldSig(label = "Member").key())
        assertEquals("customer number", AcFieldSig(name = "customer_number_8812").key())
        assertEquals("feed", AcFieldSig(name = "feed").key())
        assertEquals("", AcFieldSig().key())
        assertEquals("autocomplete on does not name a field", "member id", AcFieldSig(autocomplete = "on", label = "Member ID").key())
    }

    @Test
    fun `a form signature does not depend on the order of its fields or a generated id`() {
        val a = AcSiteMemory.formSignature("checkout", listOf("kundennr", "plz", "ort"))
        assertEquals(a, AcSiteMemory.formSignature("checkout", listOf("ort", "kundennr", "plz")))
        assertEquals(AcSiteMemory.formSignature("form_9f3a2c11", listOf("a")), AcSiteMemory.formSignature("form_77b4d0e2", listOf("a")))
        assertFalse(a == AcSiteMemory.formSignature("checkout", listOf("kundennr", "plz")))
    }

    @Test
    fun `only an unclassified, identifiable, short, non-secret value is worth remembering`() {
        val site = AcClass(null, AcKind.SITE)
        val sig = AcFieldSig(name = "kundennr")
        assertTrue(AcSiteMemory.shouldRemember(sig, site, "K-48213"))
        assertFalse("a profile field is the profile's", AcSiteMemory.shouldRemember(sig, AcClass("given-name", AcKind.PROFILE), "Ana"))
        assertFalse("a password", AcSiteMemory.shouldRemember(sig, AcClass("current-password", AcKind.PASSWORD), "x"))
        assertFalse(AcSiteMemory.shouldRemember(sig, site, "x", type = "password"))
        assertFalse(AcSiteMemory.shouldRemember(sig, site, "  "))
        assertFalse(AcSiteMemory.shouldRemember(sig, site, "x".repeat(81)))
        assertFalse("multi-line", AcSiteMemory.shouldRemember(sig, site, "a\nb"))
        assertFalse("a field nobody can name", AcSiteMemory.shouldRemember(AcFieldSig(), site, "K-1"))
        assertFalse("a card-looking digit run, whatever its box is called", AcSiteMemory.shouldRemember(sig, site, "4111 1111 1111 1111"))
        assertTrue("a short digit run is a member number", AcSiteMemory.shouldRemember(sig, site, "20481"))
        assertFalse("a search box", AcSiteMemory.shouldRemember(sig, AcFieldClassifier.classify(AcFieldSignals(type = "search", name = "q")), "shoes"))
    }

    @Test
    fun `upsert counts a repeat, keeps at most five values per field and drops the least used`() {
        var l = emptyList<AcMemoryEntry>()
        l = AcSiteMemory.upsert(l, e("a.example", "f", "kundennr", "1"), 10)
        l = AcSiteMemory.upsert(l, e("a.example", "f", "kundennr", "1"), 20)
        assertEquals(1, l.size); assertEquals(2, l.single().uses); assertEquals(20L, l.single().lastUsed)
        for (v in 2..7) l = AcSiteMemory.upsert(l, e("a.example", "f", "kundennr", "$v"), 30L + v)
        assertEquals(AcSiteMemory.MAX_PER_FIELD, l.size)
        assertTrue("the twice-used value survives", l.any { it.value == "1" })
        assertFalse("the oldest single-use value went", l.any { it.value == "2" })
        // another field or another site is a separate budget
        l = AcSiteMemory.upsert(l, e("a.example", "f", "plz", "28001"), 99)
        l = AcSiteMemory.upsert(l, e("b.example", "f", "kundennr", "9"), 99)
        assertEquals(AcSiteMemory.MAX_PER_FIELD + 2, l.size)
    }

    @Test
    fun `suggestions are the site's own - this form first, then the same field of another form, never another site`() {
        val l = listOf(
            e("a.example", "f1", "kundennr", "FORM1", uses = 1), e("a.example", "f2", "kundennr", "OTHERFORM", uses = 9),
            e("b.example", "f1", "kundennr", "OTHERSITE", uses = 99), e("a.example", "f1", "plz", "28001"),
        )
        assertEquals(listOf("FORM1", "OTHERFORM"), AcSiteMemory.suggestions(l, "a.example", "f1", "kundennr").map { it.value })
        assertEquals(listOf("OTHERSITE"), AcSiteMemory.suggestions(l, "b.example", "f1", "kundennr").map { it.value })
        assertEquals(emptyList<String>(), AcSiteMemory.suggestions(l, "c.example", "f1", "kundennr").map { it.value })
        assertEquals("a blank field key offers nothing", emptyList<AcMemoryEntry>(), AcSiteMemory.suggestions(l, "a.example", "f1", ""))
        // most used first within the same form
        val m = listOf(e("a.example", "f", "k", "rare", uses = 1), e("a.example", "f", "k", "often", uses = 5))
        assertEquals(listOf("often", "rare"), AcSiteMemory.suggestions(m, "a.example", "f", "k").map { it.value })
    }

    @Test
    fun `manage list per site, the site index and the json round trip`() {
        val l = listOf(e("a.example", "f", "b", "1"), e("a.example", "f", "a", "2"), e("b.example", "f", "a", "3"))
        assertEquals(listOf("a", "b"), AcSiteMemory.forSite(l, "a.example").map { it.fieldKey })
        assertEquals(mapOf("a.example" to 2, "b.example" to 1), AcSiteMemory.sites(l))
        assertEquals(l, AcSiteMemory.fromJson(AcSiteMemory.toJson(l)))
        assertEquals("an entry without a value or origin is dropped on load", 0, AcSiteMemory.fromJson(org.json.JSONArray("""[{"origin":"a"},{"value":"x"}]""")).size)
    }
}
