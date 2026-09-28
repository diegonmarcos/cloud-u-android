#!/usr/bin/env bash
# Meta-test for _assert_apk_identity / _apk_declared_packages in every fork engine.
#
# WHAT WENT WRONG (#628).  The assert read an APK's package id with one line:
#
#     unzip -p "$apk" AndroidManifest.xml | LC_ALL=C strings -e l
#
# and it refused the two rootfs library APKs whose applicationId was exactly
# right, printing an EMPTY list of "package-shaped strings found" as the evidence.
#
# THE CAUSE IS THE LENGTH PREFIX, NOT THE ENCODING.  A binary-XML string pool
# stores every string with a 16-bit length immediately before its characters, and
# `strings` cannot know that: when the length byte is PRINTABLE it gets glued onto
# the front of the string it precedes. Measured on the published APK:
#
#     (com.diegonmarcos.cloudlib.rootfsnixdroid     <- 40 chars, 0x28 == '('
#     &com.diegonmarcos.cloudlib.rootfstermux       <- 38 chars, 0x26 == '&'
#
# `grep -Fqx` is a whole-line match, so neither matched. The rule the old reader
# really had was "works only for an applicationId SHORTER THAN 32 CHARACTERS",
# where the prefix is a control byte and `strings` starts a fresh string at the
# package name. cld.termux (10), com.diegonmarcos.superapp (25) and
# com.diegonmarcos.cloudlib.cal (29) all cleared that bar, which is why this stood
# for as long as it did; the two rootfs libraries (38, 40) did not, and
# com.diegonmarcos.cloudlib.shizukuadbdebugtools (46) would not either - a trap
# already loaded for an id that exists today.
#
# The guard was fail-closed, which was right. Its VERDICT was wrong, which is
# worse than either: it named a defect that did not exist and hid the one that
# did, across two applications and four CI jobs.
#
# WHY THIS FILE EXISTS.  The fix reads the package with aapt/aapt2 instead of
# guessing at bytes, and keeps `strings` only as a no-SDK fallback. Widening a
# fail-closed guard is exactly how a fail-closed guard becomes a fail-OPEN one
# (#452 - this fleet's most repeated defect), so the cases below hold BOTH ends:
# an id of every length must be READ, and a genuinely wrong id and an unreadable
# manifest must both still be REFUSED - with different messages, because
# conflating those two is the original sin.
#
# THE FIXTURES CARRY A REAL LENGTH PREFIX, and that is the whole point of them.
# The first version of this tester wrote bare strings with no prefix, so every
# case passed against the UNFIXED reader too: it was a tester that agreed with
# whatever it was given, which is the same fail-open shape one level up.
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
# A zip holding an "AndroidManifest.xml" whose bytes carry $2 the way a real
# binary-XML string pool does: a 16-bit LENGTH, then the characters, then a
# terminator. That prefix is the defect under test, so a fixture without one
# would test nothing.
mkapk() {
  python3 - "$1" "$2" "$3" <<'PY'
import sys, zipfile, os, struct
out, pkg, enc = sys.argv[1], sys.argv[2], sys.argv[3]
head = b'\x03\x00\x08\x00'          # RES_XML_TYPE, as a real manifest starts
def pooled(s, utf16):
    # <u16 char-count><chars><u16 0> — exactly what glues a printable length
    # byte onto the front of the name when `strings` is pointed at it.
    if utf16:
        return struct.pack('<H', len(s)) + s.encode('utf-16-le') + b'\x00\x00'
    return struct.pack('<H', len(s)) + s.encode('utf-8') + b'\x00'
if enc in ('utf16', 'utf8'):
    body = head + pooled('application', enc == 'utf16') + pooled(pkg, enc == 'utf16')
elif enc == 'none':
    body = head + b'\x01\x02\x03\x04no-dots-here\x00'
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

# Ids chosen for their LENGTH, which is the variable that decides whether the old
# reader worked. 10 -> 0x0A control byte; 38/40/46 -> '&', '(', '.' — printable.
SHORT_ID="cld.termux"                                              # 10 chars
LIB_ID="com.diegonmarcos.cloudlib.rootfsnixdroid"                  # 40 chars, '('
LONG_ID="com.diegonmarcos.cloudlib.shizukuadbdebugtools"           # 46 chars, '.'
mkapk "$WORK/short.apk"  "$SHORT_ID" utf16
mkapk "$WORK/lib.apk"    "$LIB_ID"   utf16
mkapk "$WORK/long.apk"   "$LONG_ID"  utf16
mkapk "$WORK/utf8.apk"   "$LIB_ID"   utf8
mkapk "$WORK/nopkg.apk"      unused  none
mkapk "$WORK/nomanifest.apk" unused  missing

for ENGINE in "${ENGINES[@]}"; do
  echo "== $(basename "$ENGINE") =="

  # (1) REGRESSION LOCK. Every application APK this assert has ever guarded has a
  #     SHORT id, where the length prefix is a control byte and the old reader
  #     worked. Widening the reader must not stop reading them.
  case_is "a short applicationId still resolves (every app APK)" 0 \
    "$WORK/short.apk" "$SHORT_ID" "package $SHORT_ID confirmed"

  # (2) THE #628 CASE. 40 characters, so the pool's length prefix is '(' and the
  #     old reader emitted "(com.diegonmarcos..." — never matching, and filtered
  #     out of its own evidence list.
  case_is "a 40-char id resolves despite its printable length prefix (#628)" 0 \
    "$WORK/lib.apk" "$LIB_ID" "confirmed"

  # (3) THE TRAP THAT WAS ALREADY LOADED: 46 characters, prefix '.'. This id
  #     exists in the fleet today and would have failed the same way.
  case_is "a 46-char id resolves too (shizukuadbdebugtools)" 0 \
    "$WORK/long.apk" "$LONG_ID" "confirmed"

  # (4) Pool encoding is aapt2's choice, so read either.
  case_is "a UTF-8 string pool resolves as well as a UTF-16 one" 0 \
    "$WORK/utf8.apk" "$LIB_ID" "confirmed"

  # (5) ANTI-#452. The whole risk of widening a fail-closed guard is turning it
  #     fail-open. A genuinely wrong id must still be refused.
  case_is "a GENUINELY wrong package id is still REFUSED" 1 \
    "$WORK/lib.apk" "com.diegonmarcos.cloudlib.nope" "APK package !="

  # (6) A greedy match cannot be fooled by a PREFIX of a real id, which is the
  #     false-positive an unanchored extraction could otherwise introduce.
  case_is "an id that is only a PREFIX of the real one is REFUSED" 1 \
    "$WORK/lib.apk" "com.diegonmarcos.cloudlib" "APK package !="

  # (7) …and the wrong-id message must carry the ids it actually read, which is
  #     the evidence that was EMPTY for a whole afternoon.
  r="$(assert_identity "$ENGINE" "$WORK/lib.apk" "com.diegonmarcos.cloudlib.nope")"
  case "${r#*|}" in
    *"read from the APK: "*"$LIB_ID"*)
      printf '  [PASS] %s\n' "the mismatch names the id it DID read (not an empty list)"; pass=$((pass+1)) ;;
    *)
      printf '  [FAIL] %s\n' "the mismatch printed no id it read — that empty evidence list IS the #628 defect"
      printf '%s\n' "${r#*|}" | sed 's/^/          /'; fail=$((fail+1)) ;;
  esac

  # (8) UNREADABLE IS ITS OWN FAILURE. Still fail-closed, but it must NOT be
  #     reported as a package mismatch — conflating the two is the original sin.
  case_is "a manifest with no package id is REFUSED as UNREADABLE" 1 \
    "$WORK/nopkg.apk" "$SHORT_ID" "could not read ANY package id"
  case_is "an APK with no manifest at all is REFUSED as UNREADABLE" 1 \
    "$WORK/nomanifest.apk" "$SHORT_ID" "could not read ANY package id"

  # (9) …and never as a mismatch.
  r="$(assert_identity "$ENGINE" "$WORK/nopkg.apk" "$SHORT_ID")"
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
