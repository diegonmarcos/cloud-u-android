# ─── GENERATED: do not edit — edit 1_cicd/src/scripts/cloud-android-source-identity.sh ───
#!/bin/sh
# ╔══════════════════════════════════════════════════════════════════╗
# ║ cloud-android-source-identity                                    ║
# ║                                                                  ║
# ║ Deterministic identity of ONE app's build inputs.                ║
# ╚══════════════════════════════════════════════════════════════════╝
#
# Android builds are not byte-reproducible: the same source rebuilt gives a
# different APK, a different sha256 sidecar and a different OCI digest. The
# Constellation store compares exactly those, so any rebuild — including the
# ~10 cascade rebuilds a shared-lib touch triggers — showed up on every phone
# in the fleet as an "update" with nothing in it.
#
# The fix is to stop comparing OUTPUT bytes and start comparing INPUT identity:
# hash the git object ids of everything that can change the APK, and publish
# only when that hash moves. git already stores a content hash per directory
# (the tree sha), so this costs one `rev-parse` per path and is exact — no
# timestamps, no run ids, no file walking.
#
# WHAT IS IN THE IDENTITY
#   Exactly the `on: push: paths` list of the app's own ship workflow, plus the
#   app directory and that workflow file, whether or not the list names them.
#
#   Reading the trigger list rather than re-deriving one is the whole safety
#   property: the gate can only ever skip a build that something started, so
#   the two lists must not be able to disagree. If they were computed
#   separately, any path watched but not hashed would let a REAL change trigger
#   a build the gate then skipped — a silently suppressed update, strictly
#   worse than the phantom updates this replaces. The workflow generator
#   manages that list from each build.json module map, so both stay true as the
#   module maps evolve.
#
#   Concretely that covers: the app's own tree (its source, its build.json — so
#   a signing config, version, ABI filter or release-name change republishes —
#   and its VENDORED build.sh, the file CI actually executes), every shared
#   library directory it compiles by reference out of ab_cloud-libs-shared/,
#   and the workflow itself with its SDK/JDK pins and matrix.
#
# WHAT IS DELIBERATELY NOT IN IT
#   run ids, timestamps, the commit sha itself, and the 1_cicd engine SOURCES.
#   The engine source is excluded because it is not what runs: the vendored
#   <app>/build.sh is, and that is already inside the app tree at (1). The
#   generator's parity check is what guarantees the two agree, so a
#   parity-fixing sync lands in the app tree and correctly republishes.
#
# A NARROWER SCOPE THAN THE WHOLE APP: --paths-from FILE
#   One ship workflow publishes MANY assets: ship-cloud-libs builds every
#   module under ab_cloud-libs-shared/libs/ as its own APK. Hashing the app's
#   whole trigger list gives all 24 of them the SAME identity, so a change to
#   one module republishes every one of them - which is what the fleet saw on
#   2026-09-09, when a fix to libs/updater put 24 unchanged library APKs on the
#   release and six update prompts on the owner's phone.
#
#   With --paths-from, the caller supplies the exact input set for ONE asset
#   and the identity is scoped to it. The caller owes the same safety property
#   the workflow list gives by construction: the UNION of the path sets it
#   passes for all of its assets must cover the workflow's whole trigger list,
#   or a real change could trigger a build that every asset's gate then skips.
#   ab_cloud-libs-shared/lib-apks/build.sh derives its sets from this script's
#   own `paths` output for exactly that reason, and
#   1_cicd/src/scripts/test/publish-gate-blast-radius.test.sh asserts the
#   union property rather than trusting it.
#
# USAGE
#   cloud-android-source-identity.sh paths   <app-dir>   # inputs, one per line
#   cloud-android-source-identity.sh explain <app-dir>   # per-path object ids
#   cloud-android-source-identity.sh compute <app-dir>   # the 64-hex identity
#     ... [--paths-from FILE]                            # scope to one asset
set -eu

ROOT="${CLOUD_ANDROID_ROOT:-$(_d="$(cd "$(dirname "$0")" && pwd)"; while [ "$_d" != "/" ] && [ ! -e "$_d/.git" ]; do _d="$(dirname "$_d")"; done; printf '%s' "$_d")}"

CMD="${1:-}"
APP="${2:-}"
APP="${APP%/}"
if [ $# -ge 2 ]; then shift 2; else shift $#; fi

PATHS_FROM=""
while [ $# -gt 0 ]; do
    case "$1" in
        --paths-from) PATHS_FROM="${2:-}"; shift 2 ;;
        *) echo "unknown option: $1" >&2; exit 2 ;;
    esac
done

[ -n "$CMD" ] && [ -n "$APP" ] || {
    echo "usage: $(basename "$0") paths|explain|compute <app-dir> [--paths-from FILE]" >&2; exit 2; }

# Checked HERE, not where the file is read. `compute` runs `_explain |
# sha256sum`, which puts _paths in a subshell, so an `exit 3` down there kills
# the subshell and leaves sha256sum to hash the empty set and return 0 - a
# confident 64-hex identity for no inputs at all, which every gate would then
# compare equal on the next run and skip forever. This script already carries
# that scar in _external; the guard belongs before the first pipe.
[ -z "$PATHS_FROM" ] || [ -f "$PATHS_FROM" ] || {
    echo "$APP: --paths-from names no such file: $PATHS_FROM" >&2; exit 3; }
[ -d "$ROOT/$APP" ] || { echo "no such app dir: $APP (root=$ROOT)" >&2; exit 2; }

# ── the app's ship workflow ────────────────────────────────────────
# Found by its declared WORK_DIR, not by filename: two lib aggregators build a
# directory under ab_cloud-libs-shared/ whose name does not match their slug.
# PUBLISH_GATE_WORKFLOW overrides.
_workflow() {
    if [ -n "${PUBLISH_GATE_WORKFLOW:-}" ]; then printf '%s' "$PUBLISH_GATE_WORKFLOW"; return 0; fi
    for f in "$ROOT"/1_cicd/src/cicd/ship-*.yml; do
        [ -e "$f" ] || continue
        if grep -qx "  WORK_DIR: $APP" "$f"; then
            printf '%s' "1_cicd/src/cicd/$(basename "$f")"; return 0
        fi
    done
    printf '%s' "1_cicd/src/cicd/ship-${APP#*_}.yml"
}

# ── the input path set ─────────────────────────────────────────────
_paths() {
    # An explicit set replaces the derivation entirely - including the app dir,
    # which for a per-asset scope is the harness shared by every asset and is
    # supplied by the caller when it belongs. Refusing a missing file rather
    # than falling back keeps a typo from silently widening the scope back to
    # the whole app, which would look like it worked.
    if [ -n "$PATHS_FROM" ]; then
        grep -v '^[[:space:]]*$' "$PATHS_FROM" || true
        return 0
    fi

    printf '%s\n' "$APP"

    # Fail loud rather than hash a short list. An identity that quietly omits
    # the shared libs would let the gate skip a real update — strictly worse
    # than the phantom updates it exists to stop.
    wf="$(_workflow)"
    [ -f "$ROOT/$wf" ] || {
        echo "$APP: no ship workflow found ($wf) — refusing to compute a partial identity" >&2
        exit 3
    }
    printf '%s\n' "$wf"

    # The workflow's `on: push: paths` list, minus the glob tail. A dir entry
    # hashes as its git tree, a file entry as its blob — so `ac_cloud-nav/**`
    # and `ac_cloud-nav` are the same object either way.
    awk '
        /^    paths:$/            { inblock = 1; next }
        inblock && /^      - "/   { gsub(/^      - "|"$/, ""); print; next }
        inblock && /^      /      { next }
        inblock && NF             { exit }
    ' "$ROOT/$wf" |
    while IFS= read -r e; do
        e="${e%/\*\*}"; e="${e%/\*}"; e="${e%\*\*}"; e="${e%/}"
        [ -n "$e" ] && printf '%s\n' "$e"
    done

    return 0
}

# ── external inputs: artifacts this APK bakes in from ANOTHER repo ──
# Declared as data in build.json::build.external_inputs[] — {repo, tag, asset}.
#
# WHY THIS EXISTS (2026-08-31): cloud-watchdog ships my-watchdog, the panel and
# the app shell, all downloaded from the cloud-u-linux rolling release at build
# time. None of it is in this repo, so the identity above could not see it: the
# panel was rebuilt with fixes, this app's source had not moved, and the gate
# skipped publishing — forever. The phone kept an APK days behind while every
# rebuild agreed it was current.
_external() {
    bj="$ROOT/$APP/build.json"
    [ -f "$bj" ] || return 0
    command -v jq >/dev/null 2>&1 || return 0
    # A `while` fed by a PIPE runs in a subshell, so the `exit 3` below would
    # kill only that subshell and leave the caller hashing a short list — the
    # silent partial identity this guard exists to prevent. A heredoc keeps the
    # loop in this shell, where exit means exit.
    _list="$(jq -r '(.build.external_inputs // [])[]
           | select(type=="object" and .repo != null and .tag != null and .asset != null)
           | "\(.repo)\t\(.tag)\t\(.asset)"' "$bj" 2>/dev/null)"
    [ -n "$_list" ] || return 0
    while IFS="$(printf '\t')" read -r repo tag asset; do
        [ -n "$repo" ] && [ -n "$tag" ] && [ -n "$asset" ] || continue
        id="$(gh release view "$tag" --repo "$repo" \
                --json assets \
                --jq "[.assets[]|select(.name==\"$asset\")|\"\(.size):\(.updatedAt)\"][0] // empty" \
              2>/dev/null)"
        [ -n "$id" ] || {
            echo "$APP: cannot read $asset from $repo@$tag — refusing to compute a partial identity" >&2
            exit 3
        }
        printf '%s  external:%s@%s/%s\n' "$id" "$repo" "$tag" "$asset"
    done <<EOF
$_list
EOF
}

# ── per-path git object ids ────────────────────────────────────────
# `git rev-parse HEAD:<path>` yields the TREE sha for a directory and the BLOB
# sha for a file — one content hash for an arbitrarily deep subtree, already
# computed and stored by git. A path git does not know (untracked, or removed)
# hashes as the literal "missing" so its disappearance still moves the
# identity instead of silently hashing to the same value.
# ── mirrors identify by their PIN, not by their directory ──────────
# A mirror does not build anything: it downloads an upstream artifact,
# verifies it against `upstream.sha256` and republishes those exact bytes.
# Its output is therefore a pure function of the pin, and hashing its
# directory asks the wrong question — every README fix, every doc edit, every
# repo-wide touch moves the tree sha and republishes an APK that is
# byte-identical to the one already there.
#
# That is not theoretical. On 2026-09-01 a repo-wide restore commit rewrote
# ac_cloud-sheets/, the tree sha moved, and 250 MB of unchanged Collabora
# Office went through GHCR, through a GitHub release, and down onto every
# phone as an "update" — for an app nobody had touched.
#
# Declared per app as `build.json::source_identity.mode = "pin"`, so the rule
# lives with the app it describes and any future mirror gets it by saying so.
_pin_identity() {
    bj="$ROOT/$APP/build.json"
    [ -f "$bj" ] || return 1
    command -v jq >/dev/null 2>&1 || return 1
    [ "$(jq -r '.source_identity.mode // empty' "$bj" 2>/dev/null)" = "pin" ] || return 1
    # The whole `upstream` object, compactly and with keys sorted, so the
    # identity moves when the pin moves and stays put when it does not.
    # Sorted because a reformat of build.json must not read as a new release.
    jq -Sc '.upstream // {}' "$bj"
}

_explain() {
    # A pin identity describes the WHOLE app, so it cannot answer a question
    # scoped to one of its assets. Spelled as an `if` rather than folded into
    # the `&&` chain below it: under `set -eu` an AND-OR list that ends false
    # is itself a failing statement, so the compact spelling exits the script
    # instead of falling through to the path hashing.
    if [ -z "$PATHS_FROM" ]; then
        _pin_identity && return 0
    fi
    _paths | LC_ALL=C sort -u | while IFS= read -r p; do
        h="$(git -C "$ROOT" rev-parse "HEAD:$p" 2>/dev/null || printf 'missing')"
        printf '%s  %s\n' "$h" "$p"
    done
    # Sorted with the rest so the order of the declaration cannot change the
    # identity of an otherwise identical build.
    _external | LC_ALL=C sort -u
}

case "$CMD" in
    paths)   _paths | LC_ALL=C sort -u ;;
    explain) _explain ;;
    compute) _explain | sha256sum | cut -d' ' -f1 ;;
    *)       echo "unknown command: $CMD" >&2; exit 2 ;;
esac
