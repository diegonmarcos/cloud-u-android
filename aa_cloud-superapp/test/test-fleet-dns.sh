#!/usr/bin/env bash
# #740 Configs ▸ Watchdog ▸ Mesh ▸ DNS — the fleet resolver, chosen once and
# applied at the SuperApp's VPN.
#
#   D1  the page: config/dns sits right after Cloud Mesh in Watchdog ▸ Mesh and
#       SectionPages routes it to DnsFragment
#   D2  the declaration (build.json::ui.dns) is complete: a usable default, one
#       Mirror, public presets with a primary AND a fallback list, a Private-only
#       that can take no fallback, a Private-with-fallbacks whose choices are
#       declared presets, a reason on every unavailable preset, mesh zones that
#       hold the mesh test name and not the public one
#   D3  ONE application point: every path that brings the tunnel UP hands it
#       toTunnelConfig() (the fleet choice), and that is built from
#       FleetDns.vpnServers — no connect path may bypass it with toWgConfig()
#   D4  data-driven: the declaration is baked (UI_DNS_B64) and read, and no
#       resolver address from it is written in any Kotlin source
#   D5  the behaviour guards live in FleetDnsTest and none of them was dropped
#
# WHAT each preset puts on the VPN (Private-only has no fallback, split DNS for
# mesh names, fail-loud without a fleet resolver) is asserted by
# app/src/test/.../network/FleetDnsTest.kt on the declaration this build bakes.
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"
BJ="$APP/build.json"
SRC="$APP/app/src/main/java/com/diegonmarcos/superapp"
PAGES="$SRC/launcher/SectionPages.kt"
DNS="$SRC/network/FleetDns.kt"
FRAG="$SRC/network/DnsFragment.kt"
WGP="$SRC/network/WireGuardPrefs.kt"
GRADLE="$APP/app/build.gradle"
UT="$APP/app/src/test/java/com/diegonmarcos/superapp/network/FleetDnsTest.kt"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
for f in "$BJ" "$PAGES" "$DNS" "$FRAG" "$WGP" "$GRADLE" "$UT"; do
  [ -f "$f" ] || { echo "ERROR: missing $f — a check over nothing passes" >&2; exit 2; }
done
command -v python3 >/dev/null || { echo "ERROR: python3 missing" >&2; exit 2; }

echo "== D1: the page =="
out="$(python3 - "$BJ" <<'EOF'
import json, sys
pages = next(s for s in json.load(open(sys.argv[1]))['ui']['sections'] if s['id'] == 'config')['pages']
mesh = [p['id'] for p in pages if p.get('group') == 'Watchdog' and p.get('subgroup') == 'Mesh' and not p.get('hidden')]
dns = next((p for p in pages if p['id'] == 'dns'), {})
print('MESH', ','.join(mesh))
print('LABEL', dns.get('label'), dns.get('hidden', False))
EOF
)"
echo "$out" | sed 's/^/    /'
echo "$out" | grep -q '^MESH wg,dns,' && ok "DNS is the tile right after Cloud Mesh in Watchdog ▸ Mesh" \
  || bad "config/dns is not declared directly after wg in Watchdog ▸ Mesh"
echo "$out" | grep -qx 'LABEL DNS False' && ok "the page reads DNS and is a visible tile" \
  || bad "config/dns is missing, hidden or not labelled DNS"
grep -qE 'pageId == "dns" +-> com\.diegonmarcos\.superapp\.network\.DnsFragment\.newInstance\(\)' "$PAGES" \
  && ok "SectionPages routes config/dns to DnsFragment" \
  || bad "no SectionPages branch opens DnsFragment for config/dns — the tile would open the placeholder"

echo "== D2: the declaration =="
out="$(python3 - "$BJ" <<'EOF'
import json, sys
d = json.load(open(sys.argv[1]))['ui'].get('dns')
if not d: print('no ui.dns'); sys.exit()
ps = {p['id']: p for p in d.get('presets', [])}
pr = []
if len(ps) != len(d.get('presets', [])): pr.append('duplicate preset id')
df = ps.get(d.get('default_preset'))
if not df or df.get('available', True) is False: pr.append('default_preset %r is not a usable preset' % d.get('default_preset'))
for p in ps.values():
    if p.get('kind') not in ('mirror', 'public', 'private', 'warp'): pr.append('%s: unknown kind %r' % (p['id'], p.get('kind')))
    if p.get('available', True) is False and not str(p.get('unavailable_reason', '')).strip():
        pr.append('%s is unavailable without a reason' % p['id'])
    if p.get('kind') == 'public' and (not p.get('servers') or not p.get('fallback')):
        pr.append('%s: a public preset needs servers AND fallback' % p['id'])
if [p['id'] for p in ps.values() if p.get('kind') == 'mirror'].__len__() != 1: pr.append('not exactly one mirror preset')
priv = [p for p in ps.values() if p.get('kind') == 'private']
only = [p for p in priv if not p.get('fallback_allowed')]
fb   = [p for p in priv if p.get('fallback_allowed')]
if len(only) != 1: pr.append('expected ONE private preset with no fallback, got %d' % len(only))
for p in only:
    if p.get('fallback_choices') or p.get('default_fallbacks') or p.get('servers') or p.get('fallback'):
        pr.append('%s is private-only yet declares fallbacks or servers of its own' % p['id'])
if len(fb) != 1: pr.append('expected ONE private preset with fallbacks, got %d' % len(fb))
for p in fb:
    ch = p.get('fallback_choices', [])
    if not ch: pr.append('%s offers no fallback choices' % p['id'])
    for c in ch:
        if c not in ps or ps[c].get('kind') not in ('public', 'warp'):
            pr.append('%s: fallback choice %r is not a declared public/warp preset' % (p['id'], c))
    for c in p.get('default_fallbacks', []):
        if c not in ch: pr.append('%s: default fallback %r is not one of its choices' % (p['id'], c))
z = d.get('mesh_zones', [])
inzone = lambda n: any(n == x or n.endswith('.' + x) for x in z)
tn = d.get('test_names', {})
if not z: pr.append('no mesh_zones')
if not inzone(tn.get('mesh', '')): pr.append('test_names.mesh %r is in no mesh zone' % tn.get('mesh'))
if not tn.get('public') or inzone(tn['public']): pr.append('test_names.public %r is missing or a mesh name' % tn.get('public'))
modes = [m['id'] for m in d.get('android_private_dns', {}).get('modes', [])]
if modes != ['off', 'opportunistic', 'hostname']: pr.append('android_private_dns.modes = %r, not Android\'s three' % modes)
print('; '.join(pr) or 'OK')
EOF
)"
[ "$out" = OK ] && ok "ui.dns: usable default, one mirror, public primary+fallback, private-only takes none, fallbacks name declared presets, mesh zones hold the mesh test name" \
  || bad "ui.dns: $out"

echo "== D3: one application point =="
n_fleet=$(grep -rnE 'toTunnelConfig\(\)' "$SRC" --include=*.kt | grep -v 'fun toTunnelConfig' | grep -c .)
# toWgConfig() is the literal form; its ONE legitimate caller is the .conf export
# (exportLauncher in WireGuardFragment). Any other caller is a path that would
# bring the tunnel up — or hand it on — without the fleet's DNS.
callers=$(grep -rnF 'toWgConfig()' "$SRC" --include=*.kt | grep -v 'fun toWgConfig' || true)
export_ok=$(awk '/val exportLauncher/{f=1} /val vpnConsentLauncher/{f=0} f && /toWgConfig\(\)/{print FILENAME":"FNR}' "$SRC/network/WireGuardFragment.kt")
others=$(printf '%s\n' "$callers" | grep . | grep -v "^${export_ok}:" || true)
[ -n "$export_ok" ] && [ -z "$others" ] && ok "the .conf export is the only toWgConfig() caller — no path brings the tunnel up without the fleet DNS" \
  || { bad "toWgConfig() is called outside the export:"; printf '%s\n' "$others" | sed 's/^/      /'; }
[ "$n_fleet" -ge 4 ] && ok "$n_fleet connect paths hand the tunnel toTunnelConfig() (Mesh page, network popup, device controls, DNS page re-apply)" \
  || bad "only $n_fleet connect path(s) use toTunnelConfig() — expected the Mesh page, the network popup, the device controls and the DNS page"
grep -qF 'buildConfig(FleetDns.vpnServers(app, interfaceDns).joinToString(", "))' "$WGP" \
  && ok "toTunnelConfig() takes its DNS from FleetDns.vpnServers with the Cloud Mesh field as the fleet resolver" \
  || bad "toTunnelConfig() does not build its DNS from FleetDns.vpnServers"

echo "== D4: data-driven =="
grep -qF 'buildConfigField "String", "UI_DNS_B64", "\"${uiDnsB64}\""' "$GRADLE" && grep -qF 'stripDocs(buildJson.ui.dns)' "$GRADLE" \
  && ok "app/build.gradle bakes build.json::ui.dns as UI_DNS_B64" || bad "ui.dns is not baked into BuildConfig"
grep -qF 'BuildConfig.UI_DNS_B64' "$DNS" && ok "FleetDns reads the baked declaration" || bad "FleetDns does not read UI_DNS_B64"
lits="$(python3 - "$BJ" "$SRC" <<'EOF'
import json, sys, pathlib
d = json.load(open(sys.argv[1]))['ui']['dns']
addrs = {a for p in d['presets'] for k in ('servers', 'fallback') for a in p.get(k, [])}
addrs |= {h for p in d['presets'] for h in p.get('tls_hostnames', [])}
addrs |= set(d.get('android_private_dns', {}).get('hostname_suggestions', []))
hits = []
for f in pathlib.Path(sys.argv[2]).rglob('*.kt'):
    t = f.read_text(errors='replace')
    hits += ['%s: %s' % (f.name, a) for a in sorted(addrs) if '"%s"' % a in t]
print(len(addrs)); print('\n'.join(hits))
EOF
)"
n_addr=$(echo "$lits" | head -1); hits=$(echo "$lits" | tail -n +2 | grep . || true)
[ "$n_addr" -ge 6 ] && ok "checked $n_addr declared resolver addresses / hostnames" || bad "only $n_addr declared addresses — the literal check walks nothing"
[ -z "$hits" ] && ok "no declared resolver address is a string literal in Kotlin" \
  || { bad "resolver literals in Kotlin:"; echo "$hits" | sed 's/^/      /'; }

echo "== D5: the behaviour guards exist =="
for t in eachPublicPresetIsItsServersThenItsFallback mirrorPutsNoServerOnTheVpn \
         privateOnlyIsTheFleetResolverAndNothingElse privateWithFallbacksIsFleetFirstThenTheChosenFallbackInOrder \
         anUnavailablePresetIsNeverChosenNorUsedAsAFallback aPrivatePresetWithNoFleetResolverFailsLoudly \
         meshNamesGoToTheFleetResolverWhileTheMeshIsUp; do
  grep -qE "@Test fun $t\(\)" "$UT" && ok "FleetDnsTest.$t" || bad "FleetDnsTest.$t is gone"
done

echo "== RESULT(#740 fleet dns): $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
