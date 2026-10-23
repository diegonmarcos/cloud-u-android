#!/usr/bin/env bash
# #1106 the safe-prefs guard must be RED for a direct EncryptedSharedPreferences call and GREEN on the helper and the allow list.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
GUARD="$HERE/../cloud-android-safe-prefs-guard.py"
fails=0
mk() {
  local r; r="$(mktemp -d)"
  mkdir -p "$r/1_cicd/src/data" "$r/lib/src/main"
  echo '{"allow":{}}' > "$r/1_cicd/src/data/safe-encrypted-prefs-guard.json"
  printf 'import androidx.security.crypto.EncryptedSharedPreferences\n%s\n' "$1" > "$r/lib/src/main/Store.kt"
  echo "$r"
}
chk() {
  local r; r="$(mk "$2")"
  python3 "$GUARD" "$r" > "$r/out" 2>&1; got=$?
  if [ "$got" -eq "$3" ]; then echo "  ok   $1"; else echo "  FAIL $1 (exit $got, wanted $3)"; cat "$r/out"; fails=$((fails+1)); fi
  rm -rf "$r"
}
chk "direct create is red"          'val p = EncryptedSharedPreferences.create(ctx, "x", k, a, b)' 1
chk "MasterKey.Builder is red"      'val k = MasterKey.Builder(ctx).build()' 1
chk "SafeEncryptedPrefs is green"   'val p = SafeEncryptedPrefs.prefs(ctx, "x")' 0
chk "a comment is green"            '// EncryptedSharedPreferences.create( was here' 0
[ "$fails" -eq 0 ] && echo "safe-prefs-guard: all cases pass" || exit 1
