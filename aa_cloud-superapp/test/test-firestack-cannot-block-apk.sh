#!/usr/bin/env bash
# Tester: firestack must never be able to fail the SuperApp APK job.
#
# THE FAILURE THIS EXISTS FOR. On 2026-09-09 17:14, commit 9a0ad74b8 changed how
# the firestack aar resolved its Go dependencies. The aar build failed on every
# run afterwards, and because that build ran INSIDE the APK job — build.sh's
# run_gradle called _ensure_firestack before every single gradle invocation —
# `Build APK (engine)` died with it, `Publish to GitHub Releases` was skipped,
# and the owner's phone served the APK from 16:35 for a day and a half. Roughly
# forty commits of unrelated work (theme fixes, Spanish translations, crash
# fixes, the control panel) reached nobody. A Go library the owner does not
# maintain, wrapping a project the owner does not own, had veto power over every
# Android change in the fleet.
#
# The owner's ruling: firestack "should be a lib that would never block superapp
# release". The aar is now a prebuilt artifact, pinned by tag and per-ABI
# checksum, fetched and verified — never compiled on the way to an APK.
#
# WHAT THIS FILE GUARDS is that the coupling stays removed. The genuinely
# convincing proof is the live experiment (break firestack on purpose, watch the
# APK still build and publish), and that was run. But an experiment proves one
# afternoon; these assertions are what stop the coupling growing back the next
# time somebody debugging a missing aar reaches for the obvious one-line "fix"
# of calling the builder from the fetcher.
#
# EVERY ASSERTION BELOW WAS WATCHED FAILING ON PURPOSE before being trusted —
# by mutating a copy of the file it reads and confirming it went red for the
# right reason. This repository has shipped an assertion comparing an expression
# to itself, a pipeline's status read through `tail`, a `case` whose "[NEW]"
# was a one-character bracket expression, and four testers that passed only
# because ripgrep was absent. So: no `rg` here, no pipelines whose exit status
# is read through another command, and a missing input is a FAILURE, never a skip.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
FIREWALL="$ROOT/ab_cloud-libs-shared/libs/firewall"
SHARED_BJ="$ROOT/ab_cloud-libs-shared/build.json"
APP_BUILD="$ROOT/aa_cloud-superapp/build.sh"
LIBAPKS_BUILD="$ROOT/ab_cloud-libs-shared/lib-apks/build.sh"
WORKFLOW="$ROOT/1_cicd/src/cicd/ship-cloud-superapp.yml"

fails=0
ok()   { printf 'ok     %s\n' "$*"; }
fail() { printf 'FAIL: %s\n' "$*"; fails=$((fails + 1)); }

# A required input that is absent must be a failure. If one of these moves, this
# tester has to go red and be updated — not quietly assert nothing and pass.
for f in "$FIREWALL/fetch-firestack.sh" "$FIREWALL/build-firestack.sh" \
         "$FIREWALL/publish-firestack.sh" "$SHARED_BJ" "$APP_BUILD" \
         "$LIBAPKS_BUILD" "$WORKFLOW"; do
    [ -f "$f" ] || { fail "required file missing: ${f#"$ROOT"/}"; }
done
[ "$fails" -eq 0 ] || { printf '\n%s\n' "firestack decoupling: $fails precondition(s) missing"; exit 1; }

command -v jq >/dev/null 2>&1 || { fail "jq is required (a tester whose tool is missing must not pass)"; exit 1; }

# ── 1. the gradle path fetches; it does not build ──────────────────
#
# _ensure_firestack is the function run_gradle calls before every gradle
# invocation — it IS the APK's critical path. It must reach fetch-firestack.sh
# and must not reach build-firestack.sh.
#
# Read as the BODY of the function rather than the whole file, because
# build.sh legitimately still mentions build-firestack.sh elsewhere (the
# `firestack` command builds from source on purpose). Grepping the file would
# either pass wrongly or fail wrongly depending on which mention it found.
_ensure_body() {
    awk '/^_ensure_firestack\(\)/ { inside = 1 }
         inside                   { print }
         inside && /^}/           { exit }' "$1"
}

for consumer in "$APP_BUILD" "$LIBAPKS_BUILD"; do
    rel="${consumer#"$ROOT"/}"
    body="$(_ensure_body "$consumer")"
    if [ -z "$body" ]; then
        fail "$rel: no _ensure_firestack function found — has the gradle path been rewired?"
        continue
    fi
    case "$body" in
        *fetch-firestack.sh*) ok "$rel: _ensure_firestack fetches the pinned aar" ;;
        *) fail "$rel: _ensure_firestack does not call fetch-firestack.sh" ;;
    esac
    case "$body" in
        *build-firestack.sh*)
            fail "$rel: _ensure_firestack calls build-firestack.sh — the APK would compile Go again,"
            fail "      which is exactly the coupling that cost the fleet a day and a half on 2026-09-09" ;;
        *) ok "$rel: _ensure_firestack does not build firestack from source" ;;
    esac
done

# ── 2. the fetcher has no source-build fallback ────────────────────
#
# The single most important assertion here. A fallback would look like a
# kindness and would silently restore the whole coupling: the APK would compile
# Go again the moment a download hiccuped, and the next outage would be
# indistinguishable from the last one.
#
# Comments in fetch-firestack.sh discuss build-firestack.sh at length and must
# not trip this, so comment lines are stripped before looking for a call.
fetch_code="$(awk '{ sub(/[[:space:]]*#.*$/, ""); print }' "$FIREWALL/fetch-firestack.sh")"
case "$fetch_code" in
    *build-firestack*)
        fail "fetch-firestack.sh invokes build-firestack.sh — a source-build fallback puts the Go"
        fail "      build back on the APK's critical path. Fail loudly on a bad pin instead." ;;
    *) ok "fetch-firestack.sh has no source-build fallback" ;;
esac

# A wrong checksum must be fatal. Asserted by shape: the comparison exists and
# its failure branch exits non-zero.
case "$fetch_code" in
    *'"$GOT" != "$WANT"'*) ok "fetch-firestack.sh compares the downloaded checksum to the pin" ;;
    *) fail "fetch-firestack.sh does not compare the fetched sha256 against the pinned one" ;;
esac

# ── 2b. the engines actually parse ─────────────────────────────────
#
# `bash -n` on every script on this path. Added because writing this decoupling
# produced exactly this bug: fetch-firestack.sh opened an `if ... then` and
# closed it with `}`. Nothing in the assertions above reads shell grammar, so
# the file would have passed every one of them and then died on the runner
# twenty minutes into a build, in a step whose name says "Build APK".
for engine in "$FIREWALL/fetch-firestack.sh" "$FIREWALL/publish-firestack.sh" \
              "$ROOT/1_cicd/src/scripts/cloud-android-firestack-pin-health.sh"; do
    rel="${engine#"$ROOT"/}"
    if [ ! -f "$engine" ]; then
        fail "$rel: missing"
    elif err="$(bash -n "$engine" 2>&1)"; then
        ok "$rel: parses"
    else
        fail "$rel: shell syntax error -- $err"
    fi
done

# ── 3. the pin is complete for every ABI the bind produces ─────────
#
# The aar is per-ABI (measured on the 2026-09-09 green run: 6.8 MB arm64,
# 7.4 MB amd64), so a pin covering only one of them would fail exactly one
# matrix leg — the confusing kind of half-outage.
template="$(jq -r '.firestack.artifact.asset_template // empty' "$SHARED_BJ")"
if [ -z "$template" ]; then
    fail "build.json declares no .firestack.artifact.asset_template"
else
    ok "asset_template declared: $template"
fi

variants="$(jq -r '.firestack.build.gomobile_targets | keys[] | select(. != "")' "$SHARED_BJ")"
[ -n "$variants" ] || fail "build.json declares no gomobile_targets variants"

for v in $variants; do
    asset="$(jq -r --arg v "$v" '.firestack.artifact.assets[$v].asset // empty' "$SHARED_BJ")"
    sum="$(jq -r --arg v "$v" '.firestack.artifact.assets[$v].sha256 // empty' "$SHARED_BJ")"
    if [ -z "$asset" ] || [ -z "$sum" ]; then
        fail "no pinned aar for ABI variant '$v' (need .firestack.artifact.assets[\"$v\"].{asset,sha256})"
        continue
    fi
    # 64 hex characters, checked by shape. An empty or truncated value would
    # otherwise sail through and only fail on the runner.
    case "$sum" in
        [0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f]\
[0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f]\
[0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f]\
[0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f]\
[0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f]\
[0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f]\
[0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f]\
[0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f]) ;;
        *) fail "ABI '$v': sha256 is not 64 lowercase hex characters: '$sum'"; continue ;;
    esac
    # The publisher derives the name from the template; a hand-edited pin that
    # disagrees would 404 on the runner, twenty minutes into a build.
    want="${template//\{variant\}/$v}"
    if [ "$asset" = "$want" ]; then
        ok "ABI '$v': pinned $asset ($sum)"
    else
        fail "ABI '$v': asset '$asset' does not match asset_template's '$want'"
    fi
done

# ── 4. upstream tracking is gone (a clone, not a fork) ─────────────
for key in repo ref tracker; do
    val="$(jq -r --arg k "$key" '.firestack[$k] // empty' "$SHARED_BJ")"
    if [ -n "$val" ]; then
        fail ".firestack.$key is back ('$val') — this tree is an owned clone and tracks no upstream"
    else
        ok ".firestack.$key absent (owned clone, tracks no upstream)"
    fi
done

# The licence must survive the clone. Owning the working copy is not owning the
# copyright, and the fleet has already had to stop a build over an assumed licence.
if [ -f "$FIREWALL/firestack/LICENSE" ]; then
    ok "firestack/LICENSE still present (MPL-2.0 attribution intact)"
else
    fail "firestack/LICENSE is missing — cloning does not transfer copyright"
fi
if [ "$(jq -r '.firestack.license // empty' "$SHARED_BJ")" = "MPL-2.0" ]; then
    ok ".firestack.license is still MPL-2.0"
else
    fail ".firestack.license is no longer MPL-2.0"
fi

# ── 5. the publish guard must not have been softened ───────────────
#
# That step is the only reason the 2026-09-09 outage was ever noticed. This
# change means the APK job should stop being ABLE to fail that way — it must
# never mean the check that reports it got weakened. Guarded here because this
# task is exactly the kind that would be tempted to touch it.
guard_line='Devices are still being'
case "$(cat "$WORKFLOW")" in
    *"$guard_line"*) ok "the 'run that published nothing must not be green' guard is still in place" ;;
    *) fail "the publish guard's message is gone from ship-cloud-superapp.yml — it must not be softened" ;;
esac

# ── 6. firestack health reports, it does not veto ──────────────────
#
# The reporting step added alongside this decoupling must stay non-fatal. If it
# ever became a gate, firestack would have veto power over the APK again — by a
# different route, with the same result for the owner's phone.
health_step="$(awk '/^      - name: firestack pin health/ { inside = 1 }
                    inside                                { print }
                    inside && /^      - name: / && ++seen > 1 { exit }' "$WORKFLOW")"
if [ -z "$health_step" ]; then
    fail "ship-cloud-superapp.yml has no 'firestack pin health' step — the quiet failure is unreported"
else
    case "$health_step" in
        *"continue-on-error: true"*)
            ok "the firestack health step is continue-on-error (reports, never vetoes)" ;;
        *)
            fail "the firestack pin health step is NOT continue-on-error — a reporter has become a gate," \
                 "which is how firestack got veto power over the APK in the first place" ;;
    esac
fi

printf '\n'
if [ "$fails" -eq 0 ]; then
    printf '%s\n' "firestack decoupling: all assertions pass — a red firestack cannot fail the APK job"
    exit 0
fi
printf '%s\n' "firestack decoupling: $fails assertion(s) failed"
exit 1
