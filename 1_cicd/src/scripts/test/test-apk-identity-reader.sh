#!/usr/bin/env bash
# Meta-test for _assert_apk_identity / _apk_declared_packages in every fork engine.
#
# WHAT WENT WRONG (#628).  The assert read an APK's package id with one line:
#
#     unzip -p "$apk" AndroidManifest.xml | LC_ALL=C strings -e l
#
# `-e l` reads ONLY 16-bit little-endian. Every APK the assert had ever been
# pointed at was an APPLICATION, whose binary manifest carries a UTF-16 string
# pool, so it always worked and was never suspected. The first payload-only
# LIBRARY APK it was pointed at — #628's cloud-lib-rootfs-termux — yielded
# nothing at all, and in a `grep -Fqx` nothing is indistinguishable from the
# WRONG thing. So a library whose applicationId was exactly correct was refused
# as an identity mismatch, and the evidence printed underneath it was an empty
# list: "Package-shaped strings found: ".
#
# The guard was fail-closed, which was right. Its VERDICT was wrong, which is
# worse than either: it named a defect that did not exist and hid the one that
# did, across two applications and four CI jobs.
#
# WHY THIS FILE EXISTS.  The fix widens what the assert can read, and widening a
# fail-closed guard is exactly how a fail-closed guard becomes a fail-OPEN one
# (#452 — this fleet's most repeated defect). So the cases below hold BOTH ends:
# every encoding a real manifest can use must be READ, and a genuinely wrong id
# and an unreadable manifest must both still be REFUSED — and refused with
# different messages, because conflating them is the original sin.
#
# No SDK, no gradle, no device, no network: the fixtures are zips built here with
# python, and the engines are sourced for their pure helpers only.
set -uo pipefail

SCRIPTS="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO="$(cd "$SCRIPTS/../../.." && pwd)"

# $ENGINE_UNDER_TEST overrides the engine, for mutation proof: point it at the
# previous revision and the UTF-8 case below must go red.
#   git show HEAD~1:1_cicd/src/scripts/cloud-termux-fork-engine.sh > /tmp/old.sh
#   ENGINE_UNDER_TEST=/tmp/old.sh bash 1_cicd/src/scripts/test/test-apk-identity-reader.sh
ENGINES=()
if [ -n "${ENGINE_UNDER_TEST:-}" ]; then
  ENGINES=("$ENGINE_UNDER_TEST")
else
  for e in "$SCRIPTS"/cloud-*-fork-engine.sh "$SCRIPTS/cloud-mail-engine.sh"; do
    [ -f "$e" ] && grep -q '_apk_declared_packages' "$e" && ENGINES+=("$e")
  done
fi
[ "${#ENGINES[@]}" -gt 0 ] || { echo "no engine carries _apk_declared_packages — this tester would prove nothing" >&2; exit 1; }

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
pass=0
fail=0

# ── fixtures ────────────────────────────────────────────────────────────
# A zip holding an "AndroidManifest.xml" whose bytes carry $2 in the encoding
# $3 (utf16 | utf8 | none), which is all the reader under test looks at. Not a
# real binary manifest: a real one would need aapt2, and what is under test is
# how the bytes of the string POOL are decoded, not chunk parsing.
mkapk() {
  python3 - "$1" "$2" "$3" <<'PY'
import sys, zipfile, os
out, pkg, enc = sys.argv[1], sys.argv[2], sys.argv[3]
head = b'\x03\x00\x08\x00'          # RES_XML_TYPE, as a real manifest starts
if enc == 'utf16':
    body = head + b'\x00\x00' + pkg.encode('utf-16-le') + b'\x00\x00'
elif enc == 'utf8':
    body = head + b'\x00\x00' + pkg.encode('utf-8') + b'\x00'
elif enc == 'none':
    body = head + b'\x00\x00' + b'\x01\x02\x03\x04no-dots-here\x00'
elif enc == 'missing':
    body = None
else:
    raise SystemExit('unknown encoding ' + enc)
os.makedirs(os.path.dirname(out) or '.', exist_ok=True)
with zipfile.ZipFile(out, 'w') as z:
    if body is not None:
        z.writestr('AndroidManifest.xml', body)
    z.writestr('classes.dex', b'dex\n035\x00')
PY
}

# Runs _assert_apk_identity in a subshell with the engine sourced, and reports
# "<exit>|<output>". SCRIPT_DIR derives from $0's dirname, so the engine is
# sourced from a throwaway dir; the main-guard keeps dispatch from running.
assert_identity() {
  local engine="$1" apk="$2" expected="$3" out status
  out="$( ( cd "$WORK" && bash -c '
      set -uo pipefail
      set -- help
      source "'"$engine"'" >/dev/null 2>&1 || true
      _assert_apk_identity fixturekey "'"$apk"'" "'"$expected"'"
    ' ) 2>&1 )"
  status=$?
  printf '%s|%s' "$status" "$out"
}

# $1 desc, $2 expected exit, $3 apk, $4 expected id, $5 substring the output must contain
case_is() {
  local desc="$1" want="$2" apk="$3" id="$4" names="${5:-}"
  local r; r="$(assert_identity "$ENGINE" "$apk" "$id")"
  local status="${r%%|*}" out="${r#*|}"
  if [ "$status" != "$want" ]; then
    printf '  [FAIL] %s — expected exit %s, got %s\n' "$desc" "$want" "$status"
    printf '%s\n' "$out" | sed 's/^/          /'
    fail=$((fail+1)); return
  fi
  if [ -n "$names" ]; then
    case "$out" in
      *"$names"*) ;;
      *) printf '  [FAIL] %s — exited %s but never said %q, so it is right for the wrong reason\n' "$desc" "$status" "$names"
         printf '%s\n' "$out" | sed 's/^/          /'
         fail=$((fail+1)); return ;;
    esac
  fi
  printf '  [PASS] %s (exit %s)\n' "$desc" "$status"
  pass=$((pass+1))
}

mkapk "$WORK/utf16.apk"   "cld.termux"                                utf16
mkapk "$WORK/utf8.apk"    "com.diegonmarcos.cloudlib.rootfstermux"    utf8
mkapk "$WORK/nopkg.apk"   "unused"                                    none
mkapk "$WORK/nomanifest.apk" "unused"                                 missing

for ENGINE in "${ENGINES[@]}"; do
  echo "== $(basename "$ENGINE") =="

  # (1) REGRESSION LOCK. Every application APK this assert has ever guarded has
  #     a UTF-16 pool. Widening the reader must not stop reading them.
  case_is "a UTF-16 manifest still resolves (every app APK)" 0 \
    "$WORK/utf16.apk" "cld.termux" "package cld.termux confirmed"

  # (2) THE #628 CASE. Red against the previous revision: the reader saw nothing
  #     and the assert called a correct applicationId a mismatch.
  case_is "a UTF-8 manifest resolves too (#628's library APK)" 0 \
    "$WORK/utf8.apk" "com.diegonmarcos.cloudlib.rootfstermux" "confirmed"

  # (3) ANTI-#452. The whole risk of widening a fail-closed guard is turning it
  #     fail-open. A genuinely wrong id must still be refused.
  case_is "a GENUINELY wrong package id is still REFUSED" 1 \
    "$WORK/utf16.apk" "cld.somebody.else" "APK package !="

  # (4) …and the wrong-id message must carry the ids it actually read, which is
  #     the evidence that was EMPTY for a whole afternoon.
  r="$(assert_identity "$ENGINE" "$WORK/utf16.apk" "cld.somebody.else")"
  case "${r#*|}" in
    *"read from the APK: cld.termux"*)
      printf '  [PASS] %s\n' "the mismatch names the id it DID read (not an empty list)"; pass=$((pass+1)) ;;
    *)
      printf '  [FAIL] %s\n' "the mismatch printed no id it read — that empty evidence list IS the #628 defect"
      printf '%s\n' "${r#*|}" | sed 's/^/          /'; fail=$((fail+1)) ;;
  esac

  # (5) UNREADABLE IS ITS OWN FAILURE. Still fail-closed, but it must NOT be
  #     reported as a package mismatch — conflating the two is the original sin.
  case_is "a manifest with no package id is REFUSED as UNREADABLE" 1 \
    "$WORK/nopkg.apk" "cld.termux" "could not read ANY package id"
  case_is "an APK with no manifest at all is REFUSED as UNREADABLE" 1 \
    "$WORK/nomanifest.apk" "cld.termux" "could not read ANY package id"

  # (6) …and never as a mismatch.
  r="$(assert_identity "$ENGINE" "$WORK/nopkg.apk" "cld.termux")"
  case "${r#*|}" in
    *"APK package !="*)
      printf '  [FAIL] %s\n' "an unreadable manifest is STILL reported as a package mismatch (#628)"; fail=$((fail+1)) ;;
    *)
      printf '  [PASS] %s\n' "an unreadable manifest is never called a package mismatch"; pass=$((pass+1)) ;;
  esac
  echo
done

echo "Results: $pass passed, $fail failed"
[ "$fail" -eq 0 ]
