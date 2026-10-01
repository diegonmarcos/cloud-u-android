#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════╗
# ║ pipefail-grep-q.test — no tester may pipe into `grep -q` while   ║
# ║ pipefail is on: the verdict becomes a race                       ║
# ╚══════════════════════════════════════════════════════════════════╝
#
# WHY THIS EXISTS (#634). Under `set -o pipefail`, `producer | grep -q PAT` is
# decided by scheduling, not by the text. grep -q exits on its first match; a
# producer that still has output to write then dies of SIGPIPE, the pipeline
# returns 141, and the MATCH reads as a failure. (Where SIGPIPE is ignored, as
# on GitHub's hosted runners, the write fails with EPIPE instead and the status
# is 1: the same wrong verdict.) The producer only has to
# outgrow one write for that to happen, and a tester's producer is usually a
# whole Kotlin file through awk.
#
# Both polarities go wrong. A `has` check flakes red. A `hasnt` check
# (`| grep -q BAD && bad || ok`) goes GREEN on the very defect it exists to
# catch: with `private val NEUTRAL = 0x…` planted at the top of the 110 KB
# ProfileFragment.kt, the old spelling of test-profile-vault-connect.sh's T8
# line passed 50 runs out of 50. test-profile-journey.sh had already been
# bitten once (#585b) and had one line of it fixed by hand.
#
# The pipe-free spellings have no race, because grep reads a finished string:
#     grep -q PAT <<<"$var"            a captured value
#     grep -q PAT <<<"$(producer)"     a command
#
# WHAT IS SCANNED: every shell file in a tester directory some build.json
# declares (tests.shell.dir, the declaration cloud-android-test-engine.sh runs
# testers from), plus this directory, which holds the fleet's guard testers.
# Engines are deliberately NOT scanned. The same shape lives in
# 1_cicd/src/scripts/*-engine.sh and in every app's VENDORED build.sh copy, and
# a vendored build.sh is inside that app's publish identity: rewriting them
# republishes some thirty APKs to every phone. That is its own decision.
#
# The rule is ONE awk program. The self-check drives that same program over
# planted fixtures in both directions, and re-proves the premise — a match
# under pipefail really does come back non-zero — on the machine running it.

set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../../../.." && pwd)"
fail=0
ok()  { printf 'ok     %s\n' "$1"; }
bad() { printf 'FAIL   %s\n' "$1"; fail=1; }

command -v jq >/dev/null 2>&1 || {
    printf 'FAIL   jq is absent: tests.shell.dir cannot be read, so the scan set would be empty and this lint would pass having read nothing\n'
    exit 1; }

# The rule. For every file it is given it prints
#   PF <file>                   the file turns pipefail on
#   HIT <file>:<line>: <text>   a `| grep -q` on a code line of such a file
#   HEREDOC <file>:<line>: ...  a heredoc that never ends: the rest went unread
# Comment lines and heredoc bodies are not code in this shell, so they are
# skipped. A heredoc that never closes is reported rather than trusted, since
# everything after it would otherwise pass unseen.
read -r -d '' RULE <<'AWK' || true
# The delimiter of a heredoc this line opens, or "". Quote-aware the way bash
# is: a `<<` inside "..." or '...' or after a comment opens nothing, `<<<` is a
# here-string, and `$(` (even inside "...") starts a fresh command context, so
# x="$(cmd <<'EOF'" DOES open one. A per-line stack: C = command, D = "...".
function heredoc(s,   i, j, c, st, n, rest, d) {
    n = 1; st[1] = "C"
    for (i = 1; i <= length(s); i++) {
        c = substr(s, i, 1)
        if (st[n] == "D") {
            if (c == "\\") i++
            else if (c == "\"") n--
            else if (substr(s, i, 2) == "$(") { st[++n] = "C"; i++ }
            continue
        }
        if (c == "\\") { i++; continue }
        if (c == "'") { j = index(substr(s, i + 1), "'"); i = j ? i + j : length(s); continue }
        if (c == "\"") { st[++n] = "D"; continue }
        if (substr(s, i, 2) == "$(") { st[++n] = "C"; i++; continue }
        if (c == "(") { st[++n] = "C"; continue }
        if (c == ")") { if (n > 1) n--; continue }
        if (c == "#" && (i == 1 || substr(s, i - 1, 1) ~ /[[:space:]]/)) return ""
        if (substr(s, i, 3) == "<<<") { i += 2; continue }
        if (substr(s, i, 2) == "<<") {
            rest = substr(s, i + 2)
            if (match(rest, /^-?[[:space:]]*["']?[A-Za-z_][A-Za-z0-9_]*["']?/)) {
                d = substr(rest, 1, RLENGTH); sub(/^-?[[:space:]]*["']?/, "", d); sub(/["']$/, "", d)
                return d
            }
            i++
        }
    }
    return ""
}
function flush(   i) {
    if (file == "") return
    if (hd != "") print "HEREDOC " file ":" hdline ": <<" hd " never ends, nothing after it was linted"
    if (pf) { print "PF " file; for (i = 1; i <= n; i++) print "HIT " file ":" hit[i] }
}
FNR == 1 { flush(); file = FILENAME; pf = 0; n = 0; hd = "" }
hd != "" { t = $0; sub(/^\t+/, "", t); if (t == hd) hd = ""; next }
/^[[:space:]]*#/ { next }
{
    d = heredoc($0); if (d != "") { hd = d; hdline = FNR }
    if ($0 ~ /(^|[;&|[:space:]])set[[:space:]]+([^#;]*[[:space:]])?-[A-Za-z]*o[[:space:]]+pipefail/) pf = 1
    if ($0 ~ /(^|[^|])\|&?[[:space:]]*[ef]?grep([[:space:]]+[^|;&()`[:space:]]+)*[[:space:]]+(-[[:alnum:]]*q[[:alnum:]]*|--quiet|--silent)([[:space:]]|$)/)
        hit[++n] = FNR ": " $0
}
END { flush() }
AWK

# ── 1. the premise, on this machine ─────────────────────────────────────────
# grep matches the first line and exits; the producer's second write lands on
# a closed pipe. Run in a child shell so this file itself stays pipe-free.
premise="$(bash <<'PREMISE'
set -o pipefail
{ echo hit; sleep 0.3; echo more; } 2>/dev/null | grep -q hit; old=$?
grep -q hit <<<"$({ echo hit; sleep 0.3; echo more; })"; new=$?
echo "$old $new"
PREMISE
)"
read -r old new <<<"$premise"
if [ "${old:-0}" -ne 0 ] && [ "${new:-1}" -eq 0 ]; then
    ok "premise: under pipefail a MATCH piped into grep -q returned $old (141 = SIGPIPE, 1 = EPIPE where SIGPIPE is ignored); the here-string form returned 0"
else
    bad "premise did not reproduce (piped=$old here-string=$new) — the rule below would be guarding against nothing"
fi

# ── 2. the rule, against planted fixtures ───────────────────────────────────
FIX="$(mktemp -d)"
trap 'rm -rf "$FIX"' EXIT

cat > "$FIX/caught.sh" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
echo "$x" | grep -q hit && echo yes
printf '%s\n' "$x" | grep -qE 'a|b'
cmd | grep -Fxq word
cmd | grep -E -q word
cmd |grep --quiet word
cmd |& grep -q word
cmd \
  | grep -qv word
echo "a quoted <<EOF opens nothing"
cmd | grep -q still-seen
SH

cat > "$FIX/clean.sh" <<'SH'
#!/usr/bin/env bash
set -o errexit -o pipefail
grep -q hit <<<"$x"
grep -q hit <<<"$(cmd)"
[ -f f ] || grep -q hit f
# never: cmd | grep -q hit
cat > gen.sh <<'EOF'
cmd | grep -q hit
EOF
cmd | grep -c hit
cmd | grep -v quiet
x="$(cat <<'EOT'
cmd | grep -q inside-a-heredoc-in-a-substitution
EOT
)"
[ "$(cmd | grep -c hit)" -eq 0 ]
[ `cmd | grep -c hit` -eq 0 ]
SH

cat > "$FIX/no-pipefail.sh" <<'SH'
#!/usr/bin/env bash
set -eu
echo "$x" | grep -q hit
SH

cat > "$FIX/unterminated.sh" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
cat <<EOF
never closed
SH

got="$(awk "$RULE" "$FIX/caught.sh" | sed -n 's|^HIT [^:]*:\([0-9]*\):.*|\1|p' | tr '\n' ' ')"
[ "$got" = "3 4 5 6 7 8 10 12 " ] \
    && ok "every spelling of a pipe into grep -q is caught: -q, -qE, -Fxq, -E -q, --quiet, |&, a continued line, and one after a quoted <<EOF" \
    || bad "planted pipes caught on lines [${got% }], expected [3 4 5 6 7 8 10 12]"

got="$(awk "$RULE" "$FIX/clean.sh")"
[ "$got" = "PF $FIX/clean.sh" ] \
    && ok "here-strings, ||, comments, heredoc bodies (one opened inside a quoted command substitution too), non-quiet greps and a later [ -eq ] pass — and pipefail WAS seen (set -o errexit -o pipefail)" \
    || bad "the pipe-free file was not judged clean: '$got'"

got="$(awk "$RULE" "$FIX/no-pipefail.sh")"
[ -z "$got" ] \
    && ok "a file without pipefail is out of scope — the race needs pipefail to decide a verdict" \
    || bad "a file without pipefail was flagged: '$got'"

got="$(awk "$RULE" "$FIX/unterminated.sh")"
case "$got" in
    "HEREDOC $FIX/unterminated.sh:3: <<EOF never ends"*) ok "a heredoc that never ends is reported, not trusted" ;;
    *) bad "an unterminated heredoc went unreported: '$got'" ;;
esac

# ── 3. the tree ─────────────────────────────────────────────────────────────
files=()
while IFS= read -r -d '' bj; do
    dir="$(jq -r '(.tests? | objects | .shell? | objects | .dir?) // empty' "$ROOT/$bj")" \
        || { bad "$bj cannot be read — its tester directory cannot be located"; continue; }
    [ -n "$dir" ] || continue
    dir="$(dirname "$bj")/${dir%/}"; dir="${dir#./}"
    while IFS= read -r -d '' f; do files+=("$ROOT/$f"); done \
        < <(git -C "$ROOT" ls-files -z -- "$dir/*.sh")
done < <(git -C "$ROOT" ls-files -z -- ':(glob)**/build.json')
declared=${#files[@]}
while IFS= read -r -d '' f; do files+=("$ROOT/$f"); done \
    < <(git -C "$ROOT" ls-files -z -- "${HERE#"$ROOT"/}/*.sh")

if [ "$declared" -eq 0 ] || [ "${#files[@]}" -eq "$declared" ]; then
    bad "scan set is incomplete: $declared file(s) from tests.shell.dir declarations, $(( ${#files[@]} - declared )) from $HERE — a lint over nothing passes"
else
    report="$(awk "$RULE" "${files[@]}")" || bad "the rule could not run over the tree"
    pf_n="$(grep -c '^PF ' <<<"$report")"
    hits="$(sed -n 's|^HIT '"$ROOT"'/||p' <<<"$report")"
    unread="$(sed -n 's|^HEREDOC '"$ROOT"'/||p' <<<"$report")"
    [ "$pf_n" -gt 0 ] || bad "none of ${#files[@]} tester files turns pipefail on — the detector is broken, not the tree clean"
    [ -z "$unread" ] || bad "heredocs the rule cannot see past:"$'\n'"$unread"
    if [ -z "$hits" ]; then
        ok "no tester pipes into grep -q under pipefail (${#files[@]} files scanned, $pf_n of them set pipefail)"
    else
        bad "these testers pipe into grep -q under pipefail — rewrite each as grep -q PAT <<<\"\$(producer)\":"
        printf '%s\n' "$hits" | sed 's/^/         /'
    fi
fi

echo
[ "$fail" -eq 0 ] && echo "PASS — no tester verdict is decided by a SIGPIPE race" \
                  || echo "FAIL — see above"
exit "$fail"
