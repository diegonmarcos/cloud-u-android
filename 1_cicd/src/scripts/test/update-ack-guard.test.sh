#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════╗
# ║ update-ack-guard.test — prove the guard fails, not just that it  ║
# ║ runs                                                             ║
# ╚══════════════════════════════════════════════════════════════════╝
#
# WHY THIS EXISTS. A guard that has only ever been watched succeeding is
# indistinguishable from a guard that returns 0 unconditionally, and the defect
# this one covers is itself a thing that only ever looked like it was working:
# POST /api/system/update answered "update queued" for four releases it had
# never fetched. Watching a green tick would have been exactly as convincing
# before the fix as after it.
#
# Every case below BREAKS the tree in one specific way, demands the guard notice
# that exact way, and throws the copy away. The break happens in a throwaway
# sandbox, never in the working tree.
#
# Case 5 breaks nothing and demands the guard stay GREEN. It re-adds the defect
# as a COMMENT. The first draft of this guard failed that case — it matched raw
# file text, so the fix's own explanation of what it replaced read as the thing
# it replaced. A guard that punishes the comment explaining it is a guard whose
# comments get deleted, and then nobody knows why the branch is written that way.
#
# WHICH FILES THE SANDBOX HOLDS IS DERIVED, NOT LISTED — from the manifest's
# route key, the same way the guard finds them. A third app that grows a copy of
# this route is exercised here on the day it lands.
#
# No ripgrep anywhere: the CI runner does not have it, and testers here have
# passed on its absence rather than on their assertions.

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
GUARD="1_cicd/src/scripts/cloud-android-update-ack-guard.py"
MANIFEST="1_cicd/src/data/update-ack-guard.json"
FAILURES=0

ok()   { printf 'ok     %s\n' "$1"; }
fail() { printf 'FAIL   %s\n' "$1"; FAILURES=$((FAILURES + 1)); }

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# The files the manifest declares group-scoped (see group_scoped_dispatch). The
# guard refuses a declaration whose file is missing, so every sandbox carries
# them — derived from the manifest, not listed here.
copy_declared() {
    for f in $(python3 -c 'import json,sys; [print(e["path"]) for e in json.load(open(sys.argv[1])).get("group_scoped_dispatch", [])]' "$ROOT/$MANIFEST"); do
        mkdir -p "$1/$(dirname "$f")"; [ -e "$1/$f" ] || cp "$ROOT/$f" "$1/$f"
    done
}

ROUTE="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["routes"][0]["route"])' "$ROOT/$MANIFEST")"
if [ -z "$ROUTE" ]; then
    fail "could not read the route key out of $MANIFEST"
    exit 1
fi

# Every Kotlin file in the WORKING TREE that implements the route — the working
# tree and not the git index, because the guard has to be provable against the
# edit somebody is about to commit, not only against what is already committed.
HANDLERS="$(cd "$ROOT" && find . -name '*.kt' -not -path './.git/*' -not -path './z_archive/*' \
    -exec grep -l "\"$ROUTE\" ->" {} + 2>/dev/null | sed 's|^\./||' | sort)"

if [ -z "$HANDLERS" ]; then
    fail "no handler of \"$ROUTE\" found in the working tree — nothing to mutate"
    exit 1
fi

# One pristine sandbox, made once. The guard walks its --root for .kt files, so
# a tree holding only the handlers, the guard and the manifest exercises exactly
# the same code paths as the full repository.
PRISTINE="$WORK/pristine"
mkdir -p "$PRISTINE/1_cicd/src/scripts" "$PRISTINE/1_cicd/src/data"
cp "$ROOT/$GUARD" "$PRISTINE/1_cicd/src/scripts/"
cp "$ROOT/$MANIFEST" "$PRISTINE/1_cicd/src/data/"
for f in $HANDLERS; do
    mkdir -p "$PRISTINE/$(dirname "$f")"
    cp "$ROOT/$f" "$PRISTINE/$f"
done
copy_declared "$PRISTINE"

# Fresh copy of the pristine sandbox; prints its path.
sandbox() {
    local dir="$WORK/case-$1"
    rm -rf "$dir"
    cp -r "$PRISTINE" "$dir"
    printf '%s' "$dir"
}

run_guard() { python3 "$1/$GUARD" --root "$1" 2>&1; }

# Mutate the FIRST handler only. One broken copy has to be enough: a guard that
# needs every copy broken before it complains would have stayed green through
# the six hours the superapp spent lying while cloud-nav sat correct.
FIRST="$(printf '%s\n' "$HANDLERS" | head -1)"

# Rewrite the route's branch body in $1/$FIRST with the body held in the file $2.
# Brace-counted so it survives reindentation, the same way the guard is.
#
# The body arrives as a FILE and not on stdin. `python3 - <<EOF` spends stdin on
# the program text, so an inline script that also read sys.stdin got an empty
# string — and this tester's first run duly replaced three mutations with an
# empty branch, went red for the wrong reason, and reported the guard as broken.
# A tester that can fail for a reason other than the one it names is worth less
# than no tester, because it spends the credibility the real failure needs.
replace_body() {
    python3 - "$1/$FIRST" "$ROUTE" "$2" <<'PY'
import sys
path, route = sys.argv[1], sys.argv[2]
new = open(sys.argv[3], encoding="utf-8").read().rstrip("\n")
lines = open(path, encoding="utf-8").read().splitlines()
key = '"%s" ->' % route
start = next(i for i, l in enumerate(lines) if key in l)
depth, opened, end = 0, False, start
for i in range(start, len(lines)):
    depth += lines[i].count("{") - lines[i].count("}")
    if lines[i].count("{"):
        opened = True
    if opened and depth <= 0:
        end = i
        break
indent = " " * (len(lines[start]) - len(lines[start].lstrip()))
body = [indent + key + " {"] + [indent + "    " + l for l in new.splitlines()] + [indent + "}"]
open(path, "w", encoding="utf-8").write("\n".join(lines[:start] + body + lines[end + 1:]) + "\n")
PY
}

# ── 0. The tree as it stands is clean. ────────────────────────────────────────
out="$(run_guard "$PRISTINE")"
if [ $? -eq 0 ]; then
    ok "working tree passes the guard"
else
    fail "working tree does NOT pass the guard:"; printf '%s\n' "$out" | sed 's/^/       /'
fi

# ── 1. The original defect, put back verbatim. ────────────────────────────────
d="$(sandbox 1)"
cat > "$WORK/mut-1" <<'MUT'
DevControlBridge.runOnMain {
    DevControlBridge.host()?.onActionFromServer("check_updates")
}
reply(writer, "200 OK", "ok\n")
MUT
replace_body "$d" "$WORK/mut-1"
out="$(run_guard "$d")"
if [ $? -ne 0 ] && grep -q 'DevControlBridge.host()' <<<"$out"; then
    ok "guard rejects the handler routing through DevControlBridge.host()"
else
    fail "guard ACCEPTED the original defect (host() route):"; printf '%s\n' "$out" | sed 's/^/       /'
fi

# ── 2. The sentence that cost the four releases. ──────────────────────────────
d="$(sandbox 2)"
cat > "$WORK/mut-2" <<'MUT'
val ack = com.diegonmarcos.superapp.updater.Updater.requestCheck(ctx, "x")
reply(writer, "200 OK", "update queued\n")
MUT
replace_body "$d" "$WORK/mut-2"
out="$(run_guard "$d")"
if [ $? -ne 0 ] && grep -q 'update queued' <<<"$out"; then
    ok "guard rejects a reply asserting an outcome it did not observe"
else
    fail "guard ACCEPTED the literal \"update queued\":"; printf '%s\n' "$out" | sed 's/^/       /'
fi

# ── 3. A handler that neither lies nor works. ─────────────────────────────────
d="$(sandbox 3)"
cat > "$WORK/mut-3" <<'MUT'
reply(writer, "501 Not Implemented", """{"ok":false}""", "application/json")
MUT
replace_body "$d" "$WORK/mut-3"
out="$(run_guard "$d")"
if [ $? -ne 0 ] && grep -q 'requestCheck' <<<"$out"; then
    ok "guard rejects a handler that never starts the work"
else
    fail "guard ACCEPTED a handler with no requestCheck call:"; printf '%s\n' "$out" | sed 's/^/       /'
fi

# ── 4. The guard must notice when it has stopped matching anything. ───────────
#    Renaming the branch key is the realistic way this rots: the route gets
#    restructured, the manifest is not updated, and a guard over zero files
#    prints the identical green it prints over correct ones.
d="$(sandbox 4)"
for f in $HANDLERS; do
    python3 - "$d/$f" "$ROUTE" <<'PY'
import sys
path, route = sys.argv[1], sys.argv[2]
s = open(path, encoding="utf-8").read()
open(path, "w", encoding="utf-8").write(s.replace('"%s" ->' % route, '"system/upgrade" ->'))
PY
done
out="$(run_guard "$d")"
if [ $? -ne 0 ] && grep -q 'checking nothing' <<<"$out"; then
    ok "guard reports that it is checking nothing when the route is renamed"
else
    fail "guard swept a tree with NO implementations and called it green:"; printf '%s\n' "$out" | sed 's/^/       /'
fi

# ── 5. The defect as a COMMENT is not the defect. ─────────────────────────────
#    This case must stay GREEN. It is the one the guard's first draft failed.
d="$(sandbox 5)"
cat > "$WORK/mut-4" <<'MUT'
// Was: DevControlBridge.host()?.onActionFromServer("check_updates")
// which replied "update queued" to work it had never started.
val ack = com.diegonmarcos.superapp.updater.Updater.requestCheck(ctx, "x")
reply(writer, "200 OK", """{"ok":${ack.ok}}""", "application/json")
MUT
replace_body "$d" "$WORK/mut-4"
out="$(run_guard "$d")"
if [ $? -eq 0 ]; then
    ok "guard reads code, not comments — the fix may explain what it replaced"
else
    fail "guard tripped over a COMMENT quoting the defect:"; printf '%s\n' "$out" | sed 's/^/       /'
fi

# ── G. group_scoped_dispatch: declared, re-earned, and never a blanket. ──────
#    Drive's `when (op)` serves /api/git/{state,...}: AppDebugServer hands a
#    group handler only the sub-op, so its "state" is a registry read and not
#    GET /api/state. The manifest exempts that file BY NAME. These cases prove
#    the exemption cannot be inherited, cannot rot, and cannot be pointed at a
#    real route table. Each mutation is checked to have actually changed the
#    sandbox, because a no-op mutation prints the same green as a working guard.
G_FILES="$(cd "$ROOT" && python3 - "$MANIFEST" <<'PY2'
import json, sys
print("\n".join(e["path"] for e in json.load(open(sys.argv[1]))["group_scoped_dispatch"]))
PY2
)"
G_FILES="$G_FILES
$(cd "$ROOT" && find . -name DevControlServer.kt -not -path './z_archive/*' | sed 's|^\./||')"
GP="$WORK/g-pristine"
mkdir -p "$GP/1_cicd/src/scripts" "$GP/1_cicd/src/data"
cp "$ROOT/$GUARD" "$GP/1_cicd/src/scripts/"
cp "$ROOT/$MANIFEST" "$GP/1_cicd/src/data/"
for f in $G_FILES; do mkdir -p "$GP/$(dirname "$f")"; cp "$ROOT/$f" "$GP/$f"; done
DRIVE="$(printf '%s\n' "$G_FILES" | head -1)"
DCS="$(printf '%s\n' "$G_FILES" | grep DevControlServer.kt | head -1)"
gbox() { rm -rf "$WORK/g-$1"; cp -r "$GP" "$WORK/g-$1"; printf '%s' "$WORK/g-$1"; }
# Apply a python edit ($3) to file $2 inside sandbox $1; fail loudly if it changed nothing.
gmut() {
    cp "$1/$2" "$WORK/before"
    python3 -c "import sys; p=sys.argv[1]; s=open(p).read(); $3; open(p,'w').write(s)" "$1/$2"
    if cmp -s "$1/$2" "$WORK/before"; then fail "mutation did not apply to $2 — case proves nothing"; return 1; fi
}
gman() { gmut "$1" "$MANIFEST" "import json; d=json.loads(s); $2; s=json.dumps(d)"; }

out="$(run_guard "$GP")"
if [ $? -eq 0 ]; then ok "G0 route tables + declared group handler pass together"
else fail "G0 pristine group sandbox is red:"; printf '%s\n' "$out" | sed 's/^/       /'; fi

d="$(gbox 1)"
if gman "$d" "d['group_scoped_dispatch']=[]"; then
    out="$(run_guard "$d")"
    if [ $? -ne 0 ] && grep -q "$DRIVE:.*dispatchToHost(" <<<"$out"; then
        ok "G1 an UNDECLARED group handler colliding with a route key stays red"
    else fail "G1 undeclared group handler slipped through:"; printf '%s\n' "$out" | sed 's/^/       /'; fi
fi

d="$(gbox 2)"
if gman "$d" "d['group_scoped_dispatch'][0]['path']='gone/Moved.kt'"; then
    out="$(run_guard "$d")"
    if [ $? -ne 0 ] && grep -q 'gone/Moved.kt.*does not exist' <<<"$out"; then
        ok "G2 a stale exemption (path gone) is red"
    else fail "G2 stale exemption accepted:"; printf '%s\n' "$out" | sed 's/^/       /'; fi
fi

d="$(gbox 3)"
if gman "$d" "d['group_scoped_dispatch'].append({'path':'$DCS','why':'x'})"; then
    out="$(run_guard "$d")"
    if [ $? -ne 0 ] && grep -q "$DCS.*registers no" <<<"$out"; then
        ok "G3 declaring a real route table (DevControlServer) as group-scoped is refused"
    else fail "G3 a root route table was exempted:"; printf '%s\n' "$out" | sed 's/^/       /'; fi
fi

d="$(gbox 4)"
if gmut "$d" "$DRIVE" "s=s.replace('AppDebugServer.route(', 'OtherServer.route(')"; then
    out="$(run_guard "$d")"
    if [ $? -ne 0 ] && grep -q "$DRIVE.*registers no" <<<"$out"; then
        ok "G4 a declared file that stops being a group handler loses its exemption"
    else fail "G4 exemption outlived its reason:"; printf '%s\n' "$out" | sed 's/^/       /'; fi
fi

#    A one-liner branch used to bleed into the NEXT branch's braces and borrow
#    its dispatchToHost( — GET /api/state answering without dispatching passed.
#    The reply carries NO brace on purpose: a "{}" literal is itself brace-counted
#    and closed the body early, so the pre-fix guard caught that variant by luck.
d="$(gbox 5)"
if gmut "$d" "$DCS" "import re; s=re.sub(r'\"state\" -> \{.*?\n                \}\n', '\"state\" -> reply(writer, \"200 OK\", \"empty\", \"text/plain\")\n', s, count=1, flags=re.S)"; then
    out="$(run_guard "$d")"
    if [ $? -ne 0 ] && grep -q "$DCS.*GET /api/state never calls dispatchToHost(" <<<"$out"; then
        ok "G5 a one-liner GET /api/state that never dispatches is red (no borrowing from the next branch)"
    else fail "G5 one-liner state branch passed on a neighbour's call:"; printf '%s\n' "$out" | sed 's/^/       /'; fi
fi

echo
if [ "$FAILURES" -eq 0 ]; then
    echo "update-ack-guard.test: all cases passed"
    exit 0
fi
echo "update-ack-guard.test: $FAILURES case(s) failed"
exit 1
