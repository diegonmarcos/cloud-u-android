#!/usr/bin/env bash
# #573 the Mesh page's "Cloud" provider is the BAKED preset: one device's Address line
# (build.json ui.wireguard_default). On a phone the Account declares as another device it
# re-identified the tunnel (an A37 came up with the S21+'s 10.0.0.9). The port refuses the
# preset when none of its baked addresses is the picked device's declared wg_ip/wg_ipv6,
# from the explicit pick only - never inferred from the live tunnel, which is what it guards.
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PORT="$ROOT/app/src/main/java/com/diegonmarcos/superapp/network/mesh/AndroidMeshPort.kt"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  ok   $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL $1"; }

check() { # $1 = file
    local f="$1" A G
    A="$(awk '/override fun applyCloudPreset\(\)/,/^    }/' "$f")"
    echo "$A" | grep -q 'declaredAddresses()' || return 1
    echo "$A" | grep -q 'BuildConfig.UI_WG_INTERFACE_ADDRESS' || return 1
    echo "$A" | grep -q 'baked.none { it in mine }' || return 1
    g=$(echo "$A" | grep -n 'baked.none' | cut -d: -f1); a=$(echo "$A" | grep -n 'prefs.applyCloudPreset()' | cut -d: -f1)
    [ -n "$g" ] && [ -n "$a" ] && [ "$g" -lt "$a" ] || return 1
    G="$(awk '/private fun declaredAddresses\(\)/,/^    }/' "$f")"
    echo "$G" | grep -q 'VaultCockpit.selectedDevice(ctx)' || return 1
    echo "$G" | grep -q 'VaultCockpit.devices(bundle)' || return 1
    echo "$G" | grep -q 'it.id == picked' || return 1
    echo "$G" | grep -qE 'interfaceAddress|deviceFor\(|backend' && return 1
    return 0
}

echo "== #573 mesh cloud preset guard"
check "$PORT" && ok "applyCloudPreset refuses a baked Address the picked device does not declare, before prefs are touched" || bad "the guard is missing or runs after prefs.applyCloudPreset()"
A="$(awk '/override fun applyCloudPreset\(\)/,/^    }/' "$PORT")"
echo "$A" | grep -q 'this phone is declared as' && ok "the refusal names the declared addresses" || bad "refusal does not say what this phone is declared as"

T="$(mktemp)"; trap 'rm -f "$T"' EXIT
sed '/baked.none { it in mine }/,+1d' "$PORT" > "$T"; check "$T" && bad "mutation (guard removed) not caught" || ok "mutation caught: guard removed"
sed 's/VaultCockpit.selectedDevice(ctx).ifBlank { return null }/prefs.interfaceAddress/' "$PORT" > "$T"; check "$T" && bad "mutation (pick inferred from the live tunnel) not caught" || ok "mutation caught: pick inferred from the live tunnel"

echo
echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
