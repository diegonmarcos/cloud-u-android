package com.diegonmarcos.cloudc3.pages

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import com.diegonmarcos.cloudc3.cloud.FleetDown
import com.diegonmarcos.cloudc3.pages.ProbeModel.Leg
import com.diegonmarcos.cloudc3.pages.ProbeModel.LegResult
import com.diegonmarcos.cloudc3.pages.ProbeModel.RowProbe
import com.diegonmarcos.cloudc3.pages.ProbeModel.Status
import com.diegonmarcos.superapp.ops.dagu.DaguPrefs
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** Probes one endpoint and, optionally, asks the fleet API which containers it sees down. */
interface Prober {
    suspend fun probe(leg: Leg, target: String): LegResult

    /** Names of declared containers the FLEET API (c3-infra-api) reports not running; null = it could not say. */
    suspend fun containersDown(): Set<String>?
}

/**
 * The on-device prober: DNS through Android's resolver, then ONE HTTP request without following redirects. Any HTTP
 * reply of any status is UP (the gate's 302 / 401 included); a DNS failure, a refused or timed-out connection or a
 * TLS failure is DOWN with the error text. The latency is the whole DNS + request time.
 */
class NetProber(private val context: Context, private val timeoutMs: Int = 4000) : Prober {

    override suspend fun probe(leg: Leg, target: String): LegResult = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        fun result(status: Status, dns: String = "", http: Int? = null, error: String = "") =
            LegResult(leg, status, target, dns, http, System.currentTimeMillis() - started, error, System.currentTimeMillis())
        val url = runCatching { URL(target) }.getOrNull() ?: return@withContext result(Status.DOWN, error = "not a URL: $target")
        val dns = try {
            val addrs = InetAddress.getAllByName(url.host)
            addrs.joinToString(", ") { it.hostAddress.orEmpty() }
        } catch (e: java.net.UnknownHostException) {
            return@withContext result(Status.DOWN, dns = "no answer", error = "DNS: ${e.message ?: "unknown host"}")
        } catch (e: Exception) {
            return@withContext result(Status.DOWN, dns = "error", error = "DNS: ${e.javaClass.simpleName}: ${e.message.orEmpty()}")
        }
        var conn: HttpURLConnection? = null
        try {
            conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs; readTimeout = timeoutMs
                instanceFollowRedirects = false
                requestMethod = "GET"
            }
            val code = conn.responseCode
            result(Status.UP, dns = dns, http = code)
        } catch (e: Exception) {
            result(Status.DOWN, dns = dns, error = "${e.javaClass.simpleName}: ${e.message.orEmpty()}".trim())
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    override suspend fun containersDown(): Set<String>? = withContext(Dispatchers.IO) {
        val bearer = runCatching { DaguPrefs(context).bearerToken }.getOrDefault("")
        when (val r = runCatching { FleetDown.load(bearer) }.getOrNull()) {
            is FleetDown.Report.Measured -> r.down.values.flatten().map { it.name }.toSet()
            else -> null
        }
    }
}

/**
 * The probe results the Table shows: observable state, a timestamped cache, bounded parallelism. One instance is
 * kept for the process so leaving the page and coming back shows the last results instead of starting grey.
 */
class RoutesProbeStore {
    val results = mutableStateMapOf<String, RowProbe>()
    val running = mutableStateOf(false)
    val lastRunAt = mutableStateOf(0L)

    /**
     * Probe every leg of [rows] with at most [parallelism] requests in flight. A leg whose cached result is fresher
     * than [ProbeModel.DEFAULT_TTL_MS] is kept unless [force]. The fleet API's down list is asked once per run and
     * turns those rows RED; the whole run is then judged for off-mesh ([ProbeModel.applyMeshVerdict]).
     */
    suspend fun run(rows: List<RoutesTableModel.Row>, prober: Prober, force: Boolean, parallelism: Int = 8, now: () -> Long = System::currentTimeMillis) {
        if (running.value) return
        running.value = true
        try {
            coroutineScope {
                val down = async { prober.containersDown() }
                val gate = Semaphore(parallelism)
                val jobs = rows.flatMap { row ->
                    ProbeModel.legsOf(row).map { (leg, target) ->
                        async {
                            val cached = results[row.key]?.legs?.get(leg)
                            if (!force && cached != null && ProbeModel.isFresh(cached, now()) && cached.target == target) return@async
                            val r = gate.withPermit { prober.probe(leg, target) }
                            val old = results[row.key] ?: RowProbe()
                            results[row.key] = old.copy(legs = old.legs + (leg to r))
                        }
                    }
                }
                jobs.awaitAll()
                val downSet = down.await()
                if (downSet != null) rows.forEach { r ->
                    val old = results[r.key] ?: RowProbe()
                    results[r.key] = old.copy(containerDown = r.container in downSet)
                }
                val judged = ProbeModel.applyMeshVerdict(results.toMap())
                judged.forEach { (k, v) -> if (results[k] != v) results[k] = v }
                lastRunAt.value = now()
            }
        } finally {
            running.value = false
        }
    }

    /** Re-test one row (the sheet's button): its legs only, fresh. */
    suspend fun retest(row: RoutesTableModel.Row, prober: Prober, now: () -> Long = System::currentTimeMillis) {
        val fresh = ProbeModel.legsOf(row).map { (leg, target) -> leg to prober.probe(leg, target) }.toMap()
        val old = results[row.key] ?: RowProbe()
        results[row.key] = old.copy(legs = old.legs + fresh)
        lastRunAt.value = now()
    }

    companion object {
        /** The process-wide cache. */
        val shared = RoutesProbeStore()
    }
}
