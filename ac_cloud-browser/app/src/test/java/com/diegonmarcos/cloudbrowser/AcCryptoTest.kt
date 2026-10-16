package com.diegonmarcos.cloudbrowser

import com.diegonmarcos.superapp.browser.AcCard
import com.diegonmarcos.superapp.browser.AcKeyProvider
import com.diegonmarcos.superapp.browser.AcLogin
import com.diegonmarcos.superapp.browser.AcSealedBox
import com.diegonmarcos.superapp.browser.AcSecretsJson
import com.diegonmarcos.superapp.browser.AcSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #900 encryption round trip: AES-256-GCM, a fresh IV, the record bound in as AAD; secrets are never plaintext in the JSON. */
class AcCryptoTest {

    private val box = AcSealedBox(AcKeyProvider.fixed(ByteArray(32) { it.toByte() }))
    private val other = AcSealedBox(AcKeyProvider.fixed(ByteArray(32) { (it + 1).toByte() }))

    @Test
    fun `seal then open gives the plaintext back, unicode included`() {
        for (s in listOf("hunter2", "p@ss wörd ✓ 日本", "x".repeat(300), "4111 1111 1111 1111")) {
            val sealed = box.seal(s, "cb-ac|login|1|pw")
            assertTrue(AcSealedBox.isSealed(sealed))
            assertFalse("the envelope does not contain the plaintext", sealed.contains(s))
            assertEquals(s, box.open(sealed, "cb-ac|login|1|pw"))
        }
    }

    @Test
    fun `every seal uses a fresh IV, so the same secret never seals the same way twice`() {
        val a = box.seal("same", "aad"); val b = box.seal("same", "aad")
        assertFalse(a == b)
        assertEquals("same", box.open(a, "aad")); assertEquals("same", box.open(b, "aad"))
    }

    @Test
    fun `a blob copied onto another record does not open there`() {
        val sealed = box.seal("secret", "cb-ac|login|A|pw")
        assertNull(box.open(sealed, "cb-ac|login|B|pw"))
        assertNull("another field of the same record", box.open(sealed, "cb-ac|login|A|user"))
        assertEquals("secret", box.open(sealed, "cb-ac|login|A|pw"))
    }

    @Test
    fun `a wrong key, a damaged envelope and plaintext are all refused, never decrypted wrongly`() {
        val sealed = box.seal("secret", "aad")
        assertNull(other.open(sealed, "aad"))
        val flipped = sealed.dropLast(3) + (if (sealed.takeLast(3) == "AAA") "BBB" else "AAA")
        assertNull(box.open(flipped, "aad"))
        assertNull(box.open("ac1.not-base64!.@@", "aad"))
        assertNull("plain text is not an envelope", box.open("secret", "aad"))
        assertEquals("", box.seal("", "aad")); assertEquals("", box.open("", "aad"))
    }

    @Test
    fun `logins at rest hold no plaintext password and round trip`() {
        val logins = listOf(AcLogin("a", "example.com", "ana", "correct horse", "work", AcSource.VAULT, 5), AcLogin("b", "x.org", "", "pw2"))
        val json = AcSecretsJson.loginsToJson(logins, box)
        assertFalse(json.toString().contains("correct horse")); assertFalse(json.toString().contains("pw2"))
        assertTrue("the non-secret parts stay readable for the list", json.toString().contains("example.com"))
        val (back, bad) = AcSecretsJson.loginsFromJson(json, box)
        assertEquals(0, bad)
        assertEquals(logins.map { it.copy(updatedAt = it.updatedAt) }, back)
    }

    @Test
    fun `cards at rest hold neither the number nor the code in clear, only the last four`() {
        val cards = listOf(AcCard("c", "Mine", "Ana G", "4111 1111 1111 1111", "01", "2030", "987", AcSource.LOCAL, 1))
        val json = AcSecretsJson.cardsToJson(cards, box)
        val text = json.toString()
        assertFalse(text.contains("4111")); assertFalse(text.contains("1111 1111")); assertFalse(text.contains("987"))
        assertTrue(text.contains("\"last4\":\"1111\""))
        val (back, bad) = AcSecretsJson.cardsFromJson(json, box)
        assertEquals(0, bad); assertEquals(cards, back)
        assertNotNull(back.single().brand())
    }

    @Test
    fun `records the key cannot open are skipped and counted, not returned or deleted`() {
        val json = AcSecretsJson.loginsToJson(listOf(AcLogin("a", "example.com", "ana", "pw")), box)
        val (back, bad) = AcSecretsJson.loginsFromJson(json, other)
        assertEquals(0, back.size); assertEquals(1, bad)
        assertEquals("the stored array itself is untouched", 1, json.length())
    }
}
