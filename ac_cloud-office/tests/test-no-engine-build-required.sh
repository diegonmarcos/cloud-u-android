#!/usr/bin/env bash
#
# Cloud Office — does #224 need the ~$35 LibreOffice megabuild? (It does not.)
#
# ── THE QUESTION #241 DID NOT ASK ─────────────────────────────────────────
# On 2026-09-10 the reuse question was answered NOT FEASIBLE, on sound evidence:
# one monolithic 199 MB liblo-native-code.so, no separable component boundary,
# 119 Java classes required, twice GitHub's blob cap. Every one of those findings
# argues against REWRITING the app layer around a salvaged engine. None of them
# argues against LEAVING THE ENGINE ALONE.
#
# The owner's ask is a menu item in browser/src/control/Control.Menubar.ts and a
# binder client in android/lib/src/main/java/.../CloudTextEnhance.java. Both are
# above the engine. So the question that decides the money is not "can the engine
# be salvaged" but "can the two layers above it be rebuilt against the engine
# taken unmodified from the pinned APK". This test is that question, asked of the
# real upstream tree rather than of anybody's memory.
#
# ── WHAT IT ASSERTS, AND WHY EACH ONE MATTERS ─────────────────────────────
#   1. android/lib/build.gradle points jniLibs at a source directory. A .so there
#      is packaged, never compiled — so the engine reaches the APK through a
#      directory we can fill, and the megabuild's only job was to fill it.
#   2. CMakeLists.txt.in `cmake -E copy`s liblo-native-code.so. Upstream already
#      treats the engine as an external prebuilt binary; nothing in online's tree
#      compiles it.
#   3. patches/ touches NO native source — so libandroidapp.so, the shell's own
#      6 MB native half, does not need relinking either, and the eight static
#      archives no APK can carry stop being required.
#   4. browser/Makefile.am reads a built engine in exactly one place, the mocha
#      target. The bundle that carries the menu needs engine SOURCE only, which a
#      clone already has.
#   5. configure.ac STILL refuses --enable-androidapp without an engine build
#      tree. Asserted as present on purpose: that is the one real blocker left,
#      and the day upstream drops it we should hear about it from a red run.
#
# Assertions 1-4 passing and 5 passing together mean: no engine compile is
# needed, and the work left is a declared prebuilt-engine mode in patches/.
#
# ── FAIL CLOSED ───────────────────────────────────────────────────────────
# Every claim is a string that must be PRESENT in a file fetched over Gerrit's
# REST API at the pinned revision. Unreachable Gerrit, non-200, empty body,
# missing string, zero patches parsed — all FAIL. Comment lines are stripped
# before matching, because a tester in this repository has already passed by
# matching its own explanatory prose.
#
#   ./tests/test-no-engine-build-required.sh              # check every claim
#   ./tests/test-no-engine-build-required.sh --self-test  # also prove failures fail
#
set -uo pipefail

TEST_DIR="$(cd "$(dirname "$0")" && pwd)"
APP_DIR="$(cd "$TEST_DIR/.." && pwd)"
DATA="${ENGINE_ARTEFACTS_JSON:-$TEST_DIR/engine-artefacts.json}"
BUILD_JSON="${CLOUD_OFFICE_BUILD_JSON:-$APP_DIR/build.json}"

fail() { printf '\033[0;31mFAIL: %s\033[0m\n' "$1" >&2; exit 1; }
pass() { printf '\033[0;32mok: %s\033[0m\n' "$1"; }

command -v python3 >/dev/null 2>&1 || fail "python3 is not on PATH"
command -v curl    >/dev/null 2>&1 || fail "curl is not on PATH"
command -v base64  >/dev/null 2>&1 || fail "base64 is not on PATH"
[ -f "$DATA" ]       || fail "no separation table at $DATA"
[ -f "$BUILD_JSON" ] || fail "no build.json at $BUILD_JSON"

# One python3 program: the work is fetch + decode + line filtering, and doing it
# in shell would mean a pipeline whose exit status is read through the wrong
# process — a false green this repository has shipped before.
python3 - "$DATA" "$BUILD_JSON" "$APP_DIR" <<'PY'
import base64, json, os, re, subprocess, sys, urllib.parse

data_path, build_json_path, app_dir = sys.argv[1], sys.argv[2], sys.argv[3]

data = json.load(open(data_path))
sep  = data.get("separation")
if not sep:
    sys.exit("FAIL: engine-artefacts.json has no 'separation' block — nothing to check")

bj  = json.load(open(build_json_path))
up  = (bj.get("upstream") or {}).get("online") or {}
URL = up.get("url") or ""
PIN = up.get("revision") or ""
if not URL:
    sys.exit("FAIL: build.json::upstream.online.url is empty")
if len(PIN) != 40 or not re.fullmatch(r"[0-9a-f]{40}", PIN):
    sys.exit(f"FAIL: upstream.online.revision must be a full 40-char sha, got {PIN!r}")

BASE, PROJECT = URL.rsplit("/", 1)
CACHE = {}


def fetch(path):
    """File contents at the pinned revision, or exit. Gerrit answers base64."""
    if path in CACHE:
        return CACHE[path]
    enc = urllib.parse.quote(path, safe="")
    url = f"{BASE}/projects/{PROJECT}/commits/{PIN}/files/{enc}/content"
    p = subprocess.run(["curl", "-sS", "--max-time", "120", "-w", "\n%{http_code}", url],
                       capture_output=True)
    if p.returncode != 0:
        sys.exit(f"FAIL: cannot reach Gerrit for {path}: {p.stderr.decode().strip()}")
    body, _, code = p.stdout.rpartition(b"\n")
    if code.strip() != b"200":
        sys.exit(f"FAIL: HTTP {code.decode().strip()} for {path} at {PIN} — "
                 "the path is absent from the tree, or the pin is wrong")
    try:
        text = base64.b64decode(body).decode("utf-8", "replace")
    except Exception as e:
        sys.exit(f"FAIL: {path} did not decode as base64: {e}")
    if not text.strip():
        sys.exit(f"FAIL: {path} is empty at {PIN} — refusing to match against nothing")
    CACHE[path] = text
    return text


def code_lines(text, prefixes):
    """Lines with comment lines removed, so a claim cannot be satisfied by prose.

    Whole-line comments only: a trailing comment cannot manufacture a match for
    a string that is also real code on the same line, and stripping mid-line
    would corrupt the code we are trying to match.
    """
    out = []
    for line in text.splitlines():
        s = line.strip()
        if any(s.startswith(p) for p in prefixes):
            continue
        out.append(line)
    return out


bad = 0


def check(ok, msg):
    global bad
    if ok:
        print(f"  ok   {msg}")
    else:
        print(f"  FAIL {msg}", file=sys.stderr)
        bad += 1
    return ok


# ── 1..n: text that must be present in upstream at the pin ────────────────
claims = sep.get("upstream_claims") or []
if not claims:
    sys.exit("FAIL: separation.upstream_claims is empty — the table asserts nothing")
print(f"# upstream claims, against {PROJECT}@{PIN[:12]}")
for c in claims:
    text   = fetch(c["file"])
    lines  = code_lines(text, c.get("comment_prefixes") or [])
    needle = c["contains"]
    hit    = [i + 1 for i, l in enumerate(lines) if needle in l]
    check(bool(hit),
          f"{c['id']}: {c['file']} contains {needle!r}"
          + (f"  (code line {hit[0]})" if hit else "  — NOT FOUND outside comments"))

# ── the COOL bundle reads no BUILT engine ─────────────────────────────────
bb = sep.get("browser_bundle")
if not bb:
    sys.exit("FAIL: separation.browser_bundle is missing")
print("# the COOL bundle target and the built engine")
mk    = fetch(bb["file"])
var   = bb["engine_build_variable"]
lines = code_lines(mk, ["#"])
defs  = [l for l in lines if re.match(rf"\s*{re.escape(var)}\s*[:?]?=", l)]
check(len(defs) == 1 and bb["definition_contains"] in defs[0],
      f"{bb['file']}: {var} is defined once as {bb['definition_contains']!r}"
      f" (found {len(defs)} definition(s))")
uses = [l for l in lines if (f"$({var})" in l) and l not in defs]
want = bb["uses_outside_its_definition"]
check(len(uses) == want,
      f"{bb['file']}: {var} is used {len(uses)} time(s) outside its definition, table says {want}")
check(all(bb["sole_use_contains"] in u for u in uses) and len(uses) > 0,
      f"{bb['file']}: every use of {var} is the {bb['sole_use_contains']!r} target"
      " — the dist bundle never reads a built engine")

# ── the patch series touches nothing native ───────────────────────────────
ps = sep.get("patch_series_is_not_native")
if not ps:
    sys.exit("FAIL: separation.patch_series_is_not_native is missing")
print("# our patch series")
pdir = os.path.join(app_dir, ps["dir"])
patches = sorted(f for f in os.listdir(pdir) if f.endswith(".patch")) if os.path.isdir(pdir) else []
if len(patches) < ps["min_patches"]:
    sys.exit(f"FAIL: {len(patches)} patch(es) under {ps['dir']}/, table requires at least "
             f"{ps['min_patches']} — a series that parses to nothing is not a clean series")

touched = []
for fn in patches:
    with open(os.path.join(pdir, fn), encoding="utf-8", errors="replace") as fh:
        for line in fh:
            m = re.match(r"^diff --git a/(\S+) b/(\S+)", line)
            if m:
                touched.extend({m.group(1), m.group(2)})
touched = sorted(set(touched))
if len(touched) < ps["min_files_touched"]:
    sys.exit(f"FAIL: parsed {len(touched)} file(s) out of {len(patches)} patch(es) — "
             "the parser matched nothing, so 'no native files' would mean nothing")

sufs  = tuple(ps["native_suffixes"])
bases = set(ps["native_basenames"])
native = [t for t in touched if t.endswith(sufs) or os.path.basename(t) in bases]
check(not native,
      f"{len(patches)} patch(es), {len(touched)} file(s) touched, {len(native)} native"
      + (f": {native}" if native else " — neither .so has to be relinked"))

print()
if bad:
    sys.exit(f"FAIL: {bad} claim(s) do not hold against upstream at {PIN[:12]}")
print("# the engine and the shell's native half both ship prebuilt and neither is rebuilt here;")
print("# the only thing still standing between us and a build is configure's --with-lo-builddir gate.")
PY
rc=$?
[ $rc -eq 0 ] || exit $rc
pass "no engine build is required for the layers patches/ actually changes"

[ "${1:-}" = "--self-test" ] || exit 0

# ── prove the failure path fails ──────────────────────────────────────────
# Three ways, because the three assertions fail for three different reasons and
# a self-test that only breaks one of them has only proved one of them.
BROKEN_DIR="$(mktemp -d "${TMPDIR:-/tmp}/cloud-office-sep.XXXXXX")" || fail "cannot mktemp"
trap 'rm -rf "$BROKEN_DIR"' EXIT

echo
echo "── self-test 1: a claim whose text is not in the file ──"
python3 - "$DATA" "$BROKEN_DIR/claim.json" <<'PY'
import json, sys
d = json.load(open(sys.argv[1]))
d["separation"]["upstream_claims"][0]["contains"] = "jniLibs.srcDirs = ['no/such/dir']"
json.dump(d, open(sys.argv[2], "w"))
PY
[ $? -eq 0 ] || fail "self-test 1 could not build the broken table"
if ENGINE_ARTEFACTS_JSON="$BROKEN_DIR/claim.json" "$0" >/dev/null 2>&1; then
    fail "self-test: the checker PASSED a claim naming a jniLibs dir upstream does not set"
fi
pass "self-test: a false upstream claim fails, as it must"

echo "── self-test 2: a claim satisfied only by a comment line ──"
# browser/Makefile.am line 17 mentions ENGINE_UI_XCU_DIR inside a '#' comment.
# Claiming it as code must FAIL, or comment stripping is not happening.
python3 - "$DATA" "$BROKEN_DIR/comment.json" <<'PY'
import json, sys
d = json.load(open(sys.argv[1]))
d["separation"]["upstream_claims"] = [{
    "id": "comment-only", "file": "browser/Makefile.am", "comment_prefixes": ["#"],
    "contains": "they test for $(ENGINE_UI_XCU_DIR)",
}]
json.dump(d, open(sys.argv[2], "w"))
PY
[ $? -eq 0 ] || fail "self-test 2 could not build the broken table"
if ENGINE_ARTEFACTS_JSON="$BROKEN_DIR/comment.json" "$0" >/dev/null 2>&1; then
    fail "self-test: the checker PASSED a string that exists ONLY inside a comment"
fi
pass "self-test: a comment-only match fails, as it must"

echo "── self-test 3: a patch series that touches a .cpp ──"
python3 - "$DATA" "$BROKEN_DIR/native.json" <<'PY'
import json, sys
d = json.load(open(sys.argv[1]))
# .patch is not native in the real table; declaring it native makes our own
# series look native, which is the condition that must turn the run red.
d["separation"]["patch_series_is_not_native"]["native_suffixes"].append(".ts")
json.dump(d, open(sys.argv[2], "w"))
PY
[ $? -eq 0 ] || fail "self-test 3 could not build the broken table"
if ENGINE_ARTEFACTS_JSON="$BROKEN_DIR/native.json" "$0" >/dev/null 2>&1; then
    fail "self-test: the checker PASSED a series it was told contains native files"
fi
pass "self-test: a native file in the series fails, as it must"
