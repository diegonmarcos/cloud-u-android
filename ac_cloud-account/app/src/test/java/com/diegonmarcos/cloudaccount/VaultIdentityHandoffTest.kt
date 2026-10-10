package com.diegonmarcos.cloudaccount

import android.app.Application
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.cloudaccount.autofill.AutofillImport
import com.diegonmarcos.cloudaccount.autofill.VaultIdentityHandoff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Account ▸ Import → Cloud Vault: each ID document found in a paste becomes one explicit intent to
 * Cloud Vault's add-identity entry point, carrying the document and its holder's names; without a
 * vault that answers it, nothing is sent and the page keeps the "add it in Cloud Vault" flow. Every
 * value is an obvious fake.
 */
@RunWith(RobolectricTestRunner::class)
class VaultIdentityHandoffTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    private val paste = """
        Profile: Testy - Spain-A
        Given name: Testy
        Middle name: Quinn
        Family name: Fakeson
        Second family name: Example
        DNI (ES): FAKE-DNI-0001
        Support: FAKE-SUP-0001
        Valid until: 2099-01-01
        Issued: 2019-01-01
        Reisepass (DE): FAKE-PASS-0001
    """.trimIndent()

    private fun parsedIds() = AutofillImport.parse(paste).ids

    @Test fun ids_carry_their_holder_names() {
        val (dni, pass) = parsedIds()
        assertEquals("DNI", dni.type)
        assertEquals("Testy", dni.givenName)
        assertEquals("Quinn", dni.middleName)
        assertEquals("Fakeson Example", dni.familyName)
        assertEquals("Passport", pass.type)
        assertEquals("Fakeson Example", pass.familyName)
        // A log line never shows a whole number.
        assertFalse(dni.toString().contains("FAKE-DNI-0001"))
    }

    @Test fun import_builds_the_vault_add_identity_intent() {
        val intent = VaultIdentityHandoff.intentFor(parsedIds().first())

        assertEquals("com.diegonmarcos.cloudvault.action.ADD_IDENTITY", intent.action)
        assertEquals("com.diegonmarcos.cloudvault", intent.`package`)
        assertNull(intent.component)
        val x = "com.diegonmarcos.cloudvault.extra."
        assertEquals(1, intent.getIntExtra(x + "VERSION", 0))
        assertEquals("DNI", intent.getStringExtra(x + "ID_TYPE"))
        assertEquals("FAKE-DNI-0001", intent.getStringExtra(x + "ID_NUMBER"))
        assertEquals("FAKE-SUP-0001", intent.getStringExtra(x + "ID_SUPPORT"))
        assertEquals("ES", intent.getStringExtra(x + "ID_ISSUING_COUNTRY"))
        assertEquals("2099-01-01", intent.getStringExtra(x + "ID_VALID_UNTIL"))
        assertEquals("2019-01-01", intent.getStringExtra(x + "ID_ISSUED"))
        assertEquals("Testy", intent.getStringExtra(x + "FIRST_NAME"))
        assertEquals("Quinn", intent.getStringExtra(x + "MIDDLE_NAME"))
        assertEquals("Fakeson Example", intent.getStringExtra(x + "LAST_NAME"))
        // The passport has no support number: the extra is absent, never an empty string.
        val passport = VaultIdentityHandoff.intentFor(parsedIds()[1])
        assertFalse(passport.hasExtra(x + "ID_SUPPORT"))
        assertEquals("DE", passport.getStringExtra(x + "ID_ISSUING_COUNTRY"))
    }

    @Test fun json_ids_carry_the_profile_names() {
        val json = """{"profiles":[{"label":"Testy - Germany-A","given_name":"Testy","family_name":"Fakeson",
            "ids":[{"type":"Personalausweis","number":"FAKE-AUSWEIS-0001","country":"de","valid_until":"2099-12-31"}]}]}"""
        val id = AutofillImport.parse(json).ids.single()
        val intent = VaultIdentityHandoff.intentFor(id)
        val x = "com.diegonmarcos.cloudvault.extra."
        assertEquals("Personalausweis", intent.getStringExtra(x + "ID_TYPE"))
        assertEquals("DE", intent.getStringExtra(x + "ID_ISSUING_COUNTRY"))
        assertEquals("2099-12-31", intent.getStringExtra(x + "ID_VALID_UNTIL"))
        assertEquals("Testy", intent.getStringExtra(x + "FIRST_NAME"))
        assertEquals("Fakeson", intent.getStringExtra(x + "LAST_NAME"))
    }

    @Test fun without_a_vault_that_answers_nothing_is_sent() {
        assertFalse(VaultIdentityHandoff.isAvailable(app))
        assertFalse(VaultIdentityHandoff.send(app, parsedIds().first()))
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test fun a_vault_with_the_entry_point_gets_the_intent() {
        val resolve = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = VaultIdentityHandoff.VAULT_PACKAGE
                name = "com.x8bit.bitwarden.AddIdentityActivity"
            }
        }
        shadowOf(app.packageManager).addResolveInfoForIntent(
            Intent(VaultIdentityHandoff.ACTION).setPackage(VaultIdentityHandoff.VAULT_PACKAGE),
            resolve,
        )

        assertTrue(VaultIdentityHandoff.isAvailable(app))
        assertTrue(VaultIdentityHandoff.send(app, parsedIds().first()))
        val started = shadowOf(app).nextStartedActivity
        assertEquals(VaultIdentityHandoff.ACTION, started.action)
        assertEquals(VaultIdentityHandoff.VAULT_PACKAGE, started.`package`)
        assertEquals("FAKE-DNI-0001", started.getStringExtra(VaultIdentityHandoff.EXTRA_NUMBER))
    }

    @Test fun account_requests_and_defines_the_vault_signature_permission() {
        val manifest = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val android = "http://schemas.android.com/apk/res/android"
        fun names(tag: String): List<Element> = manifest.getElementsByTagName(tag)
            .let { l -> (0 until l.length).map { l.item(it) as Element } }
        val permission = names("permission").single { it.getAttributeNS(android, "name") == VaultIdentityHandoff.PERMISSION }
        assertEquals("signature", permission.getAttributeNS(android, "protectionLevel"))
        assertTrue(names("uses-permission").any { it.getAttributeNS(android, "name") == VaultIdentityHandoff.PERMISSION })
    }
}
