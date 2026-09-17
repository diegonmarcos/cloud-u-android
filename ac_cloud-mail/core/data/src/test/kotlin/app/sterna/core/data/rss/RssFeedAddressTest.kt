package app.sterna.core.data.rss

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The subscription address gate (#465): an RSS reader must only ever store and fetch a real
 * http(s) address, and must reject the garbage that would otherwise sit in the user's list forever
 * doing nothing. This is the exact gate the reader's "that does not look like a web address"
 * sentence reports — a feed address is never stored or fetched unless this passes.
 */
class RssFeedAddressTest {

    @Test fun `accepts http and https addresses`() {
        assertTrue(isFeedAddress("https://example.org/feed.xml"))
        assertTrue(isFeedAddress("http://example.org/rss"))
        assertTrue("surrounding space is trimmed before judging", isFeedAddress("  https://example.org/feed  "))
    }

    @Test fun `rejects anything that is not an http slash slash address`() {
        assertFalse("no scheme is not an address", isFeedAddress("example.org/feed.xml"))
        assertFalse("non-web schemes are not feeds this reader can store", isFeedAddress("ftp://example.org/feed"))
        assertFalse(isFeedAddress("file:///tmp/feed.xml"))
        assertFalse(isFeedAddress("mailto:someone@example.org"))
        assertFalse(isFeedAddress("javascript:void(0)"))
        assertFalse(isFeedAddress(""))
        assertFalse("whitespace alone is empty, not a feed", isFeedAddress("   "))
    }
}