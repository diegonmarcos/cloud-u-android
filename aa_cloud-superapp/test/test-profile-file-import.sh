#!/usr/bin/env bash
# Tester: #585 — Configs ▸ Profile ▸ "Import from a file instead" can never
# import NOTHING in silence, and the file it takes is the DECRYPTED export.
#
# WHY THIS FILE EXISTS. The owner picked cloud-vault configs/profile-secrets.json
# — sops/age-ENCRYPTED, every value ENC[…], valid JSON — and the route wrote
# the whole file into the paste store, said "saved", and changed nothing
# anywhere. Three things now hold, and each is pinned here because each is one
# refactor away from being lost:
#   T1  the picked text is CLASSIFIED (VaultFile) before anything is written,
#       and the whole-blob overwrite (`prefs.json = raw`) is gone;
#   T2  the decrypted export lands where the server fetch lands
#       (VaultConnect.Imported), never in the paste store;
#   T3  every sentence the import can end with exists in BOTH locales. (The
#       silence-guard manifest that lists the two entry points lives at the
#       repo root, 1_cicd/src/data/silence-guard.json; it is NOT read here on
#       purpose: the test engine traces every path a tester reads and downgrades
#       a tester that reaches outside its application to a warning that cannot
#       fail the release. silence-guard.yml enforces the manifest on every push.)
#   T4  the JVM test feeds REAL sops bytes: the fixture carries a sops block
#       with a MAC and ciphertext values, and the test reads that fixture;
#   T5  the journey says what the file must be BEFORE the tap.
# Then the same checks run over a scratch copy with each guarantee removed, so
# a tester that always passes is caught the day it starts doing so.
#
# Static wiring tester (no device / no gradle run).
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"        # → aa_cloud-superapp
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }

FRAGMENT="app/src/main/java/com/diegonmarcos/superapp/settings/ImportConfigsFragment.kt"
# #587 the classifier is the fleet's (libs:auth); reached from the app root like every other path here.
CLASSIFIER="../ab_cloud-libs-shared/libs/auth/src/main/java/com/diegonmarcos/cloudlib/auth/VaultFile.kt"
JOURNEY="app/src/main/java/com/diegonmarcos/superapp/profile/ProfileFragment.kt"
TEST="app/src/test/java/com/diegonmarcos/superapp/profile/VaultFileImportTest.kt"
FIXTURE="app/src/test/resources/vault-bundle-sops-encrypted.json"
EN="app/src/main/res/values/strings.xml"
ES="app/src/main/res/values-es/strings.xml"

# Code only — whole-line comments dropped, so prose about what must NOT happen
# does not read as the thing happening.
codeof() { awk '{ l=$0; sub(/^[[:space:]]+/,"",l); if (l ~ /^\/\// || l ~ /^\*/ || l ~ /^\/\*/) next; print }' "$1"; }

# checks <app root> → prints PASS/FAIL lines, returns the FAIL count.
checks() {
  local app="$1" f=0
  p() { echo "  PASS: $1"; }
  x() { echo "  FAIL: $1"; f=$((f+1)); }

  echo "== T1: classified before written; no whole-blob overwrite =="
  codeof "$app/$FRAGMENT" | grep -qF 'VaultFile.classify(' && p "the fragment classifies through VaultFile" || x "the fragment no longer classifies the text"
  codeof "$app/$FRAGMENT" | grep -qE 'prefs\.json = |\.json = raw' && x "the whole-blob overwrite is back (it erased the stored bearer)" || p "no whole-blob overwrite of the paste store"
  codeof "$ROOT/$CLASSIFIER" | grep -qF 'ENC_MARKER' && p "the classifier knows sops' ENC[ marker" || x "the classifier lost the ENC[ marker"
  codeof "$ROOT/$CLASSIFIER" | grep -qF 'Verdict.Encrypted(' && p "an encrypted file has its own verdict" || x "the Encrypted verdict is gone"

  echo "== T2: the decrypted export lands where the fetch lands =="
  codeof "$app/$FRAGMENT" | grep -qF 'VaultConnect.Imported.bundle = v.bundle' && p "the bundle lands in VaultConnect.Imported" || x "the bundle no longer reaches the Fleet tab"
  codeof "$app/$FRAGMENT" | grep -qF 'putSecret(ctx, section, key' && p "the paste shape is merged section by section" || x "the per-section merge is gone"

  echo "== T3: every sentence the import can end with exists in both locales =="
  codeof "$app/$FRAGMENT" | grep -qE 'fun report\(@StringRes' && p "one reporter, taking a string resource" || x "the reporter no longer takes a string resource"
  for key in $(codeof "$app/$FRAGMENT" | grep -oE 'report\(R\.string\.[a-z_]+' | sed 's/.*R\.string\.//' | sort -u); do
    grep -qF "name=\"$key\"" "$app/$EN" && grep -qF "name=\"$key\"" "$app/$ES" && p "$key exists in en and es" || x "$key missing from a locale"
  done

  echo "== T4: the JVM test feeds REAL sops bytes =="
  python3 - "$app/$FIXTURE" <<'PY' && p "fixture is a sops document: sops.mac + age recipients + ENC[ values" || x "fixture is not a real sops document any more"
import json, sys
d = json.load(open(sys.argv[1]))
s = d.get("sops") or {}
def enc(v):
    if isinstance(v, str): return v.startswith("ENC[")
    if isinstance(v, dict): return sum(enc(x) for x in v.values())
    if isinstance(v, list): return sum(enc(x) for x in v)
    return 0
n = enc({k: v for k, v in d.items() if k != "sops"})
sys.exit(0 if str(s.get("mac", "")).startswith("ENC[") and len(s.get("age") or []) >= 1 and n > 0 else 1)
PY
  grep -qF 'vault-bundle-sops-encrypted.json' "$app/$TEST" && p "the test reads the encrypted fixture" || x "the test no longer reads the encrypted fixture"
  grep -qF 'import_encrypted' "$app/$TEST" && p "the test asserts the loud refusal sentence" || x "the test no longer asserts the refusal"

  echo "== T5: the journey says what the file must be, before the tap =="
  codeof "$app/$JOURNEY" | grep -qF 'journey_import_file_caption' && p "step 4 captions the file route" || x "step 4 lost its file-route caption"
  return $f
}

echo "### real tree"
checks "$ROOT"; n=$?
[ "$n" -eq 0 ] && ok "real tree: every guarantee holds" || bad "real tree: $n guarantee(s) missing"

# ── mutations: each guarantee removed on a scratch copy must turn a check RED ──
scratch() {  # scratch <label> <python mutation over $S (scratch app)>
  local label="$1" py="$2" S
  S="$(mktemp -d)"
  for f in "$FRAGMENT" "$JOURNEY" "$TEST" "$FIXTURE" "$EN" "$ES"; do mkdir -p "$S/$(dirname "$f")"; cp "$ROOT/$f" "$S/$f"; done
  S="$S" python3 -c "$py"
  if checks "$S" >/dev/null; then bad "mutation not caught: $label"; else ok "mutation caught: $label"; fi
  rm -rf "$S"
}
echo "### mutations"
scratch "classification removed" 'import os;p=os.environ["S"]+"/'"$FRAGMENT"'";s=open(p).read();open(p,"w").write(s.replace("VaultFile.classify(","VaultFile.classifyX("))'
scratch "whole-blob overwrite restored" 'import os;p=os.environ["S"]+"/'"$FRAGMENT"'";s=open(p).read();open(p,"w").write(s+"\nval z = 0\nfun mut(prefs: ConfigsPrefs, raw: String) { prefs.json = raw }\n")'
scratch "bundle no longer lands" 'import os;p=os.environ["S"]+"/'"$FRAGMENT"'";s=open(p).read();open(p,"w").write(s.replace("VaultConnect.Imported.bundle = v.bundle","VaultConnect.Imported.bundle = null"))'
scratch "spanish sentence deleted" 'import os,re;p=os.environ["S"]+"/'"$ES"'";s=open(p).read();open(p,"w").write(re.sub(r"<string name=\"import_encrypted\">.*?</string>\n","",s))'
scratch "fixture decrypted (sops block gone)" 'import os,json;p=os.environ["S"]+"/'"$FIXTURE"'";d=json.load(open(p));d.pop("sops");json.dump(d,open(p,"w"))'
scratch "journey caption removed" 'import os;p=os.environ["S"]+"/'"$JOURNEY"'";s=open(p).read();open(p,"w").write(s.replace("journey_import_file_caption","journey_import_file"))'

echo
echo "PASS=$PASS FAIL=$FAIL"
[ "$FAIL" -eq 0 ]
