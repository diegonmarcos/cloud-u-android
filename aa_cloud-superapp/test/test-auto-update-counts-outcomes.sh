#!/usr/bin/env bash
# An auto-update pass must count what INSTALLED, never what it merely committed.
#
# ── THE FAILURE THIS EXISTS FOR ───────────────────────────────────────────
# Read off the owner's phone, 2026-09-11, SuperApp 0.1.1-dev (sha-9c846550):
#
#   13:06:12.739 I Updater/Install: install fleet-aa_cloud-superapp-release.apk
#   13:06:13.359 I Updater/Receiver: status=-1 msg= pkg= op=install
#   13:06:13.369 W Updater/Receiver: confirm launch deferred to notification
#   13:06:33.966 I Fleet/Worker: auto-update: installed 3 of 3 staged
#
# status=-1 is STATUS_PENDING_USER_ACTION. All three sessions were handed over
# and NONE of them installed — the background pass cannot show the system
# dialog, so each became a tap-to-install notification. The pass then logged
# "installed 3 of 3 staged". Zero apps changed version.
#
# That sentence is why this bug has been closed twice. #99 ("Update all fails
# for every app and lib") and #200 ("installs silently ... yet Constellation
# still reports it outdated") were both marked completed against a log that
# said the installs worked. A false success is worse than a failure: a failure
# gets investigated, a success ends the investigation.
#
# The cause was structural, not a typo. PackageInstaller.commit() returns on
# HANDOVER, and InstallGate deliberately opens its gate on
# STATUS_PENDING_USER_ACTION as well as on terminal outcomes (a background pass
# must not stall behind a dialog nobody can answer). So "commit returned and
# the gate released" means "start the next one", NOT "this one installed" —
# and Fleet.installAll incremented `acted` on exactly that.
#
# ── WHY THESE ASSERTIONS ──────────────────────────────────────────────────
# The fix is that the pass re-reads the installed versionCode from
# PackageManager after the gate settles and counts only builds that actually
# landed. Three things have to stay true for that to keep working, and each is
# a separate way to silently regress to the old lie:
#
#   1. `acted++` is reachable ONLY through a VersionOrder.landed() check.
#   2. The candidate's versionCode is read BEFORE commit(). A confirmed install
#      reaps the staged APK, so reading it afterwards yields null forever —
#      which fails closed (everything reports "pending") and would be just as
#      wrong in the opposite direction.
#   3. landed() is FAIL CLOSED: a null on either side is not an install.
#
# ── WHY IT STRIPS COMMENTS FIRST ──────────────────────────────────────────
# Every prose block above and in Fleet.kt quotes the very strings being
# asserted on — "installed 3 of 3 staged", "acted++", "status=-1". A grep over
# raw source matches this file's own explanation of the bug and certifies the
# bug. Comments are removed before a single assertion runs, and the stripper is
# itself checked below against a line it must delete.
#
# Usage: ./test-auto-update-counts-outcomes.sh   (no network — every check is static)
set -u

# Resolve from $0, never the caller's cwd: cloud-android-test-engine.sh runs
# these from the repo root and agents run them from this directory.
HERE="$(cd "$(dirname "$0")" && pwd)"
APP="$(cd "$HERE/.." && pwd)"                    # → aa_cloud-superapp
ROOT="$(cd "$APP/.." && pwd)"                    # → repo root
UPD="$ROOT/ab_cloud-libs-shared/libs/updater/src/main/java/com/diegonmarcos/superapp/updater"
FLEET_KT="$UPD/Fleet.kt"
ORDER_KT="$UPD/VersionOrder.kt"

PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }

for f in "$FLEET_KT" "$ORDER_KT"; do
  [ -f "$f" ] || { echo "  FAIL: missing $f"; echo "== RESULT: 0 passed, 1 failed =="; exit 1; }
done

# Strip // line comments and /* */ blocks, keeping line numbers stable so the
# "before commit()" ordering check below still reads true positions.
strip() {
  python3 - "$1" <<'PY'
import re, sys
src = open(sys.argv[1], encoding='utf-8').read()
# Blank out block comments, preserving newlines so line numbers do not shift.
src = re.sub(r'/\*.*?\*/', lambda m: re.sub(r'[^\n]', ' ', m.group(0)), src, flags=re.S)
# Drop // to end of line. No string-literal awareness is needed: nothing this
# tester asserts on lives inside a literal containing "//".
src = re.sub(r'//[^\n]*', '', src)
sys.stdout.write(src)
PY
}

FLEET_CODE="$(strip "$FLEET_KT")"
ORDER_CODE="$(strip "$ORDER_KT")"

# ── 0. The stripper works ─────────────────────────────────────────────────
# A stripper that silently returned its input would make every assertion below
# read the prose it is supposed to ignore, and this file's own header would
# satisfy them. Prove it deletes a comment that is definitely there, and that
# it did not delete everything.
if printf '%s' "$FLEET_CODE" | grep -q 'ASK THE PACKAGE MANAGER'; then
  bad "comment stripper left comment text in Fleet.kt — every assertion below would match prose"
elif ! printf '%s' "$FLEET_CODE" | grep -q 'fun installAll'; then
  bad "comment stripper ate the code as well as the comments"
else
  ok "comment stripper removes comments and keeps code"
fi

# ── 1. acted++ is guarded by landed() ─────────────────────────────────────
ACTED_LINES="$(printf '%s' "$FLEET_CODE" | grep -n 'acted++' | cut -d: -f1)"
ACTED_N="$(printf '%s' "$ACTED_LINES" | grep -c . || true)"
if [ "$ACTED_N" -ne 1 ]; then
  bad "expected exactly one 'acted++' in Fleet.kt, found $ACTED_N — a second increment is a second way to count an install that did not happen"
else
  # Look back a few code lines for the guard.
  WINDOW="$(printf '%s' "$FLEET_CODE" | sed -n "$((ACTED_LINES > 6 ? ACTED_LINES - 6 : 1)),${ACTED_LINES}p")"
  if printf '%s' "$WINDOW" | grep -q 'VersionOrder\.landed('; then
    ok "'acted++' is reached only through a VersionOrder.landed() check"
  else
    bad "'acted++' at line $ACTED_LINES is NOT guarded by VersionOrder.landed() — the pass is counting commits again, which is what logged 'installed 3 of 3' on a pass that installed nothing"
  fi
fi

# ── 2. the candidate versionCode is read BEFORE commit() ──────────────────
CAND_LINE="$(printf '%s' "$FLEET_CODE" | grep -n 'val candidateCode' | head -1 | cut -d: -f1)"
COMMIT_LINE="$(printf '%s' "$FLEET_CODE" | grep -n 'val used = commit(' | head -1 | cut -d: -f1)"
if [ -z "$CAND_LINE" ] || [ -z "$COMMIT_LINE" ]; then
  bad "could not locate both 'val candidateCode' and 'val used = commit(' in Fleet.kt"
elif [ "$CAND_LINE" -lt "$COMMIT_LINE" ]; then
  ok "the candidate versionCode is read before commit() (line $CAND_LINE < $COMMIT_LINE)"
else
  bad "the candidate versionCode is read at line $CAND_LINE, AFTER commit() at $COMMIT_LINE — a confirmed install reaps the staged APK, so it would read null forever and every install would report as pending"
fi

# ── 3. landed() is fail closed ────────────────────────────────────────────
if ! printf '%s' "$ORDER_CODE" | grep -q 'fun landed('; then
  bad "VersionOrder.landed() does not exist"
else
  # The null branch must yield false. Anything else ('true', or no branch at
  # all so nulls fall through to a comparison) counts "we do not know" as an
  # install, which is the original defect wearing a new name.
  NULLBRANCH="$(printf '%s' "$ORDER_CODE" \
    | sed -n '/fun landed(/,/^    }/p' \
    | grep 'null' | tr -d ' ')"
  if printf '%s' "$NULLBRANCH" | grep -q 'candidateCode==null||installedCode==null->false'; then
    ok "VersionOrder.landed() fails closed: a null on either side is not an install"
  else
    bad "VersionOrder.landed() has no 'null -> false' branch (found: '${NULLBRANCH:-none}') — an unreadable version would be counted as installed"
  fi
fi

# ── 4. the pass summary reports what did NOT install ──────────────────────
# The owner reads this sentence, not the code. If it only ever reports a count
# of successes it is indistinguishable from the version that lied.
if printf '%s' "$FLEET_CODE" | grep -q 'awaiting your confirmation'; then
  ok "the pass summary names the installs that are waiting on a confirmation"
else
  bad "the pass summary does not report pending installs — 'installed 0 of 3 staged' with no reason is the same dead end as 'installed 3 of 3'"
fi

echo
echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
