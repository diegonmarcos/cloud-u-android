package com.diegonmarcos.clouddrive.files.convert

import com.diegonmarcos.clouddrive.files.FileOps
import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * MHTML → HTML + assets folder, against a Chrome-shaped fixture (fixtures/sample.mht): a
 * quoted-printable page with a `<base>`, a cid: image, relative and absolute Content-Location
 * references, a stylesheet that points at a sibling image, an inline style url(), a script, a
 * link to a page that was NOT saved, and two parts that want the same file name.
 */
class MhtmlConverterTest {

    private val fixture: ByteArray = javaClass.classLoader!!.getResourceAsStream("fixtures/sample.mht")!!.readBytes()
    private fun tmp(): File = Files.createTempDirectory("mhtml").toFile()
    private val noProgress = FileOps.Progress { _, _, _ -> }

    private val refs = Regex("""(?:src|href)\s*=\s*"([^"]*)"|url\(\s*["']?([^"')]+)["']?\s*\)""")

    private fun refsOf(text: String): List<String> = refs.findAll(text).map { it.groupValues[1].ifEmpty { it.groupValues[2] } }.toList()

    @Test fun parsesEveryPartAndDecodesTheirBodies() {
        val parts = Mhtml.parse(fixture)
        assertEquals(8, parts.size)
        val page = parts.first()
        assertEquals("text/html", page.type)
        assertTrue(String(page.body, Charsets.UTF_8).contains("café softbreak")) // =C3=A9 and the "=\n" soft break
        assertEquals("logo@mhtml.blink", parts[1].contentId)
        assertEquals(0x89, parts[1].body[0].toInt() and 0xFF) // base64 → PNG signature
        assertEquals("JPEGDATA", String(parts[3].body))
    }

    @Test fun writesTheHtmlAndAnAssetsFolderWithWorkingRelativeLinks() {
        val dir = tmp()
        val out = Mhtml.convert(fixture, File(dir, "sample.html"), File(dir, "sample_files"))
        assertTrue(out.html.isFile)
        val assets = out.assetsDir!!
        assertEquals("sample_files", assets.name)
        assertEquals(7, out.assets.size)

        val html = out.html.readText()
        // every reference that is not an external URL resolves to a file that exists next to the html
        val local = refsOf(html).filter { !it.startsWith("http") && !it.startsWith("#") }
        assertTrue("expected rewritten references, got $local", local.size >= 6)
        for (ref in local) {
            assertTrue("$ref must be relative and inside the assets folder", ref.startsWith("sample_files/"))
            assertTrue("$ref must exist", File(dir, ref).isFile)
        }
        assertFalse("no cid: reference may remain", html.contains("cid:"))
        assertFalse("saved parts are no longer fetched from the network", html.contains("example.com/css/site.css") || html.contains("example.com/img/hero.jpg") || html.contains("/js/app.js\""))
        assertTrue("a link to a page that was not saved is left alone", html.contains("https://example.com/other.html"))
        assertFalse("<base> would break the relative links", html.contains("<base"))
        assertTrue(html.contains("<meta charset=\"utf-8\">"))
        assertFalse(html.contains("windows-1252"))
        assertTrue(html.contains("café softbreak"))
    }

    @Test fun twoPartsWithOneNameBothSurviveAndEachLinkPointsAtItsOwn() {
        val dir = tmp()
        val out = Mhtml.convert(fixture, File(dir, "p.html"), File(dir, "p_files"))
        val html = out.html.readText()
        val names = out.assets.map { it.name }
        assertTrue(names.containsAll(listOf("photo.png", "photo-2.png")))
        val photoRefs = refsOf(html).filter { it.contains("photo") }
        assertEquals(2, photoRefs.toSet().size)
        assertEquals("JPEGDATA", File(out.assetsDir, "hero.jpg").readText())
        assertEquals("console.log(1)", File(out.assetsDir, "app.js").readText())
    }

    @Test fun theStylesheetPointsAtItsSiblingByARelativeName() {
        val dir = tmp()
        val out = Mhtml.convert(fixture, File(dir, "s.html"), File(dir, "s_files"))
        val css = File(out.assetsDir, "site.css").readText()
        assertTrue(css, css.contains("url(bg.png)"))
        assertTrue(File(out.assetsDir, "bg.png").isFile)
        assertFalse(css.contains("../img"))
    }

    @Test fun theConverterNeverOverwritesAndReportsWhatItWrote() {
        val dir = tmp()
        val src = File(dir, "sample.mht").apply { writeBytes(fixture) }
        val first = MhtmlToHtml.convert(src, dir, noProgress) { false }
        assertEquals(listOf("sample.html", "sample_files"), first.map { it.name })
        val second = MhtmlToHtml.convert(src, dir, noProgress) { false }
        assertEquals(listOf("sample (2).html", "sample (2)_files"), second.map { it.name })
        assertTrue(File(dir, "sample.html").isFile) // the first result is untouched
    }

    @Test fun aFileThatIsNotMhtmlFailsCleanlyAndLeavesNothingBehind() {
        val dir = tmp()
        val src = File(dir, "bad.mht").apply { writeText("") }
        try { MhtmlToHtml.convert(src, dir, noProgress) { false }; fail("expected IOException") } catch (e: IOException) { assertNotNull(e.message) }
        assertEquals(listOf("bad.mht"), dir.list()!!.toList())
    }

    @Test fun aSinglePartPageNeedsNoAssetsFolder() {
        val dir = tmp()
        val raw = "Content-Type: text/html\r\nContent-Location: https://x.test/\r\n\r\n<html><head></head><body>hi</body></html>".toByteArray()
        val out = Mhtml.convert(raw, File(dir, "one.html"), File(dir, "one_files"))
        assertTrue(out.html.readText().contains("hi"))
        assertEquals(null, out.assetsDir)
        assertFalse(File(dir, "one_files").exists())
    }

    @Test fun markdownBecomesHtmlAndCannotInjectATag() {
        val html = Markdown.toHtml("# Title\n\nSome **bold** and `code <b>` and [a link](http://x.test/a?b=1&c=2) <script>alert(1)</script>\n\n- one\n  - nested\n- two\n\n| a | b |\n|---|---|\n| 1 | 2 |\n\n[bad](javascript:alert(1))\n", "t")
        assertTrue(html.contains("<h1>Title</h1>"))
        assertTrue(html.contains("<strong>bold</strong>"))
        assertTrue(html.contains("<code>code &lt;b&gt;</code>"))
        assertTrue(html.contains("<a href=\"http://x.test/a?b=1&amp;c=2\">a link</a>"))
        assertFalse(html.contains("<script>"))
        assertTrue(html.contains("&lt;script&gt;"))
        assertTrue(html.contains("<ul>") && html.contains("<li>one"))
        assertTrue(html.contains("<li>nested</li>"))
        assertTrue(html.contains("<th>a</th>") && html.contains("<td>2</td>"))
        assertTrue(html.contains("<a href=\"#\">bad</a>"))
    }

    @Test fun jsonIsPrettyPrintedWithKeyOrderKept() {
        assertEquals("{\n  \"b\": 1,\n  \"a\": [\n    1,\n    2\n  ]\n}\n", JsonPretty.pretty("{\"b\":1,\"a\":[1,2]}"))
        try { JsonPretty.pretty("{nope"); fail() } catch (e: IOException) { assertTrue(e.message!!.startsWith("not valid JSON")) }
    }
}
