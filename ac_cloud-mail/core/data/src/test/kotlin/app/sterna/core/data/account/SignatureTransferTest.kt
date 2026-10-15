package app.sterna.core.data.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What leaves with a signature export and where an import lands. The property that matters is that a
 * signature travels by ADDRESS (account ids are minted per install) and that applying one never
 * disturbs an identity it was not addressed to.
 */
class SignatureTransferTest {

    private fun sig(id: String, text: String, html: String = "") = StoredSignature(id, id, text, html)

    private fun account(id: String, vararg identities: StoredIdentity, server: List<StoredIdentity> = emptyList()) =
        StoredAccount(id = id, server = "https://s", username = "u@$id", identities = identities.toList(), serverIdentities = server)

    private val work = StoredIdentity(
        "i1", "Alex", "Alex@Work.test",
        signatures = listOf(sig("a", "Alex, Acme", "<b>Alex</b>, Acme"), sig("b", "Alex")),
        defaultSignatureId = "b",
    )
    private val bare = StoredIdentity("i2", "Home", "home@x.test")

    @Test fun collectKeysByLowercasedAddressAndSkipsIdentitiesWithoutSignatures() {
        val out = SignatureTransfer.collect(listOf(account("one", work, bare)))
        assertEquals(setOf("alex@work.test"), out.keys)
        assertEquals("b", out.getValue("alex@work.test").default)
        assertEquals(2, out.getValue("alex@work.test").signatures.size)
    }

    @Test fun aLegacySinglePlainSignatureTravelsAsTheMigratedOne() {
        val legacy = StoredIdentity("i3", "Old", "old@x.test", signature = "Old sign-off")
        val entry = SignatureTransfer.collect(listOf(account("one", legacy))).getValue("old@x.test")
        assertEquals(StoredSignature.MIGRATED_ID, entry.signatures.single().id)
        assertEquals("Old sign-off", entry.signatures.single().text)
    }

    @Test fun theFirstAccountThatNamesAnAddressWins() {
        val other = work.copy(signatures = listOf(sig("z", "other")), defaultSignatureId = "z")
        val out = SignatureTransfer.collect(listOf(account("one", work), account("two", other)))
        assertEquals(2, out.getValue("alex@work.test").signatures.size)
    }

    @Test fun anEntrySurvivesTheWireIncludingTheHtmlHalf() {
        val entry = SignatureTransfer.collect(listOf(account("one", work))).getValue("alex@work.test")
        assertEquals(entry, SignatureTransfer.decodeEntry(SignatureTransfer.encodeEntry(entry)))
        assertNull(SignatureTransfer.decodeEntry("not json"))
    }

    @Test fun anHtmlSignatureArrivingWithoutItsPlainTextGetsItDerived() {
        val entry = SignatureTransfer.decodeEntry("""{"signatures":[{"id":"f","html":"<b>Hi</b><br>there"}]}""")!!
        assertEquals("Hi\nthere", entry.signatures.single().text)
        val viaDocument = SignatureTransfer.parseDocument(
            """{"format":"cloud-mail-signatures","v":1,"identities":{"*":{"signatures":[{"id":"f","html":"<i>x</i>"}]}}}""",
        )!!
        assertEquals("x", viaDocument.getValue("*").signatures.single().text)
    }

    @Test fun aDocumentRoundTripsAndForeignOrNewerOnesAreRefused() {
        val entries = SignatureTransfer.collect(listOf(account("one", work)))
        assertEquals(entries, SignatureTransfer.parseDocument(SignatureTransfer.toDocument(entries)))
        assertNull(SignatureTransfer.parseDocument("""{"format":"something-else","v":1,"identities":{}}"""))
        assertNull(SignatureTransfer.parseDocument("""{"format":"cloud-mail-signatures","v":99,"identities":{}}"""))
        assertNull(SignatureTransfer.parseDocument("<p>html</p>"))
    }

    @Test fun applyWritesOntoTheAddressedIdentityOnlyAndMirrorsTheLegacyPair() {
        val target = StoredIdentity("t", "Alex", "alex@work.test")
        val entries = mapOf("alex@work.test" to SignatureTransfer.Entry("a", work.signatures))
        val (out, changed) = SignatureTransfer.apply(listOf(account("new", target, bare)), entries)
        assertEquals(1, changed)
        val identities = out.single().identities
        assertEquals(work.signatures, identities[0].signatures)
        assertEquals("a", identities[0].defaultSignatureId)
        assertEquals("<b>Alex</b>, Acme", identities[0].signatureHtml)
        assertEquals(bare, identities[1])
    }

    @Test fun applyIsIdempotent() {
        val entries = mapOf("alex@work.test" to SignatureTransfer.Entry("b", work.signatures))
        val first = SignatureTransfer.apply(listOf(account("n", StoredIdentity("t", "Alex", "alex@work.test"))), entries).first
        val (_, changed) = SignatureTransfer.apply(first, entries)
        assertEquals(0, changed)
    }

    @Test fun anAddressNoIdentityAnswersToIsSkipped() {
        val (out, changed) = SignatureTransfer.apply(listOf(account("n", bare)), mapOf("ghost@x.test" to SignatureTransfer.Entry("a", work.signatures)))
        assertEquals(0, changed)
        assertEquals(bare, out.single().identities.single())
    }

    @Test fun anAddressOnlyTheServerKnowsBecomesAManualOverride() {
        val server = StoredIdentity("srv", "Alex", "alex@work.test")
        val (out, changed) = SignatureTransfer.apply(
            listOf(account("n", server = listOf(server))),
            mapOf("alex@work.test" to SignatureTransfer.Entry("a", work.signatures)),
        )
        assertEquals(1, changed)
        assertEquals(work.signatures, out.single().identities.single().signatures)
        assertEquals(listOf(server), out.single().serverIdentities)
    }

    @Test fun theAllIdentitiesKeyAddsASignatureToEachWithoutDuplicatingOrDisplacing() {
        val bareFile = SignatureTransfer.Entry("f", listOf(sig("f", "From file", "<i>From file</i>")))
        val entries = mapOf(SignatureTransfer.ALL_IDENTITIES to bareFile)
        val (out, changed) = SignatureTransfer.apply(listOf(account("n", work, bare)), entries)
        assertEquals(2, changed)
        val w = out.single().identities[0]
        assertEquals(listOf("a", "b", "f"), w.signatures.map { it.id })
        assertEquals("b", w.defaultSignatureId)
        // an identity with no signature gets it as its default
        val h = out.single().identities[1]
        assertEquals("f", h.defaultSignature()?.id)
        // importing the same file again changes nothing
        assertEquals(0, SignatureTransfer.apply(out, entries).second)
    }

    @Test fun clearRemovesTheNamedSignaturesAndTheLegacyPair() {
        val withLegacy = work.copy(signature = "x", signatureHtml = "<b>x</b>")
        val (out, changed) = SignatureTransfer.clear(listOf(account("n", withLegacy, bare)), setOf("ALEX@work.test"))
        assertEquals(1, changed)
        val cleared = out.single().identities[0]
        assertTrue(cleared.signatures.isEmpty())
        assertEquals("", cleared.signature)
        assertEquals("", cleared.signatureHtml)
        assertNull(cleared.defaultSignatureId)
        assertEquals(bare, out.single().identities[1])
    }
}
