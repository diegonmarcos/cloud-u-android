#!/usr/bin/env bash
# #874 Store download — EVERY LOOKUP OF THE PROCESS GOES THROUGH THE ONE DNS BRIDGE.
#
# MEASURED 2026-10-06 (build with 0df192b8c): every Store download failed with
# "release → DNS: cannot resolve github.com (active resolver: tried Android
# system resolver (Mirror Android) → Android resolver on Wi-Fi)" while the DNS
# page said "Last successful lookup: github.com → 140.82.121.4 (system)". Two
# resolvers in one process: the page's Test asked InetAddress, the downloader
# asked InetAddress through StoreDns and OkHttp — the same call, at different
# times, with nothing tying the two readings together. Now there is ONE path:
# libs:sysdns FleetDnsBridge, the terminals' SystemDnsBridge grown an upstream
# that walks the DNS preset, serving the shell on 127.0.0.1:<bridge_port> AND
# the app's own code through resolve(); the Store's downloader and check, the
# DNS page's Test and /api/net/dns all read it.
#
#   B1  the bridge is the one place that resolves: its Mirror route is Android's
#       resolver on each underlying NON-VPN network (DnsResolver.rawQuery on
#       that Network), then the uid's default; a preset's servers are asked
#       as raw DNS; the first definitive answer wins and a miss names every
#       route tried; its port is the config's (sysdns.json bridge_port)
#   B2  the Store's download path resolves ONLY through the bridge: StoreDns's
#       selector asks the host's resolve() for every download host (release,
#       ghcr, the mesh leg's origins) and tunnels the connection to the answer;
#       nothing under libs:updater / libs:appstore main calls InetAddress's
#       resolver or brings a Dns of its own
#   B3  SuperApp wires the bridge from the DNS page's preset (FleetDns) and the
#       page's Test lookup is the same resolve(); Cloud Store wires the bridge's
#       Mirror default; both link libs:sysdns
#   B4  a failure still reads "DNS: cannot resolve <host> (active resolver: …)",
#       now naming the bridge and the routes it tried; /api/net/dns says
#       "resolver":"bridge" with "via", and /api/sysdns/state is served
#   B5  #875 one batch, one resolver, no intermittent miss (MEASURED 2026-10-06,
#       mobile data, "Install all": cloud-calc and c3-morpheus downloaded while
#       c3-watchdog and c3-watchtower failed every rung on the same github.com):
#       a route is retried with backoff before the next one, upstream queries
#       are capped, a positive answer is cached for its TTL (≥ 60 s) so one
#       github.com lookup serves the batch, and the failure counts the tries
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"
ROOT="$(cd "$APP/.." && pwd)"
LIBS="$ROOT/ab_cloud-libs-shared/libs"
BRIDGE="${BRIDGE_KT:-$LIBS/sysdns/src/main/java/com/diegonmarcos/cloudlib/sysdns/FleetDnsBridge.kt}"
SYSBRIDGE="$LIBS/sysdns/src/bridge/java/com/diegonmarcos/cloudlib/sysdns/SystemDnsBridge.java"
STOREDNS="$LIBS/appstore/src/main/java/com/diegonmarcos/superapp/appstore/StoreDns.kt"
SRC="$LIBS/updater/src/main/java/com/diegonmarcos/superapp/updater/source"
DEBUG="$LIBS/devtools/src/main/java/com/diegonmarcos/superapp/devtools/AppDebugServer.kt"
MAIN="$APP/app/src/main/java/com/diegonmarcos/superapp/App.kt"
FLEETDNS="$APP/app/src/main/java/com/diegonmarcos/superapp/network/FleetDns.kt"
FRAG="$APP/app/src/main/java/com/diegonmarcos/superapp/network/DnsFragment.kt"
OVERVIEW="$APP/app/src/main/java/com/diegonmarcos/superapp/network/DnsOverview.kt"
STORE_MAIN="$ROOT/ac_cloud-store/app/src/main/java/com/diegonmarcos/cloudstore/App.kt"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
for f in "$BRIDGE" "$SYSBRIDGE" "$STOREDNS" "$SRC/Download.kt" "$DEBUG" "$MAIN" "$FLEETDNS" "$FRAG" "$OVERVIEW" "$STORE_MAIN"; do
  [ -f "$f" ] || { echo "ERROR: missing $f" >&2; exit 2; }
done

echo "== B1: the bridge resolves on the underlying network, then the default; the preset's servers raw =="
grep -q 'c.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||' "$BRIDGE" \
  && grep -q 'Route("Android resolver on $kind", network = n)' "$BRIDGE" \
  && ok "Mirror's first routes are Android's resolver on each non-VPN network with internet" \
  || bad "the bridge's Mirror route does not bind to the underlying (non-VPN) networks"
grep -q 'underlying() + Route("Android system resolver"' "$BRIDGE" \
  && ok "…then the uid's default resolver, last" || bad "Mirror does not end on the default resolver"
grep -q 'DnsResolver.getInstance().rawQuery(network, q, DnsResolver.FLAG_EMPTY' "$BRIDGE" \
  && ok "an Android route is DnsResolver.rawQuery ON THAT Network (the query bytes as they came)" \
  || bad "the Android route does not hand the raw query to DnsResolver on the route's Network"
grep -q 'if (r.servers.isEmpty()) android(q, r.network)' "$BRIDGE" \
  && grep -q 'forward(q, s, r.network)' "$BRIDGE" \
  && ok "a route with servers forwards the raw query to them; one without asks Android" \
  || bad "the walk does not split Android routes from server routes"
grep -q 'if (a != null && DnsWire.rcode(a) in DEFINITIVE)' "$BRIDGE" \
  && grep -q 'lastFailure = tried.joinToString(" → ")' "$BRIDGE" \
  && ok "the first NOERROR/NXDOMAIN answer wins; a miss names every route tried" \
  || bad "the walk does not stop on a definitive answer or does not name what it tried"
grep -q 'public byte\[\] query(byte\[\] q, long timeoutMs)' "$SYSBRIDGE" \
  && grep -q 'byte\[\] a = query(q, ANSWER_TIMEOUT_S \* 1000);' "$SYSBRIDGE" \
  && grep -q 'b.query(DnsWire.query(host, type)' "$BRIDGE" \
  && ok "resolve() and a TCP client's query are the SAME SystemDnsBridge.query path" \
  || bad "the in-process lookup does not go through SystemDnsBridge.query"
grep -q 'buildConfigField .int., .BRIDGE_PORT., "${sysdns.bridge_port as int}"' "$LIBS/sysdns/build.gradle" \
  && grep -q 'start(dnsCtx, com.diegonmarcos.cloudlib.sysdns.BuildConfig.BRIDGE_PORT)' "$MAIN" \
  && ok "the port is data/sysdns.json::bridge_port, baked, never a literal in the app" \
  || bad "the bridge port is not the config's"
grep -q 'bindError = "127.0.0.1:$port taken' "$BRIDGE" && grep -q 'SystemDnsBridge(0, upstream, log)' "$BRIDGE" \
  && ok "a taken port (a terminal's bridge) still leaves this process its own bridge, ephemeral" \
  || bad "a taken bridge port leaves the process with no resolver"

echo "== B2: the download path resolves only through the bridge =="
LADDER="$LIBS/appstore/src/main/java/com/diegonmarcos/superapp/appstore/DnsLadder.kt"
[ -f "$LADDER" ] || { echo "ERROR: missing $LADDER" >&2; exit 2; }
grep -q '@Volatile var resolve: (String) -> List<InetAddress>' "$STOREDNS" \
  && grep -q 'bridge = { h -> try { resolve(h) }' "$STOREDNS" \
  && ok "StoreDns's FIRST rung is the host's resolve() (the bridge); #899 the ladder goes on from there" \
  || bad "StoreDns does not resolve through the host's bridge"
grep -q '(uri.scheme == "https" || uri.scheme == "http") && host != null && accepts(host) && resolveFor(host)' "$LADDER" \
  && grep -q 'DnsLadder.Relay(::isStoreHost, ::rungs)' "$STOREDNS" \
  && grep -q 'MeshMirror.bases.any' "$STOREDNS" \
  && ok "release, ghcr and the mesh leg's origins (https and the mesh's plain http) are all routed" \
  || bad "a download leg is not routed through the bridge"
grep -q 'URL(current).openConnection() as HttpURLConnection' "$SRC/Download.kt" \
  && ok "Download opens HttpURLConnections, which consult the selector" || bad "Download no longer uses HttpURLConnection"
bare="$(grep -rn 'InetAddress.getAllByName\|InetAddress.getByName' "$LIBS/updater/src/main" "$LIBS/appstore/src/main" \
  | grep -v 'getByName("127.0.0.1")' | grep -v 'DnsLadder.kt' || true)"
[ -z "$bare" ] && ok "no bare InetAddress lookup under libs:updater / libs:appstore main" \
  || bad "a bare resolver call on the Store's path: $bare"
grep -rqE 'okhttp3\.Dns|object *: *Dns|DnsOverHttps' "$LIBS/updater/src/main" "$LIBS/appstore/src/main" \
  && bad "an OkHttp Dns of the lib's own crept into the download path" || ok "no OkHttp Dns in the download path (#899's DoH is a ladder rung over HttpURLConnection)"
grep -q 'DnsResolver\|DatagramSocket' "$STOREDNS" "$LADDER" \
  && bad "the Store's ladder asks DNS over UDP by itself" || ok "the ladder never speaks plain DNS: the bridge does; DoH goes by IP over HTTPS"
grep -q 'DnsLadder.ladder(' "$STOREDNS" && grep -q 'listOf(Rung(bridgeLabel, bridge), Rung("system", system)) +' "$LADDER" \
  && grep -q 'doh.map { (label, endpoint) -> doh(label, endpoint) } + Rung("mesh", mesh)' "$LADDER" \
  && ok "#899 the ladder is bridge → system → DoH by IP → mesh" || bad "the ladder's order is not bridge, system, DoH, mesh"

echo "== B3: SuperApp and Cloud Store wire the one bridge =="
grep -q 'routes = { name -> com.diegonmarcos.superapp.network.FleetDns.bridgeRoutes(dnsCtx, name) }' "$MAIN" \
  && grep -q 'resolve = com.diegonmarcos.cloudlib.sysdns.FleetDnsBridge::resolve' "$MAIN" \
  && ok "SuperApp: the bridge's routes are the DNS page's preset; StoreDns resolves through the bridge" \
  || bad "SuperApp does not wire the bridge from FleetDns into StoreDns"
grep -q 'fun bridgeRoutes(ctx: Context, name: String): List<FleetDnsBridge.Route>' "$FLEETDNS" \
  && grep -q 'return if (servers.isEmpty()) FleetDnsBridge.mirror(label)' "$FLEETDNS" \
  && grep -q 'upstreamsFor(decl, name, up, promised(decl, p.preset, p.fallbacks, p.chosen, fleet, up), fleet)' "$FLEETDNS" \
  && ok "FleetDns.bridgeRoutes: the promised servers (split for mesh names), else Mirror" \
  || bad "FleetDns.bridgeRoutes does not derive the routes from the preset"
grep -q 'FleetDnsBridge.resolve(name)' "$FRAG" && ! grep -q 'InetAddress.getAllByName' "$FRAG" \
  && ok "the DNS page's Test is the same resolve() the downloader uses" \
  || bad "the DNS page still resolves beside the bridge"
grep -q 'resolve = FleetDnsBridge::resolve' "$STORE_MAIN" && grep -q 'FleetDnsBridge.start(this, com.diegonmarcos.cloudlib.sysdns.BuildConfig.BRIDGE_PORT)' "$STORE_MAIN" \
  && ok "Cloud Store starts the bridge (Mirror default) and resolves through it" \
  || bad "Cloud Store does not wire the bridge"
grep -q "project(':libs:sysdns')" "$APP/app/build.gradle" && grep -q "project(':libs:sysdns')" "$ROOT/ac_cloud-store/app/build.gradle" \
  && ok "both apps link libs:sysdns" || bad "an app does not link libs:sysdns"

echo "== B4: the failure names the bridge; the debug API shows it =="
grep -q '"DNS: cannot resolve ${host(t) ?: "the download host"} (active resolver: ${resolver?.takeIf { it.isNotBlank() } ?: "unknown"})"' "$SRC/DownloadFailure.kt" \
  && ok "the DNS wording keeps the host and the active resolver" || bad "DownloadFailure's DNS wording changed"
grep -q 'relay.failureFor(host)?.let { "resolvers tried: $it" }' "$STOREDNS" \
  && grep -q 'val trail: String get() = attempts.joinToString(" → ") { "${it.rung}: ${it.detail}" }' "$LADDER" \
  && ok "the active resolver names every rung asked and why it declined (the bridge's own routes ride in its detail)" || bad "the failure does not name every rung"
grep -q '"resolver":"${if (via == null) "android" else "bridge"}"' "$DEBUG" && grep -q '@Volatile var dnsVia' "$DEBUG" \
  && grep -q 'AppDebugServer.dnsVia = { com.diegonmarcos.cloudlib.sysdns.FleetDnsBridge.label }' "$MAIN" \
  && ok "/api/net/dns reports resolver=bridge with via=bridge 127.0.0.1:<port>" \
  || bad "/api/net/dns does not report the bridge"
grep -q 'AppDebugServer.route("sysdns"' "$OVERVIEW" && grep -q 'JSONObject(FleetDnsBridge.stateJson())' "$OVERVIEW" \
  && ok "/api/sysdns/state is served and the DNS page's Bridges row for this app reads it" \
  || bad "the bridge's state is not on the debug API / the DNS page"

echo "== B5: retry with backoff, a cap on in-flight queries, a TTL cache, the tries in the message =="
grep -q 'const val TRIES_PER_ROUTE = 2' "$BRIDGE" && grep -q 'for (attempt in 1..TRIES_PER_ROUTE)' "$BRIDGE" \
  && grep -q 'if (attempt > 1) Thread.sleep(BACKOFF_MS \* (attempt - 1))' "$BRIDGE" \
  && ok "each route is asked TRIES_PER_ROUTE times with a growing backoff before the next route" \
  || bad "a route gets one shot: a dropped UDP answer on mobile data is a miss"
awk '/private fun walk\(/,/^    }$/' "$BRIDGE" | grep -q 'for (r in runCatching { routes(name) }' \
  && awk '/for \(r in runCatching \{ routes\(name\) \}/,/^        }$/' "$BRIDGE" | grep -q 'for (attempt in 1..TRIES_PER_ROUTE)' \
  && ok "the retry loop sits INSIDE the route loop: the first route is retried before the second is tried" \
  || bad "the retries are not per route"
grep -q 'private val inFlight = Semaphore(MAX_IN_FLIGHT, true)' "$BRIDGE" && grep -q 'const val MAX_IN_FLIGHT = 4' "$BRIDGE" \
  && awk '/private fun ask\(/,/^    }$/' "$BRIDGE" | grep -q 'inFlight.acquire()' \
  && awk '/private fun ask\(/,/^    }$/' "$BRIDGE" | grep -q 'finally { inFlight.release() }' \
  && ok "every upstream attempt runs under a fair 4-wide semaphore, released in finally" \
  || bad "upstream queries are not capped"
grep -q 'const val MIN_TTL_MS = 60_000L' "$BRIDGE" \
  && grep -q 'cache\[key\] = Cached(a, System.currentTimeMillis() + maxOf(DnsWire.ttl(a) \* 1000L, MIN_TTL_MS))' "$BRIDGE" \
  && grep -q 'if (DnsWire.rcode(a) == 0 && DnsWire.addresses(a).isNotEmpty())' "$BRIDGE" \
  && ok "a positive answer (NOERROR with addresses) is cached for max(TTL, 60 s); NXDOMAIN and misses are not" \
  || bad "positive answers are not cached for their TTL"
awk '/private fun walk\(/,/^    }$/' "$BRIDGE" | grep -q 'cache\[key\]?.let { c -> if (c.until > System.currentTimeMillis()) { cached++; return c.answer }' \
  && grep -q 'val key = "$name/${DnsWire.qtype(q)}"' "$BRIDGE" \
  && ok "the cache is consulted before any route, per (name, qtype), and an expired entry is dropped" \
  || bad "the walk does not serve from the cache first"
grep -q 'fun ttl(m: ByteArray): Long' "$BRIDGE" && grep -q 'fun qtype(m: ByteArray): Int' "$BRIDGE" \
  && ok "DnsWire reads the answer's smallest TTL and the question's type" || bad "DnsWire cannot read a TTL / qtype"
grep -q 'lastFailure = tried.joinToString(" → ") + " after $tries tries"' "$BRIDGE" \
  && grep -q 'relay.failureFor(host)?.let { "resolvers tried: $it" }' "$STOREDNS" \
  && ok "a bridge miss reads '<route> → <route> after N tries' inside the bridge rung of the Store's row" \
  || bad "the failure does not carry the attempt count"
grep -q 'val budget = timeoutMs.toLong() \* TRIES_PER_ROUTE \* 4 + BACKOFF_MS \* TRIES_PER_ROUTE \* 4' "$BRIDGE" \
  && ok "resolve() waits out the retried walk instead of giving up at one timeout" || bad "resolve()'s wait does not cover the retries"
grep -q '",\\"tries\\":" + lastTries' "$BRIDGE" && grep -q '",\\"cached\\":" + cached' "$BRIDGE" \
  && ok "/api/sysdns/state reports tries, cached hits and the cache size" || bad "the bridge's state does not show retries / cache"

echo "== RESULT(#874 store download via the DNS bridge): $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
