package com.diegonmarcos.cloudc3

import com.diegonmarcos.cloudc3.pages.RoutesTableModel as M
import com.diegonmarcos.cloudc3.pages.RoutesTableModel.Column
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Containers > Table: sort, filters, count line, persisted state, and the shipped slice's rows. */
class RoutesTableModelTest {

    private fun row(name: String, auth: String = "woauth", vm: String = "vm-a", pub: String = "$name.example.com", cat: String = "app", addr: String = "10.0.0.6:80") =
        M.Row(cat, name, pub, auth, "$name.app:80", vm, addr)

    private val rows = listOf(
        row("charlie", "bearer", "vm-b", cat = "tools"),
        row("alpha", "woauth", "vm-a", cat = "app"),
        row("bravo", "mesh", "vm-a", pub = "", cat = "data"),
        row("delta", "mesh", "vm-10", pub = "", cat = "sec", addr = ""),
        row("echo", "open", "vm-2", cat = "obs"),
    )

    private fun names(s: M.State) = M.view(rows, s).map { it.container }

    @Test
    fun `default is sorted by container ascending`() {
        assertEquals(listOf("alpha", "bravo", "charlie", "delta", "echo"), names(M.State()))
    }

    @Test
    fun `sorting by any column works, both directions`() {
        for (c in Column.entries) {
            val asc = M.view(rows, M.State(c, true)).map { it.text(c) }.filter { it.isNotBlank() }
            val desc = M.view(rows, M.State(c, false)).map { it.text(c) }.filter { it.isNotBlank() }
            assertEquals("column $c: descending is ascending reversed", asc.sortedWith(M::naturalCompare), asc)
            assertEquals(asc.reversed(), desc)
        }
    }

    @Test
    fun `a header tap sorts ascending, the same header again flips it`() {
        val a = M.toggleSort(M.State(), Column.CATEGORY)
        assertEquals(Column.CATEGORY, a.sort); assertTrue(a.ascending)
        val b = M.toggleSort(a, Column.CATEGORY)
        assertEquals(Column.CATEGORY, b.sort); assertTrue(!b.ascending)
        val c = M.toggleSort(b, Column.VM_NAME)
        assertEquals(Column.VM_NAME, c.sort); assertTrue("a new column starts ascending", c.ascending)
    }

    @Test
    fun `blank cells sort last in both directions`() {
        assertEquals(listOf("alpha", "charlie", "echo"), M.view(rows, M.State(Column.PUBLIC_URL, true)).map { it.container }.take(3))
        assertEquals(listOf("echo", "charlie", "alpha"), M.view(rows, M.State(Column.PUBLIC_URL, false)).map { it.container }.take(3))
        assertEquals("delta", M.view(rows, M.State(Column.VM_ADDR, true)).last().container)
        assertEquals("delta", M.view(rows, M.State(Column.VM_ADDR, false)).last().container)
    }

    @Test
    fun `numbers inside text compare as numbers`() {
        assertTrue(M.naturalCompare("vm-2", "vm-10") < 0)
        assertTrue(M.naturalCompare("10.0.0.6:80", "10.0.0.6:8010") < 0)
        assertTrue(M.naturalCompare("Alpha", "alpha") == 0)
        assertEquals(listOf("vm-2", "vm-10", "vm-a", "vm-b"), M.vmOptions(rows))
    }

    @Test
    fun `auth filter is a multi-select, empty means all`() {
        assertEquals(5, M.view(rows, M.State()).size)
        assertEquals(listOf("charlie"), names(M.State(auth = setOf("bearer"))))
        assertEquals(listOf("alpha", "charlie"), names(M.State(auth = setOf("bearer", "woauth"))))
        assertEquals(listOf("bravo", "delta"), names(M.State(auth = setOf("mesh"))))
    }

    @Test
    fun `vm filter is a multi-select and combines with the auth filter`() {
        assertEquals(listOf("alpha", "bravo"), names(M.State(vms = setOf("vm-a"))))
        assertEquals(listOf("alpha", "bravo", "charlie"), names(M.State(vms = setOf("vm-a", "vm-b"))))
        assertEquals(listOf("bravo"), names(M.State(auth = setOf("mesh"), vms = setOf("vm-a"))))
        assertEquals(emptyList<String>(), names(M.State(auth = setOf("open"), vms = setOf("vm-a"))))
    }

    @Test
    fun `toggling a chip adds then removes it`() {
        val on = M.toggleAuth(M.State(), "mesh")
        assertEquals(setOf("mesh"), on.auth)
        assertEquals(emptySet<String>(), M.toggleAuth(on, "mesh").auth)
        assertEquals(setOf("vm-a"), M.toggleVm(M.State(), "vm-a").vms)
    }

    @Test
    fun `the count line says how many rows are shown`() {
        assertEquals("5 rows", M.countLine(5, 5))
        assertEquals("2 of 5 rows", M.countLine(2, 5))
    }

    @Test
    fun `the open option appears only when a row is in it`() {
        assertEquals(listOf("bearer", "woauth", "mesh", "open"), M.authOptions(rows).map { it.id })
        assertEquals(listOf("bearer", "woauth", "mesh"), M.authOptions(rows.filter { it.authType != "open" }).map { it.id })
        assertEquals(listOf("Bearer", "WOAuth", "Mesh-only"), M.authOptions(emptyList()).map { it.label })
    }

    @Test
    fun `state survives encode and decode, and garbage decodes to the default`() {
        val s = M.State(Column.VM_ADDR, false, setOf("bearer", "mesh"), setOf("vm-a", "vm-2"))
        assertEquals(s, M.decode(M.encode(s)))
        assertEquals(M.State(), M.decode(M.encode(M.State())))
        for (bad in listOf(null, "", "x", "a;b;c;d", "container;sideways;;", "nope;asc;;;extra")) assertEquals(M.State(), M.decode(bad))
    }

    @Test
    fun `the shipped slice parses into rows the table can show`() {
        var dir = File("").absoluteFile
        while (dir.parentFile != null && !File(dir, "data/infra_slice.json").isFile) dir = dir.parentFile
        val slice = Json.parseToJsonElement(File(dir, "data/infra_slice.json").readText()).jsonObject
        val shipped = M.parse(slice["routes"])
        assertTrue("routes missing from data/infra_slice.json", shipped.size > 50)
        assertTrue(shipped.all { it.authType in M.Auth.entries.map { a -> a.id } })
        assertTrue("a row with no public URL can only be mesh-only", shipped.filter { it.publicUrl.isBlank() }.all { it.authType == "mesh" })
        assertEquals(shipped.size, M.view(shipped, M.State()).size)
        assertEquals(emptyList<M.Row>(), M.parse(null))
    }
}
