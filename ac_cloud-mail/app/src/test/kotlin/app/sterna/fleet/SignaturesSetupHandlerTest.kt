package app.sterna.fleet

import app.sterna.core.data.account.SignatureTransfer
import app.sterna.core.data.account.StoredAccount
import app.sterna.core.data.account.StoredIdentity
import app.sterna.core.data.account.StoredSignature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The `mail_signatures` store as the fleet setup contract drives it: describe's keys out of export,
 * apply's values back in, atomically, onto the accounts the app already holds. The handler is a view
 * onto the accounts blob, so these run against an in-memory list standing in for [app.sterna.core.data.account.AccountStore].
 */
class SignaturesSetupHandlerTest {

    private var accounts = mutableListOf(
        StoredAccount(
            id = "acc", server = "https://s", username = "alex@work.test",
            identities = listOf(
                StoredIdentity(
                    "i1", "Alex", "alex@work.test",
                    signatures = listOf(StoredSignature("a", "Work", "Alex, Acme", "<b>Alex</b>, Acme")),
                    defaultSignatureId = "a",
                ).withLegacyMirror(),
                StoredIdentity("i2", "Home", "home@x.test"),
            ),
        ),
    )
    private val saved = mutableListOf<String>()

    private fun handler() = SignaturesSetupHandler(
        load = { accounts.toList() },
        save = { id, account -> saved += id; accounts = accounts.map { if (it.id == id) account else it }.toMutableList() },
    )

    @Test fun exportsOneKeyPerAddressThatHasASignature() {
        val v = handler().values()!!
        assertEquals(setOf("alex@work.test"), v.keys)
        val entry = SignatureTransfer.decodeEntry(v.getValue("alex@work.test").toString())!!
        assertEquals("<b>Alex</b>, Acme", entry.signatures.single().html)
    }

    @Test fun nothingToExportReadsAsNull() {
        accounts = mutableListOf(accounts.single().copy(identities = listOf(StoredIdentity("i2", "Home", "home@x.test"))))
        assertNull(handler().values())
    }

    @Test fun applyWritesThroughTheStoreAndExportSeesIt() {
        val entry = SignatureTransfer.Entry("n", listOf(StoredSignature("n", "New", "Hi", "<i>Hi</i>")))
        assertTrue(handler().apply(mapOf("home@x.test" to SignatureTransfer.encodeEntry(entry)), emptySet()))
        assertEquals(listOf("acc"), saved)
        val home = accounts.single().identities.first { it.email == "home@x.test" }
        assertEquals("<i>Hi</i>", home.defaultSignature()?.html)
        assertEquals(setOf("alex@work.test", "home@x.test"), handler().values()!!.keys)
    }

    @Test fun anUndecodableValueRefusesTheWholeApplyAndWritesNothing() {
        val good = SignatureTransfer.encodeEntry(SignatureTransfer.Entry("n", listOf(StoredSignature("n", "New", "Hi"))))
        val before = accounts.toList()
        assertFalse(handler().apply(mapOf("home@x.test" to good, "alex@work.test" to "{broken"), emptySet()))
        assertTrue(saved.isEmpty())
        assertEquals(before, accounts)
    }

    @Test fun removeClearsAnIdentitysSignatures() {
        assertTrue(handler().apply(emptyMap(), setOf("alex@work.test")))
        assertTrue(accounts.single().identities.first().resolvedSignatures().isEmpty())
    }

    @Test fun anApplyThatChangesNothingWritesNothing() {
        val same = SignatureTransfer.encodeEntry(SignatureTransfer.collect(accounts).getValue("alex@work.test"))
        assertTrue(handler().apply(mapOf("alex@work.test" to same), emptySet()))
        assertTrue(saved.isEmpty())
    }

    @Test fun theApplicationRegistersTheHandlerUnderTheDeclaredStoreName() {
        // The fleet-setup guard finds the registration by this exact text; the manifest declares the same name.
        val app = File("src/main/kotlin/app/sterna/fleet/SignaturesSetupHandler.kt").readText()
        assertTrue(Regex("""SetupStores\.register\(\s*"mail_signatures"""").containsMatchIn(app))
        val boot = File("src/main/kotlin/app/sterna/SternaApplication.kt").readText()
        assertTrue(boot.contains("SignaturesSetupHandler.register(container.accountStore)"))
    }
}
