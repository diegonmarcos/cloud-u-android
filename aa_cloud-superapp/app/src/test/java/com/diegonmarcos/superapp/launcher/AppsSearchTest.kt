package com.diegonmarcos.superapp.launcher

import com.diegonmarcos.superapp.launcher.AppsSearch.Group
import com.diegonmarcos.superapp.launcher.AppsSearch.Item
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Cloud ▸ Apps search: what a query keeps, in what order, and what Go launches.
 * Shaped like the real page — declared rows, the Actions ring, then fleet apps by folder.
 */
class AppsSearchTest {

    private fun g(title: String, vararg labels: String) =
        Group(title, labels.map { Item(it, "$title/$it") })

    private val page = listOf(
        g("Inboxes", "Mail", "Chat", "Matrix"),
        g("AGI", "Search", "Code", "B-LLM", "MyTerminal"),
        g("Configs", "SuperApp", "Store", "Vault"),
        g("Actions", "Update all", "Lock screen"),
        g("@Network", "Cloud Mesh", "Télécom"),
    )

    private fun labels(r: AppsSearch.Result<String>) = r.groups.map { gr -> gr.title to gr.items.map { it.label } }

    @Test fun `blank query filters nothing and is not active`() {
        assertFalse(AppsSearch.isActive("   "))
        assertTrue(AppsSearch.filter(page, "  ").isEmpty)
    }

    @Test fun `a label prefix outranks a match inside a label`() {
        // "ma": Mail and Matrix start with it; no other label or group name holds it.
        val r = AppsSearch.filter(page, "ma")
        assertEquals(listOf("Inboxes" to listOf("Mail", "Matrix")), labels(r))
        assertEquals("Mail", r.top?.label)
    }

    @Test fun `prefix first, then word prefix, then contains, inside one group`() {
        val r = AppsSearch.filter(listOf(g("Mixed", "Escort", "Cloud Search", "Searcher")), "sear")
        // Escort does not contain "sear"; Searcher is a prefix; Cloud Search a word prefix.
        assertEquals(listOf("Searcher", "Cloud Search"), r.groups.single().items.map { it.label })
        val c = AppsSearch.filter(listOf(g("Mixed", "Research", "Seal", "Sea Map")), "sea")
        assertEquals(listOf("Seal", "Sea Map", "Research"), c.groups.single().items.map { it.label })
        assertEquals(AppsSearch.LABEL_CONTAINS, AppsSearch.rank("sea", "Research", "Mixed"))
    }

    @Test fun `case and accents are ignored on both sides`() {
        assertEquals(listOf("@Network" to listOf("Télécom")), labels(AppsSearch.filter(page, "TELE")))
        assertEquals(listOf("@Network" to listOf("Télécom")), labels(AppsSearch.filter(page, "télé")))
        assertEquals("agi", AppsSearch.fold("  ÁGÍ "))
    }

    @Test fun `a group name match lists the whole group, after label matches`() {
        val r = AppsSearch.filter(page, "config")
        assertEquals(listOf("Configs" to listOf("SuperApp", "Store", "Vault")), labels(r))
        assertEquals(AppsSearch.GROUP_MATCH, AppsSearch.rank("config", "Vault", "Configs"))
        // "net": no label has it, the @Network folder name does.
        assertEquals(listOf("@Network" to listOf("Cloud Mesh", "Télécom")), labels(AppsSearch.filter(page, "net")))
    }

    @Test fun `empty groups are hidden and groups order by their best hit`() {
        // "lo": Lock screen starts with it (Actions), Cloud Mesh only contains it (@Network);
        // Inboxes, AGI and Configs have no hit and are not drawn.
        val r = AppsSearch.filter(page, "lo")
        assertEquals(listOf("Actions" to listOf("Lock screen"), "@Network" to listOf("Cloud Mesh")), labels(r))
        assertEquals("Lock screen", r.top?.label)
    }

    @Test fun `declared order holds among equal ranks`() {
        // "s": AGI and Configs both hold a prefix hit; AGI is declared first.
        val r = AppsSearch.filter(page, "s")
        assertEquals("AGI", r.groups.first().title)
        assertEquals(listOf("SuperApp", "Store"), r.groups.first { it.title == "Configs" }.items.map { it.label }.take(2))
    }

    @Test fun `no match is an empty result with no top — the Cloud Search row's case`() {
        val r = AppsSearch.filter(page, "zzqx")
        assertTrue(r.isEmpty)
        assertNull(r.top)
        assertTrue(r.groups.isEmpty())
    }
}
