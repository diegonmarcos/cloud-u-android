#!/usr/bin/env bash
# ac_cloud-code tester: the Chat tab (replaced Home) — no build, no network.
#
#   test/chat.mjs        the REAL src/cloud/chat/model.js and tabs.js over the REAL nav.json and the
#                        fleet data the REAL resolver derives: the agent list (Hermes, OpenClaw first;
#                        OpenClaw "not deployed" while the fleet lacks it), model / effort / permission
#                        per backend capability, MCP list source and per-agent persistence, More, the
#                        request each backend gets, the hamburger listing the ACTIVE page's items, the
#                        sessions, the stream parser, and the catalogue asked for A0 Code only.
#   test/chat_secrets.py the OpenRouter token: encrypted at rest, never logged, never handed to the
#                        page, never sent anywhere but https://openrouter.ai — then each of those is
#                        planted broken in a scratch copy and must turn RED.
#   TokenMask.java       the plugin's real mask, compiled and run (javac), when a JDK is present.
#
# The shared catalogue's own logic (the section filter, pricing, order) is tested in
# libs:model-catalogue's ModelCatalogueTest, run by Cloud Search's `gradle test`.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FAIL=0
ok()   { printf '  PASS  %s\n' "$1"; }
fail() { printf '  FAIL  %s\n' "$1"; FAIL=1; }

drive() {   # drive <label> <min checks> <command...>
  local label="$1" min="$2"; shift 2
  local outp status n=0
  outp="$("$@" 2>&1)"; status=$?
  [ "$status" -eq 0 ] || fail "$label crashed (exit $status): $outp"
  while IFS= read -r line; do
    case "$line" in
      "CHECK ok "*)  ok "${line#CHECK ok }"; n=$((n + 1)) ;;
      "CHECK bad "*) fail "${line#CHECK bad }"; n=$((n + 1)) ;;
    esac
  done <<< "$outp"
  [ "$n" -ge "$min" ] || fail "$label made only $n checks (expected at least $min) — it is not seeing the code"
}

drive "chat.mjs" 60 node "$ROOT/test/chat.mjs" "$ROOT"
drive "chat_secrets.py" 12 python3 "$ROOT/test/chat_secrets.py" "$ROOT"

# ── mutation proof: each token rule can fail ────────────────────────────────
S="$(mktemp -d)"; trap 'rm -rf "$S"' EXIT
plant() {   # plant <name> <python statement on the scratch app root R>
  rm -rf "$S/app"; mkdir -p "$S/app/src"
  cp -r "$ROOT/src/cloud" "$S/app/src/cloud"
  mkdir -p "$S/app/src/plugins"; cp -r "$ROOT/src/plugins/cloudchat" "$S/app/src/plugins/cloudchat"
  python3 - "$S/app" <<PY
import re, sys
R = sys.argv[1]
J = R + "/src/plugins/cloudchat/src/"
def edit(p, a, b):
    s = open(p).read()
    assert a in s, (p, a)
    open(p, "w").write(s.replace(a, b, 1))
$2
PY
  local verdict; verdict="$(python3 "$ROOT/test/chat_secrets.py" "$S/app" 2>&1)"
  if grep -q '^CHECK bad ' <<<"$verdict"; then ok "$1 turns it red"; else fail "$1 stays green: $verdict"; fi
}
plant "M1 the plugin logs the token" 'edit(J + "CloudChatPlugin.java", "token = secrets.resolve();", "token = secrets.resolve(); android.util.Log.d(\"chat\", token);")'
plant "M2 the status hands the page the token" 'edit(J + "CloudChatPlugin.java", ".put(\"set\", local != null)", ".put(\"set\", local != null).put(\"value\", local)")'
plant "M3 the token is stored in plain prefs" 'edit(J + "ChatSecrets.java", "prefs = EncryptedSharedPreferences.create(", "prefs = app.getSharedPreferences(PREFS, 0); Object unused = EncryptedSharedPreferences.create(")'
plant "M4 the token may go to any host" 'edit(J + "CloudChatPlugin.java", "|| !OPENROUTER_HOST.equals(u.getHost())", "")'
plant "M6 the plugin reads the fleet Account's key" 'edit(J + "ChatSecrets.java", "return local();", "String t = local(); return t != null ? t : client().revealAiKey(ACCOUNT_PROVIDER).getText();")'
plant "M5 the page keeps the token in localStorage" 'edit(R + "/src/cloud/profile.js", "const v = input.value;", "const v = input.value; localStorage.setItem(\"cloud-code.token\", v);")'

# ── the plugin's real mask, compiled and run ────────────────────────────────
if command -v javac >/dev/null 2>&1 && command -v java >/dev/null 2>&1; then
  J="$S/javac"; mkdir -p "$J"
  if javac -d "$J" "$ROOT/src/plugins/cloudchat/src/TokenMask.java" "$ROOT/test/java/TokenMaskCheck.java" 2>"$J/err"; then
    drive "TokenMaskCheck" 4 java -cp "$J" TokenMaskCheck
  else
    fail "TokenMask.java does not compile: $(cat "$J/err")"
  fi
else
  printf '  SKIP  no JDK on this runner: TokenMask is held by chat_secrets.py statically only\n'
fi

# ── the plugin is installed and its service registered (the #543 trap) ──────
python3 - "$ROOT" <<'PY' || FAIL=1
import json, os, sys
root = sys.argv[1]
pkg = json.load(open(os.path.join(root, "package.json")))
ok = pkg["cordova"]["plugins"].get("cordova-plugin-cloudchat") is not None and pkg["devDependencies"].get("cordova-plugin-cloudchat") == "file:src/plugins/cloudchat"
lock = json.load(open(os.path.join(root, "package-lock.json")))
ok = ok and lock["packages"][""]["devDependencies"].get("cordova-plugin-cloudchat") == "file:src/plugins/cloudchat" \
    and lock["packages"].get("node_modules/cordova-plugin-cloudchat", {}).get("resolved") == "src/plugins/cloudchat" \
    and "src/plugins/cloudchat" in lock["packages"]
print("  %s  the CloudChat plugin is in package.json, cordova.plugins and package-lock.json" % ("PASS" if ok else "FAIL"))
sys.exit(0 if ok else 1)
PY

if [ "$FAIL" -eq 0 ]; then
  printf '  cloud-code chat tester: ALL PASS\n'
else
  printf '  cloud-code chat tester: FAIL\n' >&2
  exit 1
fi
