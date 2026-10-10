#!/usr/bin/env bash
# Tester: MyTerminal drives the FLEET's two terminals, from the declaration, and
# says something actionable when one is not there (#675).
#
# WHAT WAS MEASURED, AND WHAT TURNED OUT NOT TO BE TRUE.
#
# #675 was opened on the theory that this app targets an upstream terminal
# PACKAGE — `com.termux` / `com.termux.nix` — the way #605 did, and that the fix
# was to swap in the fork ids. Measurement refuted the premise: this app holds no
# package id for a terminal at all, and never did. It reaches the selected env
# over LOOPBACK SSH (both sandboxes share 127.0.0.1, so there is no cross-UID
# barrier), which means the target is a host/port/user and not a packageName, and
# there is no RUN_COMMAND permission and no <queries> entry in its manifest to be
# wrong about. That is why T1 is written as a STANDING BAN rather than a
# migration: the correct number of upstream ids in this app is zero, and the
# cheapest way for the defect the ticket expected to appear later is for somebody
# to add the first one.
#
# WHAT WAS ACTUALLY WRONG, and is what T3 and T4 pin:
#   • the Configs backend switcher was a two-way Kotlin if/else over the two
#     IdePrefs constants, so a third env declared in terminal-targets.json would
#     have been switcher-unreachable — the choice was code, not data;
#   • the setup instructions were one hand-written string that told the reader to
#     start the nix-on-droid sshd on port 8022 while the JSON dialled 8024. Two
#     declarations of one port, so following the instructions exactly still gave a
#     terminal that would not connect, and the symptom was indistinguishable from
#     a broken app.
#
# Since then loopback SSH became the FALLBACK: the terminals are reached through their own
# signature-guarded session service first (test-terminal-session.sh covers that path).
#
# Nothing here touches a device or opens a socket.
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"          # → ac_cloud-myterminal
TGT="$APP/data/terminal-targets.json"
KT="$APP/hub/src/main/java/com/diegonmarcos/ide"
STRINGS="$APP/hub/src/main/res/values/strings.xml"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }

command -v jq >/dev/null || { echo "ERROR: jq required" >&2; exit 2; }
command -v awk >/dev/null || { echo "ERROR: awk required" >&2; exit 2; }
[ -f "$TGT" ] || { echo "ERROR: declaration not found at $TGT" >&2; exit 2; }
[ -f "$STRINGS" ] || { echo "ERROR: strings not found at $STRINGS" >&2; exit 2; }
[ -d "$KT" ] || { echo "ERROR: hub sources not found at $KT" >&2; exit 2; }

echo "== T1: no upstream terminal package id appears anywhere in this app =="

# The whole tree, sources and declarations alike. Since the zero-setup sessions
# this app DOES name its terminals by package — to bind their session service —
# but only the FLEET's ids (cld.termux, cld.termux.nix), declared once in
# terminal-targets.json::backends.*.package and mirrored in the manifest's
# <queries> (test-terminal-session.sh pins the two equal). An upstream com.termux*
# id is still the #605 defect, and still banned here.
#
# PROSE IS STRIPPED FIRST, and that is not a loophole. The `_doc` explaining why
# the ban exists has to NAME the thing it bans, and so does the comment above a
# guard — the first draft of this tester failed on its own documentation, which is
# the tester reading its warning as the thing it warns about. So: `_`-prefixed
# keys are dropped from every JSON object, and `//`, `/* */` and `<!-- -->`
# comments from every source file. What is left is only what the APK ACTS on.
HITS="$(python3 - "$APP" <<'PY'
import io, json, os, re, sys
root = sys.argv[1]
def strip_json(o):
    if isinstance(o, dict):
        return {k: strip_json(v) for k, v in o.items() if not k.startswith("_")}
    if isinstance(o, list):
        return [strip_json(v) for v in o]
    return o
targets = [os.path.join(root, "build.json")]
for base in (os.path.join(root, "data"), os.path.join(root, "hub", "src")):
    for dirpath, _, names in os.walk(base):
        for n in names:
            targets.append(os.path.join(dirpath, n))
PAT = re.compile(r"com\.termux[A-Za-z0-9_.]*")
for p in sorted(set(targets)):
    if not os.path.isfile(p):
        continue
    try:
        text = io.open(p, encoding="utf-8").read()
    except (UnicodeDecodeError, OSError):
        continue
    if p.endswith(".json"):
        try:
            text = json.dumps(strip_json(json.loads(text)))
        except ValueError:
            pass
    else:
        text = re.sub(r"<!--.*?-->", "", text, flags=re.S)
        text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
        text = re.sub(r"^[ \t]*//.*$", "", text, flags=re.M)
    for m in PAT.finditer(text):
        print("%s: %s" % (os.path.relpath(p, root), m.group(0)))
PY
)"
if [ -n "$HITS" ]; then
    printf '%s\n' "$HITS" | sed 's/^/    /'
    bad "T1 an upstream terminal package id is named in this app. It reaches the fleet's terminals by THEIR ids (cld.termux*), so a com.termux* name here is the #605 defect arriving — that ticket shipped com.termux.nix where cld.termux.nix was meant and login could not find proot-static"
else
    ok "T1 no com.termux* id in data/, hub/src/ or build.json"
fi

echo
echo "== T2: BOTH of the fleet's terminals are declared, and the app reads them =="

KEYS="$(jq -r '.backends | keys_unsorted | join(" ")' "$TGT")"
COUNT="$(jq -r '.backends | length' "$TGT")"
MISSING=""
for want in termux nix-on-droid; do
    jq -e --arg k "$want" '.backends | has($k)' "$TGT" >/dev/null || MISSING="$MISSING $want"
done
if [ -n "$MISSING" ]; then
    bad "T2 terminal-targets.json::backends is missing:$MISSING (has: $KEYS). The fleet ships two terminals deliberately — ac_cloud-termux and ac_cloud-nix-on-droid are different RUNTIMES, one nix profile and one Debian-style pkg, so neither may be dropped or folded into the other"
else
    # Every declared backend must carry the full tuple the SSH client needs, or
    # TerminalTargets.target() throws on getString and the env is declared but
    # unusable — reachable in the switcher and dead on tap.
    INCOMPLETE="$(jq -r '
        .backends | to_entries
        | map(select((.value.label // "") == "" or (.value.host // "") == ""
                     or ((.value.port // 0) | tonumber) <= 0 or (.value.user // "") == ""))
        | map(.key) | join(" ")' "$TGT")"
    if [ -n "$INCOMPLETE" ]; then
        bad "T2 declared backend(s) '$INCOMPLETE' lack a complete label/host/port/user tuple — offered in the switcher and dead on tap"
    else
        ok "T2 $COUNT backends declared, both fleet terminals present, every tuple complete ($KEYS)"
    fi
fi

echo
echo "== T3: the choice of backend is DATA — the switcher cycles the declared list =="

CFG="$KT/ConfigsActivity.kt"
if [ ! -f "$CFG" ]; then
    bad "T3 ConfigsActivity.kt not found at $CFG — the switcher cannot be checked, and a check that cannot reach its subject must fail"
else
    # Scope to the switcher's own lambda, not the file. IdePrefs.BACKEND_* and
    # TerminalTargets both appear in several unrelated blocks here (the
    # connection-override dialog, the SSH-key card), so a whole-file grep for
    # either proves nothing about this block. Window: from the card that renders
    # cfg_terminal_backend to the start of the next card.
    BLOCK="$(awk '
        /R\.string\.cfg_terminal_backend[^_]/ { inb = 1 }
        inb && /cfg_terminal_conn/            { exit }
        inb                                   { print }
    ' "$CFG")"
    if [ -z "$BLOCK" ]; then
        bad "T3 could not isolate the backend-switcher block in ConfigsActivity.kt — re-scope this assertion rather than widening it to the file"
    else
        USES_ALL=0
        BRANCHES=0
        printf '%s\n' "$BLOCK" | grep -q 'TerminalTargets\.all()' && USES_ALL=1
        # A two-way Kotlin if/else over the two constants is the defect: the
        # preference stops being data the moment code enumerates the options.
        printf '%s\n' "$BLOCK" | grep -qE 'if[[:space:]]*\(.*BACKEND_|BACKEND_TERMUX.*else|else.*BACKEND_' && BRANCHES=1
        if [ "$USES_ALL" -eq 0 ]; then
            bad "T3 the backend switcher does not read TerminalTargets.all() — it is not cycling the declared list, so a third entry in terminal-targets.json would be unreachable from the UI"
        elif [ "$BRANCHES" -eq 1 ]; then
            bad "T3 the backend switcher still branches on an IdePrefs.BACKEND_* constant. Two apps may PREFER different terminals, but the preference is data; code that enumerates the options is a second declaration of the list"
        else
            ok "T3 switcher cycles TerminalTargets.all() with no constant-enumerating branch"
        fi
    fi
fi

echo
echo "== T4: an unreachable terminal produces a NAMED, actionable message =="

# (a) The inline failure line must NAME the target — label, host and port. A
# bare "connection failed" is the silent half of #233: true, useless, and
# indistinguishable from the app being broken.
BRIDGE="$KT/TerminalBridge.kt"
if [ ! -f "$BRIDGE" ]; then
    bad "T4a TerminalBridge.kt not found at $BRIDGE"
else
    ERRLINE="$(awk '/catch/ { inc = 1 } inc { print } inc && /__aptyData/ { exit }' "$BRIDGE")"
    NAMED=0
    printf '%s\n' "$ERRLINE" | grep -q 'target\.label' \
        && printf '%s\n' "$ERRLINE" | grep -q 'target\.host' \
        && printf '%s\n' "$ERRLINE" | grep -q 'target\.port' && NAMED=1
    if [ "$NAMED" -eq 1 ]; then
        ok "T4a the failure line names target.label, target.host and target.port"
    else
        bad "T4a the SSH failure line does not name the label/host/port it tried. The user cannot tell a stopped sshd from a wrong port from a missing key, and per #639 the answer is never a 'go to Settings' nag — it is the actual target, printed"
    fi
fi

# (b) The setup instructions must be GENERATED from the declaration. A literal
# port in strings.xml is a second declaration of a value the app already dials,
# and that is exactly how 8022 and 8024 came to disagree. Checked in BOTH
# locales, because a translation carrying the stale literal is the same defect.
LITERALS=""
for f in "$STRINGS" "$APP/hub/src/main/res/values-es/strings.xml"; do
    [ -f "$f" ] || continue
    LINE="$(awk '/name="cfg_terminal_setup_steps"/ { print }' "$f")"
    if [ -z "$LINE" ]; then
        LITERALS="$LITERALS $(basename "$(dirname "$f")"):absent"
    elif printf '%s' "$LINE" | grep -qE 'port [0-9]|puerto [0-9]|sshd -p [0-9]|-p 80[0-9][0-9]'; then
        LITERALS="$LITERALS $(basename "$(dirname "$f")"):literal-port"
    elif ! printf '%s' "$LINE" | grep -q '%1\$s'; then
        LITERALS="$LITERALS $(basename "$(dirname "$f")"):no-placeholder"
    fi
done
if [ -n "$LITERALS" ]; then
    bad "T4b cfg_terminal_setup_steps is not generated from the declaration —$LITERALS. It told the reader to start the nix-on-droid sshd on 8022 while terminal-targets.json dialled 8024, so the instructions were correct-looking and unfollowable"
else
    ok "T4b setup instructions carry no port literal in either locale and take the generated block"
fi

# (c) The generator must exist and be fed by the declared list, or (b) passes on
# a placeholder nothing fills.
GENFILE="$(grep -rl 'cfg_terminal_setup_env_block' "$KT" || true)"
if [ -z "$GENFILE" ]; then
    bad "T4c no Kotlin reads R.string.cfg_terminal_setup_env_block — the placeholder in cfg_terminal_setup_steps is filled by nothing, so T4b would pass over instructions that render empty"
elif [ "$(printf '%s\n' "$GENFILE" | wc -l)" -ne 1 ]; then
    bad "T4c $(printf '%s\n' "$GENFILE" | wc -l) files render cfg_terminal_setup_env_block — one generator, or they will disagree about which envs exist"
else
    # Window BOTH WAYS around the reference. The iteration that feeds it reads
    # `TerminalTargets.all().joinToString { ... getString(...) }`, so the call is
    # ABOVE the resource name, and the first draft of this assertion scanned only
    # forward and went red on correct code. Eight lines either side, well inside
    # the engine's 40-line proximity cap.
    WIN="$(grep -B8 -A8 'cfg_terminal_setup_env_block' "$GENFILE")"
    if printf '%s\n' "$WIN" | grep -q 'TerminalTargets\.all()'; then
        ok "T4c the per-env setup block is generated over TerminalTargets.all() in $(basename "$GENFILE")"
    else
        bad "T4c the setup block is rendered without iterating TerminalTargets.all() — a hand-listed env is the port literal coming back under a new name"
    fi
fi

echo "== T5: the frontend Configs page offers EVERY declared terminal, and a dead one is loud =="
# The Configs page the user actually opens is the WebView overlay
# (hub/src/main/assets/frontend), not ConfigsActivity; until this block it had
# no terminal field at all, only a button to the native screen. The overlay
# learns its options from AndroidTerm.terminals() at runtime, so the claim
# "both declared terminals are reachable choices" is proved by RUNNING the real
# configs.js against the bridge's answer built from the declaration, plus a
# check that the bridge's answer is the whole declared list.
FE="$APP/hub/src/main/assets/frontend"
BRIDGE="$KT/TerminalBridge.kt"
command -v node >/dev/null || { echo "ERROR: node required" >&2; exit 2; }

# (a) bridge: terminals() iterates the declared list unfiltered, and
# selectTerminal refuses a key the declaration does not hold.
body() { awk -v f="fun $1(" 'index($0,f){on=1} on{print; n+=gsub(/\{/,"{"); n-=gsub(/\}/,"}"); if(n==0 && seen) exit; if(n>0) seen=1}' "$BRIDGE"; }
TB="$(body terminals)"; SB="$(body selectTerminal)"
if printf '%s' "$TB" | grep -q 'TerminalTargets\.all()\.forEach' && ! printf '%s' "$TB" | grep -qE 'filter|take\(|drop\('; then
    ok "T5a AndroidTerm.terminals() returns every entry of TerminalTargets.all()"
else
    bad "T5a AndroidTerm.terminals() does not return the whole declared list — a declared terminal would be missing from the Configs field"
fi
if printf '%s' "$SB" | grep -q 'TerminalTargets\.all()\.none' && printf '%s' "$SB" | grep -q '__termProbe'; then
    ok "T5b selectTerminal refuses an undeclared key out loud"
else
    bad "T5b selectTerminal accepts a key without checking the declaration, or refuses it silently — forBackend would quietly fall back to another terminal"
fi
# Since the zero-setup sessions the probe is TerminalSessions.probe (the env's session service,
# SSH only for an older build), and the sentence it reports is TerminalRoute.explain over the
# target's label, host and port — so both halves are checked, the bridge and the probe.
PB="$(body probeTerminal)"
SESS="$KT/TerminalSessions.kt"
PROBE="$(awk 'index($0,"fun probe("){on=1} on{print; n+=gsub(/\{/,"{"); n-=gsub(/\}/,"}"); if(n==0 && seen) exit; if(n>0) seen=1}' "$SESS" 2>/dev/null)"
if printf '%s' "$PB" | grep -q 'TerminalSessions\.probe(' && printf '%s' "$PB" | grep -q '__termProbe' \
   && printf '%s' "$PROBE" | grep -q 'ssh\.testConnection' \
   && printf '%s' "$PROBE" | grep -q 'TerminalRoute\.explain(.*t\.label, t\.host, t\.port'; then
    ok "T5c the probe tries the session service, then SSH, and names the terminal it could not reach"
else
    bad "T5c the selection probe does not go through TerminalSessions.probe, or that probe does not fall back to SSH / name label, host and port — a missing terminal would look like a dead tab again"
fi

# (b) run configs.js for real against the bridge shape built from the JSON.
DECL="$(jq -c '{selected: (.backends|keys_unsorted[0]), backends: [.backends|to_entries[]|{key, label:.value.label, host:.value.host, port:.value.port}]}' "$TGT")"
WANT="$(jq -r '.backends|keys_unsorted|join(",")' "$TGT")"
OUT="$(DECL="$DECL" node - "$FE/js/configs.js" <<'JS' 2>&1
const fs = require("fs");
const els = {};
const mk = (id) => els[id] || (els[id] = { id, value: "", hidden: true, style: {}, textContent: "", children: [],
  _h: {}, addEventListener(ev, f) { this._h[ev] = f; }, appendChild(c) { this.children.push(c); },
  set innerHTML(v) { this.children = []; } });
global.document = { getElementById: mk, createElement: () => ({}) };
global.localStorage = { getItem: () => null, setItem() {}, removeItem() {} };
global.alert = () => {};
global.window = global;
const picked = [];
let probes = 0;
window.AndroidTerm = { terminals: () => process.env.DECL, selectTerminal: (k) => picked.push(k), probeTerminal: () => probes++ };
eval(fs.readFileSync(process.argv[2], "utf8"));
Configs.open();
const sel = els["cfg-terminal"];
const keys = sel.children.map((o) => o.value);
for (const k of keys) sel._h.change({ target: { value: k } });
window.__termProbe(keys[1], "Cloud Terminal (nix) (127.0.0.1:8024) unreachable: Connection refused");
const st = els["cfg-terminal-status"];
console.log(JSON.stringify({ keys: keys.join(","), picked: picked.join(","), probes, status: st.textContent, red: !!st.style.color }));
JS
)"
GOT_KEYS="$(printf '%s' "$OUT" | jq -r '.keys' 2>/dev/null)"
if [ "$GOT_KEYS" = "$WANT" ] && [ "$(printf '%s' "$OUT" | jq -r '.picked')" = "$WANT" ]; then
    ok "T5d the Configs Terminal field offers and selects every declared terminal ($WANT)"
else
    bad "T5d the Configs Terminal field offers '${GOT_KEYS:-?}' but the declaration holds '$WANT' — a declared terminal is not a reachable choice ($OUT)"
fi
if [ "$(printf '%s' "$OUT" | jq -r '.probes')" -ge 1 ] && [ "$(printf '%s' "$OUT" | jq -r '.red')" = true ] \
   && printf '%s' "$OUT" | jq -r '.status' | grep -q '127.0.0.1:8024'; then
    ok "T5e opening Configs probes the selected terminal and an unreachable one renders red, named"
else
    bad "T5e an unreachable terminal is not shown loudly on the Configs page ($OUT)"
fi
if grep -q '<select id="cfg-terminal"></select>' "$FE/index.html"; then
    ok "T5f the Terminal field carries no hand-written options"
else
    bad "T5f index.html's cfg-terminal is missing or lists options by hand — a second declaration of the terminals"
fi

echo
echo "── MyTerminal targets: $PASS passed, $FAIL failed ──"
[ "$FAIL" -eq 0 ]
