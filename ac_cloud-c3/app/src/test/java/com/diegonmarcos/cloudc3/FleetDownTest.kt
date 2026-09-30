package com.diegonmarcos.cloudc3

import com.diegonmarcos.cloudc3.cloud.FleetDown
import com.diegonmarcos.cloudc3.cloud.FleetDown.Declared
import com.diegonmarcos.cloudc3.cloud.FleetDown.Report
import com.diegonmarcos.cloudc3.cloud.FleetDown.State
import com.diegonmarcos.cloudc3.cloud.OpsClient
import com.diegonmarcos.cloudc3.cloud.OpsClient.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Home down list's three guards, run on the exact [FleetDown] code the phone runs. The
 * bodies are the shapes c3-infra-api really answers (GET /health/deployed/{vm} is a
 * `docker ps` — RUNNING containers only; inspect answers `{state}` or a 502 carrying
 * docker's "No such object").
 */
class FleetDownTest {

    private val a1 = "oci-A1-f_0"
    private val e2 = "oci-E2-f_1"

    private fun deployed(vm: String, vararg running: Pair<String, String>): Outcome {
        val boxes = running.joinToString(",") { (n, s) -> "{\"name\":\"$n\",\"status\":\"$s\",\"image\":\"i\",\"ports\":\"\"}" }
        return Outcome.Ok("✓", "[{\"vm\":\"$vm\",\"alias\":\"x\",\"containers\":[$boxes]}]")
    }

    private val unreachable = Outcome.Failed(OpsClient.Kind.NETWORK, "Could not reach … — SocketTimeoutException")

    @Test
    fun `a declared container that exited appears under its VM with its state`() {
        val seen = mutableListOf<String>()
        val r = FleetDown.measure(
            listOf(Declared("gitea", a1), Declared("google-workspace-mcp", a1), Declared("never-created", a1)),
            deployed = { deployed(it, "gitea" to "Up 9 hours (healthy)") },
            stateOf = { _, n -> seen += n; if (n == "never-created") State.Missing else State.Docker("exited") },
        )
        assertTrue("an answering API must yield a measurement, got $r", r is Report.Measured)
        val down = (r as Report.Measured).down[a1].orEmpty()
        assertEquals("the exited AND the never-created container must both be listed, the running one not",
            listOf(FleetDown.Down("google-workspace-mcp", a1, State.Docker("exited")),
                   FleetDown.Down("never-created", a1, State.Missing)), down)
        assertEquals("only not-running containers are inspected", listOf("google-workspace-mcp", "never-created"), seen)
    }

    @Test
    fun `a restart-looping container is down even though docker ps lists it`() {
        val r = FleetDown.measure(listOf(Declared("loop", a1)),
            deployed = { deployed(it, "loop" to "Restarting (1) 4 seconds ago") },
            stateOf = { _, _ -> State.Docker("restarting") }) as Report.Measured
        assertEquals(listOf("loop"), r.down[a1].orEmpty().map { it.name })
    }

    @Test
    fun `an unreachable API renders the error state, never an empty list`() {
        val r = FleetDown.measure(listOf(Declared("gitea", a1), Declared("alerts-api", e2)),
            deployed = { unreachable }, stateOf = { _, _ -> State.Docker("exited") })
        assertTrue("no VM answered: this must be Unreachable, not a Measured list that reads as all-up; got $r",
            r is Report.Unreachable)
        assertEquals(OpsClient.Kind.NETWORK, (r as Report.Unreachable).kind)
    }

    @Test
    fun `a VM the API cannot list is named as unmeasured, not counted as up`() {
        val r = FleetDown.measure(listOf(Declared("gitea", a1), Declared("alerts-api", e2)),
            deployed = { vm ->
                if (vm == e2) Outcome.Ok("✓", """[{"vm":"$e2","containers":[],"error":"ssh: connect timed out"}]""")
                else deployed(vm, "gitea" to "Up 2 days")
            },
            stateOf = { _, _ -> State.Docker("exited") }) as Report.Measured
        assertEquals("ssh: connect timed out", r.blind[e2])
        assertTrue("a blind VM's containers are unmeasured, not down", r.down.isEmpty())
        assertEquals(1, r.declaredPerVm[e2])
    }

    @Test
    fun `an empty list means every declared container was seen running`() {
        val r = FleetDown.measure(listOf(Declared("gitea", a1)),
            deployed = { deployed(it, "gitea" to "Up 2 days") },
            stateOf = { _, _ -> error("a running container must not be inspected") }) as Report.Measured
        assertTrue(r.down.isEmpty() && r.blind.isEmpty())
        assertEquals(1, r.declared)
    }

    @Test
    fun `nothing declared is its own state, not an all-up fleet`() {
        assertTrue(FleetDown.measure(emptyList(), { unreachable }, { _, _ -> State.Missing }) is Report.NothingDeclared)
    }

    @Test
    fun `inspect answers map to docker's word, missing, or unread`() {
        assertEquals(State.Docker("exited"), FleetDown.stateFrom(Outcome.Ok("✓", """{"image":"i","state":"exited"}""")))
        assertEquals(State.Missing, FleetDown.stateFrom(Outcome.Failed(OpsClient.Kind.SERVER,
            "HTTP 502 — the ops API errored.\n{\"error\":\"Error: No such object: x\"}")))
        assertTrue(FleetDown.stateFrom(Outcome.Failed(OpsClient.Kind.UNAUTHORIZED, "HTTP 401")) is State.Unread)
    }

    @Test
    fun `an action's result is read back and a 2xx alone is not success`() {
        var reads = 0
        val stillExited = FleetDown.act("start", { Outcome.Ok("✓ container x: start") },
            { reads++; State.Docker("exited") })
        assertEquals("the state must be read back after the call", 1, reads)
        assertFalse("start answered 2xx but the box is still exited: that is a failure", stillExited.confirmed)
        assertEquals(State.Docker("exited"), stillExited.after)

        assertTrue(FleetDown.act("restart", { Outcome.Ok("✓") }, { State.Docker("running") }).confirmed)
        assertTrue(FleetDown.act(FleetDown.STOP, { Outcome.Ok("✓") }, { State.Docker("exited") }).confirmed)
        assertFalse(FleetDown.act(FleetDown.STOP, { Outcome.Ok("✓") }, { State.Docker("running") }).confirmed)
    }

    @Test
    fun `a failed call is still read back and never confirmed`() {
        var reads = 0
        val r = FleetDown.act("start", { unreachable }, { reads++; State.Docker("running") })
        assertEquals("a timed-out start may still have started the box, so read it back", 1, reads)
        assertFalse(r.confirmed)
    }
}
