#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════╗
# ║ Cloud Writer #800: the status probe, routes, Listen, documents   ║
# ╚══════════════════════════════════════════════════════════════════╝
#
# Static assertions over the source (no JDK, no SDK, no gradle). The pure
# logic behind them — routing and fallback, the segmenter, the OpenRouter
# wire, the document store, the markdown tools — is the plain-JVM :core
# module, unit-tested and PIT-mutated in the JVM step; this file checks the
# Android wiring around it, which no JVM suite can see.
#
# test-cloud-writer-routes-not-vacuous.sh plants one defect per assertion in
# a copy and requires the matching line here to go red.
#
#   S1  the status probe waits for the ITextTools bind before reading the
#       snapshot (the #800 false "older than the contract")
#   S2  each repairable state names the serving app
#   S3  the status card offers the one-tap Store repair
#   S4  build.json::update is complete

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && while [ "$PWD" != "/" ] && [ ! -e "$PWD/.git" ]; do cd ..; done; printf '%s' "$PWD")"
APP="$ROOT/ac_cloud-writer"
SRC="$APP/app/src/main/java/com/diegonmarcos/cloudwriter"
RES="$APP/app/src/main/res"

FAILURES=0
pass() { printf 'ok     %s\n' "$1"; }
fail() { printf 'FAIL   %s\n' "$1"; FAILURES=$((FAILURES + 1)); }

# Comments dropped: prose may NAME a call the code does not make.
code() { sed -e 's,^[[:space:]]*//.*,,' -e 's,^[[:space:]]*\*.*,,' -e 's,^[[:space:]]*/\*.*,,' "$@"; }

for f in "$SRC/ServingApp.kt" "$SRC/MainActivity.kt" "$APP/build.json" "$RES/values/strings.xml"; do
    [ -f "$f" ] || { echo "FAIL   $f is missing — every assertion below would read nothing and pass"; exit 1; }
done

SERVING="$(code "$SRC/ServingApp.kt")"
MAINACT="$(code "$SRC"/*.kt)"

# ── S1 ── wait for the bind, THEN ask ─────────────────────────────────────
PROBE="$(awk '/fun probe\(/,/^        }$/' <<<"$SERVING")"
if [ -z "$PROBE" ]; then
    fail "S1 ServingApp.probe could not be read — the check has no subject"
else
    wait_at="$(grep -n 'awaitBound(' <<<"$PROBE" | head -1 | cut -d: -f1)"
    ask_at="$(grep -n 'aiRoutingApp()' <<<"$PROBE" | head -1 | cut -d: -f1)"
    if [ -n "$wait_at" ] && [ -n "$ask_at" ] && [ "$wait_at" -lt "$ask_at" ]; then
        pass "S1 the probe waits for the bind before it reads aiRoutingSnapshot"
    else
        fail "S1 the probe reads aiRoutingSnapshot without first waiting for the bind — a binder that has not connected yet answers null and a current peer is reported as older than the contract"
    fi
fi
if grep -q 'while (!client.isConnected())' <<<"$MAINACT" && grep -q 'Thread.sleep(' <<<"$MAINACT"; then
    pass "S1 awaitBound polls the binder until the deadline"
else
    fail "S1 awaitBound no longer polls client.isConnected() — the wait is gone and the race is back"
fi

# ── S2 ── every repairable state names the app ────────────────────────────
python3 - "$RES/values/strings.xml" "$RES/values-es/strings.xml" <<'PY' || FAILURES=$((FAILURES + 1))
import re, sys
bad = 0
for path in sys.argv[1:]:
    s = open(path, encoding="utf-8").read()
    for key in ("status_serving_app_too_old", "status_serving_app_silent"):
        m = re.search(r'<string name="%s">(.*?)</string>' % key, s)
        if not m:
            print("FAIL   S2 %s has no %s" % (path, key)); bad += 1
        elif "%1$s" not in m.group(1):
            print("FAIL   S2 %s in %s does not name the serving app (%%1$s) — the owner is told to update 'it' without being told which app" % (key, path)); bad += 1
if not bad:
    print("ok     S2 the too-old and silent states name the serving app in both languages")
sys.exit(1 if bad else 0)
PY
if grep -q 'ServingApp.State.TOO_OLD -> getString' <<<"$MAINACT" && grep -q 'ServingApp.State.SILENT -> getString' <<<"$MAINACT"; then
    pass "S2 the status line distinguishes silent from too old"
else
    fail "S2 the status line no longer separates a silent peer from a too-old one — one is a restart, the other an update"
fi

# ── S3 ── one tap to the Store ────────────────────────────────────────────
if grep -q 'ServingApp.openStore(' <<<"$MAINACT" && grep -q 'storeRepair.value = serving.needsStore' <<<"$MAINACT"; then
    pass "S3 a repairable status offers the one-tap Store button"
else
    fail "S3 the status card no longer offers the Store repair for a missing, silent or too-old serving app"
fi

# ── S4 ── the Store target is declared ────────────────────────────────────
python3 - "$APP/build.json" <<'PY' || FAILURES=$((FAILURES + 1))
import json, sys
u = json.load(open(sys.argv[1])).get("update") or {}
need = ["store_package", "store_activity", "store_extras", "fallback_url", "bind_wait_ms"]
missing = [k for k in need if not u.get(k)]
if missing:
    print("FAIL   S4 build.json::update lacks %s — the status card's Store tap has nowhere to go" % ", ".join(missing))
    sys.exit(1)
if not str(u["fallback_url"]).startswith("https://"):
    print("FAIL   S4 build.json::update.fallback_url is not https"); sys.exit(1)
print("ok     S4 build.json::update declares the Store target, the fallback and the bind wait")
PY

echo "── $FAILURES failed ──"
[ "$FAILURES" = "0" ]
