package com.diegonmarcos.cloudc3

import com.diegonmarcos.cloudc3.pages.ProbeModel
import com.diegonmarcos.cloudc3.pages.ProbeModel.Leg
import com.diegonmarcos.cloudc3.pages.ProbeModel.Light
import com.diegonmarcos.cloudc3.pages.ProbeModel.RowProbe
import com.diegonmarcos.cloudc3.pages.ProbeModel.Status
import com.diegonmarcos.cloudc3.pages.RoutesTableModel as M
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The status light: which legs a row has, the green / yellow / red / grey rules, the off-mesh verdict, freshness. */
class ProbeModelTest {

    private val full = M.Row("app", "gitea", "git.example.com", "woauth", "gitea.mesh.diegonmarcos.com:3002", "vm-a", "10.0.0.6:3002")
    private val meshOnly = M.Row("data", "db", "", "mesh", "db.app:5432", "vm-a", "10.0.0.6:5432")
    private val noAddr = M.Row("tools", "cli", "", "mesh", "cli", "local", "")

    private fun r(leg: Leg, st: Status, at: Long = 1000L) = ProbeModel.LegResult(leg, st, "t", checkedAt = at)
    private fun probe(vararg p: Pair<Leg, Status>, down: Boolean = false) =
        RowProbe(p.associate { (l, s) -> l to r(l, s) }, down)

    @Test
    fun `a row has the legs it has an address for`() {
        assertEquals(mapOf(Leg.PUBLIC to "https://git.example.com", Leg.PRIVATE to "http://gitea.mesh.diegonmarcos.com:3002", Leg.DIRECT to "http://10.0.0.6:3002"),
            ProbeModel.legsOf(full))
        assertEquals(setOf(Leg.PRIVATE, Leg.DIRECT), ProbeModel.legsOf(meshOnly).keys)
        assertEquals("a private name with no address and no VM address has no leg", emptySet<Leg>(), ProbeModel.legsOf(noAddr).keys)
    }

    @Test
    fun `green when every leg answered`() {
        assertEquals(Light.GREEN, ProbeModel.light(full, probe(Leg.PUBLIC to Status.UP, Leg.PRIVATE to Status.UP, Leg.DIRECT to Status.UP)))
        assertEquals(Light.GREEN, ProbeModel.light(meshOnly, probe(Leg.PRIVATE to Status.UP, Leg.DIRECT to Status.UP)))
    }

    @Test
    fun `yellow when some answered`() {
        assertEquals(Light.YELLOW, ProbeModel.light(full, probe(Leg.PUBLIC to Status.UP, Leg.PRIVATE to Status.DOWN, Leg.DIRECT to Status.UP)))
        assertEquals(Light.YELLOW, ProbeModel.light(meshOnly, probe(Leg.PRIVATE to Status.UP, Leg.DIRECT to Status.DOWN)))
    }

    @Test
    fun `red when none answered, when the public url is down, or when the fleet says the container is down`() {
        assertEquals(Light.RED, ProbeModel.light(full, probe(Leg.PUBLIC to Status.DOWN, Leg.PRIVATE to Status.DOWN, Leg.DIRECT to Status.DOWN)))
        assertEquals("public down is red even when the mesh legs answer", Light.RED,
            ProbeModel.light(full, probe(Leg.PUBLIC to Status.DOWN, Leg.PRIVATE to Status.UP, Leg.DIRECT to Status.UP)))
        assertEquals(Light.RED, ProbeModel.light(meshOnly, probe(Leg.PRIVATE to Status.DOWN, Leg.DIRECT to Status.DOWN)))
        assertEquals(Light.RED, ProbeModel.light(full, probe(Leg.PUBLIC to Status.UP, Leg.PRIVATE to Status.UP, Leg.DIRECT to Status.UP, down = true)))
    }

    @Test
    fun `grey when unchecked, partly unchecked, or nothing is probe-able from here`() {
        assertEquals(Light.GREY, ProbeModel.light(full, null))
        assertEquals(Light.GREY, ProbeModel.light(full, RowProbe()))
        assertEquals("a leg still being checked", Light.GREY, ProbeModel.light(full, probe(Leg.PUBLIC to Status.UP)))
        assertEquals(Light.GREY, ProbeModel.light(noAddr, RowProbe()))
        assertEquals("only unreachable legs", Light.GREY, ProbeModel.light(meshOnly, probe(Leg.PRIVATE to Status.UNREACHABLE, Leg.DIRECT to Status.UNREACHABLE)))
    }

    @Test
    fun `an unreachable leg is left out of the count, never red`() {
        assertEquals(Light.GREEN, ProbeModel.light(full, probe(Leg.PUBLIC to Status.UP, Leg.PRIVATE to Status.UNREACHABLE, Leg.DIRECT to Status.UNREACHABLE)))
        assertEquals(Light.RED, ProbeModel.light(full, probe(Leg.PUBLIC to Status.DOWN, Leg.PRIVATE to Status.UNREACHABLE, Leg.DIRECT to Status.UNREACHABLE)))
    }

    @Test
    fun `off the mesh the private and direct legs turn unreachable, on the mesh they stay down`() {
        val offMesh = mapOf("a" to probe(Leg.PUBLIC to Status.UP, Leg.PRIVATE to Status.DOWN, Leg.DIRECT to Status.DOWN),
            "b" to probe(Leg.PRIVATE to Status.DOWN, Leg.DIRECT to Status.DOWN))
        val judged = ProbeModel.applyMeshVerdict(offMesh)
        assertEquals(Status.UP, judged.getValue("a").legs.getValue(Leg.PUBLIC).status)
        assertTrue(judged.values.flatMap { it.legs.values }.filter { it.leg != Leg.PUBLIC }.all { it.status == Status.UNREACHABLE })
        assertTrue(judged.getValue("b").legs.getValue(Leg.PRIVATE).error.contains("unreachable from here"))
        assertEquals(Light.GREEN, ProbeModel.light(full.copy(privateUrl = "", vmAddr = ""), judged.getValue("a")))
        val onMesh = offMesh + ("c" to probe(Leg.PRIVATE to Status.UP, Leg.DIRECT to Status.UP))
        assertEquals("one answered mesh leg anywhere: down stays down", onMesh, ProbeModel.applyMeshVerdict(onMesh))
        assertEquals(emptyMap<String, RowProbe>(), ProbeModel.applyMeshVerdict(emptyMap()))
        val publicOnly = mapOf("p" to probe(Leg.PUBLIC to Status.DOWN))
        assertEquals("public legs are never judged by the mesh", publicOnly, ProbeModel.applyMeshVerdict(publicOnly))
    }

    @Test
    fun `results age out`() {
        val res = r(Leg.PUBLIC, Status.UP, at = 10_000)
        assertTrue(ProbeModel.isFresh(res, now = 10_000 + 1000))
        assertTrue(!ProbeModel.isFresh(res, now = 10_000 + ProbeModel.DEFAULT_TTL_MS + 1))
        assertTrue("never checked is not fresh", !ProbeModel.isFresh(r(Leg.PUBLIC, Status.UP, at = 0), now = 5))
    }

    @Test
    fun `status sorts worst first and filters by light`() {
        val rows = listOf(full.copy(container = "g"), full.copy(container = "y"), full.copy(container = "r"), full.copy(container = "n"))
        val light = { row: M.Row -> mapOf("g" to Light.GREEN, "y" to Light.YELLOW, "r" to Light.RED, "n" to Light.GREY).getValue(row.container) }
        assertEquals(listOf("r", "y", "g", "n"), M.view(rows, M.State(M.Column.STATUS, true), light).map { it.container })
        assertEquals(listOf("n", "g", "y", "r"), M.view(rows, M.State(M.Column.STATUS, false), light).map { it.container })
        assertEquals(listOf("r", "y"), M.view(rows, M.State(M.Column.STATUS, true, lights = setOf("red", "yellow")), light).map { it.container })
        assertEquals(listOf("g"), M.view(rows, M.State(lights = setOf("green"), query = "g"), light).map { it.container })
        val s = M.State(M.Column.STATUS, false, lights = setOf("grey", "red"))
        assertEquals(s, M.decode(M.encode(s)))
        assertNotEquals(M.State(), M.decode(M.encode(s)))
    }

    @Test
    fun `the sheet's summary says why`() {
        assertTrue(ProbeModel.summary(Light.RED, probe(down = true)).contains("not running"))
        assertTrue(ProbeModel.summary(Light.GREY, null).contains("Not checked"))
        assertTrue(ProbeModel.summary(Light.GREEN, probe()).contains("answered"))
    }
}
