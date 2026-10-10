package com.diegonmarcos.cloudaccount

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.cloudaccount.autofill.AutofillImport
import com.diegonmarcos.cloudaccount.autofill.AutofillSotProvider
import com.diegonmarcos.cloudaccount.autofill.AutofillStore
import com.diegonmarcos.cloudaccount.autofill.profileSummary
import com.diegonmarcos.cloudaccount.autofill.ruleSummary
import com.diegonmarcos.superapp.autofill.AddressType
import com.diegonmarcos.superapp.autofill.AutofillAddress
import com.diegonmarcos.superapp.autofill.AutofillContact
import com.diegonmarcos.superapp.autofill.AutofillProfile
import com.diegonmarcos.superapp.autofill.AutofillRows
import com.diegonmarcos.superapp.autofill.AutofillSot
import com.diegonmarcos.superapp.autofill.ContactKind
import com.diegonmarcos.superapp.autofill.Fields
import com.diegonmarcos.superapp.autofill.SiteRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * The non-secret autofill Source of Truth (a0_docs/eng-specs/autofill-3-tier.md) on the real provider
 * under Robolectric: CRUD through the ContentResolver (profiles with typed addresses and contacts, rules,
 * snippets), the read/write permissions enforced in code (not only by the manifest), owner-only
 * update/delete, columns a writer may not add, cascade delete, and the manifest declarations that make
 * the permissions signature-level. Plus Import: text and JSON → reviewable profiles, IDs kept apart.
 * Every fixture is an obvious fake.
 */
@RunWith(RobolectricTestRunner::class)
class AutofillSotProviderTest {
    private lateinit var ctx: Context
    private val read = AutofillSot.PERMISSION_READ
    private val write = AutofillSot.PERMISSION_WRITE

    @Before fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        ctx.getSharedPreferences(AutofillStore.FILE, Context.MODE_PRIVATE).edit().clear().commit()
        Robolectric.buildContentProvider(AutofillSotProvider::class.java).create(AutofillSot.AUTHORITY).get()
        grant(read, write); owner(true)
    }

    @After fun tearDown() { grant(read, write); owner(true) }

    private fun grant(vararg perms: String) { AutofillSotProvider.access = { _, p -> p in perms } }
    private fun owner(yes: Boolean) { AutofillSotProvider.isOwner = { yes } }
    private fun u(path: String): Uri = AutofillSot.uri(path)

    private fun spain() = AutofillProfile(label = "Testy - Spain-A", isDefault = true,
        fields = mapOf("given_name" to "Testy", "family_name" to "Fakeson", "family_name2" to "Example", "nationality" to "ES"),
        addresses = listOf(AutofillAddress(type = AddressType.HOME, isDefault = true, fields = mapOf("street" to "Calle Falsa", "house_number" to "1",
            "floor_door" to "P04 0001", "postal_code" to "00000", "city" to "Sampletown", "country" to "ES"))),
        contacts = listOf(AutofillContact(kind = ContactKind.EMAIL, value = "testy@example.invalid", isDefault = true),
            AutofillContact(kind = ContactKind.TEL, value = "+00 600 000 000", type = "mobile", country = "ES")))

    private fun insert(path: String, m: Map<String, String>): Uri? = ctx.contentResolver.insert(u(path), AutofillRows.values(m))
    private fun rows(path: String) = ctx.contentResolver.query(u(path), null, null, null, null)!!.use { AutofillRows.rows(it) }
    private fun profiles() = AutofillRows.assemble(rows(AutofillSot.PATH_PROFILES), rows(AutofillSot.PATH_ADDRESSES), rows(AutofillSot.PATH_CONTACTS))

    private fun expectSecurity(what: String, block: () -> Unit) {
        try { block(); fail("$what: expected SecurityException") } catch (e: SecurityException) { /* refused */ }
    }

    // ── CRUD ─────────────────────────────────────────────────────────────

    @Test fun profile_with_addresses_and_contacts_round_trips_through_the_resolver() {
        val pid = AutofillStore(ctx).save(spain())!!
        val p = profiles().single()
        assertEquals(pid, p.id)
        assertEquals("Fakeson Example", p.familyName)
        assertEquals("Testy Fakeson Example", p.fullName)
        assertEquals(1, p.addresses.size)
        assertEquals("Calle Falsa, 1", com.diegonmarcos.superapp.autofill.FillTarget(p, p.addresses[0]).value("address-line1"))
        assertEquals(listOf("testy@example.invalid"), p.contacts(ContactKind.EMAIL).map { it.value })
        // update one address field through the resolver
        val aid = p.addresses[0].id
        assertEquals(1, ctx.contentResolver.update(AutofillSot.uri(AutofillSot.PATH_ADDRESSES, aid), ContentValues().apply { put("city", "Othertown") }, null, null))
        assertEquals("Othertown", profiles().single().addresses[0]["city"])
        // deleting the profile deletes its addresses and contacts
        assertEquals(1, ctx.contentResolver.delete(AutofillSot.uri(AutofillSot.PATH_PROFILES, pid), null, null))
        assertTrue(rows(AutofillSot.PATH_PROFILES).isEmpty())
        assertTrue(rows(AutofillSot.PATH_ADDRESSES).isEmpty())
        assertTrue(rows(AutofillSot.PATH_CONTACTS).isEmpty())
    }

    @Test fun children_need_an_existing_profile() {
        assertNull(insert(AutofillSot.PATH_ADDRESSES, AutofillRows.columns(AutofillAddress(fields = mapOf("city" to "X")), profileId = 99)))
        assertNull(insert(AutofillSot.PATH_CONTACTS, AutofillRows.columns(AutofillContact(kind = ContactKind.EMAIL, value = "x@example.invalid"), profileId = 99)))
        val pid = AutofillStore(ctx).save(spain())!!
        assertNull("unknown contact kind", insert(AutofillSot.PATH_CONTACTS, mapOf(AutofillSot.PROFILE_ID to "$pid", AutofillSot.C_KIND to "password", AutofillSot.C_VALUE to "x")))
    }

    @Test fun a_writer_cannot_add_a_column_the_contract_does_not_name() {
        val uri = insert(AutofillSot.PATH_PROFILES, AutofillRows.columns(spain()) + mapOf("password" to "pw-zz", "dni" to "00000000X", "cc_number" to "0000"))!!
        val stored = AutofillStore(ctx).row(AutofillSot.PATH_PROFILES, uri.lastPathSegment!!.toLong())!!
        assertFalse(stored.keys.any { it in setOf("password", "dni", "cc_number") })
        assertFalse(stored.values.any { it.contains("pw-zz") || it.contains("00000000X") })
    }

    @Test fun one_default_profile_and_one_default_address_per_profile() {
        val store = AutofillStore(ctx)
        store.save(spain()); store.save(spain().copy(label = "Testy - Germany-A"))
        assertEquals(listOf("Testy - Germany-A"), profiles().filter { it.isDefault }.map { it.label })
        val p = profiles().first { it.label == "Testy - Germany-A" }
        store.save(p.copy(addresses = p.addresses + AutofillAddress(type = AddressType.POSTAL, isDefault = true, fields = mapOf("city" to "Musterstadt", "country" to "DE"))))
        val again = profiles().first { it.label == "Testy - Germany-A" }
        assertEquals(1, again.addresses.count { it.isDefault })
        assertEquals(AddressType.POSTAL, again.addresses.single { it.isDefault }.type)
        assertEquals("the other profile's default address is untouched", 1, profiles().first { it.label == "Testy - Spain-A" }.addresses.count { it.isDefault })
    }

    @Test fun editor_save_removes_children_the_user_removed() {
        val store = AutofillStore(ctx)
        val pid = store.save(spain())!!
        val p = profiles().single()
        store.save(p.copy(contacts = p.contacts.filter { it.kind == ContactKind.TEL }))
        assertEquals(listOf(ContactKind.TEL), profiles().single().contacts.map { it.kind })
        assertEquals(pid, profiles().single().id)
    }

    @Test fun rules_must_be_valid_and_are_normalised() {
        assertNull("no field selector", insert(AutofillSot.PATH_RULES, AutofillRows.columns(SiteRule(domain = "shop.example", fieldKey = "postal-code"))))
        assertNull("unknown key", insert(AutofillSot.PATH_RULES, AutofillRows.columns(SiteRule(domain = "shop.example", fieldSelector = "#z", fieldKey = "password"))))
        assertNotNull(insert(AutofillSot.PATH_RULES, AutofillRows.columns(SiteRule(domain = "https://Shop.Example/checkout", fieldSelector = "#plz", fieldKey = "postal-code"))))
        assertNotNull("default profile for a TLD", insert(AutofillSot.PATH_RULES, AutofillRows.columns(SiteRule(domain = "de", fieldKey = Fields.PROFILE, literalValue = "Testy - Germany-A"))))
        val rules = rows(AutofillSot.PATH_RULES).map(AutofillRows::rule)
        val r = rules.first { it.fieldKey == "postal-code" }
        assertEquals("shop.example", r.domain)
        assertTrue(r.appliesTo("www.shop.example") && r.appliesTo("checkout.shop.example") && !r.appliesTo("notshop.example"))
        assertEquals("any form › #plz → postal-code", ruleSummary(r))
        val tld = rules.first { it.isProfileRule }
        assertTrue(tld.appliesTo("www.shop.de") && !tld.appliesTo("shop.example"))
        assertEquals("default profile → Testy - Germany-A", ruleSummary(tld))
    }

    @Test fun projection_and_unknown_uris() {
        AutofillStore(ctx).save(spain())
        ctx.contentResolver.query(u(AutofillSot.PATH_PROFILES), arrayOf("label", "nope"), null, null, null)!!.use { c ->
            assertEquals(listOf("label"), c.columnNames.toList())
        }
        assertNull(ctx.contentResolver.query(Uri.parse("content://${AutofillSot.AUTHORITY}/secrets"), null, null, null, null))
        assertNull(ctx.contentResolver.query(Uri.parse("content://${AutofillSot.AUTHORITY}/ids"), null, null, null, null))
        assertEquals("vnd.android.cursor.dir/vnd.${AutofillSot.AUTHORITY}.addresses", ctx.contentResolver.getType(u(AutofillSot.PATH_ADDRESSES)))
    }

    // ── permission enforcement ───────────────────────────────────────────

    @Test fun no_permission_reads_nothing_and_writes_nothing() {
        AutofillStore(ctx).save(spain())
        grant()
        for (path in AutofillSot.PATHS) expectSecurity("query $path") { ctx.contentResolver.query(u(path), null, null, null, null) }
        expectSecurity("insert") { insert(AutofillSot.PATH_PROFILES, AutofillRows.columns(spain())) }
        expectSecurity("call") { ctx.contentResolver.call(u(AutofillSot.PATH_PROFILES), AutofillSot.METHOD_VERSION, null, null) }
    }

    @Test fun read_permission_reads_but_cannot_write() {
        AutofillStore(ctx).save(spain())
        grant(read); owner(false)
        assertEquals(1, profiles().size)
        assertEquals(AutofillSot.VERSION, ctx.contentResolver.call(u(AutofillSot.PATH_PROFILES), AutofillSot.METHOD_VERSION, null, null)!!.getInt("version"))
        expectSecurity("insert") { insert(AutofillSot.PATH_PROFILES, AutofillRows.columns(spain())) }
        expectSecurity("insert address") { insert(AutofillSot.PATH_ADDRESSES, AutofillRows.columns(AutofillAddress(fields = mapOf("city" to "X")), 1)) }
    }

    @Test fun a_foreign_writer_may_insert_but_never_update_or_delete() {
        val pid = AutofillStore(ctx).save(spain())!!
        grant(read, write); owner(false)
        assertNotNull(insert(AutofillSot.PATH_ADDRESSES, AutofillRows.columns(AutofillAddress(type = AddressType.POSTAL, fields = mapOf("city" to "Saved from browser")), pid)))
        val p = AutofillSot.uri(AutofillSot.PATH_PROFILES, pid)
        expectSecurity("update") { ctx.contentResolver.update(p, ContentValues().apply { put("label", "X") }, null, null) }
        expectSecurity("delete") { ctx.contentResolver.delete(p, null, null) }
        owner(true)
        assertEquals(2, profiles().single().addresses.size)
    }

    // ── manifest: signature permissions, provider guarded ────────────────

    @Test fun manifests_declare_signature_permissions_and_guard_the_provider() {
        val app = File("src/main/AndroidManifest.xml").readText()
        val lib = File("../../ab_cloud-libs-shared/libs/autofill/src/main/AndroidManifest.xml").readText()
        for (perm in listOf(read, write)) {
            val decl = Regex("<permission\\s+android:name=\"${Regex.escape(perm)}\"[^>]*>").find(lib)?.value.orEmpty()
            assertTrue("$perm defined", decl.isNotEmpty())
            assertTrue("$perm is signature", decl.contains("android:protectionLevel=\"signature\""))
        }
        val prov = Regex("<provider\\s+android:name=\"\\.autofill\\.AutofillSotProvider\"[^>]*>").find(app)?.value.orEmpty()
        assertTrue(prov.contains("android:authorities=\"${AutofillSot.AUTHORITY}\""))
        assertTrue(prov.contains("android:readPermission=\"$read\""))
        assertTrue(prov.contains("android:writePermission=\"$write\""))
        assertFalse("never CONSTELLATION_DATA-wide", prov.contains("CONSTELLATION_DATA"))
    }

    @Test fun summaries_name_no_street_or_number() {
        val s = profileSummary(spain())
        assertFalse(s.contains("Calle Falsa")); assertFalse(s.contains("600")); assertFalse(s.contains("testy@"))
        assertEquals("ES · 1 address · 1 phone(s) · 1 email(s)", s)
    }

    // ── Import ───────────────────────────────────────────────────────────

    @Test fun import_text_es_de_br_profiles_snippets_and_ids_apart() {
        val text = """
            Profile: Testy - Spain-A
            Nombre: Testy
            Primer apellido: Fakeson
            Segundo apellido: Example
            Fecha de nacimiento: 1990-01-01
            DNI (ES): 00000000X
            Soporte: AAA000000
            Móvil (mobile, ES): +00 600 000 000
            testy@example.invalid
            Domicilio:
            Calle: Calle Falsa
            Número: 1
            Piso: P04 0001
            CP: 00000
            Ciudad: Sampletown
            País: es

            Profil: Testy - Germany-A
            Vorname: Testy
            Nachname: Fakeson
            Postanschrift:
            c/o: Erika Example
            Straße: Musterstraße
            Hausnummer: 1
            PLZ: 00000
            Ort: Musterstadt
            Land: DE
            Personalausweis (DE): X0000000
            Gültig bis: 2030-01-01
            Postfiliale: Packstation 000
            PLZ: 00000
            Ort: Musterstadt

            Profile: Testy - Brazil
            Nome: Testy
            Sobrenome: Fakeson
            Endereço:
            Rua: Rua Exemplo
            Número: 100
            Complemento: apto 00
            Bairro: Centro Falso
            CEP: 00000-000
            Cidade: Cidade Exemplo
            Estado: XX
            País: BR
            CPF: 000.000.000-00

            Snippet: About me
            Line one 🙂
            Line two
            ---
        """.trimIndent()
        val r = AutofillImport.parse(text)
        assertEquals(listOf("Testy - Spain-A", "Testy - Germany-A", "Testy - Brazil"), r.profiles.map { it.label })
        val es = r.profiles[0]
        assertEquals("Fakeson Example", es.familyName)
        assertEquals("ES", es.addresses.single()["country"])
        assertEquals(AddressType.HOME, es.addresses.single().type)
        assertEquals("P04 0001", es.addresses.single()["floor_door"])
        assertEquals("mobile", es.contacts(ContactKind.TEL).single().type)
        assertEquals("testy@example.invalid", es.contacts(ContactKind.EMAIL).single().value)
        val de = r.profiles[1]
        assertEquals(listOf(AddressType.POSTAL, AddressType.POST_OFFICE), de.addresses.map { it.type })
        assertEquals("Erika Example", de.addresses[0]["co_line"])
        val br = r.profiles[2]
        assertEquals("Centro Falso", br.addresses.single()["neighborhood"])
        assertEquals(listOf("About me"), r.snippets.map { it.label })
        assertEquals("Line one 🙂\nLine two", r.snippets.single().text)
        // IDs never become SOT data
        assertEquals(listOf("DNI", "Personalausweis", "CPF"), r.ids.map { it.type })
        assertEquals("AAA000000", r.ids[0].details["support"]); assertEquals("ES", r.ids[0].country)
        assertEquals("2030-01-01", r.ids[1].details["valid_until"])
        val all = r.profiles.flatMap { p -> p.fields.values + p.addresses.flatMap { it.fields.values } + p.contacts.map { it.value } }
        assertFalse(all.any { it.contains("00000000X") || it.contains("X0000000") || it.contains("000.000.000-00") })
        assertEquals("••••••00X", AutofillImport.mask("00000000X"))
    }

    @Test fun import_json_and_save() {
        val r = AutofillImport.parse(AutofillImport.EXAMPLE_JSON)
        assertEquals("Testy - Germany-A", r.profiles.single().label)
        assertEquals(1, r.ids.size)
        assertEquals("Line one\nLine two", r.snippets.single().text)
        val store = AutofillStore(ctx)
        r.profiles.forEach { store.save(it) }; r.snippets.forEach { store.save(it) }
        val p = profiles().single()
        assertEquals("Musterstraße 1", com.diegonmarcos.superapp.autofill.FillTarget(p, p.addresses.single()).value("address-line1"))
        assertEquals(1, rows(AutofillSot.PATH_SNIPPETS).size)
    }
}
