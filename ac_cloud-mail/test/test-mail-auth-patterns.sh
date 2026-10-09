#!/usr/bin/env bash
# test-mail-auth-patterns.sh -- rule "G0 _ Auth": the app's pattern constants are GENERATED from the vendored
# pattern set, the classifier reads them (no phrase restated in Kotlin), and the vendored copy equals the fleet's
# source of truth when that checkout is at hand.
#   G1  AuthPatterns.kt is exactly what gen-auth-patterns.py makes from auth-patterns.json
#   G2  AuthClassifier restates no phrase or token: it reads AuthPatterns
#   G3  the classifier's code decision is the reading pane's extractor
#   G4  the vendored copy equals cloud-u-containers/_shared/mail-auth-patterns.json (skipped without the checkout)
#   G5  mutation: a hand edit of the generated file turns G1 red
set -uo pipefail
APP="$(cd "$(dirname "$0")/.." && pwd)"
DATA="$APP/core/data"
CLS="$DATA/src/main/kotlin/app/sterna/core/data/text/AuthClassifier.kt"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  ok: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }

python3 "$APP/test/gen-auth-patterns.py" --check && ok "G1 AuthPatterns.kt is generated from auth-patterns.json" \
  || bad "G1 AuthPatterns.kt is stale: run python3 ac_cloud-mail/test/gen-auth-patterns.py"

if grep -nE '"(verify|confirm|reset|magic|sign in|log in|activate)[^"]*"' "$CLS" >/dev/null; then
  bad "G2 AuthClassifier.kt restates a phrase"
else
  grep -q 'AuthPatterns.LINK_PHRASES' "$CLS" && grep -q 'AuthPatterns.URL_TOKENS' "$CLS" \
    && ok "G2 the classifier reads its phrases and tokens from AuthPatterns" || bad "G2 the classifier does not read AuthPatterns"
fi

grep -q 'extractVerificationCode(subject = subject, bodyText = bodyText, html = html)' "$CLS" \
  && ok "G3 a code is decided by the reading pane's extractor" || bad "G3 the code decision is not the shared extractor"

for CU in "${CONTAINERS_REPO:-}" "$APP/../../cloud-u-containers" "/home/user/cloud-u-containers"; do
  SRC="$CU/_shared/mail-auth-patterns.json"
  [ -f "$SRC" ] || continue
  python3 - "$SRC" "$DATA/auth-patterns.json" <<'PY' && ok "G4 the vendored copy equals the fleet's source of truth" || bad "G4 the vendored copy differs from $SRC"
import json, sys
sys.exit(0 if json.load(open(sys.argv[1], encoding="utf-8")) == json.load(open(sys.argv[2], encoding="utf-8")) else 1)
PY
  break
done

T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
mkdir -p "$T/test" "$T/core/data/src/main/kotlin/app/sterna/core/data/text"
cp "$APP/test/gen-auth-patterns.py" "$T/test/"
cp "$DATA/auth-patterns.json" "$T/core/data/"
cp "$DATA/src/main/kotlin/app/sterna/core/data/text/AuthPatterns.kt" "$T/core/data/src/main/kotlin/app/sterna/core/data/text/"
sed -i 's/"magic link",/"magic linkX",/' "$T/core/data/src/main/kotlin/app/sterna/core/data/text/AuthPatterns.kt"
python3 "$T/test/gen-auth-patterns.py" --check && bad "G5 a hand edit of the generated file was not caught" || ok "G5 a hand edit of the generated file turns G1 red"

echo "== $PASS ok, $FAIL failed =="
[ "$FAIL" -eq 0 ]
