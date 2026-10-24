#!/bin/bash
# test-browser-private-names-start.sh — emulated.app must just work.
#
# THE FAILURE THIS EXISTS FOR. emulated.app showed "the private DNS server at 127.0.0.1:5354 did not answer": the
# web server app bound its DNS only inside its activity, so nothing ran after a reboot, a kill or Doze, and the
# browser asked once and gave up. The contract now, all from ONE declaration (ac_cloud-c3-webserver/build.json::serving):
#   owned names   endpoints[].resolve=declared -> baked as private_names.owned, resolved with no DNS
#   the service   serving.keepalive -> baked as private_names.service and the package in <queries>
#   the page      the browser starts that service (explicit intent) and retries before it shows the error
#   the viewport  the mobile view honours <meta viewport> (useWideViewPort), or width=1200 pages render squeezed
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"; APP="$(cd "$HERE/.." && pwd)"; REPO="$(cd "$APP/.." && pwd)"
WS="$REPO/ac_cloud-c3-webserver"
LIB="$REPO/ab_cloud-libs-shared/libs/browser/src/main/java/com/diegonmarcos/superapp/browser"
fails=0
ok()   { echo "  PASS  $1"; }
bad()  { echo "  FAIL  $1"; fails=$((fails + 1)); }
has()  { grep -qE -- "$2" "$1" 2>/dev/null; }

check() { # check <root> <label-suffix>
  local ws="$1/ac_cloud-c3-webserver" app="$1/ac_cloud-browser" lib="$1/ab_cloud-libs-shared/libs/browser/src/main/java/com/diegonmarcos/superapp/browser"
  local r=0
  python3 - "$ws/build.json" <<'PY' || r=1
import json, sys
sv = json.load(open(sys.argv[1]))["serving"]
ka = sv.get("keepalive") or {}
assert ka.get("package") and ka.get("service") and ka.get("receiver"), "serving.keepalive lacks package/service/receiver"
assert ka["service"].startswith(ka["package"] + "."), "keepalive service is not in keepalive package"
res = {e["id"]: e.get("resolve") for e in sv["endpoints"]}
assert all(v in ("declared", "dns") for v in res.values()), "an endpoint has no resolve: %r" % res
assert res.get("emulated") == "declared", "emulated.app must resolve from the declaration"
PY
  has "$app/app/build.gradle" "owned: *servingDecl\.endpoints\.findAll \{ it\.resolve == 'declared' \}" || r=1
  has "$app/app/build.gradle" "service: \['package': webserverPackage" || r=1
  has "$app/app/src/main/AndroidManifest.xml" '<package android:name="\$\{webserverPackage\}"' || r=1
  has "$lib/BrowserHostFragment.kt" 'resolveAsync\(url, start = \{ startPrivateNamesService' || r=1
  has "$lib/BrowserHostFragment.kt" 'ctx\.startForegroundService\(android\.content\.Intent\(\)\.setClassName\(target\.pkg, target\.cls\)\)' || r=1
  has "$lib/PrivateNames.kt" 'fun ladder\(' || r=1
  has "$lib/PrivateNames.kt" 'ownedAddress\(name\)\?\.let' || r=1
  # the page must not send a private name to the system resolver: loadUrl of a matched name stays behind the ladder
  # the viewport moved into ViewModePlan (BrowserDevTools.kt): the page applies the plan's flag, and the plan keeps it true
  ! grep -nE 'useWideViewPort = false' "$lib/BrowserHostFragment.kt" >/dev/null || r=1
  has "$lib/BrowserHostFragment.kt" 'useWideViewPort = plan\.wideViewPort' || r=1
  ! grep -nE 'wideViewPort = false' "$lib/BrowserDevTools.kt" >/dev/null || r=1
  has "$lib/BrowserDevTools.kt" 'ViewModePlan\(ua, wideViewPort = true' || r=1
  return $r
}

if check "$REPO"; then ok "the owned names, the keep-alive service, the retry and the viewport are all wired from the declaration"; else bad "the private-name start path is not wired (see the checks above)"; fi

# mutations: each planted defect must turn the check red
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
mutate() { # mutate <title> <file relative to repo> <python replace old> <new>
  NSEQ=$((${NSEQ:-0} + 1)); local t="$WORK/r$$_$NSEQ"; mkdir -p "$t/ac_cloud-c3-webserver" "$t/ac_cloud-browser/app/src/main" "$t/ab_cloud-libs-shared/libs/browser/src/main/java/com/diegonmarcos/superapp/browser"
  cp "$REPO/ac_cloud-c3-webserver/build.json" "$t/ac_cloud-c3-webserver/"
  cp "$REPO/ac_cloud-browser/app/build.gradle" "$t/ac_cloud-browser/app/"; cp "$REPO/ac_cloud-browser/app/src/main/AndroidManifest.xml" "$t/ac_cloud-browser/app/src/main/"
  cp "$LIB/BrowserHostFragment.kt" "$LIB/BrowserDevTools.kt" "$LIB/PrivateNames.kt" "$t/ab_cloud-libs-shared/libs/browser/src/main/java/com/diegonmarcos/superapp/browser/"
  python3 - "$t/$2" "$3" "$4" <<'PY' || { bad "mutation '$1' did not apply"; return; }
import sys
p, old, new = sys.argv[1:4]; s = open(p).read(); n = s.replace(old, new, 1)
assert n != s; open(p, "w").write(n)
PY
  if check "$t" >/dev/null 2>&1; then bad "mutation '$1' stayed green"; else ok "mutation '$1' goes red"; fi
}
BR=ab_cloud-libs-shared/libs/browser/src/main/java/com/diegonmarcos/superapp/browser
mutate "emulated.app no longer resolves from the declaration" ac_cloud-c3-webserver/build.json '"id": "emulated",
        "resolve": "declared"' '"id": "emulated",
        "resolve": "dns"'
mutate "the keep-alive declaration dropped"   ac_cloud-c3-webserver/build.json '"receiver": "com.diegonmarcos.cloudwebserver.BootReceiver"' '"receiverx": "x"'
mutate "the service is not baked into the browser" ac_cloud-browser/app/build.gradle "service: ['package': webserverPackage" "servicex: ['package': webserverPackage"
mutate "the webserver package is not queryable" ac_cloud-browser/app/src/main/AndroidManifest.xml '${webserverPackage}' 'x.y'
mutate "the page stops starting the service"  $BR/BrowserHostFragment.kt 'resolveAsync(url, start = { startPrivateNamesService' 'resolveAsync(url, start = { false; startPrivateNamesServiceX'
mutate "the owned rung is removed from the ladder" $BR/PrivateNames.kt 'ownedAddress(name)?.let' 'null?.let'
mutate "the mobile view ignores the viewport again" $BR/BrowserDevTools.kt 'ViewModePlan(ua, wideViewPort = true' 'ViewModePlan(ua, wideViewPort = false'
mutate "the page stops applying the plan's viewport" $BR/BrowserHostFragment.kt 's.useWideViewPort = plan.wideViewPort' 's.useWideViewPort = false'

[ "$fails" -eq 0 ] || { echo "private-names start: $fails check(s) FAILED"; exit 1; }
echo "private-names start: all assertions passed"
