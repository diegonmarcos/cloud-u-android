package app.sterna.core.data.account

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The signature split (plain text + optional HTML) must cost nothing to existing installs:
 */
class StoredIdentitySignatureTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test fun identityStoredBeforeTheSplitStillReadsBack() {
        val legacy = """{"id":"a","name":"Alex","email":"alex@masto.top","signature":"Alex\n+33"}"""
        val identity = json.decodeFromString(StoredIdentity.serializer(), legacy)
        assertEquals("Alex\n+33", identity.signature)
        assertEquals("", identity.signatureHtml)
    }

    @Test fun accountStoredBeforeTheSplitStillReadsBack() {
        val legacy = """
            {"id":"acc","server":"https://s","username":"alex@masto.top",
             "identities":[{"id":"a","name":"Alex","email":"alex@masto.top","signature":"Alex"}]}
        """.trimIndent()
        val account = json.decodeFromString(StoredAccount.serializer(), legacy)
        assertEquals("Alex", account.identities.single().signature)
        assertEquals("", account.identities.single().signatureHtml)
    }

    @Test fun roundTripsThroughJson() {
        val identity = StoredIdentity("a", "Alex", "alex@masto.top", "Alex", "<b>Alex</b>")
        val back = json.decodeFromString(
            StoredIdentity.serializer(),
            json.encodeToString(StoredIdentity.serializer(), identity),
        )
        assertEquals(identity, back)
    }

    // --- Legacy HTML sitting in the plain-text field (what the old "Import HTML" wrote) ----------

    @Test fun rawHtmlSignatureIsSplitIntoTextAndHtml() {
        val legacy = StoredIdentity("a", "Alex", "alex@masto.top", "<p>Alex Rivera</p><p>Acme</p>")
        val split = legacy.withSplitSignature()
        assertEquals("Alex Rivera\nAcme", split.signature)
        assertEquals("<p>Alex Rivera</p><p>Acme</p>", split.signatureHtml)
    }

    @Test fun splitIsIdempotent() {
        val once = StoredIdentity("a", "Alex", "alex@masto.top", "<p>Alex</p>").withSplitSignature()
        assertEquals(once, once.withSplitSignature())
    }

    @Test fun plainSignatureIsLeftAlone() {
        val plain = StoredIdentity("a", "Alex", "alex@masto.top", "Alex Rivera\nAcme")
        assertEquals(plain, plain.withSplitSignature())
        assertEquals("", plain.withSplitSignature().signatureHtml)
    }

    @Test fun anAlreadySplitIdentityIsNeverReflattened() {
        val split = StoredIdentity("a", "Alex", "alex@masto.top", "Alex", "<b>Alex</b>")
        assertEquals(split, split.withSplitSignature())
    }

    // --- Dedup key ------------------------------------------------------------------------------

    @Test fun identitiesDifferingOnlyByHtmlSignatureAreNotMerged() {
        val manual = listOf(
            StoredIdentity("1", "Alex", "alex@masto.top", "Alex", "<b>Alex</b>"),
            StoredIdentity("2", "Alex", "alex@masto.top", "Alex", "<i>Alex</i>"),
        )
        assertEquals(2, StoredAccount.normalizeManualIdentities(manual, emptyList()).size)
    }

    @Test fun frozenServerCopyIsStillDropped() {
        val server = listOf(StoredIdentity("s", "Alex", "alex@masto.top", "Alex", "<b>Alex</b>"))
        val manual = listOf(StoredIdentity("m", "Alex", "alex@masto.top", "Alex", "<b>Alex</b>"))
        assertTrue(StoredAccount.normalizeManualIdentities(manual, server).isEmpty())
    }

    @Test fun defaultIdentityFromALegacyHtmlAccountSignatureIsSplit() {
        val account = StoredAccount(
            id = "acc", server = "https://s", username = "alex@masto.top",
            signature = "<p>Alex Rivera</p>",
        )
        val identity = account.resolvedIdentities().single()
        assertEquals("Alex Rivera", identity.signature)
        assertEquals("<p>Alex Rivera</p>", identity.signatureHtml)
    }
}
