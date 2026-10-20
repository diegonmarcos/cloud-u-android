package app.sterna.core.data.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1007: a LinkedIn link in a signature is https:// wherever the signature comes from. */
class SignatureHttpsLinksTest {

    private val old = """<a href="http://www.linkedin.com/in/x">LinkedIn</a>"""
    private val fixed = """<a href="https://www.linkedin.com/in/x">LinkedIn</a>"""

    @Test fun storedSignatureReadsBackAsHttps() {
        val id = StoredIdentity("a", "A", "a@x.org", signatures = listOf(StoredSignature("s", "", "LinkedIn", old)))
        assertEquals(fixed, id.resolvedSignatures().single().html)
    }

    @Test fun legacyPairReadsBackAsHttps() {
        val id = StoredIdentity("a", "A", "a@x.org", "LinkedIn <http://linkedin.com/in/x>", old)
        val s = id.resolvedSignatures().single()
        assertEquals(fixed, s.html)
        assertEquals("LinkedIn <https://linkedin.com/in/x>", s.text)
    }

    @Test fun editorSaveAndImportProduceHttps() {
        assertEquals(fixed, StoredSignature.of("s", "", old).html)
        val entry = SignatureTransfer.Entry(null, listOf(StoredSignature("s", "", "t", old)))
        val back = SignatureTransfer.decodeEntry(SignatureTransfer.encodeEntry(entry))!!
        assertEquals(fixed, back.signatures.single().html)
    }

    @Test fun otherLinksAndLookalikeHostsAreUntouched() {
        val s = """<a href="http://example.org/linkedin.com">a</a> http://notlinkedin.com http://linkedin.com.evil.io"""
        assertEquals(s, StoredSignature.upgradeHttpsOnlyLinks(s))
        val sig = StoredSignature("s", "", "plain", "")
        assertSame(sig, sig.withHttpsLinks())
        assertTrue(StoredSignature.upgradeHttpsOnlyLinks("HTTP://LinkedIn.com/in/x").startsWith("https://"))
    }
}
