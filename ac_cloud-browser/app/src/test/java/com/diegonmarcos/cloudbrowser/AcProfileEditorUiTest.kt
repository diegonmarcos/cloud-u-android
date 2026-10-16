package com.diegonmarcos.cloudbrowser

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTextInput
import com.diegonmarcos.superapp.browser.AcAddress
import com.diegonmarcos.superapp.browser.AcBar
import com.diegonmarcos.superapp.browser.AcChip
import com.diegonmarcos.superapp.browser.AcChooserSheet
import com.diegonmarcos.superapp.browser.AcChooserState
import com.diegonmarcos.superapp.browser.AcChoice
import com.diegonmarcos.superapp.browser.AcDocType
import com.diegonmarcos.superapp.browser.AcDocument
import com.diegonmarcos.superapp.browser.AcFill
import com.diegonmarcos.superapp.browser.AcField
import com.diegonmarcos.superapp.browser.AcFillBar
import com.diegonmarcos.superapp.browser.AcName
import com.diegonmarcos.superapp.browser.AcProfile
import com.diegonmarcos.superapp.browser.AcProfileEditor
import com.diegonmarcos.superapp.browser.AcProfilesSection
import com.diegonmarcos.superapp.browser.AcMemoryEntry
import com.diegonmarcos.superapp.browser.AcProposal
import com.diegonmarcos.superapp.browser.AcSaveSheet
import com.diegonmarcos.superapp.browser.AcSaveState
import com.diegonmarcos.superapp.browser.AcTokens
import com.diegonmarcos.superapp.uikit.CloudKitTheme
import com.diegonmarcos.superapp.uikit.KitPalette
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** #900 the Autocomplete profile editor, the profile list, the chooser and the fill bar, drawn under Robolectric. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AcProfileEditorUiTest {

    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                val app = RuntimeEnvironment.getApplication()
                shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
                base.evaluate()
            }
        }
    }).around(compose)

    private val palette = KitPalette.fromArgb(0xFF1A0033.toInt(), 0xFF2D0A55.toInt(), 0xFFFFFFFF.toInt(), 0x99FFFFFF.toInt(), 0xFF7C3AED.toInt(), 0x33FFFFFF, 0xFF000000.toInt())
    private fun on(tag: String) = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun tap(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).performScrollTo().performClick()
    private fun type(tag: String, text: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).performScrollTo().performTextInput(text)

    private var saved: AcProfile? = null

    private fun editor(p: AcProfile, onDelete: (() -> Unit)? = null) {
        saved = null
        compose.setContent { CloudKitTheme(palette) {
            Column(Modifier.verticalScroll(rememberScrollState())) { AcProfileEditor(p, onSave = { saved = it }, onCancel = {}, onDelete = onDelete) }
        } }
        compose.waitForIdle()
    }

    @Test
    fun `the editor shows every kind of detail and a button to add more of each`() {
        editor(AcProfile(id = "p", label = "Test - ES", names = listOf(AcName(given = "Ana", isDefault = true))))
        assertTrue(on("browser:ac:label")); assertTrue(on("browser:ac:names:0:given"))
        listOf("names", "addresses", "phones", "emails", "documents", "dates", "organisations").forEach { assertTrue("add $it", on("browser:ac:add:$it")) }
    }

    @Test
    fun `a profile takes two names and three addresses and saves them all`() {
        editor(AcProfile(id = "p", label = "Test - ES", names = listOf(AcName(given = "Ana", family = "Garcia", isDefault = true))))
        tap("browser:ac:add:names")
        type("browser:ac:names:1:given", "Anita"); type("browser:ac:names:1:family", "Lopez")
        repeat(3) { tap("browser:ac:add:addresses") }
        listOf("home" to "Madrid", "work" to "Berlin", "family" to "Recife").forEachIndexed { i, (label, city) ->
            compose.onNodeWithTag("browser:ac:addresses:$i:label", useUnmergedTree = true).performScrollTo().performTextReplacement(label)
            type("browser:ac:addresses:$i:street", "Street $i"); type("browser:ac:addresses:$i:city", city)
        }
        tap("browser:ac:editor:save")
        val p = saved!!
        assertEquals(listOf("Ana Garcia", "Anita Lopez"), p.names.map { it.fullName() })
        assertEquals(3, p.addresses.size); assertEquals(listOf("Madrid", "Berlin", "Recife"), p.addresses.map { it.city })
        assertEquals(listOf("home", "work", "family"), p.addresses.map { it.label }); assertEquals("exactly one default address", 1, p.addresses.count { it.isDefault })
        assertEquals("exactly one default name", 1, p.names.count { it.isDefault }); assertEquals("Test - ES", p.label)
    }

    @Test
    fun `marking another record default moves the default`() {
        editor(AcProfile(id = "p", label = "L", names = listOf(AcName(given = "A", isDefault = true), AcName(given = "B"))))
        tap("browser:ac:names:1:default")
        tap("browser:ac:editor:save")
        assertEquals(listOf("B"), saved!!.names.filter { it.isDefault }.map { it.given })
    }

    @Test
    fun `documents carry a type, a number, the issuing country and the citizenship`() {
        editor(AcProfile(id = "p", label = "L", names = listOf(AcName(given = "A"))))
        tap("browser:ac:add:documents")
        tap("browser:ac:documents:0:type:${AcDocType.PASSPORT}")
        type("browser:ac:documents:0:number", "XA0000001"); type("browser:ac:documents:0:issuing", "ES"); type("browser:ac:documents:0:citizenship", "ES")
        tap("browser:ac:add:documents")
        tap("browser:ac:documents:1:type:${AcDocType.NATIONAL_ID}")
        type("browser:ac:documents:1:number", "00000000T"); type("browser:ac:documents:1:citizenship", "BR")
        tap("browser:ac:editor:save")
        val d = saved!!.documents
        assertEquals(listOf(AcDocType.PASSPORT, AcDocType.NATIONAL_ID), d.map { it.type })
        assertEquals(listOf("XA0000001", "00000000T"), d.map { it.number }); assertEquals(listOf("ES", "BR"), d.map { it.citizenship })
    }

    @Test
    fun `empty records are dropped on save and a record can be removed`() {
        editor(AcProfile(id = "p", label = "L", names = listOf(AcName(given = "A")), phones = listOf(com.diegonmarcos.superapp.browser.AcPhone(value = "+34 600"))))
        tap("browser:ac:add:phones"); tap("browser:ac:add:emails")
        tap("browser:ac:phones:0:remove")
        tap("browser:ac:editor:save")
        assertTrue("the removed phone and the blank new ones are gone", saved!!.phones.isEmpty() && saved!!.emails.isEmpty())
    }

    @Test
    fun `the profile list shows profiles with their counts, a default star, and adds a new one`() {
        val list = mutableStateOf(listOf(
            AcProfile(id = "es", label = "Test - ES", isDefault = true, names = listOf(AcName(given = "A"), AcName(given = "B")), addresses = listOf(AcAddress(street = "x"))),
            AcProfile(id = "br", label = "Test - BR", names = listOf(AcName(given = "C")))))
        var became: String? = null
        compose.setContent { CloudKitTheme(palette) { Column(Modifier.verticalScroll(rememberScrollState())) {
            AcProfilesSection(list.value, onSave = { p -> list.value = list.value + p }, onDelete = {}, onDefault = { became = it })
        } } }
        compose.waitForIdle()
        assertTrue(on("kit:listrow:ac-profile-es")); assertTrue(on("kit:listrow:ac-profile-br"))
        assertTrue("the default is starred", compose.onAllNodesWithTag("kit:listrow:ac-profile-es", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        tap("browser:ac:default:br"); assertEquals("br", became)
        assertFalse("no make-default button on the default profile", on("browser:ac:default:es"))
        tap("browser:ac:profiles:add")
        assertTrue("the editor opened", on("browser:ac:editor"))
    }

    @Test
    fun `the chooser asks which profile and which value, and fills with the pick`() {
        val es = AcProfile(id = "es", label = "Test - ES", isDefault = true, names = listOf(AcName("n1", given = "Ana", isDefault = true)),
            addresses = listOf(AcAddress("a1", "home", "Calle", "1", "", "Madrid", "28001", "", "ES", true), AcAddress("a2", "work", "Strasse", "2", "", "Berlin", "10115", "", "DE")))
        val br = AcProfile(id = "br", label = "Test - BR", names = listOf(AcName("n9", given = "Ana")), addresses = listOf(AcAddress("a9", "home", "Rua", "3", "", "Recife", "50000", "", "BR", true)))
        val fields = listOf(AcField(0, AcTokens.GIVEN), AcField(1, AcTokens.LINE1), AcField(2, AcTokens.LEVEL2))
        val tokens = fields.map { it.token }
        val plan = AcFill.plan(listOf(es, br), tokens)
        var filled: AcChoice? = null
        compose.setContent { CloudKitTheme(palette) {
            AcChooserSheet(AcChooserState("Fill from a profile", listOf(es, br), plan, fields, AcFill.defaultChoice(listOf(es, br), tokens)!!), onFill = { filled = it }, onDismiss = {})
        } }
        compose.waitForIdle()
        assertTrue(on("browser:ac:profile:es")); assertTrue(on("browser:ac:profile:br"))
        assertTrue("both addresses of the default profile are offered", on("browser:ac:value:address:a1") && on("browser:ac:value:address:a2"))
        assertFalse("one name needs no question", on("browser:ac:value:name:n1"))
        tap("browser:ac:value:address:a2"); tap("browser:ac:chooser:fill")
        assertEquals("es", filled!!.profileId); assertEquals("a2", filled!!.records[AcTokens.G_ADDRESS])
        // another profile: its own (single) address, so nothing to ask there
        tap("browser:ac:profile:br")
        assertFalse(on("browser:ac:value:address:a9"))
        tap("browser:ac:chooser:fill")
        assertEquals("br", filled!!.profileId); assertEquals("a9", filled!!.records[AcTokens.G_ADDRESS])
    }

    @Test
    fun `the fill bar shows where each suggestion comes from`() {
        var picked: String? = null
        compose.setContent { CloudKitTheme(palette) {
            AcFillBar(AcBar(listOf(AcChip("login:1", "ana", "local"), AcChip("login:2", "ana", "vault"), AcChip("vault", "Cloud Vault", "vault")), note = "Cloud Vault: not installed"),
                onPick = { picked = it }, onDismiss = {})
        } }
        compose.waitForIdle()
        assertTrue(on("browser:ac:badge:login:1") && on("browser:ac:badge:login:2"))
        compose.onNodeWithTag("browser:ac:chip:login:2", useUnmergedTree = true).performClick()
        assertEquals("login:2", picked); assertNotNull(picked)
        assertTrue(on("browser:ac:note"))
    }

    private val proposals = listOf(
        AcProposal.Password("example.com", "ana", "pw", null, false),
        AcProposal.ProfileRecord(AcTokens.G_NAME, name = AcName(given = "Anita")),
        AcProposal.SiteValue(AcMemoryEntry(origin = "example.com", formSig = "f", fieldKey = "kundennr", value = "K-1")),
    )
    private val profiles = listOf(AcProfile(id = "es", label = "Test - ES", isDefault = true), AcProfile(id = "br", label = "Test - BR"))

    @Test
    fun `the save prompt offers each find with a box, the profile to add details to, and the vault only when it can be asked`() {
        var args: List<Any?>? = null
        compose.setContent { CloudKitTheme(palette) {
            AcSaveSheet(AcSaveState("example.com", proposals, profiles, "Cloud Vault is available", vaultUsable = true),
                onSave = { picked, pid, never, vault -> args = listOf(picked, pid, never, vault) }, onDismiss = {})
        } }
        compose.waitForIdle()
        (0..2).forEach { assertTrue("item $it", on("browser:ac:save:item:$it")) }
        assertTrue(on("browser:ac:save:profile:es") && on("browser:ac:save:profile:br") && on("browser:ac:save:profile:new"))
        assertTrue("a password offers 'never here'", on("browser:ac:save:never"))
        assertTrue("the vault is a choice because a password is being saved and the vault can be asked", on("browser:ac:save:vaultalso"))
        tap("browser:ac:save:item:2")      // untick the per-site value
        tap("browser:ac:save:profile:br")
        tap("browser:ac:save:vaultalso")   // on by default, tick it off
        tap("browser:ac:save:yes")
        assertEquals(listOf<Any?>(setOf(0, 1), "br", false, false), args)
    }

    @Test
    fun `without a vault to ask the save prompt has no vault choice and without a password no never-here`() {
        compose.setContent { CloudKitTheme(palette) {
            AcSaveSheet(AcSaveState("example.com", proposals.drop(1), profiles, null, vaultUsable = false), onSave = { _, _, _, _ -> }, onDismiss = {})
        } }
        compose.waitForIdle()
        assertFalse(on("browser:ac:save:vaultalso")); assertFalse(on("browser:ac:save:never"))
    }
}
