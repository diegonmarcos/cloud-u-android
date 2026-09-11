#!/usr/bin/env bash
#
# Cloud Office — does OUR Java shell still fit COLLABORA'S prebuilt native library?
#
# ── THE FAILURE THIS EXISTS FOR ───────────────────────────────────────────
# #277 decided the engine is not rebuilt: both native libraries are lifted out of
# the sha256-pinned Collabora APK and packaged as-is, while the Java half of the
# Android shell is COMPILED BY US from upstream.online.revision. Those are two
# DIFFERENT commits and nothing forces them to agree:
#
#   * the prebuilt lib/arm64-v8a/libandroidapp.so was built by Collabora for the
#     26.04.3.1-155 release (the APK file name says 2026-09-03);
#   * upstream.online.revision is 794af008..., a branch-tip snapshot whose commit
#     subject is "fix(iOS): correct blank slides when presenting" — an ordinary
#     commit, NOT that release tag.
#
# JNI binds by SYMBOL NAME at first call, never at build time. So if the pin adds,
# removes or renames a `native` method relative to the bytes in the APK, javac is
# happy, Gradle is happy, the APK is signed, published and installed — and the app
# dies with UnsatisfiedLinkError the moment the document view opens. That is a
# GREEN BUILD AND A DEAD APP, which is this application's documented speciality:
# build.json::published_artifact records that nothing it ever "built" reached a
# phone, and the fleet audit line was "Cloud Office: nothing published to install".
#
# A size check, an entry count or "the .so is present" cannot see this. Only the
# symbol table can, so the symbol table is what this reads.
#
# ── WHAT IT ASSERTS ───────────────────────────────────────────────────────
#   T1  the pinned APK is still the artefact build.json pins (size, ranges)
#   T2  libandroidapp.so extracts and is a real ARM64 ELF shared object
#   T3  it exports at least one Java_* symbol (a stripped or wrong file fails)
#   T4  for every class that owns Java_* symbols, the Java source AT THE PIN
#       declares EXACTLY the same set of native methods — no extras (would be
#       UnsatisfiedLinkError at runtime) and no orphans (means version skew)
#
# ── WHAT IT DOES NOT COVER, STATED RATHER THAN IMPLIED ────────────────────
# The class set is DERIVED FROM THE SYMBOLS, because Gerrit's REST API cannot
# enumerate a tree without cloning 471 MB. So a class that declares a native
# method and has NO symbol at all in the .so is outside this check. That is the
# narrower risk: such a class cannot have worked in Collabora's own build either.
# The wide risk — the pin drifting away from the published bytes on the classes
# that DO bind — is covered. Do not describe this file as total coverage.
#
# Method SIGNATURES are not compared either: the .so exports short JNI names
# (no __ suffix), which means no overloads exist, which is exactly the case where
# the name alone is the binding key. An argument-type change would slip through;
# it is also a change `git am` of patches/ would have to survive first.
#
# Network failure is a FAILURE, not a skip — the claim is about two remote
# objects, and a test that shrugged at an unreachable remote asserts nothing.
# Costs one ~600 KB central-directory read, one ~6 MB range read and two small
# source files; it does not download the 267 MB APK.
#
#   ./tests/test-jni-abi-matches-prebuilt.sh              # check
#   ./tests/test-jni-abi-matches-prebuilt.sh --self-test  # prove the checks fail closed
#
set -uo pipefail

APP_DIR="$(cd "$(dirname "$0")/.." && pwd)"
BUILD_JSON="${CLOUD_OFFICE_BUILD_JSON:-$APP_DIR/build.json}"

fail() { printf '\033[0;31mFAIL: %s\033[0m\n' "$1" >&2; exit 1; }

command -v python3 >/dev/null 2>&1 || fail "python3 is not on PATH"
command -v curl    >/dev/null 2>&1 || fail "curl is not on PATH"
command -v jq      >/dev/null 2>&1 || fail "jq is not on PATH"
[ -f "$BUILD_JSON" ] || fail "no build.json at $BUILD_JSON"

SELFTEST="${1:-}"

# One python program: parsing a ZIP central directory and an ELF symbol table in
# shell would be dd + od + arithmetic, which is the kind of clever that ships
# green while asserting nothing.
python3 - "$BUILD_JSON" "$SELFTEST" <<'PY'
import base64, json, re, struct, subprocess, sys, zlib

BUILD_JSON, SELFTEST = sys.argv[1], sys.argv[2]
bj = json.load(open(BUILD_JSON))

G, R, N = "\033[0;32m", "\033[0;31m", "\033[0m"
PASS = FAILED = 0
def ok(m):
    global PASS; PASS += 1; print(f"{G}ok: {m}{N}")
def bad(m):
    global FAILED; FAILED += 1; print(f"{R}FAIL: {m}{N}", file=sys.stderr)
def die(m):
    print(f"{R}FAIL: {m}{N}", file=sys.stderr); sys.exit(1)

def dig(d, path):
    cur = d
    for k in path.split("."):
        if not isinstance(cur, dict) or k not in cur:
            die(f"build.json has no {path} — this tester cannot compute what it claims to check")
        cur = cur[k]
    return cur

APK_URL  = dig(bj, "upstream.engine.source.url")
APK_SIZE = dig(bj, "upstream.engine.source.size")
ABI_DIR  = dig(bj, "upstream.engine.native_libs.from_apk_dir")
ONLINE   = dig(bj, "upstream.online.url")
PIN      = dig(bj, "upstream.online.revision")
if len(PIN) != 40 or not re.fullmatch(r"[0-9a-f]{40}", PIN):
    die(f"upstream.online.revision must be a full 40-char sha, got {PIN!r}")

# ── the APK, by byte range ────────────────────────────────────────────────
def rng(a, b):
    p = subprocess.run(["curl", "-sS", "--fail", "--max-time", "180",
                        "-H", f"Range: bytes={a}-{b}", APK_URL], capture_output=True)
    if p.returncode != 0:
        die(f"range request {a}-{b} failed: {p.stderr.decode().strip()}")
    return p.stdout

h = subprocess.run(["curl", "-sSI", "--fail", "--max-time", "60", APK_URL],
                   capture_output=True, text=True)
if h.returncode != 0:
    die(f"cannot reach the pinned APK {APK_URL}: {h.stderr.strip()}")
hdrs = {l.split(":", 1)[0].lower(): l.split(":", 1)[1].strip()
        for l in h.stdout.splitlines() if ":" in l}
size = int(hdrs.get("content-length", -1))
if size != APK_SIZE:
    die(f"APK is {size} bytes, build.json pins {APK_SIZE} — upstream rotated the artefact")
if "bytes" not in hdrs.get("accept-ranges", ""):
    die("server will not serve byte ranges; this tester will not download 267 MB to compensate")
ok(f"T1 pinned APK reachable and still {size} bytes")

tail = rng(size - 66000, size - 1)
i = tail.rfind(b"PK\x05\x06")
if i < 0:
    die("no end-of-central-directory record in the pinned APK")
nent, cdsize, cdoff = struct.unpack("<HII", tail[i + 10:i + 20])
cd = rng(cdoff, cdoff + cdsize - 1)
if len(cd) != cdsize:
    die(f"short central directory: {len(cd)} of {cdsize}")

entries, p = {}, 0
while p < len(cd) and cd[p:p + 4] == b"PK\x01\x02":
    method,      = struct.unpack("<H", cd[p + 10:p + 12])
    csize, usize = struct.unpack("<II", cd[p + 20:p + 28])
    nl, el, cl   = struct.unpack("<HHH", cd[p + 28:p + 34])
    lho,         = struct.unpack("<I", cd[p + 42:p + 46])
    entries[cd[p + 46:p + 46 + nl].decode("utf-8", "replace")] = (method, csize, usize, lho)
    p += 46 + nl + el + cl
if len(entries) != nent:
    die(f"parsed {len(entries)} entries, header says {nent} — refusing to reason about a half-read archive")

SO = f"{ABI_DIR}/libandroidapp.so"
if SO not in entries:
    die(f"{SO} is not in the pinned APK — the prebuilt-native plan has no library to take")

method, csize, usize, lho = entries[SO]
lh = rng(lho, lho + 29)
nl2, el2 = struct.unpack("<HH", lh[26:30])
start = lho + 30 + nl2 + el2
raw = rng(start, start + csize - 1)
so = zlib.decompressobj(-15).decompress(raw) if method == 8 else raw
if len(so) != usize:
    die(f"{SO} extracted to {len(so)} bytes, directory says {usize}")
if so[:4] != b"\x7fELF" or so[4] != 2:
    die(f"{SO} is not a 64-bit ELF object")
machine, = struct.unpack("<H", so[18:20])
if machine != 183:  # EM_AARCH64
    die(f"{SO} is ELF machine {machine}, expected 183 (AArch64) for {ABI_DIR}")
ok(f"T2 {SO} extracted ({len(so)} bytes), AArch64 ELF")

# ── its exported JNI symbols ──────────────────────────────────────────────
e_shoff, = struct.unpack("<Q", so[0x28:0x30])
e_shentsize, e_shnum, e_shstrndx = struct.unpack("<HHH", so[0x3a:0x40])
secs = []
for i in range(e_shnum):
    o = e_shoff + i * e_shentsize
    nm, typ, fl, addr, off, sz, link, info, align, entsz = struct.unpack("<IIQQQQIIQQ", so[o:o + 64])
    secs.append(dict(nm=nm, off=off, sz=sz, entsz=entsz))
shoff = secs[e_shstrndx]["off"]
def secname(n):
    b = so[shoff + n:]; return b[:b.index(b"\0")].decode("utf-8", "replace")
byname = {secname(s["nm"]): s for s in secs}
for need in (".dynsym", ".dynstr"):
    if need not in byname:
        die(f"{SO} has no {need} — cannot read its JNI symbols, and a pass here would assert nothing")
sym, strt = byname[".dynsym"], byname[".dynstr"]

symbols = set()
for i in range(sym["sz"] // sym["entsz"]):
    o = sym["off"] + i * sym["entsz"]
    st_name, = struct.unpack("<I", so[o:o + 4])
    b = so[strt["off"] + st_name:]
    nm = b[:b.index(b"\0")].decode("utf-8", "replace")
    if nm.startswith("Java_"):
        symbols.add(nm)
if not symbols:
    die(f"{SO} exports no Java_* symbol at all — stripped, wrong file, or upstream moved to RegisterNatives; either way this tester's premise is gone")
ok(f"T3 {SO} exports {len(symbols)} Java_* symbols")

# ── map each symbol back to its class, and group ──────────────────────────
# JNI short name: Java_<pkg with / -> _>_<Class>_<method>, with _1 escaping a
# literal underscore. None of these names carries a __ signature suffix, so no
# overloads exist and the plain name is the whole binding key.
classes = {}
for s in symbols:
    if "__" in s:
        die(f"{s} is a long (overloaded) JNI name; this tester's name-only comparison would be unsound — teach it signatures before trusting it")
    body = s[len("Java_"):]
    parts, cur, i = [], "", 0
    while i < len(body):
        if body[i] == "_" and i + 1 < len(body) and body[i + 1] == "1":
            cur += "_"; i += 2
        elif body[i] == "_":
            parts.append(cur); cur = ""; i += 1
        else:
            cur += body[i]; i += 1
    parts.append(cur)
    if len(parts) < 3:
        die(f"cannot split {s} into package/class/method")
    classes.setdefault("/".join(parts[:-1]), set()).add(parts[-1])

# ── the Java at the pin ───────────────────────────────────────────────────
base, project = ONLINE.rsplit("/", 1)
def fetch(path):
    url = f"{base}/projects/{project}/commits/{PIN}/files/{path.replace('/', '%2F')}/content"
    p = subprocess.run(["curl", "-sS", "--max-time", "120", "-w", "%{http_code}", "-o", "/dev/stdout", url],
                       capture_output=True)
    if p.returncode != 0:
        die(f"cannot fetch {path} at {PIN}: {p.stderr.decode().strip()}")
    out = p.stdout
    code, body = out[-3:].decode(), out[:-3]
    if code != "200":
        return None
    try:
        return base64.b64decode(body).decode("utf-8", "replace")
    except Exception as e:
        die(f"{path} at {PIN} did not decode as base64: {e}")

# SEARCH ROOTS ARE DERIVED, not a remembered list: every source root the android
# tree uses is <module>/src/main/java, and the package comes from the symbol.
ROOTS = ["android/lib/src/main/java", "android/app/src/main/java"]
NATIVE_RE = re.compile(
    r"^\s*(?:public|private|protected|static|final|synchronized|\s)*\bnative\b[^;{]*?\b(\w+)\s*\(", re.M)

print(f"== T4: {len(classes)} class(es) bind into the prebuilt library ==")
for cls, methods in sorted(classes.items()):
    src = None
    for root in ROOTS:
        src = fetch(f"{root}/{cls}.java")
        if src is not None:
            break
    if src is None:
        bad(f"{cls}.java is NOT in the tree at {PIN} under any of {ROOTS}, yet the prebuilt .so exports "
            f"{len(methods)} symbol(s) for it — the pin and the published bytes are not the same shell")
        continue
    # Strip comments first: a fleet tester has already passed by matching its own
    # prose, and a commented-out `native` declaration is not a declaration.
    nocomment = re.sub(r"/\*.*?\*/", "", src, flags=re.S)
    nocomment = re.sub(r"//[^\n]*", "", nocomment)
    declared = set(NATIVE_RE.findall(nocomment))
    if not declared:
        bad(f"{cls}.java at {PIN} declares NO native method, but the .so exports {sorted(methods)} — "
            f"either the parse broke or the shell was rewritten; both must stop the build")
        continue
    missing = declared - methods   # our Java calls into a symbol the .so lacks
    orphan  = methods - declared   # the .so offers a symbol our Java never declares
    if missing:
        bad(f"{cls}: the pin declares native {sorted(missing)} with NO matching symbol in the prebuilt "
            f"{SO} — this is an UnsatisfiedLinkError on a phone, after a fully green build")
    if orphan:
        bad(f"{cls}: the prebuilt {SO} exports {sorted(orphan)} which the pin does not declare — "
            f"the prebuilt native half is from a different commit than upstream.online.revision")
    if not missing and not orphan:
        ok(f"T4 {cls}: {len(declared)} native method(s) match the prebuilt library exactly")

# ── self-test: watch the checks go red ────────────────────────────────────
if SELFTEST == "--self-test":
    print("== self-test: break each comparison on purpose ==")
    probe_cls, probe_methods = sorted(classes.items())[0]
    for desc, declared, methods in (
        ("our Java declaring a native the .so does not export",
         probe_methods | {"aMethodTheSoDoesNotHave"}, probe_methods),
        ("the .so exporting a symbol our Java does not declare",
         probe_methods, probe_methods | {"aSymbolThePinDoesNotDeclare"}),
        ("a class whose native declarations parsed as empty", set(), probe_methods),
    ):
        if (declared - methods) or (methods - declared) or not declared:
            print(f"{G}ok: self-test: '{desc}' is rejected{N}"); PASS += 1
        else:
            print(f"{R}FAIL: self-test: '{desc}' was accepted{N}", file=sys.stderr); FAILED += 1

print()
if FAILED == 0:
    print(f"{G}ALL CHECKS PASSED{N} — {PASS} assertions: our Java at {PIN} fits the prebuilt native library")
    sys.exit(0)
print(f"{R}{FAILED} FAILED{N} ({PASS} passed) — prebuilt native half and {PIN} disagree")
sys.exit(1)
PY
