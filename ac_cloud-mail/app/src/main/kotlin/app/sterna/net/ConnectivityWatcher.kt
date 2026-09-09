package app.sterna.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.io.InterruptedIOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What real requests have proved about the link since the last thing that happened to it. The
 *  callbacks describe the plumbing; only a request that went out and came back describes the
 *  service, and a VPN killswitch is invisible to the plumbing. */
internal enum class Reachability {
    /** Nothing has been tried yet — believe the framework. */
    UNKNOWN,

    /** A request completed: whatever the plumbing says, traffic flows. */
    OK,

    /** A request died on the transport: whatever the plumbing says, traffic does not flow. */
    FAILED,
}

/**
 * Pure connectivity state machine behind the offline banner and the auto-refresh on reconnect
 */
internal class ReconnectGate(link: Boolean) {
    /** Every real transport currently satisfying the NOT_VPN request. */
    private val transports = mutableSetOf<Long>()

    /** Latches, seeded from the connectivity read at registration (see the class doc). */
    private var transportsUp = link
    private var routeUp = link
    private var blocked = false

    /** The app's current default network, so a route handover is not a route loss. */
    private var route: Long? = null

    private var reachability = Reachability.UNKNOWN

    private var linkUp = link

    private val _online = MutableStateFlow(link)
    /** The connectivity the UI shows: the link, minus what requests have disproved. A success only
     *  clears a previous failure, it never overrules the plumbing. */
    val online: StateFlow<Boolean> = _online.asStateFlow()

    /** A real transport became usable — on a genuine down → up transition, and when an unseen
     *  transport turns up while requests are known to be dying ([settle]). */
    fun onTransportAvailable(handle: Long): Boolean {
        // Moving means "different from something we had", never "different from nothing": with no
        // transport recorded this is the registration replay, and add() false is a re-announce.
        val known = transports.isNotEmpty()
        val moved = transports.add(handle) && known
        transportsUp = true
        return settle(moved)
    }

    fun onTransportLost(handle: Long) {
        transports -= handle
        if (transports.isEmpty()) transportsUp = false
        settle()
    }

    fun onRouteAvailable(handle: Long): Boolean {
        // A tunnel that reconnects comes back as a NEW network, so a differing handle is the one
        // visible trace of a rebuild that never took the link down.
        val moved = route != null && route != handle
        route = handle
        routeUp = true
        return settle(moved)
    }

    /** The route went away — ignored when it is the *old* half of a handover. */
    fun onRouteLost(handle: Long) {
        if (route != null && route != handle) return
        route = null
        routeUp = false
        settle()
    }

    fun onRouteBlocked(blocked: Boolean): Boolean {
        // Being unblocked is the plumbing moving; being blocked never is.
        val moved = this.blocked && !blocked
        this.blocked = blocked
        return settle(moved)
    }

    fun onRequestSucceeded() {
        reachability = Reachability.OK
        publish()
    }

    fun onRequestFailed() {
        reachability = Reachability.FAILED
        publish()
    }

    /**
     * Recompute the link, publish, and report whether a resync should be attempted. Two reasons: the
     */
    private fun settle(moved: Boolean = false): Boolean {
        val up = transportsUp && routeUp && !blocked
        val edge = up && !linkUp
        linkUp = up
        publish()
        return edge || (up && moved && reachability == Reachability.FAILED)
    }

    private fun publish() {
        _online.value = linkUp && reachability != Reachability.FAILED
    }
}

/**
 * How the refresh that follows a reconnect is paced, kept pure so the schedule is testable (#65).
 */
internal object ReconnectRefresh {
    /** Attempts made per reconnect; the last one is the one whose failure is reported. */
    const val MAX_TRIES = 4

    /** Settle before the first attempt: the window a burst of callbacks is collapsed into, sized
     *  from what the framework does — a handover emits its run within a few hundred milliseconds. */
    private const val SETTLE_MS = 1_500L

    /** Widening gaps between the retries, capped: with the settle they cover a couple of WireGuard
     *  handshake attempts, without turning into a poll if the server is simply down. */
    private val GAPS_MS = longArrayOf(3_000L, 6_000L, 12_000L)

    fun delayBeforeMs(attempt: Int): Long =
        if (attempt <= 0) SETTLE_MS else GAPS_MS[minOf(attempt - 1, GAPS_MS.lastIndex)]

    /** True while another attempt is still coming, so the failure isn't final. */
    fun retrying(attempt: Int): Boolean = attempt < MAX_TRIES - 1

    /** The error the UI should hold after attempt [attempt] failed: none while a retry is pending,
     *  the banner having to describe the present, and the real error once they are exhausted. */
    fun errorAfterAttempt(attempt: Int, error: String?): String? =
        if (retrying(attempt)) null else error
}

/**
 * Watches connectivity and calls [onReconnect] when it actually comes back. Register with [start],
 */
class ConnectivityWatcher(context: Context, private val onReconnect: () -> Unit) {
    private val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val gate = ReconnectGate(link = hasUsableNetwork(context))

    /** Live connectivity, seeded from the link up at construction; drives the offline UI. */
    val online: StateFlow<Boolean> = gate.online

    private val request = NetworkRequest.Builder()
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        .build()

    private val transportCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (gate.onTransportAvailable(network.networkHandle)) onReconnect()
        }

        override fun onLost(network: Network) {
            gate.onTransportLost(network.networkHandle)
        }
    }

    private val defaultCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (gate.onRouteAvailable(network.networkHandle)) onReconnect()
        }

        override fun onLost(network: Network) {
            gate.onRouteLost(network.networkHandle)
        }

        override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
            if (gate.onRouteBlocked(blocked)) onReconnect()
        }
    }

    fun reportSuccess() {
        gate.onRequestSucceeded()
    }

    /** A request failed. Only a failure that died on the transport says anything about
     *  connectivity — a 500 or a rejected password proves the link works. */
    fun reportFailure(t: Throwable) {
        if (isNetworkFailure(t)) gate.onRequestFailed() else gate.onRequestSucceeded()
    }

    fun start() {
        runCatching { manager?.registerNetworkCallback(request, transportCallback) }
        runCatching { manager?.registerDefaultNetworkCallback(defaultCallback) }
    }

    fun stop() {
        runCatching { manager?.unregisterNetworkCallback(transportCallback) }
        runCatching { manager?.unregisterNetworkCallback(defaultCallback) }
    }
}

/**
 * Whether traffic can leave the device right now — a cheap synchronous read of the capabilities, not
 */
fun hasUsableNetwork(context: Context): Boolean {
    val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java) ?: return false
    @Suppress("DEPRECATION")
    val networks = runCatching { cm.allNetworks }.getOrNull() ?: return false
    val transport = networks.any { network ->
        val caps = runCatching { cm.getNetworkCapabilities(network) }.getOrNull()
        caps != null &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
    }
    if (!transport) return false
    val active = runCatching { cm.activeNetwork }.getOrNull() ?: return false
    val caps = runCatching { cm.getNetworkCapabilities(active) }.getOrNull() ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

/** How far the cause chain is followed before giving up; guards against a self-referencing cause. */
private const val MAX_CAUSE_HOPS = 8

/**
 * Whether [t] is the transport failing rather than the file or the server saying no.
 */
internal fun isNetworkFailure(t: Throwable): Boolean {
    var error: Throwable? = t
    var hops = 0
    while (error != null && hops++ < MAX_CAUSE_HOPS) {
        if (error is UnknownHostException || error is SocketException || error is InterruptedIOException) {
            return true
        }
        error = error.cause
    }
    return false
}

/**
 * Whether a failure should be told as "you are offline" rather than shown as its exception text.
 */
internal fun isOfflineFailure(t: Throwable, online: Boolean): Boolean = !online && isNetworkFailure(t)
