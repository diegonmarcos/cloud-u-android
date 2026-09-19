#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════╗
# ║ wireguard-profiles-guard.test — prove the guard fails, not just   ║
# ║ that it runs                                                      ║
# ╚══════════════════════════════════════════════════════════════════╝
#
# WHY THIS EXISTS. A guard that has only ever been watched succeeding is
# indistinguishable from a guard that returns 0 unconditionally. This
# repository has shipped an assertion that compared an expression to itself, a
# pipeline whose exit status was read through `tail`, and four testers that
# passed only because ripgrep was missing from the runner and their `rg` call
# failed open. The four phone profiles live in TWO hand-maintained
# declarations (vault -> cloud-infra dist, and this repo's
# aa_cloud-superapp/build.json::ui.wireguard_profiles) and the second one
# silently drifted in exactly the way #523 documented. Every case below breaks
# the contract in one specific way, demands the guard notice that exact way,
# and throws the copy away. The break happens in a throwaway copy, never in
# the working tree.
#
# WHY THE REFERENCE IS A FIXTURE, NOT THE LIVE DIST. This tester runs on
# every push, and the dist is a moving target that a sibling pipeline
# regenerates; depending on the live network for a hermetic RED/GREEN proof
# would let a sibling outage or a dist refresh turn the proof itself red for
# reasons unrelated to the code under test. So the test derives a dist-shaped
# fixture FROM the working tree's build.json: the pristine tree is green by
# construction, and each mutation below breaks exactly one thing against a
# reference the test fully controls. The LIVE fetch is exercised on every push
# by the workflow's own guard step (cloud-android-wireguard-profiles-guard.py
# check/derive check) — and the fetch-failure proofs below cover the "could
# not read the reference" direction with fixtures that cannot exist.
#
# No ripgrep anywhere: the runner has had it missing before, and testers here
# have passed on its absence rather than on their assertions.

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
GUARD="1_cicd/src/scripts/cloud-android-wireguard-profiles-guard.py"
BUILD_REL="aa_cloud-superapp/build.json"
FAILURES=0

ok()   { printf 'ok     %s\n' "$1"; }
fail() { printf 'FAIL   %s\n' "$1"; FAILURES=$((FAILURES + 1)); }

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# The pristine copy is taken from the WORKING TREE, not the git index: the
# guard has to be provable against the edit somebody is about to commit, not
# only against what is already committed.
TREE="$WORK/tree"
mkdir -p "$TREE/aa_cloud-superapp"
cp "$ROOT/$BUILD_REL" "$TREE/aa_cloud-superapp/build.json"
PRISTINE="$TREE/aa_cloud-superapp/build.json"
DIST_FIXTURE="$WORK/dist.json"

# A dist-shaped fixture derived from build.json's own wireguard block, so the
# pristine tree passes by construction (same bytes through the same
# transform — see the guard script's docstring on why the parsed shape is the
# right one to derive from).
python3 - "$PRISTINE" "$DIST_FIXTURE" <<'PY'
import json
import sys

build_json, dist_out = sys.argv[1], sys.argv[2]
build = json.load(open(build_json, encoding="utf-8"))
ui = build["ui"]["wireguard_profiles"]


def split_list(value):
    return [token.strip() for token in str(value).split(",") if token.strip()]


out = {}
for profile in ui["profiles"]:
    peers = []
    for peer in profile["peers"]:
        peers.append({
            "public_key": peer["public_key"],
            "endpoint": peer["endpoint"],
            "allowed_ips": split_list(peer["allowed_ips"]),
            "persistent_keepalive": int(peer.get("persistent_keepalive", "25") or 25),
        })
    out[profile["id"]] = {
        "name": "wg-" + profile["id"],
        "config_text": "# fixture derived from build.json by the mutation test",
        "parsed": {
            "interface": {
                "address": split_list(ui["interface_address"]),
                "dns": split_list(profile["dns"]),
                "mtu": int(ui["interface_mtu"]),
                "private_key": None,
            },
            "peers": peers,
        },
    }
with open(dist_out, "w", encoding="utf-8") as handle:
    json.dump({"wireguard": out}, handle, indent=2)
    handle.write("\n")
PY

# One mutation helper, so every case below edits JSON without a full-file
# round-trip (which would re-format the parts of build.json the mutation must
# not touch — the file is deliberately NOT canonical json.dumps output). Every
# action asserts its anchor: a mutation that matched nothing is a broken test,
# not a pass.
cat > "$WORK/mutate.py" <<'PY'
import json
import sys

action, path = sys.argv[1], sys.argv[2]
with open(path, encoding="utf-8") as handle:
    text = handle.read()
original = text


def split_lines():
    global text
    text = text.split("\n")


def join_lines():
    global text
    text = "\n".join(text)


def replace_unique(old, new):
    global text
    if text.count(old) != 1:
        sys.exit("anchor not unique in %s: %r (count=%d)" % (path, old, text.count(old)))
    text = text.replace(old, new)


def replace_line_after(anchor, needle, replacement, limit=30):
    global text
    lines = text.split("\n")
    anchor_index = next((i for i, line in enumerate(lines) if anchor in line), None)
    if anchor_index is None:
        sys.exit("anchor line not found in %s: %r" % (path, anchor))
    for i in range(anchor_index + 1, min(anchor_index + limit, len(lines))):
        if needle in lines[i]:
            lines[i] = replacement
            text = "\n".join(lines)
            return
    sys.exit("needle not found after anchor in %s: %r" % (path, needle))


def drop_profile(profile_id):
    global text
    start = text.find('"id": "%s"' % profile_id)
    if start == -1:
        sys.exit("profile %s not found in %s" % (profile_id, path))
    object_start = text.rfind("\n        {", 0, start)
    if object_start == -1:
        sys.exit("profile %s object opener not found" % profile_id)
    brace = text.index("{", object_start)
    depth = 0
    object_end = None
    for i in range(brace, len(text)):
        char = text[i]
        if char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                object_end = i + 1
                break
    if object_end is None:
        sys.exit("profile %s object never closes" % profile_id)
    tail = text[object_end:]
    if tail.startswith(","):
        tail = tail[1:]
    text = text[:object_start] + tail
    json.loads(text)  # the edit must leave valid JSON


if action == "flip_allowed":
    replace_unique(
        '"allowed_ips": "0.0.0.0/0, fd0c:1d01::/64, 2603:c026:c104:8f00:ff9b::/96"',
        '"allowed_ips": "0.0.0.0/0, ::/0"')
elif action == "flip_endpoint":
    replace_line_after(
        '"id": "v4-split"',
        '"endpoint": "35.226.147.64:443"',
        '              "endpoint": "35.226.147.64:51820",')
elif action == "flip_dns":
    replace_line_after(
        '"id": "v6-split"',
        '"dns": "fd0c:1d01::1, 10.1.0.1"',
        '              "dns": "8.8.8.8, 10.1.0.1",')
elif action == "shuffle_allowed":
    # Same routes, same set, different order and one double space — the
    # normalised comparison must NOT see this as drift. The anchor legitimately
    # appears twice (v4-split and v6-split both give oci-analytics the same
    # three routes), so every occurrence is shuffled.
    old = '"allowed_ips": "10.1.0.0/24, fd0c:1d01::/64, 2603:c026:c104:8f00:ff9b::/96"'
    if text.count(old) < 1:
        sys.exit("shuffle anchor not found in %s" % path)
    text = text.replace(
        old, '"allowed_ips": "2603:c026:c104:8f00:ff9b::/96, 10.1.0.0/24,  fd0c:1d01::/64"')
elif action == "drop_profile":
    drop_profile("v4-split")
elif action == "add_redacted_key":
    data = json.loads(text)
    data["wireguard"]["v4-split"]["parsed"]["interface"]["private_key"] = "<PROVIDED_BY_DEVICE>"
    text = json.dumps(data, indent=2)
elif action == "add_dist_peer":
    data = json.loads(text)
    data["wireguard"]["v4-split"]["parsed"]["peers"].append({
        "public_key": "EXTRAKEY0000000000000000000000000000000000000=",
        "endpoint": "10.0.0.2:51820",
        "allowed_ips": ["10.99.0.0/16"],
        "persistent_keepalive": 25,
    })
    text = json.dumps(data, indent=2)
else:
    sys.exit("unknown mutation action: %s" % action)

if text == original:
    sys.exit("mutation %s changed nothing in %s — the anchor moved; update the test" % (action, path))
with open(path, "w", encoding="utf-8") as handle:
    handle.write(text)
PY

mutate() { # mutate <action> <file>
    python3 "$WORK/mutate.py" "$1" "$2" || { fail "mutation $1 could not be applied ($2)"; exit 1; }
}

sandbox() { # sandbox <name> -> mkdir a fresh copy of the pristine tree, echo its root
    local dir="$WORK/sb-$1"
    mkdir -p "$dir/aa_cloud-superapp"
    cp "$PRISTINE" "$dir/aa_cloud-superapp/build.json"
    printf '%s' "$dir"
}

run_guard() { # run_guard <tree-root> <dist-file> <subcommand...>
    local tree="$1" dist="$2"
    shift 2
    CLOUD_ANDROID_ROOT="$tree" CLOUD_WG_DIST_FILE="$dist" python3 "$ROOT/$GUARD" "$@" 2>&1
}

expect_caught() {
    # expect_caught <name> <pattern> <tree-root> <dist-file> <subcommand...>
    local name="$1" pattern="$2" tree="$3" dist="$4"
    shift 4
    local out
    out="$(run_guard "$tree" "$dist" "$@")"
    if [ "$?" -eq 0 ]; then
        fail "$name: the guard PASSED where it must fail"
        printf '%s\n' "$out" | sed 's/^/    /'
        return 1
    fi
    if printf '%s' "$out" | grep -q "$pattern"; then
        ok "$name"
    else
        fail "$name: the guard failed but not for the expected reason. Output:"
        printf '%s\n' "$out" | sed 's/^/    /'
    fi
}

expect_clean() {
    # expect_clean <name> <tree-root> <dist-file> <subcommand...>
    local name="$1" tree="$2" dist="$3"
    shift 3
    local out
    out="$(run_guard "$tree" "$dist" "$@")"
    if [ "$?" -eq 0 ]; then
        ok "$name"
    else
        fail "$name: the guard went red where it must stay green. Output:"
        printf '%s\n' "$out" | sed 's/^/    /'
    fi
}

# ── the pristine tree is green in both directions ──────────────────
expect_clean "the working tree passes the normalised peer-set check" "$TREE" "$DIST_FIXTURE" check
expect_clean "the working tree is exactly what the deriver would produce" "$TREE" "$DIST_FIXTURE" derive --check

# ── allowed_ips drift (#523 re-introduced) is caught ───────────────
SB="$(sandbox flip-allowed)"
mutate flip_allowed "$SB/aa_cloud-superapp/build.json"
expect_caught "a v6-full allowed_ips regression to ::/0 is caught" \
    "v6-full peer oci-analytics ALLOWED_IPS differs" "$SB" "$DIST_FIXTURE" check

# ── endpoint drift is caught ───────────────────────────────────────
SB="$(sandbox flip-endpoint)"
mutate flip_endpoint "$SB/aa_cloud-superapp/build.json"
expect_caught "an endpoint change is caught" \
    "v4-split peer gcp-proxy ENDPOINT differs" "$SB" "$DIST_FIXTURE" check

# ── a peer set that stops matching is caught, in both directions ───
SB="$(sandbox drop-profile)"
mutate drop_profile "$SB/aa_cloud-superapp/build.json"
expect_caught "a profile deleted from build.json is caught" \
    "profile v4-split exists in the published dist but NOT in build.json" "$SB" "$DIST_FIXTURE" check

DIST_WITH_PEER="$WORK/dist-extra-peer.json"
cp "$DIST_FIXTURE" "$DIST_WITH_PEER"
mutate add_dist_peer "$DIST_WITH_PEER"
SB="$(sandbox dist-extra-peer)"
out="$(run_guard "$SB" "$DIST_WITH_PEER" check)"
if [ "$?" -eq 0 ]; then
    fail "a peer appearing in the published dist is caught"
    printf '%s\n' "$out" | sed 's/^/    /'
elif printf '%s' "$out" | grep -q "profile v4-split peer .* exists in the published dist but NOT in build.json"; then
    ok "a peer appearing in the published dist is caught"
else
    fail "a peer appearing in the published dist — guard failed for the wrong reason. Output:"
    printf '%s\n' "$out" | sed 's/^/    /'
fi

# ── the fetch fails closed: a missing or unusable reference is RED ──
for case in missing empty garbage no-wireguard; do
    SB="$(sandbox fetch-$case)"
    case "$case" in
        missing)      DIST="$WORK/does-not-exist.json" ;;
        empty)        DIST="$WORK/dist-empty.json";   : > "$DIST" ;;
        garbage)      DIST="$WORK/dist-garbage.json"; printf 'this is not json\n' > "$DIST" ;;
        no-wireguard) DIST="$WORK/dist-nokey.json";    printf '{"profile": {"x": 1}}\n' > "$DIST" ;;
    esac
    if out="$(run_guard "$SB" "$DIST" check)"; then
        fail "fetch failure ($case reference) was treated as a PASS"
    elif printf '%s' "$out" | grep -qE "FAIL: .*(could not read|EMPTY|not valid JSON|no 'wireguard' object)"; then
        ok "a $case reference fails closed"
    else
        fail "fetch failure ($case reference) failed for the wrong reason. Output:"
        printf '%s\n' "$out" | sed 's/^/    /'
    fi
done
SB="$(sandbox fetch-empty-wireguard)"
DIST="$WORK/dist-emptywg.json"
printf '{"wireguard": {}}\n' > "$DIST"
if out="$(run_guard "$SB" "$DIST" check)"; then
    fail "a dist declaring no profiles was treated as a PASS"
elif printf '%s' "$out" | grep -q "declares NO wireguard profiles"; then
    ok "a dist declaring no profiles fails closed"
else
    fail "a dist declaring no profiles failed for the wrong reason. Output:"
    printf '%s\n' "$out" | sed 's/^/    /'
fi

# ── normalisation: order and whitespace inside AllowedIPs are not drift ──
SB="$(sandbox shuffle-allowed)"
mutate shuffle_allowed "$SB/aa_cloud-superapp/build.json"
expect_clean "an allowed_ips reorder with extra whitespace is not drift" "$SB" "$DIST_FIXTURE" check
expect_caught "but a hand-shuffle IS drift for the derivation, which is order-canonical" \
    "allowed_ips" "$SB" "$DIST_FIXTURE" derive --check

# ── a redacted key placeholder is never a mismatch ─────────────────
DIST_REDACTED="$WORK/dist-redacted.json"
cp "$DIST_FIXTURE" "$DIST_REDACTED"
mutate add_redacted_key "$DIST_REDACTED"
SB="$(sandbox redacted-key)"
if out="$(run_guard "$SB" "$DIST_REDACTED" check)"; then
    ok "a <PROVIDED_BY_DEVICE> private key placeholder is not a mismatch"
else
    fail "a <PROVIDED_BY_DEVICE> private key placeholder caused a mismatch. Output:"
    printf '%s\n' "$out" | sed 's/^/    /'
fi

# ── the derivation is strictly stronger than the peer-set check ────
SB="$(sandbox dns-drift)"
mutate flip_dns "$SB/aa_cloud-superapp/build.json"
expect_clean "a dns drift is outside the peer-set check's scope" "$SB" "$DIST_FIXTURE" check
expect_caught "but the derivation pins dns too" \
    '"dns": "fd0c:1d01::1, 10.1.0.1"' "$SB" "$DIST_FIXTURE" derive --check

# ── the deriver heals: derive --write makes the drifted tree green ─
SB="$(sandbox heal)"
mutate flip_allowed "$SB/aa_cloud-superapp/build.json"
expect_caught "pre-heal: the drift is seen" \
    "v6-full peer oci-analytics ALLOWED_IPS differs" "$SB" "$DIST_FIXTURE" check
if out="$(run_guard "$SB" "$DIST_FIXTURE" derive --write)"; then
    :
else
    fail "derive --write could not rewrite the drifted tree. Output:"
    printf '%s\n' "$out" | sed 's/^/    /'
fi
expect_clean "post-heal: the rewritten tree passes the peer-set check" "$SB" "$DIST_FIXTURE" check
expect_clean "post-heal: the rewritten tree is exactly derived" "$SB" "$DIST_FIXTURE" derive --check

if [ "$FAILURES" -eq 0 ]; then
    echo "PASS — the guard fails on every way the APK's wireguard block can drift from the published dist, and fails closed when the reference is unreadable."
    exit 0
fi
echo "FAIL — see above."
exit 1