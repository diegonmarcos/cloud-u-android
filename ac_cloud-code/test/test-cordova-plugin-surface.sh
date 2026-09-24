#!/usr/bin/env bash
# ac_cloud-code tester (#562): every Cordova service the WEB bundle hands the
# bridge must be registered by a plugin this build INSTALLS.
#
# The Cordova form of #543's Capacitor trap. cloud-notes shipped a web side
# calling registerPlugin('Auth') after the de-clouding deleted the native class;
# Capacitor threw on first use and no compiler saw it. Cordova fails the same
# way and just as silently: cordova.exec(ok, err, 'Service', ...) against a
# service no installed plugin.xml declares is a runtime "Class not found", on
# the phone, the first time that code path runs. Neither rspack nor gradle can
# see it — the bridge is resolved by name at runtime.
#
# This app REMOVES plugins on purpose (build.json::upstream.flavor: the F-Droid
# flavour drops cordova-plugin-iap; the proot plugin is pruned from the tree),
# which is exactly the kind of change that opens this gap. So both sides are
# DERIVED by test/cordova_surface.py — service names from the bundle's own
# exec() calls, registrations from package.json::cordova.plugins minus the
# flavour's removals mapped to their plugin.xml <feature>s. No name is written
# in either file.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FAIL=0
ok()   { printf '  PASS  %s\n' "$1"; }
fail() { printf '  FAIL  %s\n' "$1"; FAIL=1; }

report="$(python3 "$ROOT/test/cordova_surface.py" "$ROOT")"
status=$?
printf '%s\n' "$report"

if [ "$status" -ne 0 ]; then
  fail "plugin-surface derivation could not run (exit $status) — see ERROR above"
else
  derived="$(printf '%s\n' "$report" | grep '^DERIVED ' || true)"
  [ -n "$derived" ] && ok "$derived" || fail "derivation printed no DERIVED line"

  if printf '%s\n' "$report" | grep -q '^MISSING '; then
    fail "a service the web bundle calls is registered by no installed plugin — it throws on the phone"
  else
    ok "every Cordova service name the bundle calls is registered by an installed plugin"
  fi

  covered="$(printf '%s\n' "$report" | grep -c '^COVERED ' || true)"
  [ "$covered" -ge 5 ] && ok "$covered services resolved end to end" \
    || fail "only $covered services resolved — the derivation is not seeing the bundle"
fi

# The flavour's removals must be real: a removed plugin still listed where
# `cordova prepare` restores from would come back on the next platform add.
while IFS= read -r p; do
  [ -n "$p" ] || continue
  if printf '%s\n' "$report" | grep -q "^NOTLOADED .*($p)"; then
    ok "removed plugin $p is not loaded by this flavour"
  else
    fail "build.json removes $p but the derivation did not see it as not-installed"
  fi
done < <(python3 -c 'import json,sys; [print(p) for p in json.load(open(sys.argv[1]))["upstream"]["flavor"]["removed_plugins"]]' "$ROOT/build.json")

if [ "$FAIL" -eq 0 ]; then
  printf '  cloud-code cordova plugin-surface tester: ALL PASS\n'
else
  printf '  cloud-code cordova plugin-surface tester: FAIL\n' >&2
  exit 1
fi
