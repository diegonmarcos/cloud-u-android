package com.diegonmarcos.cloudc3.pages

import com.diegonmarcos.cloudc3.pages.RoutesTableModel.Column
import com.diegonmarcos.cloudc3.pages.RoutesTableModel.OpenTarget
import com.diegonmarcos.cloudc3.pages.RoutesTableModel.Row

/**
 * The Table's status light, as pure data and rules (no network here, so the JVM suite drives every rule).
 * Each row has up to three LEGS - its public URL, its private (mesh) URL and the direct VM IP:PORT - and a probe
 * result per leg; [light] folds them into one colour:
 *
 *  - GREEN  every probe-able leg answered
 *  - YELLOW some answered, some did not
 *  - RED    none answered, or the public URL is down, or the fleet API says the container is not running
 *  - GREY   not probed yet, or nothing probe-able from here (an off-mesh phone leaves private/direct UNREACHABLE,
 *           and a leg that is unreachable from here is neither up nor down: it is left out of the count)
 *
 * A leg "answers" when it returns an HTTP reply of ANY status inside the timeout: a 302 to the login or a 401 from
 * the gate is UP for a public URL.
 */
object ProbeModel {

    enum class Leg(val id: String, val label: String) {
        PUBLIC("public", "Public URL"),
        PRIVATE("private", "Private URL"),
        DIRECT("direct", "Direct VM IP:PORT"),
    }

    enum class Status { UP, DOWN, UNREACHABLE }

    /** One probe of one leg. [http] is the reply's status line code; [error] is blank when UP. */
    data class LegResult(
        val leg: Leg,
        val status: Status,
        val target: String,
        val dns: String = "",
        val http: Int? = null,
        val latencyMs: Long? = null,
        val error: String = "",
        val checkedAt: Long = 0L,
        val source: String = SOURCE_DEVICE,
    )

    /** What is known about a row: its legs' results and whether the fleet API says its container is down. */
    data class RowProbe(val legs: Map<Leg, LegResult> = emptyMap(), val containerDown: Boolean = false)

    enum class Light(val id: String, val label: String) {
        RED("red", "Red"),
        YELLOW("yellow", "Yellow"),
        GREEN("green", "Green"),
        GREY("grey", "Grey"),
    }

    const val SOURCE_DEVICE = "device"
    const val SOURCE_FLEET = "fleet"

    /** The legs a row HAS, each with the URL to probe. A leg with nothing to probe is absent. */
    fun legsOf(row: Row): Map<Leg, String> = buildMap {
        (RoutesTableModel.openTarget(Column.PUBLIC_URL, row.publicUrl) as? OpenTarget.Url)?.let { put(Leg.PUBLIC, it.url) }
        (RoutesTableModel.openTarget(Column.PRIVATE_URL, row.privateUrl) as? OpenTarget.Url)?.let { put(Leg.PRIVATE, it.url) }
        (RoutesTableModel.openTarget(Column.VM_ADDR, row.vmAddr) as? OpenTarget.Url)?.let { put(Leg.DIRECT, it.url) }
    }

    fun light(row: Row, probe: RowProbe?): Light {
        if (probe == null) return Light.GREY
        if (probe.containerDown) return Light.RED
        val legs = legsOf(row).keys
        if (legs.isEmpty()) return Light.GREY
        val results = legs.map { probe.legs[it] ?: return Light.GREY }   // a leg still being checked: not decided yet
        if (probe.legs[Leg.PUBLIC]?.status == Status.DOWN) return Light.RED
        val counted = results.filter { it.status != Status.UNREACHABLE }
        if (counted.isEmpty()) return Light.GREY
        val up = counted.count { it.status == Status.UP }
        return when {
            up == counted.size -> Light.GREEN
            up == 0 -> Light.RED
            else -> Light.YELLOW
        }
    }

    /**
     * Off-mesh detection over a whole run: if private/direct legs were probed and NONE of them answered anywhere, the
     * phone is not on the mesh, so every such DOWN leg becomes UNREACHABLE ("unreachable from here") instead of
     * painting the whole fleet red. One answered leg anywhere means the mesh is there and DOWN stays DOWN.
     */
    fun applyMeshVerdict(all: Map<String, RowProbe>, offMeshNote: String = "unreachable from here (device is off the mesh)"): Map<String, RowProbe> {
        val mesh = all.values.flatMap { p -> p.legs.values.filter { it.leg != Leg.PUBLIC } }
        if (mesh.isEmpty() || mesh.any { it.status == Status.UP }) return all
        return all.mapValues { (_, p) ->
            p.copy(legs = p.legs.mapValues { (leg, r) ->
                if (leg != Leg.PUBLIC && r.status == Status.DOWN) r.copy(status = Status.UNREACHABLE, error = offMeshNote) else r
            })
        }
    }

    /** Aging: a result older than [ttlMs] is stale and is probed again on the next refresh. */
    fun isFresh(r: LegResult, now: Long, ttlMs: Long = DEFAULT_TTL_MS): Boolean = r.checkedAt > 0 && now - r.checkedAt in 0..ttlMs

    const val DEFAULT_TTL_MS = 5 * 60_000L

    /** The sort key of a light: worst first when ascending. */
    fun rank(l: Light): Int = l.ordinal

    /** The one-line summary the sheet shows under the colour. */
    fun summary(light: Light, probe: RowProbe?): String = when {
        probe?.containerDown == true -> "The fleet API reports this container is not running"
        light == Light.GREEN -> "Every checked endpoint answered"
        light == Light.YELLOW -> "Some endpoints answered, some did not"
        light == Light.RED -> "No endpoint answered, or the public URL is down"
        probe == null -> "Not checked yet"
        else -> "Nothing to check from here"
    }
}
