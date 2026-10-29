package com.diegonmarcos.superapp.wifi

import android.content.Context
import android.net.Network
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ConnectException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

/** A device found on the LAN (#1263): merged by IP from mDNS / NSD, SSDP, the ping / TCP sweep and NetBIOS. */
data class LanDevice(
    val ip: String,
    val names: Set<String> = emptySet(),
    val sources: Set<String> = emptySet(),
    val services: Set<String> = emptySet(),
    val rttMs: Double? = null,
    val server: String = "",
) {
    fun merge(o: LanDevice) = LanDevice(ip, names + o.names, sources + o.sources, services + o.services, rttMs ?: o.rttMs, server.ifEmpty { o.server })
    val title: String get() = names.firstOrNull { it.isNotBlank() } ?: ip
}

data class DnsAnswer(val name: String, val addresses: List<String>, val ms: Double, val reverse: String, val error: String = "", val viaWifi: Boolean = false)
data class PortResult(val port: Int, val open: Boolean, val service: String, val ms: Double)
data class Throughput(val direction: String, val bytes: Long, val seconds: Double, val mbps: Double, val samples: List<Double>, val error: String = "", val done: Boolean = false)

/**
 * The network tools of the page (#1263). Every tool is user-initiated, bounded by the declared limits (WifiConfig) and cancellable
 * (each runs in the controller's job). ICMP is the system `ping` binary (the only ICMP an app may send); TCP / UDP are sockets,
 * bound to the Wi-Fi [Network] when there is one so a mobile-data default route never answers in its place.
 */
class NetTools(private val ctx: Context, private val cfg: WifiConfig) {

    // ---- ping / traceroute ----

    suspend fun ping(host: String, count: Int, onLine: (String) -> Unit): String = withContext(Dispatchers.IO) {
        require(PingParser.safeHost(host)) { "not a host name or IP" }
        val iv = String.format(java.util.Locale.ROOT, "%.1f", cfg.pingIntervalS)
        run(listOf("ping", "-c", count.toString(), "-i", iv, "-W", cfg.pingTimeoutS.toString(), host), onLine)
    }

    suspend fun traceroute(host: String, onHop: (Hop) -> Unit): List<Hop> = withContext(Dispatchers.IO) {
        require(PingParser.safeHost(host)) { "not a host name or IP" }
        val hops = ArrayList<Hop>()
        for (ttl in 1..cfg.traceMaxHops) {
            coroutineContext.ensureActive()
            val t0 = System.nanoTime()
            val out = run(listOf("ping", "-c", "1", "-W", cfg.traceTimeoutS.toString(), "-t", ttl.toString(), host)) {}
            val wall = (System.nanoTime() - t0) / 1e6
            val h = PingParser.hop(ttl, out, wall)
            val named = h.ip?.let { ip -> runCatching { InetAddress.getByName(ip).canonicalHostName }.getOrNull()?.takeIf { it != ip } }
            val hop = h.copy(name = named.orEmpty())
            hops += hop; onHop(hop)
            if (hop.reached) break
        }
        hops
    }

    private suspend fun run(cmd: List<String>, onLine: (String) -> Unit): String {
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val sb = StringBuilder()
        try {
            BufferedReader(InputStreamReader(p.inputStream)).use { r ->
                while (true) {
                    coroutineContext.ensureActive()
                    val l = r.readLine() ?: break
                    sb.append(l).append('\n'); onLine(l)
                }
            }
            p.waitFor()
        } finally { p.destroy() }
        return sb.toString()
    }

    // ---- DNS ----

    suspend fun resolve(name: String, net: Network?): DnsAnswer = withContext(Dispatchers.IO) {
        val t0 = System.nanoTime()
        try {
            val addrs = if (net != null) net.getAllByName(name).toList() else InetAddress.getAllByName(name).toList()
            val ms = (System.nanoTime() - t0) / 1e6
            val rev = addrs.firstOrNull()?.let { a -> runCatching { InetAddress.getByAddress(a.address).canonicalHostName }.getOrNull() }.orEmpty()
            DnsAnswer(name, addrs.mapNotNull { it.hostAddress }, ms, rev, viaWifi = net != null)
        } catch (e: Exception) {
            DnsAnswer(name, emptyList(), (System.nanoTime() - t0) / 1e6, "", e.message ?: e.javaClass.simpleName, net != null)
        }
    }

    // ---- TCP ----

    private fun socket(net: Network?): Socket = Socket().also { s -> if (net != null) runCatching { net.bindSocket(s) } }

    /** true = open, false = closed / refused, null = no answer (filtered or no host). */
    fun tcp(host: String, port: Int, timeoutMs: Int, net: Network?): Pair<Boolean?, Double> {
        val t0 = System.nanoTime()
        return try {
            socket(net).use { it.connect(InetSocketAddress(host, port), timeoutMs) }
            true to (System.nanoTime() - t0) / 1e6
        } catch (e: ConnectException) { false to (System.nanoTime() - t0) / 1e6 }
        catch (e: SocketTimeoutException) { null to timeoutMs.toDouble() }
        catch (e: Exception) { null to (System.nanoTime() - t0) / 1e6 }
    }

    suspend fun portScan(host: String, ports: List<Int>, net: Network?, onResult: (PortResult, Int) -> Unit): List<PortResult> = withContext(Dispatchers.IO) {
        val sem = Semaphore(cfg.portConcurrency)
        var done = 0
        coroutineScope {
            ports.take(cfg.portMax).map { port ->
                async {
                    sem.withPermit {
                        ensureActive()
                        val (open, ms) = tcp(host, port, cfg.portTimeoutMs, net)
                        val r = PortResult(port, open == true, cfg.portNames[port].orEmpty(), ms)
                        synchronized(this@NetTools) { done++; onResult(r, done) }
                        r
                    }
                }
            }.awaitAll().filter { it.open }
        }
    }

    // ---- LAN discovery ----

    /** A host is up when anything answers: ICMP echo (isReachable) or any TCP answer, including a refusal (RST). */
    suspend fun sweep(hosts: List<String>, net: Network?, onDevice: (LanDevice) -> Unit, onProgress: (Int) -> Unit) = withContext(Dispatchers.IO) {
        val sem = Semaphore(cfg.sweepConcurrency)
        var n = 0
        coroutineScope {
            hosts.map { ip ->
                async {
                    sem.withPermit {
                        ensureActive()
                        val t0 = System.nanoTime()
                        var up = runCatching { InetAddress.getByName(ip).isReachable(cfg.sweepTimeoutMs) }.getOrDefault(false)
                        var rtt = if (up) (System.nanoTime() - t0) / 1e6 else null
                        val open = LinkedHashSet<String>()
                        if (!up) for (p in cfg.sweepPorts) {
                            val (o, ms) = tcp(ip, p, cfg.sweepTimeoutMs / 2, net)
                            if (o != null) { up = true; rtt = rtt ?: ms; if (o) open += "tcp/$p" ; break }
                        }
                        if (up) {
                            val nb = netbios(ip)
                            val rev = runCatching { InetAddress.getByName(ip).canonicalHostName }.getOrNull()?.takeIf { it != ip }
                            onDevice(LanDevice(ip, setOfNotNull(nb, rev), setOf("sweep") + (if (nb != null) setOf("netbios") else emptySet()), open, rtt))
                        }
                        synchronized(this@NetTools) { n++; onProgress(n) }
                    }
                }
            }.awaitAll()
        }
    }

    fun netbios(ip: String): String? = runCatching {
        DatagramSocket().use { s ->
            s.soTimeout = cfg.netbiosMs
            val q = NetBios.nodeStatusRequest()
            s.send(DatagramPacket(q, q.size, InetAddress.getByName(ip), 137))
            val buf = ByteArray(1024); val p = DatagramPacket(buf, buf.size)
            s.receive(p)
            NetBios.parseName(buf.copyOf(p.length))
        }
    }.getOrNull()

    suspend fun ssdp(onDevice: (LanDevice) -> Unit) = withContext(Dispatchers.IO) {
        withMulticast {
            runCatching {
                DatagramSocket().use { s ->
                    s.soTimeout = 400
                    val m = Ssdp.mSearch().toByteArray()
                    val group = InetAddress.getByName(Ssdp.ADDR)
                    repeat(2) { s.send(DatagramPacket(m, m.size, group, Ssdp.PORT)) }
                    val end = System.currentTimeMillis() + cfg.ssdpMs
                    val buf = ByteArray(4096)
                    while (System.currentTimeMillis() < end) {
                        coroutineContext.ensureActive()
                        val p = DatagramPacket(buf, buf.size)
                        try { s.receive(p) } catch (e: SocketTimeoutException) { continue }
                        val h = Ssdp.headers(String(buf, 0, p.length, Charsets.UTF_8))
                        if (h.isEmpty()) continue
                        onDevice(LanDevice(p.address.hostAddress.orEmpty(), emptySet(), setOf("ssdp"), setOfNotNull(h["ST"]?.takeIf { it.isNotBlank() }), null, h["SERVER"].orEmpty()))
                    }
                }
            }
        }
    }

    /** mDNS / DNS-SD through NsdManager for every declared service type, resolving each service to its host. */
    suspend fun nsd(onDevice: (LanDevice) -> Unit) = withContext(Dispatchers.Main) {
        val nsd = ctx.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return@withContext
        val listeners = ArrayList<NsdManager.DiscoveryListener>()
        val found = ConcurrentHashMap<String, NsdServiceInfo>()
        withMulticast {
            for (type in cfg.nsdTypes) {
                val l = object : NsdManager.DiscoveryListener {
                    override fun onDiscoveryStarted(t: String) {}
                    override fun onDiscoveryStopped(t: String) {}
                    override fun onStartDiscoveryFailed(t: String, e: Int) {}
                    override fun onStopDiscoveryFailed(t: String, e: Int) {}
                    override fun onServiceFound(s: NsdServiceInfo) { found.putIfAbsent(s.serviceName + "|" + s.serviceType, s) }
                    override fun onServiceLost(s: NsdServiceInfo) {}
                }
                runCatching { nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, l); listeners += l }
            }
            delay(cfg.nsdMs)
            listeners.forEach { runCatching { nsd.stopServiceDiscovery(it) } }
            // Resolve one at a time: the legacy resolver refuses concurrent requests (FAILURE_ALREADY_ACTIVE).
            for (s in found.values) {
                val r = resolve(nsd, s) ?: continue
                @Suppress("DEPRECATION")
                val ip = (if (Build.VERSION.SDK_INT >= 34) r.hostAddresses.firstOrNull() else r.host)?.hostAddress ?: continue
                onDevice(LanDevice(ip, setOf(r.serviceName), setOf("mdns"), setOf(r.serviceType.trim('.') + ":" + r.port)))
            }
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun resolve(nsd: NsdManager, s: NsdServiceInfo): NsdServiceInfo? {
        var out: NsdServiceInfo? = null; var finished = false
        runCatching {
            nsd.resolveService(s, object : NsdManager.ResolveListener {
                override fun onResolveFailed(i: NsdServiceInfo, e: Int) { finished = true }
                override fun onServiceResolved(i: NsdServiceInfo) { out = i; finished = true }
            })
        }.onFailure { return null }
        var waited = 0
        while (!finished && waited < 2500) { delay(50); waited += 50 }
        return out
    }

    private suspend fun <T> withMulticast(block: suspend () -> T): T {
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = runCatching { wm?.createMulticastLock("cloud-wifi")?.apply { setReferenceCounted(false); acquire() } }.getOrNull()
        try { return block() } finally { runCatching { lock?.release() } }
    }

    // ---- throughput ----

    /** Download from the declared endpoint, sampling Mbps every 250 ms, stopping at the byte budget or [WifiConfig.throughputMaxS]. */
    suspend fun download(net: Network?, onSample: (Throughput) -> Unit): Throughput = withContext(Dispatchers.IO) {
        transfer("download", net, onSample) { c, tick ->
            c.connect()
            if (c.responseCode !in 200..299) throw IllegalStateException("HTTP ${c.responseCode}")
            c.inputStream.use { ins -> val buf = ByteArray(64 * 1024); while (true) { val n = ins.read(buf); if (n < 0) break; if (!tick(n.toLong())) break } }
        }
    }

    suspend fun upload(net: Network?, onSample: (Throughput) -> Unit): Throughput = withContext(Dispatchers.IO) {
        transfer("upload", net, onSample) { c, tick ->
            c.doOutput = true; c.requestMethod = "POST"; c.setFixedLengthStreamingMode(cfg.uploadBytes)
            c.setRequestProperty("Content-Type", "application/octet-stream")
            c.outputStream.use { os ->
                val buf = ByteArray(64 * 1024); var left = cfg.uploadBytes
                while (left > 0) { val n = minOf(left, buf.size.toLong()).toInt(); os.write(buf, 0, n); left -= n; if (!tick(n.toLong())) break }
            }
            if (c.responseCode !in 200..299) throw IllegalStateException("HTTP ${c.responseCode}")
        }
    }

    private suspend fun transfer(dir: String, net: Network?, onSample: (Throughput) -> Unit, body: (HttpURLConnection, (Long) -> Boolean) -> Unit): Throughput {
        val url = URL(if (dir == "download") cfg.downloadUrlFor() else cfg.uploadUrl)
        val conn = ((if (net != null) net.openConnection(url) else url.openConnection()) as HttpURLConnection).apply { connectTimeout = 8000; readTimeout = 8000; useCaches = false }
        val ctxJob = coroutineContext
        val t0 = System.nanoTime(); var bytes = 0L; var lastT = t0; var lastB = 0L
        val samples = ArrayList<Double>()
        fun secs() = (System.nanoTime() - t0) / 1e9
        return try {
            body(conn) { n ->
                bytes += n
                val now = System.nanoTime()
                if (now - lastT >= 250_000_000L) {
                    samples += (bytes - lastB) * 8 / ((now - lastT) / 1e9) / 1e6; lastT = now; lastB = bytes
                    onSample(Throughput(dir, bytes, secs(), bytes * 8 / secs() / 1e6, samples.toList()))
                }
                ctxJob.isActiveOrTrue() && secs() < cfg.throughputMaxS
            }
            Throughput(dir, bytes, secs(), if (secs() > 0) bytes * 8 / secs() / 1e6 else 0.0, samples, done = true).also(onSample)
        } catch (e: Exception) {
            Throughput(dir, bytes, secs(), if (secs() > 0) bytes * 8 / secs() / 1e6 else 0.0, samples, e.message ?: e.javaClass.simpleName, true).also(onSample)
        } finally { conn.disconnect() }
    }

    private fun kotlin.coroutines.CoroutineContext.isActiveOrTrue(): Boolean = this[kotlinx.coroutines.Job]?.isActive ?: true
}
