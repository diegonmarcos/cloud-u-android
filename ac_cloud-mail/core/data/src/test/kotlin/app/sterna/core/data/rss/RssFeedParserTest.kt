package app.sterna.core.data.rss

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader

/**
 * The feed parser proves itself against one real RSS 2.0 and one real Atom document. Both specs
 * have exact, well-known shapes; the samples below carry every field the reader shows, plus the
 * dialect differences (RSS's text link and description vs Atom's href attribute and content) so a
 * regression in either is caught here rather than on a user's phone.
 */
class RssFeedParserTest {

    private val parser = RssFeedParser()

    @Test fun `parses an RSS two-oh feed`() {
        val result = parser.parse(StringReader(RSS_20))

        assertTrue("expected a parse, got $result", result is RssParseResult.Ok)
        val feed = (result as RssParseResult.Ok).feed
        assertEquals("Sterna Blog", feed.title)
        assertEquals("https://example.org/", feed.link)
        assertEquals(2, feed.items.size)

        val first = feed.items[0]
        assertEquals("Hello, world", first.title)
        assertEquals("https://example.org/2026/hello.html", first.link)
        assertEquals("Mon, 01 Sep 2026 09:30:00 GMT", first.published)
        assertTrue("summary should carry the description", first.summary.contains("first post"))

        val second = feed.items[1]
        assertEquals("A second article", second.title)
        assertEquals("https://example.org/2026/second.html", second.link)
    }

    @Test fun `parses an atom feed`() {
        val result = parser.parse(StringReader(ATOM))

        assertTrue("expected a parse, got $result", result is RssParseResult.Ok)
        val feed = (result as RssParseResult.Ok).feed
        assertEquals("Example Feed", feed.title)
        assertEquals("https://example.org/", feed.link)
        assertEquals(1, feed.items.size)

        val entry = feed.items[0]
        assertEquals("Atom-Powered Robots Run Amok", entry.title)
        assertEquals("https://example.org/2003/12/13/atom03", entry.link)
        assertEquals("2003-12-13T18:30:02Z", entry.published)
        assertTrue("summary should carry the entry content", entry.summary.contains("internet-enabled"))
    }

    @Test fun `an atom feed with no link attribute still parses`() {
        val result = parser.parse(StringReader(ATOM_NO_LINK_HREF))

        assertTrue("expected a parse, got $result", result is RssParseResult.Ok)
        val feed = (result as RssParseResult.Ok).feed
        // The entry keeps an empty link rather than crashing; the reader then hides it.
        assertEquals("", feed.items[0].link)
    }

    @Test fun `a body that is not a feed is reported, not raised`() {
        val result = parser.parse(StringReader("<!DOCTYPE html><html><body>hi</body></html>"))
        assertEquals(RssParseResult.Failed(RssParseFailure.NOT_A_FEED), result)
    }

    @Test fun `a feed with no articles is reported as empty`() {
        val result = parser.parse(
            StringReader(
                """<?xml version="1.0"?><rss version="2.0"><channel>
                   <title>Quiet</title><link>https://example.org/</link></channel></rss>""",
            ),
        )
        assertEquals(RssParseResult.Failed(RssParseFailure.EMPTY), result)
    }

    @Test fun `an rss channel without a channel element is not a feed`() {
        val result = parser.parse(StringReader("<?xml version=\"1.0\"?><rss version=\"2.0\"><item></item></rss>"))
        assertEquals(RssParseResult.Failed(RssParseFailure.NOT_A_FEED), result)
    }

    private companion object {
        val RSS_20 = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0">
              <channel>
                <title>Sterna Blog</title>
                <link>https://example.org/</link>
                <description>The blog of the Arctic tern.</description>
                <item>
                  <title>Hello, world</title>
                  <link>https://example.org/2026/hello.html</link>
                  <pubDate>Mon, 01 Sep 2026 09:30:00 GMT</pubDate>
                  <description>A warm first post.</description>
                </item>
                <item>
                  <title>A second article</title>
                  <link>https://example.org/2026/second.html</link>
                  <pubDate>Tue, 02 Sep 2026 11:00:00 GMT</pubDate>
                  <description>More words.</description>
                </item>
              </channel>
            </rss>
        """.trimIndent()

        val ATOM = """
            <?xml version="1.0" encoding="utf-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom">
              <title>Example Feed</title>
              <link href="https://example.org/"/>
              <updated>2003-12-13T18:30:02Z</updated>
              <author><name>John Doe</name></author>
              <id>urn:uuid:60a76c80-d399-11d9-b93C-0003939e0af6</id>
              <entry>
                <title>Atom-Powered Robots Run Amok</title>
                <link href="https://example.org/2003/12/13/atom03"/>
                <id>urn:uuid:1225c695-cfb8-4ebb-aaaa-80da344efa6a</id>
                <updated>2003-12-13T18:30:02Z</updated>
                <summary>Some text about an internet-enabled robot.</summary>
              </entry>
            </feed>
        """.trimIndent()

        val ATOM_NO_LINK_HREF = """
            <?xml version="1.0" encoding="utf-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom">
              <title>Example Feed</title>
              <entry>
                <title>No link here</title>
                <id>urn:uuid:1</id>
                <updated>2003-12-13T18:30:02Z</updated>
              </entry>
            </feed>
        """.trimIndent()
    }
}