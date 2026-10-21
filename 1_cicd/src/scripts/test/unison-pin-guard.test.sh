#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════╗
# ║ unison-pin-guard.test — prove the guard FAILS when any peer's   ║
# ║ Unison differs from the pin, and passes when they all agree     ║
# ╚══════════════════════════════════════════════════════════════════╝
#
# #1084. A guard only ever watched passing is indistinguishable from `exit 0`. This builds a fixture
# from the REAL pin, gradle and workflow, with a peer checkout that agrees, requires a pass, then
# breaks each property on a copy, proves the break landed, and requires a FAIL naming it.
# python3 and coreutils only; no network, no build.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
GUARD="$ROOT/1_cicd/src/scripts/cloud-android-unison-pin-guard.py"
FAILURES=0
ok()   { printf 'ok     %s\n' "$1"; }
fail() { printf 'FAIL   %s\n' "$1"; FAILURES=$((FAILURES + 1)); }

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
PIN=ab_cloud-libs-shared/libs/rclone-engine/data/unison-binary.json
GRADLE=ab_cloud-libs-shared/libs/rclone-engine/build.gradle
WF=.github/workflows/build-unison-static.yml

fresh() {  # fresh <name>: a copy of the real inputs plus peer files that agree
    local d="$WORK/$1"; rm -rf "$d"
    for f in "$PIN" "$GRADLE" "$WF"; do mkdir -p "$d/repo/$(dirname "$f")"; cp "$ROOT/$f" "$d/repo/$f"; done
    python3 - "$d" <<'PYTHON'
import json, os, sys
d = sys.argv[1]
pin = json.load(open(d + "/repo/ab_cloud-libs-shared/libs/rclone-engine/data/unison-binary.json"))
for p in pin["peers"]:
    f = os.path.join(d, "peers", p["repo"], p["path"])
    os.makedirs(os.path.dirname(f), exist_ok=True)
    json.dump({"version": pin["version"], "ocaml": pin["ocaml"]}, open(f, "w"))
PYTHON
    echo "$d"
}
run() { python3 "$GUARD" check "$1/repo" --peers-root "$1/peers" --require-peers 2>&1; }
expect_fail() {  # expect_fail <label> <dir> <needle>
    local out; out="$(run "$2")"
    if [ $? -ne 0 ] && grep -q -- "$3" <<<"$out"; then ok "$1 -> RED ($3)"; else fail "$1 was not caught (wanted '$3'): $out"; fi
}
mutate() {  # mutate <file> <python expression on d (the json) or sed script>
    python3 - "$1" "$2" <<'PYTHON'
import json, sys
f, expr = sys.argv[1:3]
d = json.load(open(f)); exec(expr); json.dump(d, open(f, "w"))
PYTHON
}

D=$(fresh green)
out="$(run "$D")"; if [ $? -eq 0 ]; then ok "the real pin with agreeing peers passes"; else fail "the real fixture is red: $out"; fi

D=$(fresh peer-version)
f=$(find "$D/peers" -name unison-pin.json | head -1); mutate "$f" 'd["version"]="2.53.7"'
expect_fail "a peer on another Unison version" "$D" "U4 .*version '2.53.7'"

D=$(fresh peer-ocaml)
f=$(find "$D/peers" -name unison-pin.json | tail -1); mutate "$f" 'd["ocaml"]="5.1.0"'
expect_fail "a peer built with another OCaml" "$D" "U4 .*ocaml '5.1.0'"

D=$(fresh peer-missing)
rm "$(find "$D/peers" -name unison-pin.json | head -1)"
expect_fail "a peer that cannot be read (required)" "$D" "U4 peer .* is not readable"

D=$(fresh peer-shape)
f=$(find "$D/peers" -name unison-pin.json | head -1); echo '{"version":"2.53.8"}' > "$f"
expect_fail "a peer file without ocaml" "$D" "U4 .*both .version. and .ocaml."

D=$(fresh pin-version)
mutate "$D/repo/$PIN" 'd["version"]="2.54.0"'
expect_fail "the pin bumped but its URLs and directories not" "$D" "U1 pin .*does not carry version"

D=$(fresh pin-hash)
mutate "$D/repo/$PIN" 'd["binaries"]["arm64-v8a"]["binary_sha256"]="abc"'
expect_fail "a malformed binary hash" "$D" "U1 pin arm64-v8a.binary_sha256"

D=$(fresh pin-nohash)
mutate "$D/repo/$PIN" 'd["binaries"]["x86_64"]["tar_sha256"]=""'
expect_fail "an unpinned tarball hash" "$D" "U1 pin x86_64.tar_sha256"

D=$(fresh pin-interp)
mutate "$D/repo/$PIN" 'd["interp"]="glibc"'
expect_fail "a dynamically linked binary declared" "$D" "U1 pin interp"

D=$(fresh gradle-literal)
printf '\ndef shadowPin = "%s"\n' "$(python3 -c "import json;print(json.load(open('$ROOT/$PIN'))['version'])")" >> "$D/repo/$GRADLE"
expect_fail "a literal version in gradle" "$D" "U2 .*literally"

D=$(fresh gradle-nohash)
sed -i 's/hasProgramInterpreter/hasNothing/g' "$D/repo/$GRADLE"
expect_fail "gradle that stops checking PT_INTERP" "$D" "U2 .*hasProgramInterpreter"

D=$(fresh wf-nopin)
sed -i 's/unison-binary.json/somewhere-else.json/g' "$D/repo/$WF"
expect_fail "a build workflow that does not read the pin" "$D" "U3 .*does not read version and ocaml"

D=$(fresh nopeers)
mutate "$D/repo/$PIN" 'd["peers"]=[]'
expect_fail "a pin that lists no peer" "$D" "U4 the pin lists no peer"

# without --require-peers an absent checkout is a NOTE, never a pass for a peer that differs
D=$(fresh optional)
rm "$(find "$D/peers" -name unison-pin.json | head -1)"
out="$(python3 "$GUARD" check "$D/repo" --peers-root "$D/peers" 2>&1)"
if [ $? -eq 0 ] && grep -q '^NOTE peer' <<<"$out"; then ok "an absent peer is named, not silently skipped"; else fail "optional-peer mode lost its NOTE: $out"; fi
f=$(find "$D/peers" -name unison-pin.json | head -1); mutate "$f" 'd["version"]="1.0.0"'
python3 "$GUARD" check "$D/repo" --peers-root "$D/peers" >/dev/null 2>&1 && fail "a present peer that differs passed in optional mode" || ok "a present peer that differs fails even in optional mode"

# the peer-side comparison the private repositories run against the public pin
echo '{"version":"2.53.8","ocaml":"4.14.2"}' > "$WORK/p-good.json"; echo '{"version":"2.53.6","ocaml":"4.14.2"}' > "$WORK/p-bad.json"
python3 "$GUARD" peer-check "$WORK/p-good.json" "$ROOT/$PIN" >/dev/null && ok "peer-check passes a peer on the pin" || fail "peer-check rejected a matching peer"
python3 "$GUARD" peer-check "$WORK/p-bad.json" "$ROOT/$PIN" >/dev/null && fail "peer-check passed a drifted peer" || ok "peer-check fails a drifted peer"

echo
if [ "$FAILURES" -eq 0 ]; then echo "PASS  unison-pin-guard.test"; exit 0; fi
echo "$FAILURES failure(s)"; exit 1
