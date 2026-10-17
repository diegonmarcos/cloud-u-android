#!/bin/sh
# ╔══════════════════════════════════════════════════════════════════╗
# ║ No secret in logs: no Log/println/Timber/console call in the     ║
# ║ browser (lib + app, Kotlin + JS assets) names a password, card,  ║
# ║ cvc or vault value.                                              ║
# ╚══════════════════════════════════════════════════════════════════╝
# Cloud Vault overwrites the local fill; neither value may reach logcat.
# Self-test: a planted offending line must be caught.
set -eu
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
DIRS="$ROOT/ab_cloud-libs-shared/libs/browser/src/main $ROOT/ac_cloud-browser/app/src/main"
LOGCALL='(Log\.[vdiwe]|Log\.wtf|println|Timber\.[a-z]+|console\.(log|info|warn|error|debug))[[:space:]]*\('
SECRET='(password|passwd|[^a-z]pw[^a-z]|card|cvc|cvv|csc|cc_number|\.number|secret|vault)'

scan() { grep -rniE --include='*.kt' --include='*.java' --include='*.js' "$LOGCALL.*$SECRET" "$@" 2>/dev/null || true; }

# self-test
T=$(mktemp -d)
printf 'fun x(l: AcLogin) { android.util.Log.d("ac", "pw=" + l.password) }\n' > "$T/Bad.kt"
printf 'console.log("card", c.number);\n' > "$T/bad.js"
n=$(scan "$T" | wc -l)
rm -r "$T"
[ "$n" -eq 2 ] || { echo "FAIL self-test: the guard missed a planted secret log ($n/2)"; exit 1; }

hits=$(scan $DIRS)
if [ -n "$hits" ]; then
  echo "FAIL a log call names a secret:"; echo "$hits"; exit 1
fi
echo "PASS no Log/println/console call names a password, card or vault value"
