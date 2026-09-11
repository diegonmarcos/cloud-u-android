#!/usr/bin/env bash
#
# Cloud Office — is the engine build's output actually inside Collabora's APK?
#
# WHY THIS EXISTS. Two agents concluded Cloud Office needs its own ~12-hour
# LibreOffice engine build. The cheaper idea nobody had tested: the engine ships
# as one 199 MB liblo-native-code.so inside an APK this repository already
# downloaded and sha256-pinned, and upstream's CMake does not COMPILE that file,
# it `cmake -E copy`s it. So — extract instead of compile?
#
# The answer turns on one fact, and this test is that fact. An APK carries the
# RUNTIME half of what the engine hands the shell. The engine also hands it a
# LINK-TIME half: eight static archives and four header trees that configure and
# CMake read by absolute path out of $LOBUILDDIR. No APK has ever contained a .a
# file or a .h file. tests/engine-artefacts.json declares, per artefact, which
# half it is; this script checks every claim against the live archive.
#
# It reads the ZIP CENTRAL DIRECTORY over HTTP range requests — about 600 KB, not
# the 267 MB file. Network failure is a FAILURE, not a skip: the claim is about a
# remote object, and a test that shrugged at an unreachable remote would assert
# nothing. Same rule as tests/patches-apply.sh.
#
#   ./tests/engine-artefacts-in-apk.sh              # check every row
#   ./tests/engine-artefacts-in-apk.sh --self-test  # also prove the failure path fails
#
set -uo pipefail

TEST_DIR="$(cd "$(dirname "$0")" && pwd)"
DATA="${ENGINE_ARTEFACTS_JSON:-$TEST_DIR/engine-artefacts.json}"

fail() { printf '\033[0;31mFAIL: %s\033[0m\n' "$1" >&2; exit 1; }
pass() { printf '\033[0;32mok: %s\033[0m\n' "$1"; }

command -v python3 >/dev/null 2>&1 || fail "python3 is not on PATH"
command -v curl    >/dev/null 2>&1 || fail "curl is not on PATH"
[ -f "$DATA" ] || fail "no artefact table at $DATA"

# Everything below is one python3 program on purpose: parsing a ZIP central
# directory in shell would mean dd + od + arithmetic, which is exactly the kind
# of clever that ships green while asserting nothing.
python3 - "$DATA" <<'PY'
import json, struct, subprocess, sys

data = json.load(open(sys.argv[1]))
apk  = data["apk"]
URL  = apk["url"]

def rng(a, b):
    p = subprocess.run(["curl", "-sS", "--fail", "-H", f"Range: bytes={a}-{b}", URL],
                       capture_output=True)
    if p.returncode != 0:
        sys.exit(f"FAIL: range request {a}-{b} failed: {p.stderr.decode().strip()}")
    return p.stdout

head = subprocess.run(["curl", "-sSI", "--fail", URL], capture_output=True, text=True)
if head.returncode != 0:
    sys.exit(f"FAIL: cannot reach {URL}: {head.stderr.strip()}")
hdrs = {l.split(":", 1)[0].lower(): l.split(":", 1)[1].strip()
        for l in head.stdout.splitlines() if ":" in l}
size = int(hdrs["content-length"])
if size != apk["size"]:
    sys.exit(f"FAIL: APK size is {size}, table says {apk['size']} — upstream rotated the artefact")
if "bytes" not in hdrs.get("accept-ranges", ""):
    sys.exit("FAIL: server will not serve byte ranges; this test cannot run without downloading 267 MB")

tail = rng(max(0, size - 66000), size - 1)
i = tail.rfind(b"PK\x05\x06")
if i < 0:
    sys.exit("FAIL: no end-of-central-directory record; not a ZIP?")
nent, cdsize, cdoff = struct.unpack("<HII", tail[i + 10:i + 20])
j = tail.rfind(b"PK\x06\x06")
if j >= 0 and (cdoff == 0xFFFFFFFF or nent == 0xFFFF):
    nent, = struct.unpack("<Q", tail[j + 32:j + 40])
    cdsize, cdoff = struct.unpack("<QQ", tail[j + 40:j + 56])

cd = rng(cdoff, cdoff + cdsize - 1)
if len(cd) != cdsize:
    sys.exit(f"FAIL: short central directory: got {len(cd)} of {cdsize}")

names, p = set(), 0
while p < len(cd) and cd[p:p + 4] == b"PK\x01\x02":
    nlen, elen, clen = struct.unpack("<HHH", cd[p + 28:p + 34])
    names.add(cd[p + 46:p + 46 + nlen].decode("utf-8", "replace"))
    p += 46 + nlen + elen + clen
if len(names) != nent:
    sys.exit(f"FAIL: parsed {len(names)} entries, header says {nent}")
print(f"# central directory: {len(names)} entries, {cdsize:,} bytes fetched of {size:,}")

bad = shipped = withheld = 0
for a in data["artefacts"]:
    apk_path, want = a.get("apk_path"), a["in_apk"]
    # in_apk=false with apk_path=null means "an APK cannot carry this kind of
    # file at all" — there is no name to look up, and inventing one would make
    # the row assert nothing. Assert the shape instead: no .a and no .h anywhere.
    if apk_path is None:
        if want:
            print(f"BAD TABLE: {a['lobuilddir_path']} claims in_apk=true with no apk_path")
            bad += 1
        else:
            withheld += 1
        continue
    got = apk_path in names
    if got != want:
        print(f"MISMATCH: {apk_path}: in_apk={want} but archive says {got}"
              f"  (needed for: {a['needed_for']}; {a['required_by']})")
        bad += 1
    elif want:
        shipped += 1
    else:
        withheld += 1

# The load-bearing claim, checked against the whole archive rather than a list:
# link-time inputs are absent by category, not by accident.
for ext in (".a", ".h", ".hxx"):
    hit = [n for n in names if n.endswith(ext)]
    if hit:
        print(f"MISMATCH: archive contains {len(hit)} '{ext}' entries, e.g. {hit[:3]}"
              " — the link-time half may now be shippable; re-read the recommendation")
        bad += 1

print(f"# engine artefacts present in the APK : {shipped}")
print(f"# engine artefacts the APK cannot hold: {withheld}")
if bad:
    sys.exit(f"FAIL: {bad} row(s) disagree with the archive")
print("# every row matches the archive")
PY
rc=$?
[ $rc -eq 0 ] || exit $rc
pass "engine-artefacts.json matches the pinned APK"

[ "${1:-}" = "--self-test" ] || exit 0

# ── prove the failure path fails ───────────────────────────────────────
# An assertion nobody has watched fail is decoration. Flip one row's claim and
# the run must go red; if it stays green the checker is not checking.
echo
echo "── self-test: flipping in_apk on the setuprc row (it must now FAIL) ──"
BROKEN="${TMPDIR:-/tmp}/engine-artefacts-broken.$$.json"
trap 'rm -f "$BROKEN"' EXIT
python3 - "$DATA" "$BROKEN" <<'PY'
import json, sys
d = json.load(open(sys.argv[1]))
for a in d["artefacts"]:
    if a["lobuilddir_path"] == "instdir/program/setuprc":
        a["in_apk"] = True          # a lie: setuprc is not in the APK
        break
else:
    sys.exit("self-test cannot run: no setuprc row to flip")
json.dump(d, open(sys.argv[2], "w"))
PY
[ $? -eq 0 ] || fail "self-test could not build the broken table"

if ENGINE_ARTEFACTS_JSON="$BROKEN" "$0" >/dev/null 2>&1; then
    fail "self-test: the checker PASSED a table that claims setuprc ships in the APK"
fi
pass "self-test: the broken table fails, as it must"
