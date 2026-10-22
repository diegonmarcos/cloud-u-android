package com.diegonmarcos.cloudaccount

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.superapp.autofill.AutocompleteContract
import com.diegonmarcos.superapp.profile.AccountData
import com.diegonmarcos.superapp.profile.AccountHost
import com.diegonmarcos.superapp.profile.AccountStore
import com.diegonmarcos.superapp.profile.AutocompletePublisher
import com.diegonmarcos.superapp.profile.MeAutocompleteSync
import com.diegonmarcos.superapp.settings.AccountVault
import com.diegonmarcos.superapp.settings.ConfigsPrefs
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * #1103 Cloud Account to Cloud Keyboard: Account holds the owner's private data (synced from cloud-me-api) and serves the
 * Autocomplete rows over its accountdata provider, only to a package granted `autocomplete.read`. The keyboard reads the
 * answer with the contract of libs:autofill; this test holds the two halves together, on a real provider under Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
class AutocompleteSyncTest {
    private lateinit var ctx: Context
    private val keyboard = AccountVault.KEYBOARD_PACKAGE
    private val stranger = "com.example.stranger"
    private var caller = keyboard

    // made-up rows: obvious fakes, no real data
    private val meAnswer = """{"ids":[{"label":"National ID","value":"X0000000T"}],
        "addresses":[{"label":"Home","value":"1 Example Street, Sampletown"}],
        "urls":[{"label":"Site","value":"https://example.org"}],
        "secrets":[{"label":"Wi-Fi","value":"fake-passphrase"},{"label":"blank","value":""}],
        "passwords":[{"label":"dropped","value":"x"}]}"""
    private val decl = """{"services":[{"id":"cloud-me-api","kind":"data","mesh_dns":"cloud-me-api.app","mesh_port":8124,"mesh_scheme":"http","policy":"mesh_only","health":"/me/health"}]}"""

    @Before fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        ctx.getSharedPreferences("vault_test", Context.MODE_PRIVATE).edit().clear().commit()
        ConfigsPrefs.factory = { it.getSharedPreferences("vault_test", Context.MODE_PRIVATE) }
        val dir = File(ctx.filesDir, "account_test_ac").also { it.deleteRecursively() }
        AccountVault.storeSource = { AccountStore(dir, AccountStore.PlainIo) }
        AccountData.callerResolver = { caller }
        AccountHost.fleetEndpointsDecl = { decl }
    }

    @After fun tearDown() {
        ConfigsPrefs.factory = null
        MeAutocompleteSync.http = { url -> com.diegonmarcos.superapp.fleetendpoints.FleetHttp.call(
            com.diegonmarcos.superapp.fleetendpoints.FleetHttp.shared, "GET", url, emptyMap(), null) }
        AccountHost.fleetEndpointsDecl = { "" }
        AccountData.callerResolver = { c -> c.packageManager.getPackagesForUid(android.os.Binder.getCallingUid())?.firstOrNull() ?: c.packageName }
    }

    private fun call() = Robolectric.buildContentProvider(AccountData.Provider::class.java)
        .create(AccountData.AUTHORITY).get().call(AutocompleteContract.METHOD, null, null)

    private fun synced(): AccountVault {
        val v = AccountVault(ctx)
        MeAutocompleteSync.http = { meAnswer.let { b -> 200 to b } }
        assertEquals(MeAutocompleteSync.Outcome.Synced(4), MeAutocompleteSync.sync(ctx, v, decl))
        return v
    }

    @Test fun theTwoHalvesOfTheContractAreTheSameNames() {
        assertEquals(AutocompleteContract.METHOD, AutocompletePublisher.METHOD)
        assertEquals(AutocompleteContract.GRANT, AutocompletePublisher.GRANT)
        assertEquals(AutocompleteContract.KEY_OK, AutocompletePublisher.KEY_OK)
        assertEquals(AutocompleteContract.KEY_JSON, AutocompletePublisher.KEY_JSON)
        assertEquals(AutocompleteContract.PAGES, AutocompletePublisher.PAGES)
        assertEquals(AutocompleteContract.AUTHORITY, AccountData.AUTHORITY)
        assertEquals(AutocompleteContract.MAX_PER_PAGE, AutocompletePublisher.MAX_PER_PAGE)
    }

    @Test fun anUngrantedPackageGetsNothingAndTheKeyboardIsSeededTheGrant() {
        synced()
        caller = stranger
        assertFalse(call().getBoolean(AutocompleteContract.KEY_OK))
        val v = AccountVault(ctx)
        assertFalse(v.grants.allow(keyboard, AutocompleteContract.GRANT))
        v.seedDefaults()
        assertTrue("the keyboard package is granted by the seed", v.grants.allow(keyboard, AutocompleteContract.GRANT))
        assertFalse(v.grants.allow(stranger, AutocompleteContract.GRANT))
        // a revoked grant is not seeded back
        v.grants.revoke(keyboard); v.seedDefaults()
        caller = keyboard
        assertFalse(call().getBoolean(AutocompleteContract.KEY_OK))
    }

    @Test fun aGrantedKeyboardReadsTheRowsTheMeApiSyncStored() {
        val v = synced()
        v.grants.grant(keyboard, AutocompleteContract.GRANT)
        val answer = call()
        assertTrue(answer.getBoolean(AutocompleteContract.KEY_OK))
        val rows = AutocompleteContract.parse(answer.getString(AutocompleteContract.KEY_JSON))
        assertEquals(listOf("ids", "addresses", "urls", "secrets"), rows.map { it.page })
        assertEquals("fake-passphrase", rows.single { it.page == "secrets" }.value)
        assertTrue("unknown pages and blank values never travel", rows.none { it.label == "dropped" || it.label == "blank" })
    }

    @Test fun beforeTheFirstSyncAGrantedKeyboardGetsAnEmptyDocument() {
        AccountVault(ctx).grants.grant(keyboard, AutocompleteContract.GRANT)
        val answer = call()
        assertTrue(answer.getBoolean(AutocompleteContract.KEY_OK))
        assertTrue(AutocompleteContract.parse(answer.getString(AutocompleteContract.KEY_JSON)).isEmpty())
    }

    @Test fun theSyncGoesToTheDeclaredMeApiOverTheMesh() {
        assertEquals("http://cloud-me-api.app:8124/me/autocomplete", MeAutocompleteSync.url(decl))
        var asked = ""
        MeAutocompleteSync.http = { url -> asked = url; 200 to meAnswer }
        MeAutocompleteSync.sync(ctx, AccountVault(ctx), decl)
        assertEquals("http://cloud-me-api.app:8124/me/autocomplete", asked)
        assertEquals(MeAutocompleteSync.Outcome.NoService, MeAutocompleteSync.sync(ctx, AccountVault(ctx), """{"services":[]}"""))
    }

    @Test fun aFailedSyncKeepsWhatWasStored() {
        val v = synced()
        val before = v.autocompleteJson()
        MeAutocompleteSync.http = { 503 to "down" }
        assertEquals(MeAutocompleteSync.Outcome.Failed("HTTP 503"), MeAutocompleteSync.sync(ctx, v, decl))
        MeAutocompleteSync.http = { 200 to "<html>" }
        assertEquals(MeAutocompleteSync.Outcome.Failed("not a document"), MeAutocompleteSync.sync(ctx, v, decl))
        MeAutocompleteSync.http = { throw java.io.IOException("no route") }
        assertEquals(MeAutocompleteSync.Outcome.Failed("IOException"), MeAutocompleteSync.sync(ctx, v, decl))
        assertEquals(before, v.autocompleteJson())
    }

    @Test fun theStoredDocumentLivesInTheEncryptedConfigsPrefsAndNeverInTheOtherSections() {
        val v = synced()
        assertTrue(JSONObject(v.autocompleteJson()).has("secrets"))
        assertFalse(v.prefs.text(ConfigsPrefs.K_DATA).contains("fake-passphrase"))
        assertFalse(v.prefs.json.contains("fake-passphrase"))
    }
}
