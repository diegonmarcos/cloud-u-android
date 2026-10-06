#!/usr/bin/env bash
#
# Permissions ▸ Wireless Debugging ▸ Pair failed on the phone with
#   "Pair failed: pair failed: cannot create signer: no such algorithm:
#    SHA256WITHRSA for provider BC"
#
# The signer in AdbManager.selfSign was ALREADY handed a BouncyCastleProvider
# INSTANCE, so the provider on the path was the bundled bcprov, not Android's
# stripped platform "BC". What was missing was a -keep: the shipping variant
# runs R8, BouncyCastleProvider registers algorithms via Class.forName on
# "…asymmetric.RSA$Mappings"-style names and silently skips classes the
# shrinker removed, and app/proguard-rules.pro only -dontwarn'ed the package.
# A provider instance with no RSA signature is exactly the message above.
#
#   T1  the signer and the cert converter take a provider INSTANCE — never
#       setProvider("BC") by name, which resolves to Android's platform stub.
#   T2  the lib that owns the bcprov dependency ships a consumer -keep for
#       org.bouncycastle.**, so every consuming app inherits it.
#   T3  nothing on the pairing path calls Security.getProvider/"BC" by name.
#
# FAIL CLOSED: if a file has moved this tester has proven nothing and says so.

set -uo pipefail

APP="$(cd "$(dirname "$0")/.." && pwd)"          # -> aa_cloud-superapp
ROOT="$(cd "$APP/.." && pwd)"                    # -> cloud-u-android
LIB="$ROOT/ab_cloud-libs-shared/libs/shizuku-adb-debug-tools"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }

MANAGER="$LIB/src/main/java/com/diegonmarcos/superapp/adbdebug/AdbManager.kt"
RULES="$LIB/consumer-rules.pro"

for f in "$MANAGER" "$RULES"; do
  if [ ! -f "$f" ]; then
    echo "  FAIL: missing file $f — the tree is not what this tester was written against"
    echo "== RESULT: 0 passed, 1 failed =="
    exit 1
  fi
done

# code <file> — comment lines removed, so prose in KDoc/.pro comments cannot
# satisfy (or trip) an assertion meant for the implementation.
code() { grep -vE '^[[:space:]]*(//|\*|/\*|#)' "$1"; }

echo "== wireless debugging pair: BouncyCastle signer survives R8 =="

# ── T1 ── provider INSTANCE on the signer + converter, never the name "BC"
if grep -q 'setProvider("BC")' <<<"$(code "$MANAGER")"; then
  bad "T1 AdbManager pins provider \"BC\" by NAME — that resolves to Android's stub, which has no RSA signer"
elif grep -q 'setProvider(bcProvider)' <<<"$(code "$MANAGER" | grep -A1 'JcaContentSignerBuilder(')" \
  && grep -q 'setProvider(bcProvider)' <<<"$(code "$MANAGER" | grep -A1 'JcaX509CertificateConverter()')"; then
  ok "T1 signer and cert converter take the BouncyCastleProvider instance"
else
  bad "T1 signer/converter no longer pass the bcProvider instance"
fi

# ── T2 ── the keep rule lives with the dependency, as a consumer rule
if grep -qE '^-keep class org\.bouncycastle\.\*\* \{ \*; \}' <<<"$(code "$RULES")"; then
  ok "T2 consumer-rules.pro keeps org.bouncycastle.** — R8 cannot strip the reflectively-loaded \$Mappings"
else
  bad "T2 no '-keep class org.bouncycastle.** { *; }' in consumer-rules.pro — the shipping (minified) variant loses RSA signing again"
fi

# ── T3 ── no by-name provider lookup anywhere on the pairing path
if grep -q . <<<"$(grep -rn --include='*.kt' --include='*.java' -E 'getInstance\([^)]*,[[:space:]]*"BC"\)|Security\.getProvider\("BC"\)' \
     "$LIB/src/main")"; then
  bad "T3 a by-name \"BC\" provider lookup exists on the adbdebug path"
else
  ok "T3 no by-name \"BC\" provider lookup under shizuku-adb-debug-tools"
fi

echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
