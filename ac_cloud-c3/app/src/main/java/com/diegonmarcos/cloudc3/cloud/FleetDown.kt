package com.diegonmarcos.cloudc3.cloud

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * Every DECLARED container that is not running, fleet-wide — what the Home page shows.
 *
 * Compose runs the fleet with restart "no" on purpose, so a container that exits stays
 * exited until a human notices. One did, for days, with nothing surfacing it. This is the
 * surface: DECLARED (data/services_*.json on a VM data/mesh.json names) minus RUNNING
 * (c3-infra-api GET /health/deployed/{vm}, a `docker ps` per VM), so a container that
 * crashed, one that was created and never started, and one that does not exist at all
 * all appear — the last two are exactly what a `docker ps -a` listing alone cannot show.
 *
 * THREE OUTCOMES, NEVER TWO. [Report.Unreachable] (no VM answered), [Report.Measured]
 * with VMs the API could not see into listed in `blind`, and an empty Measured — which
 * therefore means every declared container on every declared VM was SEEN running. An
 * unreachable API rendering as an empty list is the false "all good" this type exists to
 * make unrepresentable.
 *
 * The comparison ([measure]) and the action verdict ([act]) are pure and take JSON text
 * and functions, so FleetDownTest runs the exact code the phone runs, with no network.
 */
object FleetDown {

    data class Declared(val name: String, val vm: String)

    /** What the API says a not-running container is. */
    sealed class State {
        /** docker's own word from inspect: exited, created, dead, restarting, running… */
        data class Docker(val word: String) : State()
        /** inspect answered "No such object": declared, never created (or removed). */
        object Missing : State()
        /** The state could not be read; [why] is the API's own failure line. */
        data class Unread(val why: String) : State()
    }

    data class Down(val name: String, val vm: String, val state: State)

    sealed class Report {
        data class Unreachable(val kind: OpsClient.Kind, val detail: String) : Report()
        /** No container is declared on any mesh VM — a broken bake, not a healthy fleet. */
        object NothingDeclared : Report()
        data class Measured(
            val declared: Int,
            /** Down containers grouped by VM, VMs in name order. */
            val down: Map<String, List<Down>>,
            /** VM → the API's reason it could not list that VM; its containers are unmeasured. */
            val blind: Map<String, String>,
            /** VM → how many declared containers sit there, for the blind VMs' captions. */
            val declaredPerVm: Map<String, Int>,
        ) : Report()
    }

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * [deployed] is asked once per declared VM and returns that VM's /health/deployed/{vm}
     * outcome; [stateOf] reads one not-running container's state. Running means docker's
     * status starts with "Up" — `docker ps` also lists Restarting, which is not up (the
     * same rule c3-infra-api's own drift applies).
     */
    fun measure(
        declared: List<Declared>,
        deployed: (vm: String) -> OpsClient.Outcome,
        stateOf: (vm: String, name: String) -> State,
    ): Report {
        if (declared.isEmpty()) return Report.NothingDeclared
        val byVm = declared.groupBy { it.vm }.toSortedMap()
        val down = sortedMapOf<String, List<Down>>()
        val blind = sortedMapOf<String, String>()
        val httpFailures = mutableListOf<OpsClient.Outcome.Failed>()

        for ((vm, boxes) in byVm) {
            val outcome = deployed(vm)
            if (outcome is OpsClient.Outcome.Failed) {
                httpFailures += outcome
                blind[vm] = "${outcome.kind}: ${outcome.message.lineSequence().first()}"
                continue
            }
            val running = when (val r = runningOn(vm, (outcome as OpsClient.Outcome.Ok).body)) {
                is Listing.Seen -> r.names
                is Listing.Blind -> { blind[vm] = r.why; continue }
            }
            boxes.filter { it.name !in running }
                .map { Down(it.name, vm, stateOf(vm, it.name)) }
                .takeIf { it.isNotEmpty() }
                ?.let { down[vm] = it }
        }

        // Every VM failed at the HTTP layer: the API itself is not answering us. (A VM the
        // API answered for with an ssh error is the API working, and stays a blind VM.)
        if (httpFailures.size == byVm.size) {
            return Report.Unreachable(httpFailures.first().kind, httpFailures.first().message)
        }
        return Report.Measured(declared.size, down, blind, byVm.mapValues { it.value.size })
    }

    private sealed class Listing {
        data class Seen(val names: Set<String>) : Listing()
        data class Blind(val why: String) : Listing()
    }

    /** One /health/deployed/{vm} body: `[{vm, containers:[{name,status}], error?}]`. */
    private fun runningOn(vm: String, body: String): Listing {
        val entry = runCatching {
            json.parseToJsonElement(body).jsonArray.map { it.jsonObject }.firstOrNull { it.str("vm") == vm }
        }.getOrElse { return Listing.Blind("unparseable answer: ${body.take(120)}") }
            ?: return Listing.Blind("the API returned no entry for $vm")
        entry.str("error")?.let { return Listing.Blind(it.trim().ifBlank { "error with no message" }) }
        val names = (entry["containers"] as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .filter { it.str("status").orEmpty().startsWith("Up") }
            .mapNotNull { it.str("name") }
        return Listing.Seen(names.toSet())
    }

    /** One inspect answer. Ok → docker's word; "No such object" → [State.Missing]. */
    fun stateFrom(outcome: OpsClient.Outcome): State = when (outcome) {
        is OpsClient.Outcome.Ok -> runCatching {
            json.parseToJsonElement(outcome.body).jsonObject.str("state")
        }.getOrNull()?.let { State.Docker(it) } ?: State.Unread(outcome.body.take(120))
        is OpsClient.Outcome.Failed ->
            if (outcome.message.contains(NO_SUCH)) State.Missing
            else State.Unread("${outcome.kind}: ${outcome.message.lineSequence().first()}")
    }

    /** docker's inspect error for a name it has no container for, relayed by the API's 502. */
    private const val NO_SUCH = "No such object"

    // ── actions ──────────────────────────────────────────────────────────

    /** An action's REAL outcome: the call, then the state read back after it. */
    data class ActionResult(val call: OpsClient.Outcome, val after: State, val confirmed: Boolean)

    /**
     * Runs [call] and then ALWAYS [readBack] — also after a failed call, because a timed-out
     * start can still have started the box. [confirmed] is true only when the call succeeded
     * AND the read-back shows what the action promised: stopped for stop, running for the
     * rest. A 2xx with the box still exited is reported as the failure it is.
     */
    fun act(action: String, call: () -> OpsClient.Outcome, readBack: () -> State): ActionResult {
        val r = call()
        val after = readBack()
        val running = after is State.Docker && after.word == RUNNING
        val promised = if (action == STOP) after is State.Docker && !running else running
        return ActionResult(r, after, r is OpsClient.Outcome.Ok && promised)
    }

    const val STOP = "stop"
    private const val RUNNING = "running"

    // ── the network half (OpsClient) ─────────────────────────────────────

    /** The declared estate: every service entry whose vm is a node of the declared mesh. */
    fun declared(): List<Declared> {
        val vms = Stacks.mesh().nodes.map { it.name }.toSet()
        return (Services.publicServices().map { Declared(it.name, it.vm) } +
            Services.privateServices().map { Declared(it.name, it.vm) })
            .filter { it.vm in vms }
            .distinct()
    }

    fun state(vm: String, name: String, bearer: String): State =
        stateFrom(OpsClient.get("/vms/$vm/containers/$name/inspect", bearer, "inspect $name"))

    /** Blocking; call off the main thread. One /health/deployed/{vm} per declared VM:
     *  the fleet-wide route waits on every VM's SSH timeout and outlasts OpsClient's read. */
    fun load(bearer: String): Report = measure(
        declared(),
        deployed = { vm -> OpsClient.get("/health/deployed/$vm", bearer, "containers on $vm") },
        stateOf = { vm, name -> state(vm, name, bearer) },
    )

    /** The container's recent log, ANSI colour codes stripped. */
    fun logs(vm: String, name: String, bearer: String): OpsClient.Outcome =
        when (val r = OpsClient.get("/files/logs/$vm/$name?lines=$LOG_LINES", bearer, "logs $name")) {
            is OpsClient.Outcome.Failed -> r
            is OpsClient.Outcome.Ok -> {
                val text = runCatching { json.parseToJsonElement(r.body).jsonObject.str("content") }
                    .getOrNull() ?: r.body
                OpsClient.Outcome.Ok(r.message, text.replace(ANSI, ""))
            }
        }

    // ponytail: fixed tail length; make it a build.json knob if a longer read is ever needed.
    private const val LOG_LINES = 200
    private val ANSI = Regex("\u001B\\[[0-9;]*m")

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
