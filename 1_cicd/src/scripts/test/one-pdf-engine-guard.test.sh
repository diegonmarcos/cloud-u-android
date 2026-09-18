#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════╗
# ║ one-pdf-engine-guard.test — prove the guard FAILS, and prove it  ║
# ║ can pass, so a green result means something                      ║
# ╚══════════════════════════════════════════════════════════════════╝
#
# WHY THIS EXISTS. A guard only ever watched succeeding is indistinguishable
# from `exit 0`. Ticket #463's whole premise is that the one-PDF-engine
# invariant "holds by luck" today -- it was never enforced, and the defect it
# exists to prevent (a second engine arriving silently) has therefore never
# been exercised. This file exercises it: it drops a renderer into a non-
# drive app tree nine ways and requires the guard to RED each one, then
# rebuilds the fleet clean and requires GREEN. A green verdict on a guard
# whose red path has never run is the fleet's dominant defect, found at six
# separate layers.
#
# The fixture mirrors the REAL repo shape the policy was written against: the
# two legitimately-vendored read-only renderers (Mattermost's pdfium viewer,
# Element X's mediaviewer PdfRenderer) present under the policy's EXACT
# exclusion paths, everything else clean -- which is what must be green today.
# The mutation cases then add a renderer OUTSIDE those exclusions and require
# RED. The policy data, not this file, owns the exclusion list; the fixture
# just has to reproduce it verbatim or the green case is testing a different
# world than CI runs.
#
# No ripgrep, and nothing outside coreutils + python3. Testers in this
# repository have previously passed only because a tool was missing; the
# guard is python3 and this file is bash and nothing more.

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
GUARD="$ROOT/1_cicd/src/scripts/cloud-android-one-pdf-engine-guard.py"
POLICY="$ROOT/1_cicd/src/data/one-pdf-engine-guard.json"
FAILURES=0

ok()   { printf 'ok     %s\n' "$1"; }
fail() { printf 'FAIL   %s\n' "$1"; FAILURES=$((FAILURES + 1)); }

# Plain substring containment -- NOT `case` (in a case pattern, [dirname]
# brackets and [NEW] turn into character classes).
contains() { [ "${1#*"$2"}" != "$1" ]; }

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# ── fixture: the fleet shape the policy expects to be green ──────────
# ac_cloud-drive owns the engine (pdf.min.js/pdf.worker.min.js are ALLOWED
# there and must NOT trip the guard); mail, libs and every other app are
# clean; the two vendored read-only renderers sit exactly under the policy's
# exclusion paths.
build_fixture() {
  local t="$1"
  mkdir -p "$t/ac_cloud-drive/app/src/main/assets/vendor"
  : > "$t/ac_cloud-drive/app/src/main/assets/vendor/pdf.min.js"
  : > "$t/ac_cloud-drive/app/src/main/assets/vendor/pdf.worker.min.js"
  mkdir -p "$t/ac_cloud-mail/app/src/main"
  : > "$t/ac_cloud-mail/app/src/main/Attachment.kt"
  mkdir -p "$t/ac_cloud-libs-shared/app"
  : > "$t/ac_cloud-libs-shared/app/README.md"

  # Mattermost's vendored secure-pdf-viewer (a pdfium binding) -- EXCLUDED.
  mkdir -p \
    "$t/ac_cloud-chat/libraries/@mattermost/secure-pdf-viewer/android/src/main/java/com/mattermost/securepdfviewer/pdfium"
  cat > "$t/ac_cloud-chat/libraries/@mattermost/secure-pdf-viewer/android/src/main/java/com/mattermost/securepdfviewer/pdfium/PdfDocument.kt" <<'EOF'
package com.mattermost.securepdfviewer.pdfium
class PdfDocument { fun open() {} }
EOF

  # Element X's vendored mediaviewer in-process renderer -- EXCLUDED.
  mkdir -p \
    "$t/ac_cloud-matrix/libraries/mediaviewer/impl/src/main/kotlin/io/element/android/libraries/mediaviewer/impl/local/pdf"
  cat > "$t/ac_cloud-matrix/libraries/mediaviewer/impl/src/main/kotlin/io/element/android/libraries/mediaviewer/impl/local/pdf/PdfRendererManager.kt" <<'EOF'
import android.graphics.pdf.PdfRenderer
class PdfRendererManager { fun open() { PdfRenderer(null) } }
EOF
}

run_guard() { python3 "$GUARD" "$1" 2>&1; }

# ── case 1: a pdf.worker.min.js copied into cloud-mail → FAIL, names rule ─
# This IS the defect as filed, verbatim: the exact mutation the local
# evidence run performs. It must name BOTH the file and the RULE -- a message
# that only says "unexpected file" teaches the next person nothing.
T="$WORK/c1_drive_mail"; build_fixture "$T"
mkdir -p "$T/ac_cloud-mail/vendor"
: > "$T/ac_cloud-mail/vendor/pdf.worker.min.js"
OUT="$(run_guard "$T")"; RC=$?
if [ "$RC" -ne 1 ]; then
  fail "mail copy: expected exit 1, got $RC: $OUT"
elif ! contains "$OUT" "pdf.worker.min.js"; then
  fail "mail copy: guard did not name the file: $OUT"
elif ! contains "$OUT" "reached by manifest"; then
  fail "mail copy: guard did not name the RULE (a message that names only the file teaches nothing): $OUT"
else
  ok "mail copy: exit 1 and names the rule -- the way to add a consumer is the manifest"
fi

# ── case 2: a pdf.min.js in the shared lib → FAIL ─────────────────────
# The refactor the ticket EXPLICITLY refused: building a shared pdf lib.
# ab_cloud-libs-shared is scanned, so this future mistake fails.
T="$WORK/c2_sharedlib"; build_fixture "$T"
mkdir -p "$T/ac_cloud-libs-shared/libs/pdf"
: > "$T/ac_cloud-libs-shared/libs/pdf/pdf.min.js"
OUT="$(run_guard "$T")"; RC=$?
if [ "$RC" -ne 1 ]; then
  fail "shared-lib pdf lib: expected exit 1, got $RC: $OUT"
elif ! contains "$OUT" "pdf.min.js"; then
  fail "shared-lib pdf lib: guard did not name the file: $OUT"
else
  ok "shared-lib pdf lib: exit 1 (a shared pdf.js copy is still not the manifest route)"
fi

# ── case 3: a pdfium binding DIRECTORY in cloud-mail → FAIL ────────────
T="$WORK/c3_pdfiumdir"; build_fixture "$T"
mkdir -p "$T/ac_cloud-mail/pdfium"
: > "$T/ac_cloud-mail/pdfium/PdfRenderer.kt"
OUT="$(run_guard "$T")"; RC=$?
if [ "$RC" -ne 1 ]; then
  fail "pdfium dir: expected exit 1, got $RC: $OUT"
elif ! contains "$OUT" "pdfium"; then
  fail "pdfium dir: guard did not name the pdfium binding: $OUT"
else
  ok "pdfium dir: exit 1 (a directory binding is caught by name, not by a copied .js)"
fi

# ── case 4: an in-process renderer via code marker → FAIL ──────────────
# android.graphics.pdf.PdfRenderer is the OS-level pdfium-backed renderer.
# A non-vendored app wiring it in is a second engine that no .js filename
# would ever reveal.
T="$WORK/c4_sysrenderer"; build_fixture "$T"
mkdir -p "$T/ac_cloud-notes/app/src/main"
cat > "$T/ac_cloud-notes/app/src/main/PdfActivity.kt" <<'EOF'
import android.graphics.pdf.PdfRenderer
class PdfActivity { lateinit var pr: PdfRenderer }
EOF
OUT="$(run_guard "$T")"; RC=$?
if [ "$RC" -ne 1 ]; then
  fail "system PdfRenderer: expected exit 1, got $RC: $OUT"
elif ! contains "$OUT" "android.graphics.pdf.PdfRenderer"; then
  fail "system PdfRenderer: marker not reported: $OUT"
else
  ok "system PdfRenderer: exit 1 (an in-process renderer is caught even with no .js file)"
fi

# ── case 5: exclusion is PER-PATH, not per-app / per-marker ────────────
# The Mattermost and Element X renderers are legal ONLY under the policy's
# two written paths. The SAME pdfium marker moved one directory out of its
# exclusion must fail -- an exclusion that quietly covers the whole app tree
# is how a guard stops guarding.
T="$WORK/c5_misplaced"; build_fixture "$T"
mkdir -p "$T/ac_cloud-chat/vendor"
: > "$T/ac_cloud-chat/vendor/pdf.worker.min.js"
OUT="$(run_guard "$T")"; RC=$?
if [ "$RC" -ne 1 ]; then
  fail "exclusion by path: a copy OUTSIDE the exclusion must fail; got exit $RC: $OUT"
elif ! contains "$OUT" "ac_cloud-chat/vendor/pdf.worker.min.js"; then
  fail "exclusion by path: guard did not name the misplaced copy: $OUT"
else
  ok "exclusion is per-path: a copy one tree away from the exclusion still fails"
fi

# ── case 6: clean fleet (the two vendored renderers under excl paths) → PASS
# The state that must be green TODAY: drive owns the engine, mail+libs
# clean, the two read-only vendored renderers under their written exclusions.
T="$WORK/c6_clean"; build_fixture "$T"
OUT="$(run_guard "$T")"; RC=$?
if [ "$RC" -ne 0 ]; then
  fail "clean fleet: expected exit 0, got $RC: $OUT"
elif ! contains "$OUT" "ok"; then
  fail "clean fleet: guard did not say ok: $OUT"
elif ! contains "$OUT" "secure-pdf-viewer"; then
  fail "clean fleet: green did not report the Mattermost exclusion is load-bearing: $OUT"
elif ! contains "$OUT" "mediaviewer"; then
  fail "clean fleet: green did not report the Element X exclusion is load-bearing: $OUT"
else
  ok "clean fleet: exit 0, and the exclusions are NAMED on green (not silent skips)"
fi

# ── case 7: no app trees at all → refuse to report clean ───────────────
# The false-green shape this fleet keeps hitting: a guard that scans nothing
# and prints "clean" because there was nothing to look at.
T="$WORK/c7_empty"; mkdir -p "$T/1_cicd/src"
OUT="$(run_guard "$T")"; RC=$?
if [ "$RC" -eq 0 ]; then
  fail "no app trees: reported CLEAN on an empty checkout (false green)"
elif [ "$RC" -ne 2 ]; then
  fail "no app trees: expected exit 2, got $RC: $OUT"
else
  ok "no app trees: exit 2, fails closed instead of reporting green on nothing"
fi

# ── case 8: a dead exclusion is LOUD, not silent ───────────────────────
# Removing the vendored app the exclusion exists to hide, then running the
# guard, must not quietly keep excluding a path that now matches nothing.
T="$WORK/c8_deadexcl"; build_fixture "$T"
rm -rf "$T/ac_cloud-matrix"
OUT="$(run_guard "$T")"; RC=$?
if [ "$RC" -ne 0 ]; then
  fail "dead exclusion: missing-arg must stay EXIT 0 (it is only reported), got $RC: $OUT"
elif ! contains "$OUT" "NOT on disk"; then
  fail "dead exclusion: green did not flag the now-unload-bearing exclusion: $OUT"
elif ! contains "$OUT" "mediaviewer"; then
  fail "dead exclusion: green did not name the dead exclusion path: $OUT"
else
  ok "dead exclusion: still exit 0 but the unused exclusion is flagged on green"
fi

echo
if [ "$FAILURES" -eq 0 ]; then
  echo "PASS   8/8 cases"; exit 0
else
  echo "FAIL   $FAILURES case(s)"; exit 1
fi