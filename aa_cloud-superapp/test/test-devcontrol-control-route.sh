#!/usr/bin/env bash
# #904 /api/devcontrol/control — a Control-panel switch from the fleet API.
#
# The terminal cannot tap a tile; the mesh tunnel (WireGuard engine + VPN consent) was
# "stored, down" with no way to bring it up from the loopback API. The route flips ONE
# catalog entry (DeviceControls.byId) through the tile's own set, after the tile's own
# blocked reason, and reads the live state back. No second catalog, no remembered boolean.
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRV="$ROOT/app/src/main/java/com/diegonmarcos/superapp/devcontrol/DevControlServer.kt"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  ok   $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL $1"; }

echo "== #904 devcontrol/control"
grep -q 'Spec("devcontrol/control",' "$SRV" && ok "the route is in the /api/docs catalog" || bad "no Spec for devcontrol/control"
grep -q '"devcontrol/control" ->' "$SRV" && ok "the route is handled" || bad "no handler for devcontrol/control"
H="$(awk '/"devcontrol\/control" ->/,/reply\(writer, "200 OK", o.toString\(\)/' "$SRV")"
echo "$H" | grep -q 'DeviceControls.byId\[id\]' && ok "the switch is the catalog entry (DeviceControls.byId), not a second list" || bad "handler does not resolve DeviceControls.byId"
echo "$H" | grep -q 'c.blocked(ctx)' && ok "the tile's blocked reason is asked" || bad "blocked() is not consulted"
b=$(echo "$H" | grep -n 'val why = c.blocked(ctx)' | cut -d: -f1); s=$(echo "$H" | grep -n 'c.set.invoke' | cut -d: -f1)
[ -n "$b" ] && [ -n "$s" ] && [ "$b" -lt "$s" ] && ok "blocked is checked before set runs" || bad "set may run while the panel would refuse"
echo "$H" | grep -q 'put("state", c.read(ctx))' && ok "the state answered is read live (Control.read)" || bad "state is not the live read"
echo "$H" | grep -q 'on == null -> o.put("ok", true).put("state", c.read(ctx))' && ok "without on: a read, nothing flipped" || bad "a bare call is not a plain read"

echo
echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
