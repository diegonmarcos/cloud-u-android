package app.sterna.core.data.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The trust boundary an attachment crosses: a filename and a MIME type chosen by a STRANGER, on the
 * way to this device's disk and to another application's intent filter.
 *
 * Every hostile case here is resolved against a real directory path rather than compared to an
 * expected string. A test asserting `of("../x") == "_.._x"` would pass for a rule that mangles and
 * for a rule that contains, and only one of those is the property that matters.
 */
class AttachmentBoundaryTest {

    private val dir = File("/data/user/0/app.sterna/cache/attachments")

    private fun resolved(declared: String?): File = File(dir, SafeFileName.of(declared)).canonicalFile

    @Test
    fun `no declared name escapes the attachment directory`() {
        val hostile = listOf(
            "../../../../data/data/app.sterna/databases/sterna.db",
            "....//....//etc/passwd",
            "/etc/passwd",
            "a/b/../../etc/passwd",
            "..\\..\\windows\\system32\\evil.dll",
            "\\\\server\\share\\evil.exe",
            "..",
            ".",
            "...",
            "",
            "   ",
            "\n/etc/shadow",
            "..%2f..%2fetc%2fpasswd",
            null,
        )
        for (name in hostile) {
            assertEquals(
                "\"$name\" resolved outside the attachment directory",
                dir.canonicalPath,
                resolved(name).parentFile?.canonicalPath,
            )
        }
    }

    @Test
    fun `a name that is only directory dots becomes the fallback`() {
        // `..` is the parent DIRECTORY. Writing to it throws rather than escaping, so this is a
        // crash where a file should be -- which is still a defect, and still this rule's job.
        for (dots in listOf(".", "..", "...", "....")) {
            assertEquals(SafeFileName.FALLBACK, SafeFileName.of(dots))
        }
    }

    @Test
    fun `an ordinary name survives unchanged`() {
        assertEquals("invoice.pdf", SafeFileName.of("invoice.pdf"))
        assertEquals("report-2026_v2.xlsx", SafeFileName.of("report-2026_v2.xlsx"))
    }

    @Test
    fun `a long name is shortened but keeps its extension`() {
        // The extension is what decides which app opens the file, so a length limit must not be able
        // to remove it -- that would be a security-relevant change made by a cosmetic rule.
        val safe = SafeFileName.of("A".repeat(300) + ".pdf")
        assertTrue("kept the extension", safe.endsWith(".pdf"))
        assertTrue("length ${safe.length} is bounded", safe.length <= 100)
    }

    @Test
    fun `the file decides the type, not the sender`() {
        // The sender says one thing, the file says another: the file wins.
        assertEquals("application/pdf", AttachmentMime.of(declared = "text/plain", fromExtension = "application/pdf"))
        // Nothing known from the file: the claim is used, but only if it IS a media type.
        assertEquals("image/png", AttachmentMime.of(declared = "image/png", fromExtension = null))
        assertEquals(AttachmentMime.UNKNOWN, AttachmentMime.of(declared = "not a mime type", fromExtension = null))
        assertEquals(AttachmentMime.UNKNOWN, AttachmentMime.of(declared = null, fromExtension = null))
        // Parameters are not part of what an intent filter matches.
        assertEquals("text/plain", AttachmentMime.of(declared = "text/plain; charset=utf-8", fromExtension = null))
    }

    @Test
    fun `the package installer is never summoned by a mail attachment`() {
        val apk = "application/vnd.android.package-archive"
        assertEquals(AttachmentMime.UNKNOWN, AttachmentMime.of(declared = apk, fromExtension = null))
        // And not through the extension either: a `.apk` the platform map recognises is still a file
        // a stranger sent, arriving on a surface where a brushed finger is a way to get here.
        assertEquals(AttachmentMime.UNKNOWN, AttachmentMime.of(declared = null, fromExtension = apk))
    }
}
