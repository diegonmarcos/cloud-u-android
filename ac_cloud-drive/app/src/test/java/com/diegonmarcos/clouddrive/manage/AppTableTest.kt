package com.diegonmarcos.clouddrive.manage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Manage ▸ Apps: ranking, sorting, cell formatting, the "—" states and the shell parsers. */
class AppTableTest {
    private fun row(pkg: String, app: Long?, data: Long?, cache: Long?, running: Boolean? = null, ram: Long? = null) =
        AppRow(pkg, pkg.substringAfterLast('.'), false, app, data, cache, running, ram)

    @Test fun ranksByTotalDescendingWithUnknownLast() {
        val rows = listOf(row("a.small", 1, 1, 1), row("a.unknown", null, null, null), row("a.big", 100, 50, 10), row("a.mid", 10, 10, 10))
        assertEquals(listOf("a.big", "a.mid", "a.small", "a.unknown"), AppTable.sort(rows).map { it.pkg })
        assertEquals(160L, rows[2].totalBytes)
        assertNull("a partial sum is not a total", row("x.p", 5, null, 1).totalBytes)
        assertEquals(listOf("a.small", "a.mid", "a.big", "a.unknown"), AppTable.sort(rows, AppColumn.TOTAL, descending = false).map { it.pkg })
        assertEquals(listOf("a.big", "a.mid", "a.small", "a.unknown"), AppTable.sort(rows, AppColumn.NAME, descending = false).map { it.pkg })
        assertEquals(listOf("a.big", "a.mid", "a.small", "a.unknown"), AppTable.sort(rows, AppColumn.CACHE).map { it.pkg })
    }

    @Test fun formatsCellsAndDashesWhatItCannotRead() {
        assertEquals("—", AppTable.size(null))
        assertEquals("0 B", AppTable.size(0))
        assertEquals("1.5 KB", AppTable.size(1536))
        assertEquals("2.0 GB", AppTable.size(2L * 1024 * 1024 * 1024))
        assertEquals("—", AppTable.running(null, "yes", "no"))
        assertEquals("yes", AppTable.running(true, "yes", "no"))
        assertEquals("no", AppTable.running(false, "yes", "no"))
        assertEquals("—", AppTable.ram(null))
        assertEquals("100.0 MB", AppTable.ram(102400))
    }

    @Test fun withoutPermissionEverythingButOwnIsDash() {
        val noUsage = row("other.app", null, null, null)
        assertEquals(listOf("—", "—", "—", "—"), listOf(noUsage.totalBytes, noUsage.appBytes, noUsage.dataBytes, noUsage.cacheBytes).map(AppTable::size))
        assertEquals("—", AppTable.running(noUsage.running, "yes", "no"))
        assertEquals("—", AppTable.ram(noUsage.ramKb))
        // no shell answered: nothing is invented
        assertEquals(listOf(noUsage), AppTable.withShell(listOf(noUsage), null, null))
    }

    @Test fun parsesDumpsysMeminfoAndPs() {
        val meminfo = """
            Applications Memory Usage (in Kilobytes):
            Uptime: 1 Realtime: 1

            Total PSS by process:
                312,456K: com.android.systemui (pid 1234)
                 98,000K: com.foo (pid 2222 / activities)
                  2,000K: com.foo:remote (pid 2223)

            Total PSS by OOM adjustment:
                  1,000K: Native
        """.trimIndent()
        assertEquals(mapOf("com.android.systemui" to 312456L, "com.foo" to 100000L), AppTable.parseMeminfo(meminfo))
        assertEquals(setOf("com.foo", "com.bar", "init"), AppTable.parsePs("NAME\ninit\ncom.foo\ncom.foo:remote\ncom.bar\n"))
        val rows = AppTable.withShell(listOf(row("com.foo", 1, 1, 1), row("com.idle", 1, 1, 1)), setOf("com.foo"), mapOf("com.foo" to 100000L))
        assertEquals(true, rows[0].running); assertEquals(100000L, rows[0].ramKb)
        assertEquals(false, rows[1].running); assertNull(rows[1].ramKb)
    }
}
