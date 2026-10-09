package com.diegonmarcos.superapp.adbdebug

/** Who launches the local server in Local server mode: the embedded adb session, or Shizuku. */
enum class LaunchVia(val id: String, val title: String) {
    ADB("adb", "Embedded adb"),
    SHIZUKU("shizuku", "Shizuku");

    companion object { fun parse(id: String?): LaunchVia = values().firstOrNull { it.id == id } ?: ADB }
}

/** One step of the Connect sequence. */
enum class StepId(val label: String) {
    DEV_OPTIONS("Developer options on"),
    WIRELESS_DEBUG("Wireless debugging on"),
    PAIR("Pair with code"),
    ADB_CONNECT("Connect embedded adb (mDNS)"),
    SHIZUKU_RUNNING("Shizuku running"),
    SHIZUKU_PERMISSION("Shizuku permission"),
    START_SERVER("Start local server"),
    VERIFY("Round trip"),
}

enum class StepState { PENDING, RUNNING, DONE, SKIPPED, WAITING, FAILED }

data class StepRun(val id: StepId, val state: StepState = StepState.PENDING, val detail: String = "")

/** What a step's executor reports. [needsUser] = the step started something only the person can finish. */
data class StepResult(val ok: Boolean, val detail: String = "", val needsUser: Boolean = false)

/** The device side of the sequence. Fakes stand in for it in tests. */
interface ConnectExecutor {
    /** A fresh reading, taken before each step so an already-satisfied step is skipped. */
    fun facts(): ChannelFacts
    fun run(step: StepId): StepResult
}

enum class ConnectStatus { SUCCESS, FAILED, WAITING_FOR_USER }

data class ConnectOutcome(
    val steps: List<StepRun>,
    val status: ConnectStatus,
    val failedStep: StepId? = null,
    /** Which step stopped the run and what to do about it; null on success. */
    val explanation: String? = null,
)

/** One way to connect in a mode, as the page lists it. */
data class Method(val title: String, val detail: String)

/**
 * The Connect sequence as a step machine. [steps] decides WHICH steps a mode runs (the method that
 * applies); [run] walks them in order, skipping the ones already true, stopping at the first one that
 * fails or needs the person, and saying which step it was and why.
 */
object ConnectPlan {

    /** The methods that apply to [mode] (the page shows these and nothing else). */
    fun methods(mode: ChannelMode, via: LaunchVia, ownsServer: Boolean = true): List<Method> {
        val pair = Method("Pair with code", "Once: type the code into the notification (Developer options > Wireless debugging > Pair device with pairing code).")
        val mdns = Method("Connect via mDNS", "Finds the wireless-debugging port by itself; no port to type.")
        return when {
            mode == ChannelMode.SHIZUKU ->
                listOf(Method("Shizuku only", "Start the Shizuku service in its own app, then allow this app; nothing else is used."))
            // An app with no server of its own (a terminal) has nothing to launch: it connects the embedded way.
            mode == ChannelMode.EMBEDDED_ONLY || !ownsServer -> listOf(pair, mdns)
            mode == ChannelMode.AUTO ->
                listOf(pair, mdns, Method("Launch the server via adb", "Started through the adb session when it is up; Shizuku is the last fallback."))
            via == LaunchVia.SHIZUKU ->
                listOf(Method("Launch the server via Shizuku", "Shizuku runs the one launch line; commands then run on the loopback server."))
            else -> listOf(pair, mdns,
                Method("Launch the server via adb", "One launch line over adb; commands then run on the loopback server."))
        }
    }

    /** The steps [mode] runs, in order. */
    fun steps(mode: ChannelMode, via: LaunchVia, ownsServer: Boolean = true): List<StepId> {
        val adb = listOf(StepId.DEV_OPTIONS, StepId.WIRELESS_DEBUG, StepId.PAIR, StepId.ADB_CONNECT)
        val shizuku = listOf(StepId.SHIZUKU_RUNNING, StepId.SHIZUKU_PERMISSION)
        return when (mode) {
            ChannelMode.EMBEDDED_ONLY -> adb + StepId.VERIFY
            ChannelMode.SHIZUKU -> shizuku + StepId.VERIFY
            ChannelMode.LOCAL_SERVER ->
                (if (via == LaunchVia.SHIZUKU) shizuku else adb) + (if (ownsServer) listOf(StepId.START_SERVER) else emptyList()) + StepId.VERIFY
            ChannelMode.AUTO -> adb + (if (ownsServer) listOf(StepId.START_SERVER) else emptyList()) + StepId.VERIFY
        }
    }

    /** Is [step] already true in [f]; such a step is skipped. VERIFY is never skipped. */
    fun satisfied(step: StepId, f: ChannelFacts): Boolean = when (step) {
        StepId.DEV_OPTIONS -> f.devOptions == true
        StepId.WIRELESS_DEBUG -> f.wirelessDebug == true
        StepId.PAIR -> f.adbPaired
        StepId.ADB_CONNECT -> f.adbConnected
        StepId.SHIZUKU_RUNNING -> f.shizukuRunning
        StepId.SHIZUKU_PERMISSION -> f.shizukuGranted
        StepId.START_SERVER -> f.serverRunning
        StepId.VERIFY -> false
    }

    /** The sentence shown when [step] stopped the run. [detail] is what the executor said. */
    fun explain(step: StepId, detail: String, waiting: Boolean): String {
        val tail = if (detail.isBlank()) "" else " ($detail)"
        return "Stopped at \"${step.label}\": " + when (step) {
            StepId.DEV_OPTIONS -> "Developer options are off. Open About phone and tap Build number seven times, then Connect again.$tail"
            StepId.WIRELESS_DEBUG -> "Wireless debugging is off. Turn it on in Developer options (and join Wi-Fi), then Connect again.$tail"
            StepId.PAIR ->
                if (waiting) "pairing is waiting for you: open Wireless debugging > Pair device with pairing code and type the 6-digit code into the notification. The connection finishes by itself."
                else "pairing could not start. Allow notifications for this app (the code is typed into one) and try again.$tail"
            StepId.ADB_CONNECT -> "no wireless-debugging service answered. It must be ON and this phone on Wi-Fi; if the pairing was revoked, pair again.$tail"
            StepId.SHIZUKU_RUNNING -> "Shizuku is not running. Open Shizuku and start its service, then Connect again.$tail"
            StepId.SHIZUKU_PERMISSION -> "Shizuku has not allowed this app. Approve its prompt, then Connect again.$tail"
            StepId.START_SERVER -> "the local server did not come up.$tail"
            StepId.VERIFY -> "the round trip got no shell answer.$tail"
        }
    }

    /**
     * Walk the plan. [onUpdate] gets the whole step list after every change (the page redraws from it).
     * Steps after the stopping one stay PENDING.
     */
    fun run(
        mode: ChannelMode, via: LaunchVia, exec: ConnectExecutor,
        log: ChannelLog? = null, ownsServer: Boolean = true, onUpdate: (List<StepRun>) -> Unit = {},
    ): ConnectOutcome {
        val runs = steps(mode, via, ownsServer).map { StepRun(it) }.toMutableList()
        fun set(i: Int, state: StepState, detail: String = "") {
            runs[i] = runs[i].copy(state = state, detail = detail); onUpdate(runs.toList())
        }
        onUpdate(runs.toList())
        for (i in runs.indices) {
            val id = runs[i].id
            val f = exec.facts()
            if (satisfied(id, f)) {
                set(i, StepState.SKIPPED, "already ok")
                log?.add("connect", "${id.label}: already ok")
                continue
            }
            set(i, StepState.RUNNING)
            val r = runCatching { exec.run(id) }.getOrElse { StepResult(false, it.message ?: it.javaClass.simpleName) }
            when {
                r.ok -> { set(i, StepState.DONE, r.detail); log?.add("connect", "${id.label}: ok ${r.detail}".trim()) }
                r.needsUser -> {
                    set(i, StepState.WAITING, r.detail)
                    log?.add("connect", "${id.label}: waiting for you ${r.detail}".trim())
                    return ConnectOutcome(runs.toList(), ConnectStatus.WAITING_FOR_USER, id, explain(id, r.detail, true))
                }
                else -> {
                    set(i, StepState.FAILED, r.detail)
                    log?.add("connect", "${id.label}: FAILED ${r.detail}".trim())
                    return ConnectOutcome(runs.toList(), ConnectStatus.FAILED, id, explain(id, r.detail, false))
                }
            }
        }
        return ConnectOutcome(runs.toList(), ConnectStatus.SUCCESS)
    }
}
