#!/usr/bin/env bash
# Tester: MyTerminal's Store tab is a UI over cloud-store — the ONE engine —
# and a broken link reaches the screen BY NAME (#659, building on #576/#644).
#
# WHY. #597 shipped the tab as store.js running `nix profile` / `pkg` itself.
# #644 then shipped cloud-store, which also drives the nix profile (update,
# packages-rollback) and owns the $HOME link tree. Two things driving one
# package layer from one phone is the "two copies of one engine" shape this
# fleet keeps paying for, so the tab now runs nothing but cloud-store, and the
# engine grew the package verbs (installed/search/install/remove) the tab needs.
#
# T1  store.js invokes no package manager itself (comments stripped).
# T2  the engine path store.js calls equals the shipped install path declared
#     in ab_cloud-terminal-store/store.json — the same literal login-init.sh
#     carries; two spellings of it would be a tab that finds no engine.
# T3  END TO END against the REAL engine, through store.js's own transport and
#     parsers: apply → ok; a link mis-aimed in place → the rendered tab names
#     THAT link and not its healthy sibling; repair → ok; generations lists the
#     live one; rollback lands on the generation that still holds the broken
#     link and the tab names it again (rollback verifies where it lands).
# T4  a terminal that declares no package manager gets the engine's refusal,
#     named, not an empty list; an argument that is not a plain token is
#     refused before it reaches a shell; on a nix terminal Install/Remove
#     reach nix against the DECLARED profile, through the engine.
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"                  # → ac_cloud-myterminal
STORE_DIR="$(cd "$APP/../ab_cloud-terminal-store" && pwd)"
JS="$APP/hub/src/main/assets/frontend/js/store.js"
ENGINE="$STORE_DIR/cloud-store"
DECL_JSON="$STORE_DIR/store.json"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }

for t in node jq python3; do command -v "$t" >/dev/null || { echo "ERROR: $t required" >&2; exit 2; }; done
for f in "$JS" "$ENGINE" "$DECL_JSON"; do [ -f "$f" ] || { echo "ERROR: $f not found" >&2; exit 2; }; done

echo "== T1: store.js drives no package manager of its own =="
HITS="$(python3 - "$JS" <<'PY'
import re, sys
s = open(sys.argv[1], encoding="utf-8").read()
s = re.sub(r"/\*.*?\*/", "", s, flags=re.S)
s = re.sub(r"(^|[^:\\])//.*$", r"\1", s, flags=re.M)
for m in re.finditer(r"\bnix(-env)?\s+(profile|search|-i|-e|--install|--uninstall)\b|\bnix-env\b|\bpkg\s+(install|uninstall|search|list-installed)\b|\bapt(-get)?\s+(install|remove)\b", s):
    print(m.group(0))
PY
)"
if [ -n "$HITS" ]; then
    printf '%s\n' "$HITS" | sed 's/^/    /'
    bad "T1 store.js invokes a package manager directly — that is a second engine beside cloud-store; route it through a cloud-store verb instead"
else
    ok "T1 no nix/nix-env/pkg/apt invocation in store.js code"
fi

echo
echo "== T2: the engine path store.js calls is the declared install path =="
WANT="/$(jq -r '.store.install_dir' "$DECL_JSON")/$(jq -r '.store.engine' "$DECL_JSON")"
GOT="$(node -e 'const {Store}=require(process.argv[1]); console.log(Store.ENGINE_PATH+"|"+Store.ENGINE_NAME)' "$JS")"
if [ "$GOT" = "$WANT|$(jq -r '.store.engine' "$DECL_JSON")" ]; then
    ok "T2 Store.ENGINE_PATH = $WANT (store.json::store.install_dir + store.engine)"
else
    bad "T2 store.js calls '$GOT', store.json declares '$WANT' — the tab would look for an engine where none is shipped"
fi

echo
echo "== T3: through store.js, against the real engine: a broken link is NAMED =="
SB="$(mktemp -d)"; trap 'rm -rf "$SB"' EXIT
mkdir -p "$SB/home" "$SB/tools" "$SB/lib"
for t in alpha beta; do printf '#!/bin/sh\necho %s\n' "$t" >"$SB/tools/$t"; chmod +x "$SB/tools/$t"; done
cp "$ENGINE" "$SB/lib/cloud-store"; chmod +x "$SB/lib/cloud-store"
# The shape render-store.py emits, in search mode over the sandbox tool dir.
cat >"$SB/lib/declaration.sh" <<EOF
CLOUD_STORE_ROOT=$(jq -r '.store.root' "$DECL_JSON")
CLOUD_STORE_CURRENT=$(jq -r '.store.current' "$DECL_JSON")
CLOUD_STORE_GENERATIONS=$(jq -r '.store.generations' "$DECL_JSON")
CLOUD_STORE_BIN=$(jq -r '.store.bin' "$DECL_JSON")
CLOUD_STORE_ENGINE=$(jq -r '.store.engine' "$DECL_JSON")
CLOUD_STORE_MODE=search
CLOUD_STORE_SEARCH="$SB/tools"
CLOUD_STORE_TOOLS="alpha beta"
CLOUD_STORE_PACKAGE_MANAGER=none
EOF
# The same store, declared as a nix terminal, with a recording stand-in for nix:
# what is asserted is WHICH profile and attribute the engine hands nix.
mkdir -p "$SB/fakebin"
printf '#!/bin/sh\necho "NIXARGS: $*"\n' >"$SB/fakebin/nix"; chmod +x "$SB/fakebin/nix"
sed -e 's/^CLOUD_STORE_PACKAGE_MANAGER=none$/CLOUD_STORE_PACKAGE_MANAGER=nix/' "$SB/lib/declaration.sh" >"$SB/lib/declaration-nix.sh"
echo "CLOUD_STORE_NIX_PROFILE=$SB/declared-profile" >>"$SB/lib/declaration-nix.sh"

OUT="$(SB="$SB" CLOUD_STORE_HOME="$SB/home" node - "$JS" <<'JS' 2>&1
const { spawn } = require("child_process");
const fs = require("fs");
const { Store } = require(process.argv[2]);
const SB = process.env.SB;
// Fake PTY: echo the line back (a real PTY does), then run it in sh.
let onData = null;
global.Transport = {
  onPty: (id, cb) => { onData = cb; },
  onPtyExit: () => {},
  ptyStart: async () => {},
  ptyWrite: (id, line) => {
    onData(line.replace(/\n$/, "\r\n"));
    const p = spawn("sh", ["-c", line], { env: process.env });
    p.stdout.on("data", (d) => onData(d.toString()));
    p.stderr.on("data", (d) => onData(d.toString()));
  },
};
Store.ENGINE_PATH = SB + "/lib/cloud-store";
const say = (k, v) => console.log(k + "=" + v);
(async () => {
  await Store.engine("apply");
  let v = Store.parseVerify(await Store.engine("verify"));
  say("FRESH_OK", v.ok);

  // Break beta IN PLACE in the live generation: mis-aimed, as a stale link is.
  const gen = fs.readlinkSync(SB + "/home/.cloud-store/current");
  fs.unlinkSync(gen + "/bin/beta");
  fs.symlinkSync("/nonexistent/beta", gen + "/bin/beta");
  v = Store.parseVerify(await Store.engine("verify"));
  const html = Store.renderLinks(v);
  say("BROKEN_OK", v.ok);
  say("BROKEN_NAMES", v.broken.map((b) => b.name).join(","));
  say("HTML_NAMES_BETA", /store-item-name">beta</.test(html));
  say("HTML_NAMES_ALPHA", /store-item-name">alpha</.test(html));

  await Store.engine("repair");
  v = Store.parseVerify(await Store.engine("verify"));
  say("REPAIRED_OK", v.ok);
  const gens = Store.parseGenerations(await Store.engine("generations"));
  say("GENS", gens.map((g) => g.n + (g.live ? "*" : "")).join(","));

  const rb = await Store.engine("rollback");
  v = Store.parseVerify(rb);
  say("ROLLBACK_NAMES", v.broken.map((b) => b.name).join(","));
  say("ROLLBACK_LIVE", Store.parseGenerations(await Store.engine("generations")).filter((g) => g.live).map((g) => g.n).join(","));

  const inst = await Store.engine("installed");
  say("NOPM_RC", inst.rc);
  say("NOPM_SAYS", /no package manager/.test(inst.out));
  let refused = false;
  try { Store._line(["x;rm", "-rf"]); } catch { refused = true; }
  say("REFUSES_META", refused);
  process.env.CLOUD_STORE_DECLARATION = SB + "/lib/declaration-nix.sh";
  process.env.PATH = SB + "/fakebin:" + process.env.PATH;
  const ins = await Store.engine("install", "ripgrep");
  say("NIX_INSTALL", /NIXARGS: profile install --profile \S+\/declared-profile nixpkgs#ripgrep( |$)/m.test(ins.out));
  const rem = await Store.engine("remove", "ripgrep");
  say("NIX_REMOVE", /NIXARGS: profile remove --profile \S+\/declared-profile ripgrep( |$)/m.test(rem.out));
  delete process.env.CLOUD_STORE_DECLARATION;
  Store.ENGINE_PATH = SB + "/absent/cloud-store";
  const miss = await Store.engine("verify");
  say("MISSING_RC", miss.rc);
  say("MISSING_NAMED", miss.out.includes(SB + "/absent/cloud-store"));
  process.exit(0);
})().catch((e) => { console.log("ERROR=" + e.stack); process.exit(1); });
JS
)"
NODE_RC=$?
get() { printf '%s\n' "$OUT" | sed -n "s/^$1=//p" | tail -1; }
if [ "$NODE_RC" -ne 0 ]; then
    printf '%s\n' "$OUT" | sed 's/^/    /'
    bad "T3 the node driver crashed (rc=$NODE_RC)"
else
    [ "$(get FRESH_OK)" = true ] && ok "T3a after apply the tab reports every link resolving" \
        || bad "T3a a freshly applied store does not verify ok through the tab (FRESH_OK=$(get FRESH_OK))"
    if [ "$(get BROKEN_OK)" = false ] && [ "$(get BROKEN_NAMES)" = beta ] \
       && [ "$(get HTML_NAMES_BETA)" = true ] && [ "$(get HTML_NAMES_ALPHA)" = false ]; then
        ok "T3b a mis-aimed link is rendered by NAME (beta), its healthy sibling is not"
    else
        bad "T3b the tab did not name the broken link: ok=$(get BROKEN_OK) names='$(get BROKEN_NAMES)' html(beta)=$(get HTML_NAMES_BETA) html(alpha)=$(get HTML_NAMES_ALPHA) — a count or a bare 'failed' is the defect #644 was filed against"
    fi
    [ "$(get REPAIRED_OK)" = true ] && ok "T3c Repair through the tab rebuilds from the declaration and verifies ok" \
        || bad "T3c Repair through the tab did not leave a verifying store"
    [ "$(get GENS)" = "1,2*" ] && ok "T3d generations lists 1,2 with 2 live" \
        || bad "T3d generations through the tab = '$(get GENS)', want '1,2*'"
    if [ "$(get ROLLBACK_LIVE)" = 1 ] && [ "$(get ROLLBACK_NAMES)" = beta ]; then
        ok "T3e Rollback lands on generation 1 and the tab names its broken link (beta)"
    else
        bad "T3e Rollback: live='$(get ROLLBACK_LIVE)' named='$(get ROLLBACK_NAMES)', want live=1 named=beta"
    fi
    if [ "$(get NOPM_RC)" != 0 ] && [ "$(get NOPM_SAYS)" = true ]; then
        ok "T4a a terminal with no package manager gets the engine's named refusal, not an empty list"
    else
        bad "T4a installed on a no-package-manager terminal: rc=$(get NOPM_RC) named=$(get NOPM_SAYS)"
    fi
    [ "$(get REFUSES_META)" = true ] && ok "T4b a non-token argument is refused before any shell sees it" \
        || bad "T4b store.js passed shell metacharacters into its sh -c line"
    if [ "$(get NIX_INSTALL)" = true ] && [ "$(get NIX_REMOVE)" = true ]; then
        ok "T4d on a nix terminal, Install/Remove reach nix through the engine against the DECLARED profile"
    else
        bad "T4d install/remove did not hand nix the declared profile: install=$(get NIX_INSTALL) remove=$(get NIX_REMOVE)"
    fi
    if [ "$(get MISSING_RC)" = 127 ] && [ "$(get MISSING_NAMED)" = true ]; then
        ok "T4c a terminal without the engine gets a message naming where it looked"
    else
        bad "T4c absent engine: rc=$(get MISSING_RC) named=$(get MISSING_NAMED) — silence here reads as a dead tab"
    fi
fi

echo
echo "RESULT: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
