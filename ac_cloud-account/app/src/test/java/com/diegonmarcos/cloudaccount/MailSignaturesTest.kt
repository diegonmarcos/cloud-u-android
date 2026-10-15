package com.diegonmarcos.cloudaccount

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.superapp.fleetconfig.SetupContract
import com.diegonmarcos.superapp.profile.AccountStore
import com.diegonmarcos.superapp.settings.AccountVault
import com.diegonmarcos.superapp.settings.BundleCrypto
import com.diegonmarcos.superapp.settings.ConfigsPrefs
import com.diegonmarcos.superapp.settings.MailSignatures
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Mail's signatures through Cloud Account: captured from Mail over the fleet setup contract into the
 * encrypted bundle, handed back on import, and exchanged as a file (a signature document, or a bare
 * .html file) on the Connections page. Mail is a fake on the contract's wire shape; the real
 * handler is SignaturesSetupHandlerTest in Mail.
 */
@RunWith(RobolectricTestRunner::class)
class MailSignaturesTest {
    private lateinit var ctx: Context
    private val calls = mutableListOf<Triple<String, String?, JSONObject?>>()
    private var answer: (String) -> SetupContract.Reply = { SetupContract.Reply.Unreachable("Mail is not installed") }
    private val origPackage = MailSignatures.packageOf
    private val origTransport = MailSignatures.transport

    private val entry = """{"default":"a","signatures":[{"id":"a","name":"Work","text":"Alex","html":"<b>Alex</b>"}]}"""

    @Before fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        ctx.getSharedPreferences("vault_test", Context.MODE_PRIVATE).edit().clear().commit()
        ConfigsPrefs.factory = { it.getSharedPreferences("vault_test", Context.MODE_PRIVATE) }
        val dir = File(ctx.filesDir, "account_test").also { it.deleteRecursively() }
        AccountVault.storeSource = { AccountStore(dir, AccountStore.PlainIo) }
        MailSignatures.packageOf = { "com.example.mail" }
        MailSignatures.transport = { _, _, method, store, body -> calls += Triple(method, store, body); answer(method) }
    }

    @After fun tearDown() {
        ConfigsPrefs.factory = null
        MailSignatures.packageOf = origPackage
        MailSignatures.transport = origTransport
    }

    private fun exported(vararg kv: Pair<String, String>) = SetupContract.Reply.Ok(
        JSONObject().put("app", "mail").put(
            "stores",
            JSONObject().put("mail_signatures", JSONObject().put("mail_signatures", JSONObject().also { o -> kv.forEach { o.put(it.first, it.second) } })),
        ),
    )

    private val applied = SetupContract.Reply.Ok(JSONObject().put("store", "mail_signatures").put("ok", true).put("written", 1))

    // -- the contract ----------------------------------------------------------

    @Test fun captureReadsMailsExportAndDropsTheTypeTable() {
        answer = { exported("alex@work.test" to entry) }
        val values = MailSignatures.capture(ctx)!!
        assertEquals(mapOf("alex@work.test" to entry), values)
        assertEquals(Triple("export", "mail_signatures", null), calls.single())
    }

    @Test fun captureIsNullWhenMailIsAbsentOldOrEmpty() {
        assertNull(MailSignatures.capture(ctx))
        answer = { SetupContract.Reply.Refused("unknown method") }
        assertNull(MailSignatures.capture(ctx))
        answer = { exported() }
        assertNull(MailSignatures.capture(ctx))
        MailSignatures.packageOf = { null }
        assertNull(MailSignatures.capture(ctx))
    }

    @Test fun handBackAppliesTheValuesToTheDeclaredStoreAndReportsWhatMailSaid() {
        answer = { applied }
        val r = MailSignatures.handBack(ctx, mapOf("*" to entry))
        assertTrue(r.why, r.ok)
        assertEquals(1, r.count)
        val (method, store, body) = calls.single()
        assertEquals("apply", method)
        assertEquals("mail_signatures", store)
        assertEquals("mail_signatures", body!!.getString("file"))
        assertEquals(entry, body.getJSONObject("values").getString("*"))

        answer = { SetupContract.Reply.Ok(JSONObject().put("ok", false).put("why", "1 key(s) refused: nothing written")) }
        assertEquals("1 key(s) refused: nothing written", MailSignatures.handBack(ctx, mapOf("*" to entry)).why)
        answer = { SetupContract.Reply.Unreachable("no provider") }
        assertFalse(MailSignatures.handBack(ctx, mapOf("*" to entry)).ok)
        assertFalse(MailSignatures.handBack(ctx, emptyMap()).ok)
    }

    // -- the file --------------------------------------------------------------

    @Test fun aSignatureDocumentRoundTripsThroughTheContractsValues() {
        val doc = MailSignatures.document(mapOf("alex@work.test" to entry, "broken@x.test" to "not json"))
        assertEquals("cloud-mail-signatures", doc.getString("format"))
        assertEquals(setOf("alex@work.test"), doc.getJSONObject("identities").keys().asSequence().toSet())
        val back = MailSignatures.valuesOf(doc)!!
        assertEquals(setOf("alex@work.test"), back.keys)
        assertEquals("<b>Alex</b>", JSONObject(back.getValue("alex@work.test")).getJSONArray("signatures").getJSONObject(0).getString("html"))
    }

    @Test fun aBareHtmlFileImportsAsOneSignatureForEveryIdentityNamedAfterTheFile() {
        val html = "<p>Alex<br><a href=\"https://x.y\">x.y</a></p>"
        val values = MailSignatures.parseImport(html, "Work sig.html")!!
        assertEquals(setOf("*"), values.keys)
        val sig = JSONObject(values.getValue("*")).getJSONArray("signatures").getJSONObject(0)
        assertEquals("Work sig", sig.getString("name"))
        assertEquals(html, sig.getString("html"))
        // Mail derives the plain text; this side has no flattener of its own to disagree with it.
        assertEquals("", sig.getString("text"))
        // Importing the same file twice names the same signature, so Mail can recognise it.
        assertEquals(sig.getString("id"), JSONObject(MailSignatures.parseImport(html, "other.html")!!.getValue("*")).getJSONArray("signatures").getJSONObject(0).getString("id"))
        // No file name: the page's own title, else a fixed word.
        assertEquals("Mine", JSONObject(MailSignatures.parseImport("<title>Mine</title><b>x</b>")!!.getValue("*")).getJSONArray("signatures").getJSONObject(0).getString("name"))
        assertEquals("Imported", JSONObject(MailSignatures.parseImport("<b>x</b>")!!.getValue("*")).getJSONArray("signatures").getJSONObject(0).getString("name"))
    }

    @Test fun aSignatureDocumentImportsAsItself() {
        val text = MailSignatures.document(mapOf("alex@work.test" to entry)).toString()
        assertEquals(setOf("alex@work.test"), MailSignatures.parseImport(text, "cloud-mail-signatures.json")!!.keys)
    }

    @Test fun aFileThatIsNeitherIsRefused() {
        assertNull(MailSignatures.parseImport("just some words"))
        assertNull(MailSignatures.parseImport("{\"kind\":\"cloud-account-bundle\",\"v\":1}"))
        assertNull(MailSignatures.parseImport("{\"format\":\"cloud-mail-signatures\",\"v\":99,\"identities\":{\"a@b.c\":{}}}"))
        assertNull(MailSignatures.parseImport("{broken"))
        assertNull(MailSignatures.parseImport(""))
    }

    // -- the bundle ------------------------------------------------------------

    @Test fun theBundleCapturesMailsSignaturesAndImportHandsThemBack() {
        answer = { exported("alex@work.test" to entry) }
        val text = AccountVault(ctx).also { it.putConnection("dns.preset", "quad9") }
            .exportBundle("pw".toCharArray(), "2026-10-15T00:00:00Z")
        val plain = JSONObject(BundleCrypto.decrypt(text, "pw".toCharArray()))
        val sig = plain.getJSONObject("signatures").getJSONObject("identities").getJSONObject("alex@work.test")
        assertEquals("a", sig.getString("default"))
        assertFalse("signatures stay in the encrypted payload", text.contains("Alex"))

        // a clean phone, with Mail installed
        ctx.getSharedPreferences("vault_test", Context.MODE_PRIVATE).edit().clear().commit()
        calls.clear()
        answer = { applied }
        val r = AccountVault(ctx).importBundle(text, "pw".toCharArray())
        assertTrue(r.why, r.ok)
        assertTrue(r.sections.toString(), "signatures" in r.sections)
        val apply = calls.single { it.first == "apply" }
        assertEquals("mail_signatures", apply.second)
        assertEquals(JSONObject(entry).toString(), JSONObject(apply.third!!.getJSONObject("values").getString("alex@work.test")).toString())
    }

    @Test fun withoutMailTheVaultKeepsTheCopyForTheNextExportAndTheImportStillSucceeds() {
        answer = { exported("alex@work.test" to entry) }
        val text = AccountVault(ctx).exportBundle("pw".toCharArray())
        ctx.getSharedPreferences("vault_test", Context.MODE_PRIVATE).edit().clear().commit()
        answer = { SetupContract.Reply.Unreachable("Mail is not installed") }
        val fresh = AccountVault(ctx)
        assertNull(fresh.signatures())
        assertTrue(fresh.importBundle(text, "pw".toCharArray()).ok)
        // Mail is still not there: the held copy goes into the next bundle.
        val held = fresh.signatures()
        assertNotNull(held)
        assertTrue(held!!.getJSONObject("identities").has("alex@work.test"))
        val next = JSONObject(BundleCrypto.decrypt(fresh.exportBundle("pw2".toCharArray()), "pw2".toCharArray()))
        assertTrue(next.getJSONObject("signatures").getJSONObject("identities").has("alex@work.test"))
    }

    @Test fun aBundleWithoutSignaturesStillExportsAndImportsAsBefore() {
        val text = AccountVault(ctx).also { it.putConnection("dns.preset", "quad9") }.exportBundle("pw".toCharArray())
        assertFalse(JSONObject(BundleCrypto.decrypt(text, "pw".toCharArray())).has("signatures"))
        val r = AccountVault(ctx).importBundle(text, "pw".toCharArray())
        assertTrue(r.ok)
        assertFalse("signatures" in r.sections)
    }
}
