#!/usr/bin/env bash
# Configs > Network > ADB Shell: the privileged channel's page. Static (no build, no device).
#  1. build.json: the page is declared once, in Setup > Network, RIGHT AFTER Apps Mesh, labelled "ADB Shell".
#  2. SectionPages routes it to AdbShellFragment, which only hosts the lib's ShellChannelPanel.
#  3. The panel lives in libs:shizuku-adb-debug-tools and carries the four modes, the layer status and
#     every action; Cloud Store's setting uses the SAME ChannelModeBar (no second copy of the UI).
#  4. The SuperApp's default mode is the local server (it declares its own local_server port).
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"; ROOT="$(cd "$HERE/../.." && pwd)"
PASS=0; FAIL=0; ok(){ PASS=$((PASS+1)); echo "  PASS: $1"; }; bad(){ FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
L="$ROOT/ab_cloud-libs-shared/libs/shizuku-adb-debug-tools/src/main/java/com/diegonmarcos/superapp/adbdebug"
out="$(python3 - "$ROOT/aa_cloud-superapp/build.json" <<'PY'
import json, sys
pages = next(s for s in json.load(open(sys.argv[1]))['ui']['sections'] if s['id'] == 'config')['pages']
net = [p['id'] for p in pages if p.get('subgroup') == 'Network' and not p.get('hidden')]
by = {p['id']: p for p in pages}
print('AFTER', net[net.index('apps-mesh') + 1] if 'apps-mesh' in net else 'none')
print('LABEL', by.get('adb-shell', {}).get('label'))
print('ONCE', sum(1 for p in pages if p['id'] == 'adb-shell'))
print('OWN', 'local_server' in json.load(open(sys.argv[1])).get('shizuku_diagnostics', {}))
PY
)"
echo "$out" | grep -qx 'AFTER adb-shell' && ok "ADB Shell comes right after Apps Mesh" || bad "ADB Shell is not right after Apps Mesh"
echo "$out" | grep -qx 'LABEL ADB Shell' && ok "the label is ADB Shell (build.json, one place)" || bad "the label is not ADB Shell"
echo "$out" | grep -qx 'ONCE 1' && ok "declared once" || bad "declared 0 or 2+ times"
echo "$out" | grep -qx 'OWN True' && ok "the SuperApp declares its own local server, so its default mode is Local server" || bad "no local_server in the SuperApp's build.json"
grep -q 'pageId == "adb-shell".*AdbShellFragment' "$ROOT/aa_cloud-superapp/app/src/main/java/com/diegonmarcos/superapp/launcher/SectionPages.kt" && ok "routed to AdbShellFragment" || bad "adb-shell is not routed"
grep -q 'ShellChannelPanel(' "$ROOT/aa_cloud-superapp/app/src/main/java/com/diegonmarcos/superapp/configs/AdbShellFragment.kt" && ok "the fragment hosts the lib panel" || bad "the fragment does not host ShellChannelPanel"
for a in '"Pair"' '"Reconnect"' '"Start server"' '"Restart"' '"Stop server"' '"Open Shizuku"' '"Developer options"' '"Test"'; do
  grep -qF "actionButton($a" "$L/ShellChannelPanel.kt" && ok "action $a in the lib panel" || bad "action $a missing"
done
for m in LOCAL_SERVER EMBEDDED_ONLY SHIZUKU AUTO; do grep -q "ChannelMode.$m" "$L/ShellChannelPanel.kt" && ok "mode $m in the selector" || bad "mode $m missing"; done
grep -q 'ChannelModeBar' "$ROOT/ac_cloud-store/app/src/main/java/com/diegonmarcos/cloudstore/shell/ShellChannelSection.kt" && ok "Cloud Store uses the same ChannelModeBar" || bad "Cloud Store has its own mode UI"
echo "== RESULT: $PASS passed, $FAIL failed =="; [ "$FAIL" -eq 0 ]
