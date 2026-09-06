#!/usr/bin/env bash
# Tester: a date of birth is STORED as ISO, and only ever converted when the
# conversion cannot be wrong.
#
# BUG: the field was labelled "(YYYY-MM-DD)", the wire contract documents
# `"birth": "1815-12-10"  // optional, ISO YYYY-MM-DD`, and ProfileSync sends
# prefs.birth verbatim — but a typed `18-07-1987` was stored and uploaded
# exactly as typed. The advisory named the correction and then declined to
# make it, so the value on the server was unparseable.
#
# FIX: on blur (this form has no Save button — every field persists per
# keystroke, so leaving the box IS the save), an UNAMBIGUOUS DD-MM-YYYY is
# rewritten to ISO in the box, in prefs, and in a snack that says what it
# became.
#
# THE AMBIGUITY RULE IS THE WHOLE POINT. 05-07-1987 is the 5th of July or the
# 7th of May depending on who typed it. Only a first field >12 settles it.
#   T1  the conversion is gated on day 13..31 and a real month
#   T2  it commits on BLUR, not per keystroke
#   T3  the change is VISIBLE — box rewritten and a note shown
#   T4  the advisory still exists for the ambiguous case
#   T5  nothing else rewrites a stored birth behind the user's back
set -uo pipefail
APP="$(cd "$(dirname "$0")/.." && pwd)"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  ok: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
has() { grep -qF "$2" "$1" 2>/dev/null; }

PF="$APP/app/src/main/java/com/diegonmarcos/superapp/profile/ProfileFragment.kt"
SY="$APP/app/src/main/java/com/diegonmarcos/superapp/profile/ProfileSync.kt"

echo "== the conversion refuses to guess =="
has "$PF" 'if (d.toInt() !in 13..31) return null' \
  && ok "T1: only a day >12 converts — 05-07-1987 is left alone" \
  || bad "T1: the day>12 gate is gone; ambiguous dates would be guessed"
has "$PF" 'if (mo.toInt() !in 1..12) return null' \
  && ok "T1: the month is still range-checked" \
  || bad "T1: a first field >12 would be taken to prove the second is a month"

# The rule, spelled out as a table. This mirrors isoFromDmy so the INTENT is
# reviewable here even though the Kotlin cannot be executed on this host.
python3 - <<'PY' && ok "T1: the rule's table is what we want" || bad "T1: rule table mismatch"
import re, sys
DMY = re.compile(r'^(\d{2})-(\d{2})-(\d{4})$')
def iso(t):
    m = DMY.match(t)
    if not m: return None
    d, mo, y = m.groups()
    if not (13 <= int(d) <= 31): return None
    if not (1 <= int(mo) <= 12): return None
    return "%s-%s-%s" % (y, mo, d)
want = {
    "18-07-1987": "1987-07-18",   # the real case: unambiguous, converts
    "31-12-1990": "1990-12-31",
    "13-01-2000": "2000-01-13",   # 13 is the first day that cannot be a month
    "05-07-1987": None,           # AMBIGUOUS — advisory only, never guessed
    "12-12-1990": None,           # both readings valid
    "1987-07-18": None,           # already ISO, untouched
    "25-13-1990": None,           # month out of range
    "32-01-1990": None,           # day out of range
    "00-07-1987": None,
    "1-4-1990":   None,           # not the two-digit shape
    "":           None,
}
bad = [(k, iso(k), v) for k, v in want.items() if iso(k) != v]
for k, got, exp in bad:
    print("    %r -> %r, expected %r" % (k, got, exp))
sys.exit(1 if bad else 0)
PY

echo "== it commits at the save moment, not mid-typing =="
has "$PF" 'setOnFocusChangeListener { v, hasFocus ->' \
  && ok "T2: conversion hangs off focus loss" \
  || bad "T2: the blur listener is gone"
if awk '/addTextChangedListener\(object : TextWatcher/,/^            }\)/' "$PF" \
     | grep -q 'prefs.birth ='; then
  bad "T2: a TextWatcher writes prefs.birth — that reorders under a typing finger"
else
  ok "T2: no keystroke watcher rewrites the stored date"
fi

echo "== the user can see what happened =="
has "$PF" 'v.setText(iso)' \
  && ok "T3: the box itself is rewritten, so the stored value is on screen" \
  || bad "T3: the box is not updated — the rewrite would be silent"
has "$PF" 'view?.snack("Date of birth saved as $iso (was $typed)")' \
  && ok "T3: a note names both the new value and what it replaced" \
  || bad "T3: nothing tells the user the date was converted"

echo "== the ambiguous case still gets advised =="
has "$PF" '"Use YYYY-MM-DD — did you mean $y-$m-$d?"' \
  && ok "T4: the did-you-mean advisory survives for dates we will not convert" \
  || bad "T4: the advisory was removed along with the guessing"

echo "== nothing else rewrites a stored birth =="
has "$SY" 'put("birth", prefs.birth.trim())' \
  && ok "T5: the wire still sends what is stored, verbatim" \
  || bad "T5: ProfileSync started transforming birth — normalise in ONE place"
# ProfileSync.fill and ConfigAutoImport write birth from elsewhere. Those are
# values the user never typed here, and converting them would be exactly the
# silent rewrite of an untouched stored value this change refuses to do.
n=$(grep -rc 'isoFromDmy' "$APP/app/src/main/java/com/diegonmarcos/superapp/profile/" 2>/dev/null | awk -F: '{s+=$2} END {print s+0}')
[ "$n" -le 3 ] \
  && ok "T5: the conversion has one call site, on the field the user typed in" \
  || bad "T5: isoFromDmy leaked to $n sites — restore/import must not be rewritten"

echo
echo "PASS=$PASS FAIL=$FAIL"
[ "$FAIL" -eq 0 ]
