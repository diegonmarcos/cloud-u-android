#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════╗
# ║ nav-ack-guard.test — prove the guard fails for EVERY route it    ║
# ║ claims to cover, not just the first one                          ║
# ╚══════════════════════════════════════════════════════════════════╝
#
# WHY THIS EXISTS, ALONGSIDE update-ack-guard.test.sh. That tester proves the
# guard can fail, and does it thoroughly — but only ever against routes[0].
# While it was the only tester, the manifest could grow a route whose rules
# matched nothing at all and the suite would go on printing green, which is the
# exact failure mode the manifest's own min_implementations field exists to
# catch one level down. A guard is only as trustworthy as the least-tested rule
# in it.
#
# So this one is driven ENTIRELY by the manifest: every route, every rule
# declared on that route, no route names and no rule text written here. Adding
# a route to update-ack-guard.json adds four cases to this tester on the same
# commit, and there is no second list for anyone to forget.
#
# THE DEFECT IT WAS WRITTEN FOR (#367). POST /api/nav/goto and /api/nav/action
# replied the literal "ok\n" unconditionally after firing through
# DevControlBridge.host()?.…, a WeakReference live only between the Activity's
# onResume and onPause. Backgrounded, the safe-call discarded the request and
# the caller was told "ok" — the same shape as the "update queued" defect that
# cost four published releases (#280), arriving at the transport layer. Every
# automated check standing on those routes was passing on a reply that asserted
# nothing.
#
# Navigation is NOT update: an update can be queued and acknowledged later, but
# a navigation hop needs the live Activity, so there is nothing to queue. A
# navigation request that arrives with no live host is legitimately a FAILURE
# and the route's job is to say so. That is why the nav rules forbid reaching
# host() from the branch and require DevControlServer.dispatchToHost, which
# waits for the main thread and reports what actually happened.
#
# Every case below BREAKS a throwaway copy in one specific way and demands the
# guard notice that exact way. The working tree is never touched.
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

# A case that does not apply to this route, SAID OUT LOUD. A case that is
# silently not run is indistinguishable in the output from a case that ran and
# passed, which is the same "coverage that looks like coverage" this whole
# suite exists to refuse. If a rule is untested because the manifest states no
# such rule, the reader should be told which rule and why.
skip() { printf 'skip   %s\n' "$1"; }

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

run_guard() { python3 "$1/$GUARD" --root "$1" 2>&1; }

# ── 0. The tree as it stands is clean. ────────────────────────────────────────
out="$(python3 "$ROOT/$GUARD" --root "$ROOT" 2>&1)"
if [ $? -eq 0 ]; then
    ok "working tree passes the guard"
else
    fail "working tree does NOT pass the guard:"; printf '%s\n' "$out" | sed 's/^/       /'
fi

# Route keys, straight from the manifest. Tab-separated so a label may hold
# spaces; read with IFS=$'\t' for the same reason.
ROUTES="$(python3 - "$ROOT/$MANIFEST" <<'PY'
import json, sys
for r in json.load(open(sys.argv[1], encoding="utf-8"))["routes"]:
    print(r["route"])
PY
)"

if [ -z "$ROUTES" ]; then
    fail "the manifest declares no routes at all — this tester is checking nothing"
    exit 1
fi

# Emit a Kotlin branch body built from one route's OWN manifest rules.
#   want=defect    every forbidden_call, plus the post-and-forget shape
#   want=literal   every forbidden_reply_literal, with the required calls present
#   want=gutted    neither the forbidden things nor the required calls
#   want=comment   forbidden things as COMMENTS, required calls for real
# Nothing here is compiled: the guard is a text lint over a `when` branch, so a
# body that is the right SHAPE exercises exactly the rules under test.
# How many entries a route declares for one manifest rule.
#
# Cases A and B each prove a rule the manifest STATES. A route that states no
# such rule has nothing to prove there, and running the case anyway asserts a
# rule nobody wrote — so the case must be skipped, and the question "does this
# route declare that rule" must be put to the manifest DIRECTLY.
#
# It used to be inferred from the shape of a generated body instead, and the
# inference was wrong in the direction that fails honest data: case B ran
# whenever the `literal` body was non-empty, but that body also carries the
# route's required_calls, so a route declaring required_calls and NO
# forbidden_reply_literals produced a non-empty body containing no literal at
# all. GET /api/state is exactly that route (#373), and the tester failed it
# for the absence of a rule rather than for any defect in the guard.
rule_count() {
    python3 - "$ROOT/$MANIFEST" "$1" "$2" <<'PY'
import json, sys
manifest, route, field = sys.argv[1], sys.argv[2], sys.argv[3]
spec = next(r for r in json.load(open(manifest, encoding="utf-8"))["routes"]
            if r["route"] == route)
print(len(spec.get(field, [])))
PY
}

emit_body() {
    python3 - "$ROOT/$MANIFEST" "$1" "$2" <<'PY'
import json, sys
manifest, route, want = sys.argv[1], sys.argv[2], sys.argv[3]
spec = next(r for r in json.load(open(manifest, encoding="utf-8"))["routes"]
            if r["route"] == route)
calls = spec.get("forbidden_calls", [])
lits = spec.get("forbidden_reply_literals", [])
need = spec.get("required_calls", [])


def realise(i, sub):
    # A line of Kotlin that genuinely CONTAINS the required substring. Nothing
    # here is compiled - the guard is a text lint over a `when` branch - so this
    # only has to put the substring into live code rather than into a comment.
    # Deterministic by construction: no hashing, no ordering games. A tester
    # whose fixtures vary from run to run cannot be told apart from the defect
    # it is hunting.
    if sub.endswith("("):
        return 'val observed%d = host%sctx, "x")' % (i, sub)
    return "val observed%d = %s" % (i, sub)


def reply_with(lit):
    # The forbidden literal on the wire, inside a RAW Kotlin string, because
    # that is how the real handlers carried it - haptic replied a raw
    # triple-quoted {"ok":true,...}. Writing it as an escaped "..." instead puts
    # backslashes between the quotes, the guard stops matching its own rule, and
    # the case passes while proving nothing. That is exactly how the first draft
    # of this tester reported haptic green.
    return 'reply(writer, "200 OK", """%s""", "application/json")' % lit


out = []
if want == "defect":
    for c in calls:
        out.append("DevControlBridge.runOnMain { %s?.dispatch() }" % c)
    out.append('reply(writer, "200 OK", "ok\\n")')
elif want == "literal":
    # Required calls present and real, so ONLY the literal rule can trip. A
    # mutation that trips two rules at once cannot show which one is alive.
    for i, n in enumerate(need):
        out.append(realise(i, n))
    for l in lits:
        out.append(reply_with(l))
elif want == "gutted":
    out.append('reply(writer, "501 Not Implemented", "unimplemented\\n")')
elif want == "comment":
    for c in calls:
        out.append("// Was: %s?.dispatch(), which replied before it had looked." % c)
    for l in lits:
        out.append("// It answered the literal %s to work it had not observed." % l)
    for i, n in enumerate(need):
        out.append(realise(i, n))
    out.append('reply(writer, "200 OK", ackJson(observed0), "application/json")')
print("\n".join(out))
PY
}

# Rewrite branch $3's body in the file $1/$2, with the body held in file $4.
# Brace-counted so it survives reindentation, the same way the guard is.
#
# The body arrives as a FILE, never on stdin: `python3 - <<EOF` spends stdin on
# the program text, so an inline script that also read sys.stdin would get an
# empty string and silently replace the mutation with an empty branch — a
# tester that can fail for a reason other than the one it names is worth less
# than no tester.
replace_body() {
    python3 - "$1/$2" "$3" "$4" <<'PY'
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

CASE=0
for ROUTE in $ROUTES; do
    # Every Kotlin file in the WORKING TREE implementing this route — the
    # working tree and not the index, because the guard has to be provable
    # against the edit somebody is about to commit.
    HANDLERS="$(cd "$ROOT" && find . -name '*.kt' -not -path './.git/*' -not -path './z_archive/*' \
        -exec grep -l "\"$ROUTE\" ->" {} + 2>/dev/null | sed 's|^\./||' | sort)"
    if [ -z "$HANDLERS" ]; then
        fail "$ROUTE: no handler found in the working tree — nothing to mutate"
        continue
    fi
    FIRST="$(printf '%s\n' "$HANDLERS" | head -1)"

    PRISTINE="$WORK/pristine-$CASE"
    mkdir -p "$PRISTINE/1_cicd/src/scripts" "$PRISTINE/1_cicd/src/data"
    cp "$ROOT/$GUARD" "$PRISTINE/1_cicd/src/scripts/"
    cp "$ROOT/$MANIFEST" "$PRISTINE/1_cicd/src/data/"
    for f in $HANDLERS; do
        mkdir -p "$PRISTINE/$(dirname "$f")"
        cp "$ROOT/$f" "$PRISTINE/$f"
    done

    sandbox() { CASE=$((CASE + 1)); rm -rf "$WORK/c$CASE"; cp -r "$PRISTINE" "$WORK/c$CASE"; printf '%s' "$WORK/c$CASE"; }

    # ── A. The original defect, restored. ─────────────────────────────────────
    #    Mutating ONE copy has to be enough: a guard needing every copy broken
    #    would have stayed green through the hours the superapp lied while
    #    cloud-nav sat correct.
    if [ "$(rule_count "$ROUTE" forbidden_calls)" -gt 0 ]; then
        d="$(sandbox)"; emit_body "$ROUTE" defect > "$WORK/body"
        replace_body "$d" "$FIRST" "$ROUTE" "$WORK/body"
        out="$(run_guard "$d")"
        if [ $? -ne 0 ] && printf '%s' "$out" | grep -q 'DevControlBridge.host()'; then
            ok "$ROUTE: guard rejects the branch reaching DevControlBridge.host() itself"
        else
            fail "$ROUTE: guard ACCEPTED the branch reaching host():"; printf '%s\n' "$out" | sed 's/^/       /'
        fi
    else
        skip "$ROUTE: declares no forbidden_calls — no such rule to prove"
    fi

    # ── B. A reply that asserts an outcome the handler never observed. ────────
    if [ "$(rule_count "$ROUTE" forbidden_reply_literals)" -gt 0 ]; then
        d="$(sandbox)"; emit_body "$ROUTE" literal > "$WORK/body"
        replace_body "$d" "$FIRST" "$ROUTE" "$WORK/body"
        out="$(run_guard "$d")"
        if [ $? -ne 0 ] && printf '%s' "$out" | grep -q 'an outcome it has not'; then
            ok "$ROUTE: guard rejects a reply asserting an unobserved outcome"
        else
            fail "$ROUTE: guard ACCEPTED an unobserved success literal:"; printf '%s\n' "$out" | sed 's/^/       /'
        fi
    else
        skip "$ROUTE: declares no forbidden_reply_literals — no such rule to prove"
    fi

    # ── C. A handler that neither lies nor works. ─────────────────────────────
    d="$(sandbox)"; emit_body "$ROUTE" gutted > "$WORK/body"
    replace_body "$d" "$FIRST" "$ROUTE" "$WORK/body"
    out="$(run_guard "$d")"
    if [ $? -ne 0 ] && printf '%s' "$out" | grep -q 'never calls'; then
        ok "$ROUTE: guard rejects a handler that does none of the real work"
    else
        fail "$ROUTE: guard ACCEPTED a handler with the work deleted:"; printf '%s\n' "$out" | sed 's/^/       /'
    fi

    # ── D. The defect as a COMMENT is not the defect. Must stay GREEN. ────────
    #    The first draft of this guard failed this shape: it matched raw file
    #    text, so the fix's own explanation of what it replaced read as the
    #    thing it replaced. A rule that punishes the comment explaining it is a
    #    rule whose comments get deleted, and then nobody knows why the branch
    #    is written that way.
    d="$(sandbox)"; emit_body "$ROUTE" comment > "$WORK/body"
    replace_body "$d" "$FIRST" "$ROUTE" "$WORK/body"
    out="$(run_guard "$d")"
    if [ $? -eq 0 ]; then
        ok "$ROUTE: guard reads code, not comments — the fix may explain what it replaced"
    else
        fail "$ROUTE: guard tripped over a COMMENT quoting the defect:"; printf '%s\n' "$out" | sed 's/^/       /'
    fi
done

# ── E. The guard must notice when it has stopped matching anything. ───────────
#    Renaming the branch key is the realistic way this rots: the route gets
#    restructured, the manifest is not updated, and a guard over zero files
#    prints the identical green it prints over correct ones.
FIRSTROUTE="$(printf '%s\n' "$ROUTES" | head -1)"
HANDLERS="$(cd "$ROOT" && find . -name '*.kt' -not -path './.git/*' -not -path './z_archive/*' \
    -exec grep -l "\"$FIRSTROUTE\" ->" {} + 2>/dev/null | sed 's|^\./||' | sort)"
d="$WORK/rename"; rm -rf "$d"
mkdir -p "$d/1_cicd/src/scripts" "$d/1_cicd/src/data"
cp "$ROOT/$GUARD" "$d/1_cicd/src/scripts/"; cp "$ROOT/$MANIFEST" "$d/1_cicd/src/data/"
for f in $HANDLERS; do
    mkdir -p "$d/$(dirname "$f")"
    sed "s|\"$FIRSTROUTE\" ->|\"system/renamed-away\" ->|" "$ROOT/$f" > "$d/$f"
done
out="$(run_guard "$d")"
if [ $? -ne 0 ] && printf '%s' "$out" | grep -q 'checking nothing'; then
    ok "guard reports that it is checking nothing when a route is renamed away"
else
    fail "guard swept a tree with NO implementations and called it green:"; printf '%s\n' "$out" | sed 's/^/       /'
fi

echo
if [ "$FAILURES" -eq 0 ]; then
    echo "nav-ack-guard.test: all cases passed"
    exit 0
fi
echo "nav-ack-guard.test: $FAILURES case(s) failed"
exit 1
