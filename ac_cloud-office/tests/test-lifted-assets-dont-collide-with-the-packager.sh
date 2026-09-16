#!/usr/bin/env bash
# NOTHING WE LIFT OUT OF COLLABORA'S APK MAY BE SOMETHING OUR OWN PACKAGER MAKES.
#
# Run 35042334724 built the entire COOL bundle, compiled everything, ran 136 of
# 136 Gradle tasks and then died in :app:packageRelease with
#
#   Zip file 'app-arm64-v8a-release-unsigned.apk' already contains entry
#   'assets/dexopt/baseline.prof', cannot overwrite
#
# assets/dexopt is AGP's BASELINE PROFILE — build output of Collabora's own
# Gradle run, which AGP injects at PACKAGING time out of the merged art-profile
# artifact rather than out of the asset source directory. That second road is
# what makes it different from copyDocTemplates and copyKitConfig, which also
# write into src/main/assets and merely overwrite what we lifted. Shipping it
# would be wrong even if it packaged: a baseline profile names classes and
# methods by the dex identity of the app that produced it.
#
# AND THE COUNT IS THE POINT. assets/dexopt holds TWO entries, baseline.prof and
# baseline.profm. Excluding only the one the build happened to name first would
# have bought exactly one more five-minute run. So this asserts the WHOLE
# directory against the live archive, never a file list somebody typed.
#
# Both halves are read from build.json and kept deliberately apart:
#   upstream.engine.assets.packager_generated_assets — the PROBLEM
#   upstream.engine.assets.exclude                   — the FIX
# Asserting one against the other is what stops this being circular, and T2
# stops the problem list rotting into fiction by requiring each prefix to still
# match something in the artefact we actually pin.
#
# Usage: ./test-lifted-assets-dont-collide-with-the-packager.sh   (needs network: one range read)
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
APP="$(cd "$HERE/.." && pwd)"
BJ="${CLOUD_OFFICE_BUILD_JSON:-$APP/build.json}"

for t in python3 jq; do
    command -v "$t" >/dev/null 2>&1 || { echo "ERROR: $t is not on PATH — refusing to report a verdict this run never computed" >&2; exit 2; }
done
[ -f "$BJ" ] || { echo "ERROR: $BJ missing" >&2; exit 2; }

python3 - "$BJ" <<'PY'
import json, struct, sys, urllib.request

GREEN, RED, OFF = "\033[0;32m", "\033[0;31m", "\033[0m"
passed = failed = 0
def ok(m):
    global passed; passed += 1; print(f"  {GREEN}ok{OFF}: {m}")
def bad(m):
    global failed; failed += 1; print(f"  {RED}FAIL{OFF}: {m}")
def die(m):
    sys.exit(f"ERROR: {m}")

eng = json.load(open(sys.argv[1], encoding="utf-8"))["upstream"]["engine"]
src, assets = eng["source"], eng["assets"]
url, size = src["url"], src["size"]
root = assets["from_apk_dir"].rstrip("/") + "/"
pfx = lambda xs: tuple(x.rstrip("/") + "/" for x in xs)
exclude = pfx(assets.get("exclude", []))
hazard  = pfx(assets.get("packager_generated_assets", []))

# FAIL CLOSED on an empty rule: zero prefixes would run every loop below zero
# times and report green for a check that examined nothing.
if not hazard:
    die("upstream.engine.assets.packager_generated_assets is empty — a hazard list "
        "that names nothing must not report green")
if not exclude:
    die("upstream.engine.assets.exclude is empty")

def rng(a, b):
    r = urllib.request.Request(url, headers={"Range": f"bytes={a}-{b}"})
    return urllib.request.urlopen(r, timeout=180).read()

print("== T1: the pinned APK's central directory was read IN FULL this run ==")
try:
    tail = rng(size - 700000, size - 1)
except Exception as e:
    die(f"cannot range-read {url}: {e} — UNKNOWN, not a verdict about the artefact")
i = tail.rfind(b"PK\x05\x06")
if i < 0:
    die("no end-of-central-directory record in the tail — refusing to guess")
nent = struct.unpack("<H", tail[i + 10:i + 12])[0]
cdoff = struct.unpack("<I", tail[i + 16:i + 20])[0]
cdsize = struct.unpack("<I", tail[i + 12:i + 16])[0]
j = tail.rfind(b"PK\x06\x06")
if j >= 0:
    nent = struct.unpack("<Q", tail[j + 32:j + 40])[0]
    cdsize = struct.unpack("<Q", tail[j + 40:j + 48])[0]
    cdoff = struct.unpack("<Q", tail[j + 48:j + 56])[0]
cd = tail[-(size - cdoff):] if size - cdoff <= len(tail) else rng(cdoff, cdoff + cdsize - 1)
names, p = [], 0
while p < len(cd) - 4 and cd[p:p + 4] == b"PK\x01\x02":
    n, m, k = struct.unpack("<HHH", cd[p + 28:p + 34])
    names.append(cd[p + 46:p + 46 + n].decode("utf-8", "replace"))
    p += 46 + n + m + k
if len(names) != nent:
    die(f"parsed {len(names)} entries, header says {nent} — refusing to reason about a half-read archive")
ok(f"{len(names)} entries parsed, header agrees")

print("== T2: every packager-generated path is REALLY in the artefact we pin ==")
for h in hazard:
    hit = [x for x in names if x.startswith(h)]
    if hit:
        ok(f"{h} is present in the pinned APK: {len(hit)} entr(y/ies) — {sorted(hit)}")
    else:
        bad(f"{h} matches nothing in the pinned APK — either upstream stopped shipping it "
            f"(drop it from packager_generated_assets) or the prefix is a typo; either way "
            f"this list has stopped describing the artefact")

print("== T3: and every one of them is excluded from what we lift ==")
for h in hazard:
    for x in sorted(x for x in names if x.startswith(h)):
        if x.startswith(exclude):
            ok(f"{x} is excluded")
        else:
            bad(f"{x} would be LIFTED into our APK and :app:packageRelease will refuse it "
                f"with 'already contains entry', after building everything")

print("== T4: no exclusion is stale — each one still matches the artefact ==")
for e in exclude:
    if not e.startswith(root):
        bad(f"exclude '{e}' is not under {root}, so it can never match anything")
        continue
    hit = sum(1 for x in names if x.startswith(e))
    if hit:
        ok(f"exclude '{e}' matches {hit} entr(y/ies)")
    else:
        bad(f"exclude '{e}' matches nothing — a rule that excludes nothing is either a typo "
            f"or a leftover, and both read as protection that is not there")

print(f"\npassed {passed}, failed {failed}")
sys.exit(1 if failed else 0)
PY
rc=$?
[ "$rc" -eq 2 ] && exit 2

# ── self-test: drop the exclusion and require a red ───────────────────
if [ -z "${CLOUD_OFFICE_COLLIDE_SELFTEST:-}" ]; then
    echo "== T5: a build.json that stops excluding the packager's own assets must FAIL =="
    TMP="$(mktemp -d "${TMPDIR:-/tmp}/cool-collide.XXXXXX")" || exit 2
    trap 'rm -rf "$TMP"' EXIT
    jq '.upstream.engine.assets.exclude |= map(select(. as $e
          | ($.upstream.engine.assets.packager_generated_assets // []) | index($e) | not))' \
       "$BJ" > "$TMP/build.json" 2>/dev/null \
      || jq --argjson h "$(jq -c '.upstream.engine.assets.packager_generated_assets' "$BJ")" \
            '.upstream.engine.assets.exclude |= map(select(IN($h[]) | not))' "$BJ" > "$TMP/build.json"
    if [ "$(jq -r '.upstream.engine.assets.exclude | length' "$TMP/build.json")" \
         -ge "$(jq -r '.upstream.engine.assets.exclude | length' "$BJ")" ]; then
        echo -e "  \033[0;31mFAIL\033[0m: self-test could not remove the exclusion, so it proved nothing"
        exit 1
    fi
    if CLOUD_OFFICE_COLLIDE_SELFTEST=1 CLOUD_OFFICE_BUILD_JSON="$TMP/build.json" \
       "$0" >/dev/null 2>&1; then
        echo -e "  \033[0;31mFAIL\033[0m: dropping the exclusion still PASSED — this tester asserts nothing"
        exit 1
    fi
    echo -e "  \033[0;32mok\033[0m: dropping the packager-generated exclusion turns this tester red"
fi

[ "$rc" -eq 0 ]
