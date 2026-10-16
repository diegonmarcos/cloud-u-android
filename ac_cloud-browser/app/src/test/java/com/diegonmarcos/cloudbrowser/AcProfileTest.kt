package com.diegonmarcos.cloudbrowser

import com.diegonmarcos.superapp.browser.AcAddress
import com.diegonmarcos.superapp.browser.AcChoice
import com.diegonmarcos.superapp.browser.AcDate
import com.diegonmarcos.superapp.browser.AcDocType
import com.diegonmarcos.superapp.browser.AcDocument
import com.diegonmarcos.superapp.browser.AcEmail
import com.diegonmarcos.superapp.browser.AcField
import com.diegonmarcos.superapp.browser.AcFill
import com.diegonmarcos.superapp.browser.AcName
import com.diegonmarcos.superapp.browser.AcPhone
import com.diegonmarcos.superapp.browser.AcProfile
import com.diegonmarcos.superapp.browser.AcProfiles
import com.diegonmarcos.superapp.browser.AcTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #900 the personal-profile model: many-valued fields, defaults, and which value a fill picks per kind. */
class AcProfileTest {

    /** The owner's case: two names, three addresses, two citizenships' documents (fixtures are obvious fakes). */
    private val es = AcProfile(
        id = "es", label = "Test Person - ES", isDefault = true,
        names = listOf(AcName("n1", given = "Ana", family = "Garcia Lopez", isDefault = true), AcName("n2", given = "Anita", family = "Garcia", alias = "Ani")),
        addresses = listOf(
            AcAddress("a1", "home", "Calle Falsa", "123", "2B", "Madrid", "28001", "Madrid", "ES", true),
            AcAddress("a2", "work", "Hauptstrasse", "5", "", "Berlin", "10115", "Berlin", "DE"),
            AcAddress("a3", "family", "Rua Teste", "77", "", "Sao Paulo", "01000-000", "SP", "BR"),
        ),
        phones = listOf(AcPhone("p1", "mobile", "+34 600 000 000", true), AcPhone("p2", "work", "+49 30 000000")),
        emails = listOf(AcEmail("e1", "personal", "ana@example.test", true), AcEmail("e2", "work", "ana@work.example.test")),
        documents = listOf(
            AcDocument("d1", AcDocType.NATIONAL_ID, "00000000T", "ES", "ES", "2030-01-01", true),
            AcDocument("d2", AcDocType.PASSPORT, "XA0000001", "ES", "ES"),
            AcDocument("d3", AcDocType.PASSPORT, "YB0000002", "BR", "BR"),
        ),
        dates = listOf(AcDate("b1", "birth", "1990-02-03", true)),
        organisations = listOf("Example Org", "Other Org"),
    )
    private val br = AcProfile(id = "br", label = "Test Person - BR", names = listOf(AcName("n9", given = "Ana", family = "Silva")),
        addresses = listOf(AcAddress("a9", "home", "Rua Teste", "1", "", "Recife", "50000-000", "PE", "BR")))

    private fun form(vararg tokens: String) = tokens.mapIndexed { i, t -> AcField(i, t) }

    @Test
    fun `a profile keeps many values of each kind and exactly one default`() {
        assertEquals(2, es.names.size); assertEquals(3, es.addresses.size); assertEquals(3, es.documents.size)
        val messy = es.copy(names = es.names.map { it.copy(isDefault = true) }, phones = es.phones.map { it.copy(isDefault = false) })
        val n = messy.normalized()
        assertEquals("one default name even when two are marked", 1, n.names.count { it.isDefault })
        assertEquals("the first marked wins", "n1", n.names.first { it.isDefault }.id)
        assertEquals("a list with none marked gets its first", "p1", n.phones.first { it.isDefault }.id)
        val all = AcProfiles.normalize(listOf(es.copy(isDefault = false), br.copy(isDefault = false)))
        assertEquals("exactly one default profile", 1, all.count { it.isDefault })
        assertEquals("es", AcProfiles.default(all)!!.id)
    }

    @Test
    fun `json round trip keeps every record and tolerates a missing newer field`() {
        val back = AcProfiles.fromJson(AcProfiles.toJson(listOf(es, br)))
        assertEquals(listOf(es, br), back)
        val old = org.json.JSONArray("""[{"id":"x","label":"Old","names":[{"given":"Ana"}]}]""")
        val p = AcProfiles.fromJson(old).single()
        assertEquals("Old", p.label); assertEquals("Ana", p.names.single().given)
        assertTrue("records without an id get one", p.names.single().id.isNotBlank())
        assertTrue(p.dates.isEmpty() && p.documents.isEmpty())
    }

    @Test
    fun `the default choice picks the default profile and its default record of each kind`() {
        val tokens = listOf(AcTokens.GIVEN, AcTokens.FAMILY, AcTokens.LINE1, AcTokens.POSTAL, AcTokens.LEVEL2, AcTokens.EMAIL, AcTokens.TEL)
        val c = AcFill.defaultChoice(listOf(br, es), tokens)!!
        assertEquals("es", c.profileId)
        assertEquals(mapOf(AcTokens.G_NAME to "n1", AcTokens.G_ADDRESS to "a1", AcTokens.G_EMAIL to "e1", AcTokens.G_PHONE to "p1"), c.records)
        val v = AcFill.values(listOf(es), form(*tokens.toTypedArray()), c)
        assertEquals("Ana", v[0]); assertEquals("Garcia Lopez", v[1]); assertEquals("Calle Falsa, 123", v[2])
        assertEquals("28001", v[3]); assertEquals("Madrid", v[4]); assertEquals("ana@example.test", v[5]); assertEquals("+34 600 000 000", v[6])
    }

    @Test
    fun `the chooser asks which profile, and which value only for kinds with several`() {
        val tokens = listOf(AcTokens.GIVEN, AcTokens.LINE1, AcTokens.POSTAL, AcTokens.BDAY)
        val plan = AcFill.plan(listOf(es, br), tokens)
        assertEquals(listOf("es", "br"), plan.profiles.map { it.id })
        assertEquals("es has two names and three addresses; one birth date is not asked", listOf(AcTokens.G_NAME, AcTokens.G_ADDRESS), plan.groups.getValue("es").map { it.group })
        assertTrue("br has one of each: nothing to ask", plan.groups.getValue("br").isEmpty())
        assertFalse(plan.isTrivial())
        assertTrue(AcFill.plan(listOf(br), listOf(AcTokens.GIVEN, AcTokens.LINE1)).isTrivial())
        // the default value of each group is listed first
        assertEquals("a1", plan.groups.getValue("es").first { it.group == AcTokens.G_ADDRESS }.options.first().recordId)
    }

    @Test
    fun `choosing another address fills every address field from that same address`() {
        val tokens = listOf(AcTokens.LINE1, AcTokens.POSTAL, AcTokens.LEVEL2, AcTokens.COUNTRY, AcTokens.LEVEL1)
        val c = AcFill.with(AcFill.defaultChoice(listOf(es), tokens)!!, AcTokens.G_ADDRESS, "a2")
        val v = AcFill.values(listOf(es), form(*tokens.toTypedArray()), c)
        assertEquals(listOf("Hauptstrasse 5", "10115", "Berlin", "DE", "Berlin"), (0..4).map { v[it] })
        // Spanish and Brazilian addresses put the number after the street with a comma (AddressFormat); German without
        val br3 = AcFill.with(c, AcTokens.G_ADDRESS, "a3")
        assertEquals("Rua Teste, 77", AcFill.values(listOf(es), form(AcTokens.LINE1), br3)[0])
    }

    @Test
    fun `split street forms get the street and the number apart, and a street-address is the whole block`() {
        val c = AcFill.defaultChoice(listOf(es), listOf(AcTokens.X_STREET))!!
        assertEquals("Calle Falsa", AcFill.valueFor(es, AcTokens.X_STREET, c))
        assertEquals("123", AcFill.valueFor(es, AcTokens.X_NUMBER, c))
        assertEquals("Calle Falsa, 123\n2B", AcFill.valueFor(es, AcTokens.STREET_ADDRESS, c))
        assertEquals("España", AcFill.valueFor(es, AcTokens.COUNTRY_NAME, c))
    }

    @Test
    fun `names - the second name fills given, family and the full name from itself`() {
        val c = AcFill.with(AcFill.defaultChoice(listOf(es), listOf(AcTokens.GIVEN))!!, AcTokens.G_NAME, "n2")
        assertEquals("Anita", AcFill.valueFor(es, AcTokens.GIVEN, c))
        assertEquals("Garcia", AcFill.valueFor(es, AcTokens.FAMILY, c))
        assertEquals("Anita Garcia", AcFill.valueFor(es, AcTokens.NAME, c))
        assertEquals("Ani", AcFill.valueFor(es, AcTokens.NICKNAME, c))
        // a full name only: given and family are split from it
        val only = AcProfile(id = "f", names = listOf(AcName("x", full = "Maria Souza Lima")))
        val oc = AcFill.defaultChoice(listOf(only), listOf(AcTokens.GIVEN))!!
        assertEquals("Maria", AcFill.valueFor(only, AcTokens.GIVEN, oc)); assertEquals("Souza Lima", AcFill.valueFor(only, AcTokens.FAMILY, oc))
    }

    @Test
    fun `documents - a typed field draws from documents of that type, and the issuing country follows the chosen one`() {
        val tokens = listOf(AcTokens.X_PASSPORT, AcTokens.X_ISSUING, AcTokens.X_CITIZENSHIP)
        val plan = AcFill.plan(listOf(es), tokens)
        assertEquals("two passports: the chooser asks which", listOf("doc:passport"), plan.groups.getValue("es").map { it.group })
        val c = AcFill.defaultChoice(listOf(es), tokens)!!
        val v = AcFill.values(listOf(es), form(*tokens.toTypedArray()), c)
        assertEquals("XA0000001", v[0]); assertEquals("ES", v[1]); assertEquals("ES", v[2])
        val other = AcFill.values(listOf(es), form(*tokens.toTypedArray()), AcFill.with(c, "doc:passport", "d3"))
        assertEquals("YB0000002", other[0]); assertEquals("BR", other[1]); assertEquals("BR", other[2])
        // a national-ID field never receives a passport number
        val id = AcFill.defaultChoice(listOf(es), listOf(AcTokens.X_NATIONAL_ID))!!
        assertEquals("00000000T", AcFill.valueFor(es, AcTokens.X_NATIONAL_ID, id))
    }

    @Test
    fun `birth date parts and the organisation`() {
        val c = AcFill.defaultChoice(listOf(es), listOf(AcTokens.BDAY, AcTokens.ORGANIZATION))!!
        assertEquals("1990-02-03", AcFill.valueFor(es, AcTokens.BDAY, c))
        assertEquals(listOf("03", "02", "1990"), listOf(AcTokens.BDAY_DAY, AcTokens.BDAY_MONTH, AcTokens.BDAY_YEAR).map { AcFill.valueFor(es, it, c) })
        assertEquals("Example Org", AcFill.valueFor(es, AcTokens.ORGANIZATION, c))
    }

    @Test
    fun `a secret token is never given a value, whatever asks`() {
        val c = AcChoice("es", emptyMap())
        val v = AcFill.values(listOf(es), listOf(AcField(0, AcTokens.CURRENT_PASSWORD), AcField(1, AcTokens.CC_NUMBER), AcField(2, AcTokens.OTP), AcField(3, AcTokens.EMAIL)), c)
        assertEquals(setOf(3), v.keys)
        assertNull(AcFill.valueFor(es, AcTokens.CC_NUMBER, c))
        assertFalse(AcTokens.isSecret(AcTokens.EMAIL)); assertTrue(AcTokens.isSecret(AcTokens.CC_CSC))
    }

    @Test
    fun `a profile that holds nothing for a form is not offered, and the last used profile is preselected`() {
        val tokens = listOf(AcTokens.GIVEN, AcTokens.LINE1)
        val onlyEmail = AcProfile(id = "m", emails = listOf(AcEmail("e", value = "x@example.test")))
        assertEquals(listOf("es", "br"), AcFill.usable(listOf(es, br, onlyEmail), tokens).map { it.id })
        assertEquals("br", AcFill.defaultChoice(listOf(es, br), tokens, preferred = "br")!!.profileId)
        assertNull(AcFill.defaultChoice(listOf(onlyEmail), tokens))
    }
}
