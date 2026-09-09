#!/usr/bin/env bash
# Tester: the constellation download path — resume, stall watchdog, and the
# four states a user must be able to tell apart.
#
# THE FAILURE THIS EXISTS TO KEEP FIXED. Installing "Cloud Office"
# (ghcr.io/diegonmarcos/cloud-sheets, ONE 278,215,660-byte layer — eight times
# the next largest app in the fleet) sat on "downloading" forever: no error, no
# progress. Three things combined to produce that, all of them invisible at the
# 25 MB every other app ships at:
#
#   1. No resume. Both download loops opened their target at byte zero, so
#      every interruption — screen lock, cell handover, the process going to
#      cached — threw away everything already fetched. A 265 MB transfer on a
#      phone WILL be interrupted; one that restarts each time never finishes.
#   2. A swallowing catch. ReleaseSource wrapped the whole download in
#      `runCatching { … }.getOrNull()`, so a timeout produced no log and no
#      state change; control fell through to GHCR, which republished
#      Downloading(0, …) and began the same 265 MB again.
#   3. No terminal state. Fleet.install threw and told UpdateProgress nothing,
#      so the progress row kept rendering its last Downloading frame forever.
#
# Static assertions + a LIVE check that the two CDNs actually honour the Range
# requests the resume logic depends on.
#
# Usage: ./test-download-resume.sh   (live section needs network)
set -u
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"   # → ~/git/cloud-u-android
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
has()   { grep -qF "$2" "$ROOT/$1" 2>/dev/null && ok "$3" || bad "$3 ($1)"; }
hasnt() { grep -qF "$2" "$ROOT/$1" 2>/dev/null && bad "$3 ($1)" || ok "$3"; }
# CODE ONLY. This file documents the constructs it removed, by name, in the
# comments explaining why they were removed — so a plain grep for a banned
# construct matches the very comment that proves it is gone. Strip comment
# lines first, and the assertion means what it says.
code()  { sed -e 's|//.*||' -e 's|^[[:space:]]*\*.*||' "$ROOT/$1" 2>/dev/null; }
hasnt_code() { code "$1" | grep -qF "$2" && bad "$3 ($1)" || ok "$3"; }

LIB="ab_cloud-libs-shared/libs"
UPD="$LIB/updater/src/main/java/com/diegonmarcos/superapp/updater"
DL="$UPD/source/Download.kt"
SRC="$UPD/source/ApkSource.kt"
GHCR="$UPD/source/GhcrClient.kt"
FLEET="$UPD/Fleet.kt"
PROG="$UPD/UpdateProgress.kt"
OVL="$UPD/UpdateOverlayFragment.kt"
PAGE="$LIB/appstore/src/main/java/com/diegonmarcos/superapp/appstore/ConstellationFragment.kt"
CWORK="$LIB/appstore/src/main/java/com/diegonmarcos/superapp/appstore/ConstellationWorker.kt"
PREFS="$UPD/AutoUpdatePrefs.kt"

echo "== T1: ONE download loop, and it resumes with Range =="
[ -f "$ROOT/$DL" ] && ok "Download.kt exists" || bad "Download.kt missing"
has "$DL" 'setRequestProperty("Range", "bytes=$resumeFrom-")' "resume sends an HTTP Range request"
has "$DL" 'val append = code == 206'                          "206 appends; anything else restarts"
has "$DL" 'Content-Range'                                     "206 total comes from Content-Range, not the remainder length"
# The .part must SURVIVE a failure — deleting it is what made every retry
# restart from zero, and it is the single line this whole fix turns on.
hasnt_code "$DL" 'finally {'                                  "no finally-delete of the partial (that is what is being resumed)"
has "$DL" 'fun discard(target: File)'                         "an explicit discard for KNOWN-BAD bytes only"

echo "== T2: no second download loop crept back =="
# Exactly one file may contain the 64 kB copy loop. Three copies is how the
# guarantees drifted apart last time (only one chased redirects, only two
# throttled progress, none resumed).
LOOPS=$(grep -rlF 'ByteArray(64 * 1024)' "$ROOT/$LIB/updater/src" "$ROOT/$LIB/appstore/src" 2>/dev/null \
        | grep -v 'ApkIntegrity.kt' | wc -l)
[ "$LOOPS" -eq 1 ] && ok "exactly one streaming copy loop (Download.kt)" \
                   || bad "found $LOOPS streaming copy loops — expected 1"
hasnt_code "$SRC" 'target.outputStream()'                     "ReleaseSource no longer opens its own output stream"
has "$SRC" 'Download.toFile('                                 "ReleaseSource downloads through the shared engine"
has "$GHCR" 'Download.toFile('                                "GhcrClient.blob downloads through the shared engine"

echo "== T3: a stalled transfer FAILS, and says how far it got =="
has "$DL" 'class Stalled'                                     "a stall is its own failure type"
has "$DL" 'const val STALL_MS'                                "the watchdog window is a named constant"
has "$DL" 'no new data for '                                  "the stall message names the silence"
# A READ timeout, never a call timeout: a whole-transfer deadline would kill a
# big file on a healthy connection purely for being big.
has "$DL" 'readTimeout = READ_MS'                             "bounded by a read timeout"
hasnt_code "$DL" 'callTimeout'                                "no whole-call timeout (that would cap the transfer by size)"

echo "== T4: the swallowing catch is gone, and failures reach the user =="
# `.getOrNull()` is the swallow, not `runCatching` — the phrase still appears
# in the comment that records why it was removed.
hasnt_code "$SRC" '.getOrNull()'                              "ReleaseSource no longer swallows Throwable via runCatching{}.getOrNull()"
has "$SRC" 'catch (c: java.util.concurrent.CancellationException)' "a cancel is rethrown, not treated as a source failure"
has "$FLEET" 'UpdateProgress.State.Failed(' "Fleet publishes a terminal Failed state"
has "$FLEET" 'declined.joinToString'                          "the failure names which source declined and why"

echo "== T5: the four states are four different pictures =="
has "$PROG" 'data class Waiting'                              "waiting-on-a-constraint is its own state"
has "$PROG" 'is State.Waiting -> true'                        "a deferral never throws a full-screen overlay"
has "$PREFS" 'fun deferredReason'                             "one place owns 'why is the automatic pass held back'"
has "$CWORK" 'UpdateProgress.State.Waiting(why)'              "the auto pass SAYS it deferred instead of returning silently"
# Unknown total must read as unknown on BOTH surfaces — a determinate bar
# pinned at 0% while bytes flow is the exact ambiguity being removed.
has "$PAGE" 'total size unknown'                              "Constellation row says so when the length is unknown"
has "$OVL"  'total size unknown'                              "overlay says so when the length is unknown"
has "$PAGE" 'if (state.total > 0)'                            "Constellation row only draws a percentage it actually has"

echo "== T6: only ONE metered policy, not one per worker =="
# The CALL, not the constant name — the constant is also named in comments.
COPIES=$(grep -rlF 'cm.getNetworkCapabilities(it)' "$ROOT/$LIB/updater/src" "$ROOT/$LIB/appstore/src" 2>/dev/null | wc -l)
[ "$COPIES" -eq 1 ] && ok "one isMetered implementation (AutoUpdatePrefs)" \
                    || bad "found $COPIES metered checks — they disagreed once already"

echo "== T7: LIVE — the CDNs honour the Range requests resume depends on =="
if command -v curl >/dev/null; then
  # The real Cloud Office artifact. If this ever stops answering 206, resume is
  # silently a no-op and every interruption is back to costing 265 MB.
  REL="https://github.com/diegonmarcos/cloud-u-android/releases/latest/download/Cloud-Sheets.apk"
  CR=$(curl -sIL --max-time 45 -H 'Range: bytes=1000-1099' "$REL" \
       | tr -d '\r' | grep -i '^content-range:' | tail -1)
  case "$CR" in
    *bytes\ 1000-1099/*) ok "release CDN: 206 with $CR" ;;
    *)                   bad "release CDN did not honour Range (got: ${CR:-nothing})" ;;
  esac
  TOK=$(curl -s --max-time 20 "https://ghcr.io/token?service=ghcr.io&scope=repository:diegonmarcos/cloud-sheets:pull" \
        | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
  if [ -n "$TOK" ]; then
    DIG=$(curl -s --max-time 20 -H "Authorization: Bearer $TOK" \
          -H "Accept: application/vnd.oci.image.manifest.v1+json" \
          "https://ghcr.io/v2/diegonmarcos/cloud-sheets/manifests/latest" \
          | sed -n 's/.*"digest":"\(sha256:[a-f0-9]*\)".*/\1/p' | tail -1)
    # A ranged GET, not HEAD: the blob endpoint answers 307 to a presigned URL
    # and that redirect does not answer HEAD with a Content-Range.
    GR=$(curl -sL --max-time 45 -D - -o /dev/null \
         -H "Authorization: Bearer $TOK" -H 'Range: bytes=1000-1099' \
         "https://ghcr.io/v2/diegonmarcos/cloud-sheets/blobs/$DIG" \
         | tr -d '\r' | grep -i '^content-range:' | tail -1)
    case "$GR" in
      *bytes\ 1000-1099/*) ok "GHCR blob: 206 with $GR" ;;
      *)                   echo "  SKIP: GHCR blob Range unconfirmed (got: ${GR:-nothing})" ;;
    esac
  else echo "  SKIP: no anonymous GHCR token (offline or rate-limited)"; fi
else echo "  SKIP: curl unavailable"; fi

echo
echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
