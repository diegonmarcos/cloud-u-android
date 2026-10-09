#!/usr/bin/env bash
# The WireGuard engine (libs/net-wg) raises ONE wireguard-go device per process. NetBackendService is
# a bound service: destroyed when its last client unbinds, created again on the next bind, while the
# VpnService and the Go device it raised live on. A GoBackend per service instance starts at handle -1
# and raises a second device on the next UP; the first keeps its UDP sockets and keeps handshaking
# with the hubs under the same key, so their replies land in a dead tun (four devices, tun0..tun3,
# found alive after four binds). The backend and the tunnel registry (GoBackend compares tunnels by
# identity) are therefore process-wide, in the companion, and the service only reaches them.
set -u
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SVC="$ROOT/libs/net-wg/src/main/java/com/diegonmarcos/superapp/netwg/NetBackendService.kt"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  ok   $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL $1"; }

check() { # $1 = file
    local f="$1" C
    C="$(awk '/companion object \{/,/^    }/' "$f")"
    [ "$(grep -c 'GoBackend(' "$f")" -eq 1 ] || return 1                       # constructed in ONE place
    echo "$C" | grep -q 'GoBackend(ctx.applicationContext)' || return 1       # ...and that place is the companion
    echo "$C" | grep -q '@Volatile private var shared: GoBackend?' || return 1
    echo "$C" | grep -q 'val sharedTunnels = HashMap<String, NamedTunnel>()' || return 1
    grep -q 'private val backend get() = sharedBackend(applicationContext)' "$f" || return 1
    grep -q 'private val tunnels get() = sharedTunnels' "$f" || return 1
    grep -qE 'private val (backend|tunnels) (by lazy|=)' "$f" && return 1   # no per-instance copy
    return 0
}

echo "== net-wg: one Go backend per process"
check "$SVC" && ok "the Go backend and the tunnel registry are process-wide (companion), the service only reaches them" || bad "the engine can raise a second Go device per service instance"

T="$(mktemp)"; trap 'rm -f "$T"' EXIT
sed 's/private val backend get() = sharedBackend(applicationContext)/private val backend by lazy { GoBackend(applicationContext) }/' "$SVC" > "$T"
check "$T" && bad "mutation (backend per instance) not caught" || ok "mutation caught: a backend per service instance"
sed 's/private val tunnels get() = sharedTunnels/private val tunnels = HashMap<String, NamedTunnel>()/' "$SVC" > "$T"
check "$T" && bad "mutation (tunnel registry per instance) not caught" || ok "mutation caught: a tunnel registry per service instance"

echo
echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
