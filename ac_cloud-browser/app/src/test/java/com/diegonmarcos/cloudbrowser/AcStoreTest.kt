package com.diegonmarcos.cloudbrowser

import com.diegonmarcos.superapp.autofill.AutofillAddress
import com.diegonmarcos.superapp.autofill.AutofillContact
import com.diegonmarcos.superapp.autofill.AutofillProfile
import com.diegonmarcos.superapp.autofill.ContactKind
import com.diegonmarcos.superapp.browser.AcCard
import com.diegonmarcos.superapp.browser.AcKeyProvider
import com.diegonmarcos.superapp.browser.AcLogin
import com.diegonmarcos.superapp.browser.AcMemoryEntry
import com.diegonmarcos.superapp.browser.AcMemoryKv
import com.diegonmarcos.superapp.browser.AcName
import com.diegonmarcos.superapp.browser.AcProfile
import com.diegonmarcos.superapp.browser.AcSealedBox
import com.diegonmarcos.superapp.browser.AcSource
import com.diegonmarcos.superapp.browser.AcStore
import com.diegonmarcos.superapp.browser.AcVaultItem
import com.diegonmarcos.superapp.browser.CardMeta
import com.diegonmarcos.superapp.browser.BrowserProfile
import com.diegonmarcos.superapp.browser.ProfileAddress
import com.diegonmarcos.superapp.browser.ProfileIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #900 the store: schema migration that never wipes, the legacy and Cloud Account imports, the sealed secrets, vault-wins through the store. */
class AcStoreTest {

    private val box = AcSealedBox(AcKeyProvider.fixed(ByteArray(32) { 7 }))
    private var now = 1000L
    private fun store(p: AcMemoryKv = AcMemoryKv(), s: AcMemoryKv = AcMemoryKv(), b: AcSealedBox? = box) = AcStore(p, s, b) { now++ }

    @Test
    fun `migrate stamps the schema, is idempotent, and keeps what was already there`() {
        val p = AcMemoryKv(); val s = AcMemoryKv()
        p.putString("profiles", """[{"id":"x","label":"Before the schema","names":[{"given":"Ana"}]}]""")
        p.putString("some_future_key", "kept")
        val st = store(p, s)
        st.migrate(); st.migrate()
        assertEquals(AcStore.SCHEMA, st.schemaOf(p)); assertEquals(AcStore.SCHEMA, st.schemaOf(s))
        assertEquals("Before the schema", st.profiles().single().label)
        assertEquals("kept", p.getString("some_future_key"))
    }

    @Test
    fun `a newer schema is left as it is, never walked backwards or wiped`() {
        val p = AcMemoryKv(); p.putString("schema", "9"); p.putString("profiles", """[{"id":"y","label":"From the future"}]""")
        val st = store(p); st.migrate()
        assertEquals(9, st.schemaOf(p)); assertEquals("From the future", st.profiles().single().label)
    }

    @Test
    fun `unreadable json is backed up before anything is written over it`() {
        val p = AcMemoryKv(); p.putString("profiles", "{not json")
        val st = store(p)
        assertTrue(st.profiles().isEmpty())
        assertEquals("{not json", p.getString("bak_profiles"))
        st.upsertProfile(AcProfile(id = "n", label = "New"))
        assertEquals("the backup survives the new write", "{not json", p.getString("bak_profiles"))
        assertEquals("New", st.profiles().single().label)
    }

    @Test
    fun `the first profile becomes the default, a second does not, and default can move`() {
        val st = store()
        st.upsertProfile(AcProfile(id = "a", label = "A")); st.upsertProfile(AcProfile(id = "b", label = "B"))
        assertEquals(listOf("a"), st.profiles().filter { it.isDefault }.map { it.id })
        st.setDefaultProfile("b")
        assertEquals(listOf("b"), st.profiles().filter { it.isDefault }.map { it.id })
        st.deleteProfile("b")
        assertEquals("deleting the default leaves another default", listOf("a"), st.profiles().filter { it.isDefault }.map { it.id })
    }

    @Test
    fun `the old browser profile is imported once into the new model and the old data is not touched`() {
        val legacy = BrowserProfile(
            identity = ProfileIdentity(first = "Ana", last = "Garcia", email = "ana@example.test", phone = "+34 600", company = "Example Org"),
            addresses = listOf(ProfileAddress(label = "Home", street = "Calle Falsa", number = "1", city = "Madrid", zip = "28001", country = "ES", phone = "+34 600")),
            cards = listOf(CardMeta("Visa", "Ana", "4242", "01", "2030", "visa")),
        )
        val st = store(); st.upsertProfile(AcProfile(id = "mine", label = "Mine"))
        val added = st.importLegacyOnce(legacy)
        assertNotNull(added)
        val all = st.profiles()
        assertEquals("the existing profile stays", 2, all.size); assertEquals("mine", all.first { it.isDefault }.id)
        val p = all.first { it.source == "legacy" }
        assertEquals("Ana Garcia", p.names.single().fullName()); assertEquals("Calle Falsa", p.addresses.single().street)
        assertEquals(listOf("+34 600"), p.phones.map { it.value }); assertEquals(listOf("ana@example.test"), p.emails.map { it.value })
        assertEquals(listOf("Example Org"), p.organisations)
        assertNull("a second import adds nothing", st.importLegacyOnce(legacy)); assertEquals(2, st.profiles().size)
        assertNull("an empty legacy profile imports nothing and does not use up the once", store().importLegacyOnce(BrowserProfile()))
    }

    @Test
    fun `a Cloud Account profile imports with its addresses and contacts, once per label`() {
        val acc = AutofillProfile(id = 4, label = "Spain-A", isDefault = true,
            fields = mapOf("given_name" to "Ana", "family_name" to "Garcia", "family_name2" to "Lopez", "organization" to "Example Org", "bday" to "1990-02-03"),
            addresses = listOf(AutofillAddress(label = "Home", isDefault = true, fields = mapOf("street" to "Calle Falsa", "house_number" to "1", "city" to "Madrid", "postal_code" to "28001", "country" to "ES"))),
            contacts = listOf(AutofillContact(kind = ContactKind.EMAIL, value = "ana@example.test", isDefault = true), AutofillContact(kind = ContactKind.TEL, value = "+34 600")))
        val st = store()
        assertEquals(1, st.importAccount(listOf(acc, AutofillProfile(label = "Empty"))))
        val p = st.profiles().single()
        assertEquals("Spain-A", p.label); assertEquals("account", p.source)
        assertEquals("Garcia Lopez", p.names.single().family); assertEquals("1990-02-03", p.dates.single().value)
        assertEquals("Madrid", p.addresses.single().city); assertEquals(listOf("+34 600"), p.phones.map { it.value })
        assertEquals("again: nothing new", 0, st.importAccount(listOf(acc)))
    }

    @Test
    fun `logins and cards are saved sealed, found by site, and survive a reopen`() {
        val p = AcMemoryKv(); val s = AcMemoryKv()
        var st = store(p, s)
        st.saveLogin(AcLogin("l1", "example.com", "ana", "pw-one")); st.saveLogin(AcLogin("l2", "other.org", "ana", "pw-two"))
        st.saveCard(AcCard("c1", "Mine", "Ana", "4111111111111111", "01", "2030", "123"))
        assertFalse("nothing secret is stored in clear", (s.getString("logins").orEmpty() + s.getString("cards").orEmpty()).let { it.contains("pw-one") || it.contains("pw-two") || it.contains("4111111111111111") || it.contains("123\"") })
        st = store(p, s)
        assertEquals(listOf("l1"), st.loginsFor("https://login.example.com/x").map { it.id })
        assertEquals("pw-one", st.logins().first.first { it.id == "l1" }.password)
        assertEquals("4111111111111111", st.cards().first.single().number)
        st.saveLogin(AcLogin("l1", "example.com", "ana", "pw-changed"))
        assertEquals(2, st.logins().first.size); assertEquals("pw-changed", st.logins().first.first { it.id == "l1" }.password)
        st.deleteLogin("l2"); assertEquals(listOf("l1"), st.logins().first.map { it.id })
    }

    @Test
    fun `without a Keystore key nothing secret is shown or saved, and nothing is deleted`() {
        val s = AcMemoryKv()
        store(AcMemoryKv(), s).saveLogin(AcLogin("l1", "example.com", "ana", "pw"))
        val blob = s.getString("logins")
        val locked = store(AcMemoryKv(), s, b = null)
        assertFalse(locked.secretsAvailable); assertTrue(locked.logins().first.isEmpty()); assertTrue(locked.cards().first.isEmpty())
        assertEquals("the stored secrets are still there", blob, s.getString("logins"))
    }

    @Test
    fun `a login the key cannot open stays on disk when others are saved or deleted`() {
        val s = AcMemoryKv()
        store(AcMemoryKv(), s, AcSealedBox(AcKeyProvider.fixed(ByteArray(32) { 1 }))).saveLogin(AcLogin("old", "example.com", "ana", "pw-old"))
        val st = store(AcMemoryKv(), s)
        assertEquals(1, st.logins().second); assertTrue(st.logins().first.isEmpty())
        st.saveLogin(AcLogin("new", "example.com", "bob", "pw-new"))
        assertTrue("the unreadable record was carried over", s.getString("logins")!!.contains("\"old\""))
        st.deleteLogin("new")
        assertTrue(s.getString("logins")!!.contains("\"old\""))
    }

    @Test
    fun `vault wins through the store - the local password is replaced and marked vault`() {
        val st = store()
        st.saveLogin(AcLogin("l1", "example.com", "ana", "local-pw"))
        val m = st.mergeVaultLogin(AcVaultItem.Login("https://example.com/login", "ana", "vault-pw"))
        assertTrue(m.changed)
        val l = st.logins().first.single()
        assertEquals("vault-pw", l.password); assertEquals(AcSource.VAULT, l.source); assertEquals("l1", l.id)
        val c = st.mergeVaultCard(AcVaultItem.Card("4111111111111111", "Ana", "05", "2031"))
        assertTrue(c.created); assertEquals(AcSource.VAULT, st.cards().first.single().source)
    }

    @Test
    fun `per-site memory, never-save lists and the last profile used per site`() {
        val st = store()
        st.remember(AcMemoryEntry(origin = "a.example", formSig = "f", fieldKey = "kundennr", value = "K-1"))
        st.remember(AcMemoryEntry(origin = "a.example", formSig = "f", fieldKey = "kundennr", value = "K-1"))
        assertEquals(1, st.memory().size); assertEquals(2, st.memory().single().uses)
        st.remember(AcMemoryEntry(origin = "b.example", formSig = "f", fieldKey = "kundennr", value = "K-2"))
        st.forgetSite("a.example"); assertEquals(listOf("b.example"), st.memory().map { it.origin })
        st.clearMemory(); assertTrue(st.memory().isEmpty())
        st.setNeverSave("password", "example.com", true); st.setNeverSave("password", "other.org", true); st.setNeverSave("password", "other.org", false)
        assertEquals(setOf("example.com"), st.neverSaveOn("password"))
        st.setLastProfileFor("example.com", "p1"); assertEquals("p1", st.lastProfileFor("example.com")); assertNull(st.lastProfileFor("nope.org"))
        assertEquals(AcName().isEmpty, true)
    }
}
