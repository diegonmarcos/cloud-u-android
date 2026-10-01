#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════════════╗
# ║ #646 / #705 — the two pinned git binaries, checked where they now ship    ║
# ╚══════════════════════════════════════════════════════════════════════════╝
#
# libs:gh and libs:gix each land a sha256-pinned static binary as jniLibs/<abi>/
# lib<name>.so. Until #705 Cloud Drive compiled both, so these checks lived in
# its test-drive-git-auth-chain.sh. Since #705 no app compiles either: gh runs in
# Cloud-Lib-Gh.apk behind an engine service, and gix ships only as Cloud-Lib-Gix.apk.
# Cloud Drive's ship no longer watches libs/gh or libs/gix, so a change there runs
# THIS pipeline (Cloud Libs) and no other — the checks moved here with the code.
#
#   C5  each payload is REFUSED at build time on a wrong archive hash, a wrong
#       binary hash, a wrong byte count, or a PT_INTERP; the pin declares the
#       property per ABI; and the entry is read by Gradle's tar reader, never by
#       hand (#646's own red).
#   C6  gix can NEVER be asked to push: the pin declares clone and fetch only, and
#       GixRunner refuses anything else off that pin, holding no literal of its own.
#   C7  gh's token rides the environment, never argv; `gh auth token` is never
#       invoked; nothing logs it.
#   MUT each property, broken on a copy (and proven broken), goes red.
#
# OWN-SOURCE ONLY: reads ab_cloud-libs-shared/libs, nothing an app owns.
# python3 and grep only, no network, no build.
set -uo pipefail

ROOT="${CLOUD_ANDROID_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && while [ "$PWD" != "/" ] && [ ! -e "$PWD/.git" ]; do cd ..; done; printf '%s' "$PWD")}"
LIBS="$ROOT/ab_cloud-libs-shared/libs"
GH_PIN="$LIBS/gh/data/gh-binary.json"
GH_GRADLE="$LIBS/gh/build.gradle"
GH_RUNNER="$LIBS/gh/src/main/java/com/diegonmarcos/cloudlib/gh/GhRunner.kt"
GIX_PIN="$LIBS/gix/data/gix-binary.json"
GIX_GRADLE="$LIBS/gix/build.gradle"
GIX_RUNNER="$LIBS/gix/src/main/java/com/diegonmarcos/cloudlib/gix/GixRunner.kt"
for required in "$GH_PIN" "$GH_GRADLE" "$GH_RUNNER" "$GIX_PIN" "$GIX_GRADLE" "$GIX_RUNNER"; do
    [ -f "$required" ] || { echo "ERROR missing source: $required — this tester is unrun, not passing"; exit 1; }
done

FAILURES=0
pass() { echo "  PASS  $*"; }
fail() { echo "  FAIL  $*"; FAILURES=$((FAILURES + 1)); }

# A file's CODE, with its comment lines stripped: both build.gradle files DOCUMENT
# the bug they used to have, and a naive grep would call the explanation the defect.
_code() { grep -vE '^[[:space:]]*(\*|//|/\*)' "$1"; }

# c5_one <name> <pin.json> <build.gradle> : the payload is refused at BUILD time
c5_one() {
    local name="$1" pin="$2" gradle="$3" bad=0
    # THE ARCHIVE, before anything is extracted from it.
    grep -qE 'fetchPinned\(url, tar, pin\.tar_sha256\)' "$gradle" \
        || { echo "    $name: the tarball's own sha256 is not checked"; bad=1; }
    grep -qE 'if \(actual != sha256\)' "$gradle" \
        || { echo "    $name: fetchPinned does not refuse a wrong archive hash"; bad=1; }
    # THE EXTRACTED BINARY: hash, exact byte count, and no dynamic loader. All
    # three, per module — a guard present in one and absent in the other is how
    # exactly one unverified binary ships.
    grep -qE 'if \(actual != pin\.binary_sha256\)' "$gradle" \
        || { echo "    $name: the extracted binary's sha256 is not checked"; bad=1; }
    grep -qE 'if \(staged\.length\(\) != \(long\) pin\.binary_bytes\)' "$gradle" \
        || { echo "    $name: the extracted binary's byte count is not checked"; bad=1; }
    grep -qE "pinJson\.interp == 'none' && hasProgramInterpreter\(staged\)" "$gradle" \
        || { echo "    $name: a PT_INTERP-bearing payload is not refused"; bad=1; }
    grep -qE 'if \(type == 3\) return true' "$gradle" \
        || { echo "    $name: the program-header walk does not look for PT_INTERP (type 3)"; bad=1; }
    # Every refusal DELETES the staged file. One left on disk is one the next
    # build's up-to-date check hands straight to the packager.
    local deletes
    deletes="$(_code "$gradle" | grep -cE 'staged\.delete\(\)')"
    [ "${deletes:-0}" -ge 3 ] \
        || { echo "    $name: only ${deletes:-0} refusal(s) delete the staged binary; all three must"; bad=1; }
    # THE PIN states the property those guards enforce, per ABI, or they enforce
    # nothing: `interp` is what turns the program-header walk into a refusal.
    python3 - "$pin" "$name" <<'PYTHON' || bad=1
import json, sys
p = json.load(open(sys.argv[1], encoding="utf-8")); name = sys.argv[2]; bad = 0
if p.get("interp") != "none":
    print("    %s: the pin does not declare interp=none, so the PT_INTERP guard is inert" % name); bad += 1
if not p.get("binaries"):
    print("    %s: no ABI is pinned at all" % name); bad += 1
for abi, b in sorted((p.get("binaries") or {}).items()):
    for field in ("tar_sha256", "binary_sha256"):
        v = b.get(field) or ""
        if len(v) != 64 or v.strip("0123456789abcdef"):
            print("    %s/%s: %s is not a sha256: %r" % (name, abi, field, v)); bad += 1
    n = b.get("binary_bytes")
    if not isinstance(n, int) or n <= 0:
        print("    %s/%s: binary_bytes is not a positive integer: %r" % (name, abi, n)); bad += 1
sys.exit(1 if bad else 0)
PYTHON
    return $bad
}

# c5b <build.gradle...> : the entry is read by GRADLE, never by hand
#
# THIS IS #646's OWN REGRESSION GUARD, and the reason this ticket needed a second
# landing. The first attempt hand-walked 512-byte ustar headers and advanced past
# each entry's data with ((size + 511L) / 512L) * 512L — correct C, and wrong
# Groovy, where `/` on two Longs is BigDecimal division and the whole expression
# is just size + 511. The walk left the header grid on the FIRST entry of the real
# gh tarball and parsed man-page bytes as an octal size field, which is the
# "ST/]OWNER/RE" under radix 8 that turned main red. No offset arithmetic, and no
# part of a hand-rolled tar reader, may come back into either module.
c5b() {
    local bad=0 g name
    for g in "$@"; do
        name="$(basename "$g")"
        grep -qE 'archiveOperations\.tarTree\(archiveOperations\.gzip\(tarGz\)\)' "$g" \
            || { echo "    $name: the entry is not read through Gradle's own tar reader with gzip STATED"; bad=1; }
        grep -qE '@javax\.inject\.Inject abstract ArchiveOperations getArchiveOperations\(\)' "$g" \
            || { echo "    $name: ArchiveOperations is not an injected service — the configuration cache would refuse it"; bad=1; }
        # An absent or ambiguous entry still REFUSES rather than staging a short
        # file: an empty jniLibs payload installs an app whose binary is not there.
        grep -qE 'if \(matched\.size\(\) != 1\)' "$g" \
            || { echo "    $name: a missing or ambiguous entry is not refused"; bad=1; }
        local handrolled
        handrolled="$(_code "$g" | grep -nE 'parseLong\(sizeField|/ 512L|511L|GZIPInputStream|header\[156\]|cString\(header')"
        [ -z "$handrolled" ] || {
            echo "    $name: ustar is being parsed by hand again — that is exactly #646's red:"
            printf '%s\n' "$handrolled" | sed 's/^/        /'; bad=1; }
    done
    return $bad
}

# c6 <gix pin> <GixRunner.kt> : gix can never be asked to push
c6() {
    local pin="$1" runner="$2" bad=0
    # THE DECLARATION forbids it. Measured: gitoxide 0.59.0 answers `push` with
    # "error: unrecognized subcommand", so a build that declared the verb would
    # spawn a process that can only ever fail.
    python3 - "$pin" <<'PYTHON' || bad=1
import json, sys
verbs = json.load(open(sys.argv[1], encoding="utf-8")).get("verbs")
if not isinstance(verbs, list) or not verbs:
    print("    gix declares no verbs, so nothing constrains what it may be asked to run"); sys.exit(1)
if "push" in verbs:
    print("    gix declares a push verb; gitoxide has no push subcommand"); sys.exit(1)
if sorted(verbs) != ["clone", "fetch"]:
    print("    gix declares %r; clone and fetch are the measured pair" % (verbs,)); sys.exit(1)
PYTHON
    # THE RUNNER refuses an undeclared verb BEFORE spawning anything, and reads
    # the list off the baked pin instead of holding a literal of its own.
    grep -qE 'require\(verb in VERBS\)' "$runner" \
        || { echo "    GixRunner does not refuse an undeclared verb before spawning"; bad=1; }
    grep -qE 'val VERBS: List<String> = BuildConfig\.GIX_VERBS' "$runner" \
        || { echo "    GixRunner's verb list is not the baked pin"; bad=1; }
    local literal
    literal="$(_code "$runner" | grep -nE 'listOf\("clone"|listOf\("fetch"|"push"')"
    [ -z "$literal" ] || {
        echo "    GixRunner holds a literal verb list, or names push:"
        printf '%s\n' "$literal" | sed 's/^/        /'; bad=1; }
    return $bad
}

# c7 <GhRunner.kt> : gh's token never reaches argv, stdout or a log
c7() {
    local runner="$1" bad=0
    # The token travels in the ENVIRONMENT, never argv — argv is world-readable
    # through /proc/<pid>/cmdline and every process listing.
    grep -qE 'put\("GH_TOKEN", token\)' "$runner" \
        || { echo "    the token is not passed to gh in the environment"; bad=1; }
    # `gh auth token` PRINTS the credential to stdout; it must never be INVOKED.
    if _code "$runner" | grep -nE '"auth" *, *"token"'; then
        echo "    gh auth token is invoked, which prints the credential to stdout"; bad=1
    fi
    local leak
    leak="$(grep -nE 'Log\.[a-z]+\(.*(token|secret)|println\(.*token|putString\(.*token' "$runner" || true)"
    [ -z "$leak" ] || { echo "    a token leaves memory:"; printf '%s\n' "$leak" | sed 's/^/        /'; bad=1; }
    return $bad
}

echo "── C5 the pinned payload is REFUSED at build time ──"
c5_one gh "$GH_PIN" "$GH_GRADLE" && pass "gh: archive hash, binary hash, exact byte count and PT_INTERP all refuse, and the pin declares interp=none" \
    || fail "an unverified or dynamically linked gh could reach jniLibs"
c5_one gix "$GIX_PIN" "$GIX_GRADLE" && pass "gix: the same four refusals, on the same declared property" \
    || fail "an unverified or dynamically linked gix could reach jniLibs"
c5b "$GH_GRADLE" "$GIX_GRADLE" && pass "both modules read their entry through Gradle's tar reader; no hand-rolled ustar, no offset arithmetic" \
    || fail "a hand-rolled tar walk is back — #646's red"

echo "── C6 gix can never be asked to push ──"
c6 "$GIX_PIN" "$GIX_RUNNER" && pass "the pin declares clone and fetch only, and the runner refuses the rest off that pin" \
    || fail "push could reach gitoxide, which has no push"

echo "── C7 gh's token stays out of argv, stdout and logs ──"
c7 "$GH_RUNNER" && pass "the token rides the environment and gh auth token is never run" \
    || fail "gh's token can reach a process listing, stdout or a log"

# ══ MUT every check above goes RED when its property is broken ══════════════
# Each mutation runs the SAME function on a COPY, verified GREEN first; an edit
# that does not apply exits loudly instead of reading as a hollow guard.
MUT="$(mktemp -d)"
trap 'rm -rf "$MUT"' EXIT
MUTATIONS=0; HOLLOW=0
W="$MUT/w"
_stage() {
    rm -rf "$W"; mkdir -p "$W"
    cp "$GH_PIN" "$W/gh.json";         cp "$GH_GRADLE" "$W/gh.gradle"
    cp "$GIX_PIN" "$W/gix.json";       cp "$GIX_GRADLE" "$W/gix.gradle"
    cp "$GIX_RUNNER" "$W/GixRunner.kt"; cp "$GH_RUNNER" "$W/GhRunner.kt"
}
_sub() {
    python3 - "$1" "$2" "$3" <<'PYTHON' || HOLLOW=$((HOLLOW + 1))
import sys
p, old, new = sys.argv[1:4]
src = open(p, encoding="utf-8").read()
if old not in src:
    sys.stderr.write("MUTATION DID NOT APPLY: %r absent from %s\n" % (old, p)); sys.exit(2)
open(p, "w", encoding="utf-8").write(src.replace(old, new, 1))
PYTHON
}
_json() {
    python3 - "$1" "$2" <<'PYTHON' || HOLLOW=$((HOLLOW + 1))
import json, sys
d = json.load(open(sys.argv[1], encoding="utf-8"))
before = json.dumps(d, sort_keys=True)
exec(sys.argv[2])
if json.dumps(d, sort_keys=True) == before:
    sys.stderr.write("MUTATION DID NOT APPLY: %s left %s unchanged\n" % (sys.argv[2], sys.argv[1])); sys.exit(2)
json.dump(d, open(sys.argv[1], "w", encoding="utf-8"), indent=2)
PYTHON
}
_green() {
    local label="$1"; shift
    "$@" >/dev/null 2>&1 && return 0
    echo "  MUT-VOID    $label — the UNMUTATED copy already fails, so any red below is meaningless"
    HOLLOW=$((HOLLOW + 1)); return 1
}
_red() {
    local label="$1"; shift
    MUTATIONS=$((MUTATIONS + 1))
    if "$@" >/dev/null 2>&1; then
        echo "  MUT-HOLLOW  $label — mutated and STILL PASSES: that check proves nothing"
        HOLLOW=$((HOLLOW + 1))
    else
        echo "  MUT-RED     $label"
    fi
}

echo "── MUT each property, broken on a copy, must turn its own check red ──"
_stage && _green "c5 gh" c5_one gh "$W/gh.json" "$W/gh.gradle" && {
    _sub "$W/gh.gradle" "pinJson.interp == 'none' && hasProgramInterpreter(staged)" 'false'
    _red "C5 gh: a PT_INTERP-bearing payload is no longer refused" c5_one gh "$W/gh.json" "$W/gh.gradle"; }
_stage && _green "c5 gh" c5_one gh "$W/gh.json" "$W/gh.gradle" && {
    _sub "$W/gh.gradle" 'if (staged.length() != (long) pin.binary_bytes)' 'if (false)'
    _red "C5 gh: the exact byte count is no longer enforced" c5_one gh "$W/gh.json" "$W/gh.gradle"; }
_stage && _green "c5 gh" c5_one gh "$W/gh.json" "$W/gh.gradle" && {
    _sub "$W/gh.gradle" 'if (actual != pin.binary_sha256)' 'if (false)'
    _red "C5 gh: the extracted binary's sha256 is no longer checked" c5_one gh "$W/gh.json" "$W/gh.gradle"; }
_stage && _green "c5 gh" c5_one gh "$W/gh.json" "$W/gh.gradle" && {
    _json "$W/gh.json" 'd["interp"] = "glibc"'
    _red "C5 gh: the pin stops declaring interp=none, making the guard inert" c5_one gh "$W/gh.json" "$W/gh.gradle"; }
_stage && _green "c5 gix" c5_one gix "$W/gix.json" "$W/gix.gradle" && {
    _json "$W/gix.json" 'd["binaries"]["arm64-v8a"]["binary_bytes"] = 0'
    _red "C5 gix: an unpinned byte count" c5_one gix "$W/gix.json" "$W/gix.gradle"; }
_stage && _green "c5b" c5b "$W/gh.gradle" "$W/gix.gradle" && {
    _sub "$W/gh.gradle" 'archiveOperations.tarTree(archiveOperations.gzip(tarGz))' \
                        'archiveOperations.tarTree(tarGz)'
    _red "C5 the gzip compression is guessed from the file name instead of stated" c5b "$W/gh.gradle" "$W/gix.gradle"; }
_stage && _green "c5b" c5b "$W/gh.gradle" "$W/gix.gradle" && {
    _sub "$W/gh.gradle" '        target.parentFile.mkdirs(); target.delete()' \
                        '        long size = Long.parseLong(sizeField, 8)
        long skip = ((size + 511L) / 512L) * 512L
        target.parentFile.mkdirs(); target.delete()'
    _red "C5 #646's OWN RED: a hand-rolled ustar walk back in libs:gh" c5b "$W/gh.gradle" "$W/gix.gradle"; }
_stage && _green "c5b" c5b "$W/gh.gradle" "$W/gix.gradle" && {
    _sub "$W/gix.gradle" 'if (matched.size() != 1)' 'if (false)'
    _red "C5 gix: an absent entry stages a short file instead of refusing" c5b "$W/gh.gradle" "$W/gix.gradle"; }
_stage && _green "c6" c6 "$W/gix.json" "$W/GixRunner.kt" && {
    _json "$W/gix.json" 'd["verbs"].append("push")'
    _red "C6 push declared as a gix verb" c6 "$W/gix.json" "$W/GixRunner.kt"; }
_stage && _green "c6" c6 "$W/gix.json" "$W/GixRunner.kt" && {
    _sub "$W/GixRunner.kt" 'require(verb in VERBS)' 'require(verb.isNotBlank())'
    _red "C6 the runner stops refusing an undeclared verb" c6 "$W/gix.json" "$W/GixRunner.kt"; }
_stage && _green "c6" c6 "$W/gix.json" "$W/GixRunner.kt" && {
    _sub "$W/GixRunner.kt" 'val VERBS: List<String> = BuildConfig.GIX_VERBS' \
                           'val VERBS: List<String> = listOf("clone", "fetch", "push") + BuildConfig.GIX_VERBS'
    _red "C6 the runner holds a literal verb list beside the pin" c6 "$W/gix.json" "$W/GixRunner.kt"; }
_stage && _green "c7" c7 "$W/GhRunner.kt" && {
    _sub "$W/GhRunner.kt" 'put("GH_TOKEN", token)' 'add("--token"); add(token)'
    _red "C7 the token moves from the environment into argv (/proc-readable)" c7 "$W/GhRunner.kt"; }
_stage && _green "c7" c7 "$W/GhRunner.kt" && {
    printf '\nprivate val leak = Log.d("gh", "token=$token")\n' >>"$W/GhRunner.kt"
    _red "C7 a token reaches a log line" c7 "$W/GhRunner.kt"; }

echo "── $MUTATIONS mutations, $HOLLOW hollow/void/no-op ──"
[ "$MUTATIONS" -ge 13 ] || { echo "  only $MUTATIONS mutations ran — a mutation block that stops early proves less than it prints"; FAILURES=$((FAILURES + 1)); }
[ "$HOLLOW" -eq 0 ] || FAILURES=$((FAILURES + HOLLOW))

echo
if [ "$FAILURES" -eq 0 ]; then echo "test-git-binaries: all checks passed"; else echo "test-git-binaries: $FAILURES check(s) FAILED"; exit 1; fi
