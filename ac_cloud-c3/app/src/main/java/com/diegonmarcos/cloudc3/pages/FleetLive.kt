package com.diegonmarcos.cloudc3.pages

import android.content.Context
import android.os.StatFs
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.diegonmarcos.cloudc3.cloud.OpsClient
import com.diegonmarcos.superapp.ops.dagu.DaguPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * The live half of the Infra pages: what c3-infra-api can say that the declaration cannot (a disk's usage, this month's
 * cost, the zone's records as the API sees them). Every method returns the API's body or a NAMED failure; the page
 * keeps showing the declared value when one fails, and says why.
 */
interface FleetLive {
    /** GET [path] with the Authelia bearer this device already holds. */
    suspend fun get(path: String): Result

    /** This phone's own app-data volume, "X GB free of Y GB"; null if the OS cannot say. */
    fun thisPhoneStorage(): String?

    sealed class Result {
        data class Body(val text: String) : Result()
        data class Failed(val why: String) : Result()
    }
}

/** The on-device source: [OpsClient] (same base URL, same bearer, same named failures as every other C3 call). */
class ApiFleetLive(private val context: Context) : FleetLive {

    override suspend fun get(path: String): FleetLive.Result = withContext(Dispatchers.IO) {
        val bearer = runCatching { DaguPrefs(context).bearerToken }.getOrDefault("")
        when (val r = OpsClient.get(path, bearer, path)) {
            is OpsClient.Outcome.Ok -> FleetLive.Result.Body(r.body)
            is OpsClient.Outcome.Failed -> FleetLive.Result.Failed("${r.kind}: ${r.message.lineSequence().first()}")
        }
    }

    override fun thisPhoneStorage(): String? = runCatching {
        val s = StatFs(context.filesDir.path)
        fun gb(b: Long) = String.format(java.util.Locale.US, "%.1f GB", b / 1_000_000_000.0)
        "${gb(s.availableBytes)} free of ${gb(s.totalBytes)}"
    }.getOrNull()
}

/**
 * What the pages observe. [disks] by vm id AND alias; [costs] by provider id; [dns] null until the API answered;
 * [notes] says per source why a live value is absent ("disks", "cost:oci", "dns").
 */
class FleetLiveState {
    var disks by mutableStateOf<Map<String, FleetModel.DiskUsage>>(emptyMap())
    var costs by mutableStateOf<Map<String, String>>(emptyMap())
    var dns by mutableStateOf<List<FleetModel.DnsRecord>?>(null)
    var notes by mutableStateOf<Map<String, String>>(emptyMap())
    var phone by mutableStateOf<String?>(null)

    /**
     * One refresh. Disks are asked PER VM (the fleet-wide /health/tier3 waits on every VM's SSH timeout and outlasts
     * the client's read) and only for the VMs that are mesh members: the declaration says which ones have a WireGuard
     * IP, and an on-demand box without one would only time out.
     */
    suspend fun refresh(slice: FleetModel.Slice, src: FleetLive) {
        phone = src.thisPhoneStorage()
        coroutineScope {
            val diskJobs = slice.vms.filter { it.wgIp != null }.map { v ->
                async { v to src.get("/health/tier3/${v.id}") }
            }
            val costJobs = slice.providers.mapNotNull { p -> p.cost?.live?.let { path -> async { p.id to src.get(path) } } }
            val dnsJob = async { src.get("/cloud-data/cloudflare-dns") }

            val nextDisks = mutableMapOf<String, FleetModel.DiskUsage>()
            val nextNotes = mutableMapOf<String, String>()
            diskJobs.awaitAll().forEach { (v, r) ->
                when (r) {
                    is FleetLive.Result.Body -> {
                        val parsed = FleetModel.parseDisks(r.text)
                        if (parsed.isEmpty()) nextNotes["disks:${v.id}"] = "no disk figures in the answer"
                        nextDisks.putAll(parsed)
                    }
                    is FleetLive.Result.Failed -> nextNotes["disks:${v.id}"] = r.why
                }
            }
            val nextCosts = mutableMapOf<String, String>()
            costJobs.awaitAll().forEach { (pid, r) ->
                when (r) {
                    is FleetLive.Result.Body -> {
                        val total = FleetModel.parseCostTotal(r.text)
                        if (total != null) nextCosts[pid] = total else nextNotes["cost:$pid"] = "no total in the answer"
                    }
                    is FleetLive.Result.Failed -> nextNotes["cost:$pid"] = r.why
                }
            }
            when (val r = dnsJob.await()) {
                is FleetLive.Result.Body -> dns = FleetModel.parseLiveDns(r.text)
                is FleetLive.Result.Failed -> nextNotes["dns"] = r.why
            }
            disks = nextDisks
            costs = nextCosts
            notes = nextNotes
        }
    }
}
