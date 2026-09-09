package app.sterna.core.data.text

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Executes [attachmentFileName] against pairs written by hand — never by re-running the rule.
 */
class AttachmentFileNameTest {

    @Test fun `an ordinary name comes back as it is`() {
        assertEquals("rapport.pdf", attachmentFileName("rapport.pdf"))
    }

    /**
     * The reason this function exists. 100 letters and `.pdf`: the trunk alone pays the cap and
     */
    @Test fun `a long name is cut, and keeps its extension`() {
        assertEquals("a".repeat(80) + ".pdf", attachmentFileName("a".repeat(100) + ".pdf"))
    }

    /** The nastier half of the same defect: the cap landing INSIDE the extension, so `.pdf`
     *  becomes `.pd` — a file type that does not exist, on a document that looks named. */
    @Test fun `a name whose cap would fall inside the extension keeps it whole`() {
        assertEquals("a".repeat(77) + ".pdf", attachmentFileName("a".repeat(77) + ".pdf"))
    }

    @Test fun `a name with no dot is a trunk and nothing else`() {
        assertEquals("rapport", attachmentFileName("rapport"))
    }

    @Test fun `the LAST dot is the one that counts`() {
        assertEquals("archive.tar.gz", attachmentFileName("archive.tar.gz"))
        // Split at the FIRST dot instead and the extension kept here would be `tar.gz`; the only
        // way to see the difference is a name long enough for the trunk to be cut.
        assertEquals("a".repeat(80) + ".gz", attachmentFileName("a".repeat(100) + ".tar.gz"))
    }

    @Test fun `neither the trunk nor the extension may carry a path character`() {
        assertEquals("a_b_c.txt", attachmentFileName("a/b:c.txt"))
        assertEquals("rapport.p_df", attachmentFileName("rapport.p*df"))
    }

    /** A leading dot is a name, not an extension: `.bashrc` must not become `attachment.bashrc`. */
    @Test fun `a dot in first position opens no extension`() {
        assertEquals(".bashrc", attachmentFileName(".bashrc"))
    }

    /** A trailing dot opens no extension either, and is not silently dropped: what she sees in
     *  the picker is what the part was called. */
    @Test fun `a dot in last position opens no extension`() {
        assertEquals("nom.", attachmentFileName("nom."))
    }

    /** Ten characters is still a file type; eleven is a word, and the whole thing is the trunk.
     *  Both cases need a name long enough to be cut, or the two answers are the same string. */
    @Test fun `past ten characters what follows the dot is not an extension`() {
        assertEquals("a".repeat(80) + ".abcdefghij", attachmentFileName("a".repeat(100) + ".abcdefghij"))
        assertEquals("a".repeat(80), attachmentFileName("a".repeat(100) + ".abcdefghijk"))
        assertEquals("fichier.unetreslongueextension", attachmentFileName("fichier.unetreslongueextension"))
    }

    @Test fun `nothing at all falls back to attachment`() {
        assertEquals("attachment", attachmentFileName(null))
        assertEquals("attachment", attachmentFileName(""))
        assertEquals("attachment", attachmentFileName("   "))
    }
}
