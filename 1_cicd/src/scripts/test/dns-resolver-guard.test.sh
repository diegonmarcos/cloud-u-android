#!/usr/bin/env bash
# The DNS resolver guard (#741) must go RED for every way an app can resolve a
# name past Android's resolver, and stay green for the allowed trees and the
# loopback bridge. A guard that cannot fail is worse than none: it reads as
# enforcement. Each plant is checked to have landed before its verdict counts.
#
# Two halves:
#   1. a fixture repository holding exactly what the REAL policy names (the
#      allowed trees, the exempted files copied from this checkout) plus one
#      planted file per case, judged with the real policy;
#   2. the brief's own mutation on the REAL tree: an `8.8.8.8` lookup dropped
#      into an app goes red, and the tree is clean again once it is removed.
#
# Run from anywhere: bash 1_cicd/src/scripts/test/dns-resolver-guard.test.sh
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
GUARD="$ROOT/1_cicd/src/scripts/cloud-android-dns-resolver-guard.py"
POLICY="$ROOT/1_cicd/src/data/dns-resolver-guard.json"
W="$(mktemp -d)"
PLANT_REAL="$ROOT/ac_cloud-drive/app/src/main/java/DnsResolverGuardProbe.kt"
trap 'rm -rf "$W"; rm -f "$PLANT_REAL"' EXIT

fail=0
ok()  { printf 'ok    %s\n' "$1"; }
bad() { printf 'FAIL  %s\n' "$1"; fail=1; }

# ── the fixture: the real policy's world, nothing else ───────────────────────
FX="$W/fx"
mkdir -p "$FX"
git -C "$FX" init -q
python3 - "$POLICY" "$ROOT" "$FX" <<'PY'
import json, os, shutil, sys
policy, root, fx = sys.argv[1:4]
cfg = json.load(open(policy))
for prefix in cfg["allow"]:
    os.makedirs(os.path.join(fx, prefix), exist_ok=True)
    open(os.path.join(fx, prefix, "placeholder.txt"), "w").write("allowed tree\n")
for e in cfg["exempt"] + cfg.get("probe", []):
    dst = os.path.join(fx, e["path"])
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    shutil.copy(os.path.join(root, e["path"]), dst)
os.makedirs(os.path.join(fx, "ac_cloud-probe"), exist_ok=True)
open(os.path.join(fx, "ac_cloud-probe", "Clean.kt"), "w").write(
    "val a = java.net.InetAddress.getByName(\"github.com\")\n")
PY

judge() { python3 "$GUARD" "$FX" "$POLICY" >"$W/out" 2>&1; }

if judge; then ok "the fixture with nothing planted is green"
else bad "the clean fixture is red: $(head -5 "$W/out")"; fi

# expect_red <case> <file under the fixture> <content>
expect_red() {
    local case="$1" rel="$2" content="$3"
    mkdir -p "$FX/$(dirname "$rel")"
    printf '%s\n' "$content" > "$FX/$rel"
    if ! grep -qF -- "$content" "$FX/$rel"; then bad "$case: MUTATION DID NOT APPLY"; return; fi
    if judge; then bad "$case: MUTATION SURVIVED — the guard is green over: $content"
    else ok "$case: red ($(grep -m1 'FAIL' "$W/out" | sed 's/^ *FAIL //' | cut -c1-90))"; fi
    rm -f "$FX/$rel"
}
# expect_green <case> <file> <content>
expect_green() {
    local case="$1" rel="$2" content="$3"
    mkdir -p "$FX/$(dirname "$rel")"
    printf '%s\n' "$content" > "$FX/$rel"
    if judge; then ok "$case: green"
    else bad "$case: red where it must not be: $(grep -m1 FAIL "$W/out")"; fi
    rm -f "$FX/$rel"
}

expect_red "a public resolver looked up from an app" \
    "ac_cloud-probe/Lookup.kt" 'val dns = java.net.InetAddress.getByName("8.8.8.8")'
expect_red "a terminal rootfs baking a public nameserver" \
    "ac_cloud-probe/rootfs/rootfs.json" '"nameservers": ["1.1.1.1"]'
expect_red "a resolv.conf written with a public server" \
    "ac_cloud-probe/setup.sh" "printf 'nameserver 9.9.9.9\\n' > /etc/resolv.conf"
expect_red "the mesh resolver hardcoded per app instead of the menu's split-DNS rule" \
    "ac_cloud-probe/setup.sh" "echo 'nameserver 10.0.0.1' > \$ROOTFS/etc/resolv.conf"
expect_red "an IPv6 public resolver" \
    "ac_cloud-probe/Net.java" 'String v6 = "2606:4700:4700::1111";'
expect_red "a DNS-over-HTTPS client library" \
    "ac_cloud-probe/build.gradle" "implementation 'com.squareup.okhttp3:okhttp-dnsoverhttps:4.12.0'"
expect_red "dnsjava" \
    "ac_cloud-probe/Resolver.java" 'import org.xbill.DNS.Lookup;'
expect_red "a custom OkHttp Dns" \
    "ac_cloud-probe/Client.kt" 'val dns = object : Dns { override fun lookup(h: String) = emptyList<java.net.InetAddress>() }'
expect_red "an OkHttp client handed a Dns of its own" \
    "ac_cloud-probe/Client2.kt" 'val c = OkHttpClient.Builder().dns(myDns).build()'
expect_red "a DoH endpoint" \
    "ac_cloud-probe/doh.ts" 'const u = "https://dns.google/dns-query";'
expect_red "a raw socket to :53" \
    "ac_cloud-probe/Raw.kt" 'sock.connect(java.net.InetSocketAddress("10.0.0.1", 53))'

expect_green "the loopback bridge in a resolv.conf" \
    "ac_cloud-probe/setup.sh" "printf 'nameserver 127.0.0.1\\n' > /etc/resolv.conf"
expect_green "OkHttp's own system resolver" \
    "ac_cloud-probe/Client3.kt" 'val c = OkHttpClient.Builder().dns(Dns.SYSTEM).build()'
expect_green "a public resolver inside the SuperApp, the declared DNS layer" \
    "aa_cloud-superapp/data/dns-presets.json" '{"cloudflare": "1.1.1.1"}'
expect_green "firestack's own DNS transports" \
    "ab_cloud-libs-shared/libs/firewall/doh.go" 'const u = "https://1.1.1.1/dns-query"'

# An exemption excuses one text in one file: the same file gaining another resolver is red.
first="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["exempt"][0]["path"])' "$POLICY")"
cp "$FX/$first" "$W/saved"
printf 'val pinned = "8.8.4.4"\n' >> "$FX/$first"
if ! grep -q '8\.8\.4\.4' "$FX/$first"; then bad "exempted file: MUTATION DID NOT APPLY"
elif judge; then bad "exempted file: MUTATION SURVIVED — an exemption excused a second, different resolver"
else ok "an exempted file gaining a different resolver: red"; fi
cp "$W/saved" "$FX/$first"

# A stale exemption fails too: delete the excused text and the entry must be reported.
: > "$FX/$first"
if [ -s "$FX/$first" ]; then bad "stale exemption: MUTATION DID NOT APPLY"
elif judge; then bad "stale exemption: MUTATION SURVIVED — an exemption that excuses nothing stayed green"
elif grep -q 'matches nothing any more' "$W/out"; then ok "stale exemption: red, and says which entry to delete"
else bad "stale exemption: red for another reason: $(grep -m1 FAIL "$W/out")"; fi
cp "$W/saved" "$FX/$first"

# #758 the on-device probe every app serves: deleting it, or a part of it, is red.
# probe_red <case> <path> <exact text to remove>
probe_red() {
    local case="$1" rel="$2" text="$3"
    cp "$FX/$rel" "$W/probe-saved"
    python3 - "$FX/$rel" "$text" <<'PY'
import sys
p, t = sys.argv[1:3]
s = open(p, encoding="utf-8").read()
open(p, "w", encoding="utf-8").write(s.replace(t, ""))
PY
    if grep -qF -- "$text" "$FX/$rel"; then bad "$case: MUTATION DID NOT APPLY"
    elif judge; then bad "$case: MUTATION SURVIVED — the guard is green without: $text"
    elif grep -q '\[probe\]' "$W/out"; then ok "$case: red, and names the probe"
    else bad "$case: red for another reason: $(grep -m1 FAIL "$W/out")"; fi
    cp "$W/probe-saved" "$FX/$rel"
}
SRV="ab_cloud-libs-shared/libs/devtools/src/main/java/com/diegonmarcos/superapp/devtools/AppDebugServer.kt"
probe_red "the /api/net/dns route removed from every app" "$SRV" '"net/dns" ->'
probe_red "/api/net/resolve stops asking Android's resolver" "$SRV" 'InetAddress.getAllByName(host)'
probe_red "the probe stops reporting whether the app is behind the VPN" "$SRV" 'NetworkCapabilities.TRANSPORT_VPN'
probe_red "the permission the probe reads the network with is dropped" \
    "ab_cloud-libs-shared/libs/devtools/src/main/AndroidManifest.xml" 'android.permission.ACCESS_NETWORK_STATE'
rm -f "$FX/$SRV"
if judge; then bad "probe file deleted: MUTATION SURVIVED"
elif grep -q '\[probe\]' "$W/out"; then ok "the debug server itself deleted: red"
else bad "probe file deleted: red for another reason: $(grep -m1 FAIL "$W/out")"; fi
cp "$ROOT/$SRV" "$FX/$SRV"
if judge; then ok "the fixture is green again once the probe is restored"
else bad "restored fixture is red: $(grep -m1 FAIL "$W/out")"; fi

# ── the brief's mutation, on the REAL tree ───────────────────────────────────
if python3 "$GUARD" "$ROOT" >"$W/real" 2>&1; then ok "the real tree is green before the plant"
else bad "the real tree is red before any plant: $(grep -m3 FAIL "$W/real")"; fi
printf 'val lookup = java.net.InetAddress.getByName("8.8.8.8")\n' > "$PLANT_REAL"
if ! grep -q '8\.8\.8\.8' "$PLANT_REAL"; then bad "real tree: MUTATION DID NOT APPLY"
elif python3 "$GUARD" "$ROOT" >"$W/real" 2>&1; then bad "real tree: MUTATION SURVIVED — an 8.8.8.8 lookup in cloud-drive is green"
elif grep -q 'DnsResolverGuardProbe.kt' "$W/real"; then ok "real tree: an 8.8.8.8 lookup in cloud-drive goes red and is named"
else bad "real tree: red but not for the plant: $(grep -m1 FAIL "$W/real")"; fi
rm -f "$PLANT_REAL"

[ "$fail" -eq 0 ] && echo "dns-resolver-guard tester: every bypass goes red, the bridge and the DNS layer stay green" || echo "FAIL — see above."
exit "$fail"
