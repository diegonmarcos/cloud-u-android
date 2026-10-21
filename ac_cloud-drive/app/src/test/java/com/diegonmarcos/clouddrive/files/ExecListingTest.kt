package com.diegonmarcos.clouddrive.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecListingTest {
    private val mimes = FileOps.MimeResolver { if (it == "md") "text/markdown" else null }
    private val out = "d\t4096\t1760000000.5\tgit\nf\t12\t1760000001.0\tREADME.md\nf\t3\t1760000002.0\t.hidden\nbroken line\n"

    @Test fun parsesTypesSizesAndNames() {
        val e = ExecListing.parse("/store", out, mimes, showHidden = true)
        assertEquals(listOf("git", "README.md", ".hidden"), e.map { it.name })
        assertTrue(e[0].isDirectory); assertEquals(0L, e[0].size)
        assertEquals("text/markdown", e[1].mime); assertEquals(12L, e[1].size)
        assertEquals("/store/README.md", (e[1].location as Location.Local).path)
    }

    @Test fun hiddenFilesFollowTheFlag() {
        assertEquals(listOf("git", "README.md"), ExecListing.parse("/store", out, mimes, showHidden = false).map { it.name })
    }
}
