#!/usr/bin/env bash
# Guards the firestack build against resolving dependencies at build time.
#
# Three APK publishes were lost in one day, on three different modules, to the
# same failure: `gomobile bind` shells out to `go mod tidy`, tidy walks out to
# sum.golang.org, and sum.golang.org returned a stream error partway through.
# Every phone stayed on the previous APK each time.
#
# `go mod tidy` is not a build step. It RESOLVES and MUTATES dependency state,
# which makes the APK depend on a third-party service being healthy and lets two
# builds of the same commit resolve differently. The fix was to delete it from
# the Makefile, pin the tools go.mod can pin, and have build-firestack.sh seed
# the module cache from the committed go.mod/go.sum and then build with
# GOPROXY=off. This tester exists so a future edit that puts resolution back
# into a build fails HERE, at review time, instead of at 3am when the checksum
# service hiccups.
#
# Nothing is hardcoded: the Go modules, the scripts that build them and the
# workflows that reach them are all discovered from the tree, so a second Go
# module or a third shipping workflow is covered the day it is added.
#
# Run from anywhere:  bash ab_cloud-libs-shared/libs/firewall/build-firestack.test.sh
set -uo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/../../.." || exit 1   # repo root

fail=0
note() { printf '%-6s %s\n' "$1" "$2"; [ "$1" = FAIL ] && fail=1; return 0; }

ENGINE=ab_cloud-libs-shared/libs/firewall/build-firestack.sh

# Commands that reach out to the module proxy / checksum database and rewrite
# the dependency set. None of these belongs in a build recipe.
RESOLVERS='go[[:space:]]+mod[[:space:]]+(tidy|vendor|edit)|go[[:space:]]+get([[:space:]]|$)'

# Every Go module THIS REPOSITORY COMMITS, and the Makefile that drives it (if
# any). Found, not listed — libwg-go is here for the same reason firestack is.
#
# Asked of GIT, not of the filesystem. This used to be a `find` with a prune
# list (.git, vendor), and the omission was not one more directory to add: a
# `find` cannot tell a module we own from a module we downloaded. The moment a
# workflow restored ab_cloud-libs-shared/.cache/gomodcache BEFORE this tester
# ran, every cached DEPENDENCY became a "module of ours with no committed
# go.sum" and assertion 3 produced dozens of failures about golang.org/x/sync
# and gopkg.in/yaml.v3 — modules nobody here is supposed to commit anything
# for. ship-cloud-superapp.yml only escaped it because its tester step happens
# to run before its cache step; ship-firestack-aar.yml restores the cache first
# and went red on its first run.
#
# Tracked-ness is not a proxy for the property under test, it IS the property:
# assertion 3 asks whether the pins are COMMITTED. So the question goes to the
# thing that knows what is committed, and no prune list can rot out of date.
mapfile -t GOMODS < <(git ls-files -- '*go.mod' ':!:*/vendor/*' | sort)
[ "${#GOMODS[@]}" -gt 0 ] || note FAIL "git tracks no go.mod anywhere — this tester is looking in the wrong place, or is not running inside the repository"

# 1. No build recipe resolves or mutates dependency state.
#    Recipe lines only (leading TAB): a `go mod tidy` written in a comment, or
#    named in prose explaining why it is gone, is not a build step.
for gm in "${GOMODS[@]}"; do
    mod=$(dirname "$gm"); mk="$mod/Makefile"
    [ -f "$mk" ] || { note ok "${mod#./} has no Makefile"; continue; }
    if hits=$(grep -nE "^	.*($RESOLVERS)" "$mk"); then
        note FAIL "${mk#./} resolves dependencies in a build recipe:"
        printf '         %s\n' "$hits"
    else
        note ok "${mk#./} has no dependency-resolving recipe"
    fi
done

# 2. No build recipe installs a tool at a floating version. `@latest` re-resolves
#    on every build, so the tool that produced yesterday's aar is not the tool
#    that produces today's — the same non-reproducibility as the tidy, and it
#    hits the same checksum database on the way.
for gm in "${GOMODS[@]}"; do
    mod=$(dirname "$gm"); mk="$mod/Makefile"
    [ -f "$mk" ] || continue
    if hits=$(grep -nE '^	.*go[[:space:]]+install[^#]*@latest' "$mk"); then
        note FAIL "${mk#./} installs a build tool at @latest (unpinned):"
        printf '         %s\n' "$hits"
    else
        note ok "${mk#./} pins every tool it installs"
    fi
done

# 3. Pins are committed. Disabling resolution is only safe if what is pinned is
#    actually there; a module with no go.sum would fail closed on every build.
for gm in "${GOMODS[@]}"; do
    mod=$(dirname "$gm")
    [ -f "$mod/go.sum" ] && note ok "${mod#./} has a committed go.sum" \
                         || note FAIL "${mod#./} has go.mod but no committed go.sum — nothing to build against"
done

# 4. The engine seeds from the committed pins BEFORE building, and the build
#    itself resolves nothing. Order matters: seeding after the build would leave
#    the build reaching out, which is the bug.
if [ ! -f "$ENGINE" ]; then
    note FAIL "$ENGINE is missing — the one engine both callers share"
else
    # Command lines only. Matching anywhere would match the comment block above
    # the seed step, which explains what `go mod download` does — and a comment
    # always sorts before the build, so the ordering check below would have
    # passed no matter where the real command sat. That is a green light wired
    # to nothing; this tester exists precisely to not be one.
    seed=$(grep -nE '^[^#]*go mod download' "$ENGINE" | head -1 | cut -d: -f1)
    bind=$(grep -nE '^[^#]*GOPROXY=off.*make ' "$ENGINE" | head -1 | cut -d: -f1)
    [ -n "$seed" ] && note ok "engine seeds the module cache from the committed pins" \
                   || note FAIL "engine does not seed from the committed pins (no 'go mod download')"
    [ -n "$bind" ] && note ok "engine runs the gomobile bind with GOPROXY=off" \
                   || note FAIL "engine runs the bind without GOPROXY=off — the build can resolve again"
    if [ -n "$seed" ] && [ -n "$bind" ]; then
        [ "$seed" -lt "$bind" ] && note ok "seed precedes the build" \
                                || note FAIL "engine seeds AFTER the build (line $seed > $bind) — the build still reaches out"
    fi
    # The seed must not itself rewrite the pins. `go mod download all` walks the
    # test dependencies of dependencies and WRITES the hashes it finds into
    # go.sum, so the step meant to enforce the pins silently edits them instead.
    # This was written the wrong way first time and caught by diffing go.sum
    # before and after; the assertion is here so the next person does not have to.
    if grep -qE '^[^#]*go mod download[[:space:]]+all' "$ENGINE"; then
        note FAIL "engine seeds with 'go mod download all' — that rewrites go.sum; use bare 'go mod download'"
    else
        note ok "seed does not mutate the committed pins"
    fi
fi

# 5. EVERY workflow that reaches this engine caches the module cache, not just
#    the one someone happened to be looking at. Two workflows build this aar and
#    a fix in one leaves the other failing on its own schedule, so the set is
#    derived from the tree: scripts that call the engine -> their project dir ->
#    the workflows whose WORK_DIR points at it.
mapfile -t CALLER_DIRS < <(
    grep -rl 'build-firestack\.sh' --include='*.sh' . 2>/dev/null \
      | grep -v "$ENGINE" \
      | xargs -r -n1 dirname | sed 's|^\./||' | sort -u
)
[ "${#CALLER_DIRS[@]}" -gt 0 ] || note FAIL "no script calls $ENGINE — did the engine get renamed?"

# A workflow reaches a caller dir in one of TWO ways, and both have to count.
# WORK_DIR is how an APP workflow names the directory it builds in. But a
# workflow can also just RUN one of the caller scripts by path, and
# ship-firestack-aar.yml does exactly that — it invokes
# libs/firewall/publish-firestack.sh directly and deliberately declares no
# WORK_DIR, because the generator would then rewrite its trigger paths from a
# gradle module map that cannot describe a Go source tree.
#
# Matching only on WORK_DIR therefore went BLIND to the third workflow that
# builds this aar the day it was added: it reported "libs/firewall has no
# shipping workflow of its own" and skipped the module-cache assertion entirely.
# That is the same shape as this whole tester going unrun before #203 — a guard
# that quietly checks nothing looks exactly like a guard that passes.
covered=0
for dir in "${CALLER_DIRS[@]}"; do
    found=0
    for wf in .github/workflows/*.yml; do
        reaches=0
        grep -qE "^[[:space:]]*WORK_DIR:[[:space:]]*$dir[[:space:]]*$" "$wf" && reaches=1
        # ...or the workflow EXECUTES a script in that dir. It must be an
        # execution, not a mention: the first draft of this matched any literal
        # occurrence of the path and immediately failed publish-gate-guard.yml,
        # which names lib-apks/build.sh in a COMMENT and builds no aar at all.
        # A guard that fails on workflows it was not asked about teaches people
        # to ignore it, so the pattern requires an interpreter or ./ in front of
        # the path and refuses YAML comment lines outright.
        for caller in "$dir"/*.sh; do
            [ -e "$caller" ] || continue
            # awk with index(), not a regex: a script path is full of dots and
            # slashes, and leaving them as metacharacters is how this kind of
            # check matches something it was not asked about and calls it a pass.
            grep -qE "^[[:space:]]*(run:[[:space:]]*)?(bash|sh|\./)" "$wf" || continue
            awk -v p="$caller" '
                /^[[:space:]]*#/            { next }
                index($0, p) == 0           { next }
                /(bash|sh|\.\/)[[:space:]]*[^[:space:]]*$/ { hit = 1 }
                index($0, "bash " p) || index($0, "sh " p) { hit = 1 }
                END                         { exit hit ? 0 : 1 }
            ' "$wf" && reaches=1
        done
        [ "$reaches" = 1 ] || continue
        found=1; covered=$((covered+1))
        if grep -q 'gomodcache' "$wf"; then
            note ok "${wf#.github/workflows/} (builds $dir) caches the Go module cache"
        else
            note FAIL "${wf#.github/workflows/} builds $dir but does not cache the Go module cache — it keeps hitting sum.golang.org"
        fi
    done
    [ "$found" = 1 ] || note ok "$dir has no shipping workflow of its own"
done
[ "$covered" -gt 0 ] || note FAIL "matched no workflow to any firestack caller — the WORK_DIR mapping broke"

# 6. Every tool a recipe installs at an EXPLICIT @version is, by construction,
#    outside go.mod — that is the only reason to write the version there — so the
#    `go mod download` seed cannot reach it and the engine must seed it SEPARATELY,
#    before GOPROXY=off. This is what went red in run 34446987088: go-patch-overlay
#    was pinned in build.json and never seeded, so its `go install ...@version` was
#    the one piece of resolution still running inside the offline build, where the
#    only outcome available to it was "module lookup disabled by GOPROXY=off".
#    Derived from the Makefiles, so the SECOND pinned tool is covered the day it is
#    added rather than the day it costs a ship run.
bindline=$(grep -nE '^[^#]*GOPROXY=off.*make ' "$ENGINE" 2>/dev/null | head -1 | cut -d: -f1)
for gm in "${GOMODS[@]}"; do
    mod=$(dirname "$gm"); mk="$mod/Makefile"
    [ -f "$mk" ] || continue
    # Recipe lines only (leading TAB), and only installs carrying an @version.
    while read -r pkg; do
        [ -n "$pkg" ] || continue
        # index(), not a regex: a package path is full of dots and slashes, and
        # letting them stay metacharacters is how this kind of check matches
        # something it was not asked about and calls it a pass. Line numbers
        # come from the file itself, not from a filtered stream, or the
        # ordering comparison below would be against renumbered lines.
        seedline=$(awk -v p="$pkg@" 'index($0, p) && $0 !~ /^[[:space:]]*#/ { print NR; exit }' "$ENGINE")
        if [ -z "$seedline" ]; then
            note FAIL "${mk#./} installs $pkg at a pinned version but $ENGINE never seeds it — GOPROXY=off will refuse it mid-build"
        elif [ -n "$bindline" ] && [ "$seedline" -ge "$bindline" ]; then
            note FAIL "$ENGINE seeds $pkg at line $seedline, at or after the offline build at line $bindline — too late to help"
        else
            note ok "engine seeds pinned build tool $pkg before the offline build"
        fi
    done < <(sed -n 's/^	.*go[[:space:]]\{1,\}install[[:space:]]\{1,\}\([^ 	@]\{1,\}\)@.*/\1/p' "$mk" | sort -u)
done

# 7. SEEDING THE MODULE IS NOT SEEDING THE TOOL, and check 6 cannot tell them
#    apart: it only asks whether the engine mentions the package before the
#    offline build. It said ok through runs 34462765453 and 34464514365, both of
#    which died anyway on
#
#        loading deprecation for github.com/felixge/go-patch-overlay:
#        module lookup disabled by GOPROXY=off
#
#    because the seed installed the binary into the DEFAULT GOBIN while the
#    recipe installs into its own ($(GOBIN) = $(CURDIR)/bin). The recipe
#    therefore still had work to do, ran inside GOPROXY=off, and asked the proxy
#    a question it was forbidden to answer. The phone stayed on an APK from
#    2026-09-09 for a day and a half.
#
#    What actually keeps resolution out of the offline build is make finding the
#    recipe's TARGET already present and newer than its prerequisite, so the
#    recipe never executes. That requires the seed to write the target binary
#    into the very directory the Makefile names. Assert both halves.
#
#    Derived from the Makefile, never hardcoded: rename the bin dir or add a
#    second tool and this check follows it.
#    SCOPED TO WHAT THE BUILD ACTUALLY REACHES. The Makefile also has an $(XGO)
#    recipe with the same shape, but it hangs off the linux/windows targets and
#    this build asks for `intra`. Demanding a seed for a tool no recipe will run
#    would be a failure nobody can act on, and a tester that cries about
#    unreachable code is one people learn to skim. So walk the prerequisite
#    graph from the target the engine really builds and check only what it needs.
for gm in "${GOMODS[@]}"; do
    mod=$(dirname "$gm"); mk="$mod/Makefile"
    [ -f "$mk" ] || continue

    # The Makefile's own GOBIN, e.g. `GOBIN=$(CURDIR)/bin` -> bin.
    gobin_leaf=$(awk -F'/' '/^GOBIN[[:space:]]*=/ { sub(/[[:space:]]*$/, "", $NF); print $NF; exit }' "$mk")
    [ -n "$gobin_leaf" ] || continue

    # The target the engine hands to make, read from the same build.json field
    # the engine reads it from — never a copy of the value.
    goal=$(jq -r '.firestack.build.make_target // empty' "$mod/../../../build.json" 2>/dev/null)
    [ -n "$goal" ] || continue

    # Transitive prerequisites of that goal, textually: targets and prerequisites
    # spell variables the same way ($(GOMOBILE)), so no expansion is needed to
    # match them against each other. Bounded rounds — a Makefile cycle must not
    # hang the suite.
    reach=$(awk -v goal="$goal" '
        /^[^\t#][^:]*:[^=]/ {
            split($0, kv, ":"); t = kv[1]; gsub(/^[ \t]+|[ \t]+$/, "", t)
            prereq[t] = prereq[t] " " kv[2]
        }
        END {
            seen[goal] = 1; frontier = goal
            for (round = 0; round < 12; round++) {
                next_frontier = ""
                n = split(frontier, cur, " ")
                for (i = 1; i <= n; i++) {
                    if (cur[i] == "" || !(cur[i] in prereq)) continue
                    m = split(prereq[cur[i]], deps, " ")
                    for (j = 1; j <= m; j++)
                        if (deps[j] != "" && !(deps[j] in seen)) {
                            seen[deps[j]] = 1; next_frontier = next_frontier " " deps[j]
                        }
                }
                if (next_frontier == "") break
                frontier = next_frontier
            }
            for (t in seen) print t
        }' "$mk")

    # Of the reachable targets, the ones whose recipe installs into GOBIN are
    # exactly the recipes that would resolve inside the offline build.
    while read -r target; do
        [ -n "$target" ] || continue
        while read -r tool; do
            [ -n "$tool" ] || continue
            # The engine must install this tool with GOBIN pointed at the
            # Makefile's bin dir. Matched on the SAME LINE, so a GOBIN set
            # elsewhere in the engine for another purpose cannot stand in for it.
            if awk -v t="$tool" '/^[[:space:]]*#/ { next }
                                 index($0, "go install") && index($0, t) && index($0, "GOBIN=") { found = 1 }
                                 END { exit !found }' "$ENGINE"; then
                note ok "engine seeds $tool into the Makefile's own GOBIN ($gobin_leaf/), so $target is already up to date and its recipe never runs"
            else
                note FAIL "$ENGINE seeds $tool without GOBIN=<the Makefile's $gobin_leaf/> — it lands in the default GOBIN, so make still rebuilds $target and does it inside GOPROXY=off"
            fi
        done < <(awk -v tgt="$target" '
            # index(), not a regex: a make target is spelled $(GOMOBILE), and
            # every one of those characters is a metacharacter. Matched as a
            # regex it silently matches nothing, which turns this whole check
            # into a green tick over an unread recipe.
            index($0, tgt ":") == 1 { inrecipe = 1; next }
            /^[^\t#]/ { inrecipe = 0 }
            inrecipe && /^\t/ && index($0, "GOBIN=") && index($0, "go install") {
                for (i = 1; i <= NF; i++)
                    if ($i == "install") { sub(/@.*/, "", $(i+1)); print $(i+1); break }
            }' "$mk" | sort -u)
    done <<< "$reach"
done

# 7. Seeding this module's own dependencies is NOT enough, and believing it was
#    cost every APK between 2026-09-09 17:14 and 2026-09-10. `gomobile bind`
#    resolves twice more on its own, inside the GOPROXY=off build:
#    `go list -m -tags=<tags> all` (x/mobile cmd/gomobile/bind.go:229-243) and
#    `go mod tidy` in the module it synthesises (bind_androidapp.go:383).
#
#    What made it cost a day and a half is that gomobile does not REPORT the
#    first failure: bind.go:240-243 turns the error into `return nil, nil` and
#    writeGoMod then writes a zero-byte go.mod, so the build dies in tidy with
#    "missing module declaration" — pointing at a file gomobile had just
#    emptied, naming neither GOPROXY nor the missing module.
FIRESTACK_CFG=ab_cloud-libs-shared/build.json
FIRESTACK_MK=ab_cloud-libs-shared/libs/firewall/firestack/Makefile

if [ -f "$ENGINE" ]; then
    bindline=$(grep -nE '^[^#]*GOPROXY=off.*make ' "$ENGINE" | head -1 | cut -d: -f1)

    # Command lines only, for the same reason the seed check above says so, plus
    # one this file learned the hard way: these steps LOG what they are about to
    # do, and those log strings quote the very command being searched for. A
    # detector that accepts them passes whether or not the command survives —
    # and the log line sorts BEFORE the command, so an ordering check built on
    # it is green by construction. Drop log/errlog lines before matching.
    engine_cmd() { grep -nE "$1" "$ENGINE" | grep -vE '^[0-9]+:[[:space:]]*(log|errlog)[[:space:]]'; }

    # 7a. The engine pre-resolves gomobile's `go list -m all` with the proxy up.
    graphseed=$(engine_cmd '^[^#]*go list -m .*all' | grep -v 'GOPROXY=off' | head -1 | cut -d: -f1)
    if [ -n "$graphseed" ] && [ -n "$bindline" ] && [ "$graphseed" -lt "$bindline" ]; then
        note ok "engine seeds the module graph gomobile's own 'go list -m all' walks"
    else
        note FAIL "engine never seeds gomobile's 'go list -m all' before the offline bind — gomobile will swallow the failure and write an empty go.mod"
    fi

    # 7b. There is a pre-flight that runs that resolution OFFLINE, before the
    #     bind, so the failure is named here instead of swallowed there.
    preflight=$(engine_cmd '^[^#]*GOPROXY=off[^#]*go list -m ' | head -1 | cut -d: -f1)
    if [ -n "$preflight" ] && [ -n "$bindline" ] && [ "$preflight" -lt "$bindline" ]; then
        note ok "engine pre-flights gomobile's resolution with GOPROXY=off before building"
    else
        note FAIL "engine has no offline pre-flight before the bind — the next missing module reappears as 'missing module declaration'"
    fi

    # 7c. THE PRE-FLIGHT MUST NOT TRUST THE EXIT STATUS ALONE. `go list` exiting
    #     0 with no output is exactly what bind.go mistakes for success, so a
    #     pre-flight that only checks `if ! ...` reproduces the bug it guards.
    if grep -qE '^[^#]*\[ -n "\$offline_list" \]' "$ENGINE"; then
        note ok "offline pre-flight treats empty output as failure, not success"
    else
        note FAIL "offline pre-flight checks only the exit status — an empty 'go list' is the exact shape gomobile mis-reads as success"
    fi

    # 7d. The aar smoke-test must not degrade to a no-op. It used to `log` and
    #     carry on when unzip was absent, which is a silent pass on any host
    #     without unzip — the one check between a hollow aar and a published APK.
    if grep -qE '^[^#]*unzip absent — skipping' "$ENGINE"; then
        note FAIL "engine skips the aar smoke-test when unzip is missing — a silent pass on the only check that inspects the aar"
    else
        note ok "aar smoke-test cannot silently skip itself"
    fi
fi

# 7e. bind_packages is DATA, and data drifts. The engine pre-resolves what
#     gomobile binds by standing up a replica module importing these packages;
#     if the Makefile's bind targets move and this list does not, the replica
#     seeds the wrong closure and the offline build fails for a reason nobody
#     will connect to a JSON file. Compare the two directly.
if [ -f "$FIRESTACK_CFG" ] && [ -f "$FIRESTACK_MK" ]; then
    cfg_pkgs=$(jq -r '.firestack.build.bind_packages[]? // empty' "$FIRESTACK_CFG" | sort)
    # Expand $(IMPORT_PATH) from the Makefile's own definition rather than
    # assuming the import path — the aar is vendored and upstream can rename it.
    mk_pkgs=$(awk -F= '
        $1 == "IMPORT_PATH"     { ip = $2 }
        $1 == "INTRA_BUILD_CMD" { line = $2 }
        END {
            gsub(/\$\(IMPORT_PATH\)/, ip, line)
            n = split(line, a, /[[:space:]]+/)
            for (i = 1; i <= n; i++) if (a[i] != "") print a[i]
        }' "$FIRESTACK_MK" | sort)

    if [ -z "$cfg_pkgs" ]; then
        note FAIL "build.json has no .firestack.build.bind_packages — the engine cannot pre-resolve what gomobile binds"
    elif [ "$cfg_pkgs" = "$mk_pkgs" ]; then
        note ok "build.json bind_packages matches the Makefile's bind targets"
    else
        note FAIL "build.json bind_packages has drifted from the Makefile's INTRA_BUILD_CMD:"
        printf '         build.json: %s\n' $cfg_pkgs
        printf '         Makefile:   %s\n' $mk_pkgs
    fi
else
    note FAIL "cannot compare bind_packages — $FIRESTACK_CFG or $FIRESTACK_MK is missing"
fi

[ "$fail" -eq 0 ] && echo "PASS — the firestack build resolves nothing; pins are committed and every caller is covered." \
                  || echo "FAIL — see above."
exit "$fail"
