package com.diegonmarcos.superapp.decisions.engine

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileStoresTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun aDocumentIsWrittenAtomicallyAndReadBack() {
        val f = File(tmp.root, "sub/dir/ledger.json")
        val kv = FileKv(f)
        assertNull(kv.read())
        kv.write(JSONObject().put("a", 1))
        assertEquals(1, kv.read()!!.getInt("a"))
        kv.write(JSONObject().put("a", 2))
        assertEquals(2, kv.read()!!.getInt("a"))
        assertFalse(File(f.path + ".tmp").exists())
    }

    @Test fun aCorruptDocumentReadsAsNone() {
        val f = File(tmp.root, "bad.json").also { it.writeText("{not json") }
        assertNull(FileKv(f).read())
    }

    @Test fun aWriteThatCannotHappenIsSwallowed() {
        val blocker = File(tmp.root, "file").also { it.writeText("x") }
        FileKv(File(blocker, "inside.json")).write(JSONObject().put("a", 1))   // its parent is a file
        assertNull(FileKv(File(blocker, "inside.json")).read())
    }

    @Test fun theJournalAppendsTailsAndTrims() {
        val s = FileSink(File(tmp.root, "d/journal.jsonl"))
        assertTrue(s.tail(5).isEmpty())
        for (i in 1..5) s.append("l$i")
        assertEquals(listOf("l4", "l5"), s.tail(2))
        assertEquals(5, s.tail(10).size)
        s.trim(3)
        assertEquals(listOf("l3", "l4", "l5"), s.tail(10))
        s.trim(10)
        assertEquals(3, s.tail(10).size)
        s.append("l6")
        assertEquals(listOf("l5", "l6"), s.tail(2))
    }
}
