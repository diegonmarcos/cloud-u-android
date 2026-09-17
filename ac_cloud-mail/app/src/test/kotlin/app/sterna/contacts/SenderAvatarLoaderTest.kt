package app.sterna.contacts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.File

/**
 * The avatar fallback chain's pure, device-free half (task #464). The order itself — device photo,
 * then the sender domain's logo, then the monogram — is a decision that must never invert, and the
 * functions below are the pieces a unit test CAN reach without Android (ImageBitmap needs
 * Robolectric, so the order is tested on plain values through the generic [pickAvatar]). The
 * network fetch is deliberately not here: [SenderAvatarLoader.load] is suspend + Android + OkHttp.
 */
class SenderAvatarLoaderTest {

    @Test fun `a device photo always beats a domain logo`() {
        assertEquals(
            "the fallback order puts the device's own photo first: nothing the network could offer " +
                "should replace the address book's picture of the actual person",
            "device",
            SenderAvatarLoader.pickAvatar(device = "device", domain = null),
        )
        assertSame(
            "device",
            SenderAvatarLoader.pickAvatar(device = "device", domain = "domain"),
        )
    }

    @Test fun `a domain logo fills in when the device has no photo`() {
        assertSame(
            "with no device photo the sender's domain logo is the next choice",
            "domain",
            SenderAvatarLoader.pickAvatar(device = null, domain = "domain"),
        )
    }

    @Test fun `neither image falls through to null for the monogram`() {
        assertNull(SenderAvatarLoader.pickAvatar(device = null, domain = null))
    }

    @Test fun `the domain is the bare lower-cased host`() {
        assertEquals("example.com", SenderAvatarLoader.domainOf("alice@Example.COM"))
        assertEquals("example.com", SenderAvatarLoader.domainOf(" alice@Example.COM "))
        assertEquals("", SenderAvatarLoader.domainOf("not-an-address"))
        assertEquals("", SenderAvatarLoader.domainOf(""))
    }

    /** The privacy guarantee made testable: the fetcher asks the sender's OWN host, by name, in a
     *  bounded order — never an individual address, and never a third-party avatar service. */
    @Test fun `the logo candidates are the sender's own host, and only it`() {
        assertEquals(
            listOf("https://example.com/favicon.ico", "https://example.com/favicon.png"),
            SenderAvatarLoader.faviconCandidates("example.com"),
        )
    }

    @Test fun `the disk cache is keyed by domain and stable`() {
        val dir = File("/tmp/avatar-test")
        val a = SenderAvatarLoader.cacheFile(dir, "example.com")
        assertEquals("the same domain must always map to the same file, so a recomposition never " +
            "re-fetches", a, SenderAvatarLoader.cacheFile(dir, "example.com"))
        val b = SenderAvatarLoader.cacheFile(dir, "another.example.net")
        assertNotEquals("two domains must never share a cache entry — that would label one sender " +
            "with another's brand", a, b)
        assertEquals("the file name is derived from the domain, never from an address",
            "avatar-${"example.com".hashCode()}.img", a.name)
    }
}
