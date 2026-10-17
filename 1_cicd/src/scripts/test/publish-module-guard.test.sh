#!/usr/bin/env bash
# publish-module-guard.test — prove the guard fails, not just that it runs.
# Every case BREAKS a throwaway copy in one specific way and demands the guard
# notice. The working tree is never touched.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
GUARD="1_cicd/src/scripts/cloud-android-publish-module-guard.py"
FAILURES=0
ok()   { printf 'ok     %s\n' "$1"; }
fail() { printf 'FAIL   %s\n' "$1"; FAILURES=$((FAILURES + 1)); }
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/pristine"
cp -R "$ROOT/1_cicd" "$WORK/pristine/1_cicd"

run_guard() { ( cd "$1" && CLOUD_ANDROID_ROOT="$1" python3 "$GUARD" ) >"$1/out.txt" 2>&1; }
fresh() { rm -rf "$WORK/c"; mkdir "$WORK/c"; cp -R "$WORK/pristine/1_cicd" "$WORK/c/1_cicd"; }

# 0. the untouched tree passes
fresh; if run_guard "$WORK/c"; then ok "pristine tree passes"; else fail "pristine tree fails: $(cat "$WORK/c/out.txt")"; fi

expect_fail() { # name needle
    if run_guard "$WORK/c"; then fail "$1: guard passed on a broken tree"
    elif grep -q "$2" "$WORK/c/out.txt"; then ok "$1"
    else fail "$1: failed for the wrong reason: $(cat "$WORK/c/out.txt")"; fi
}

fresh; W=1_cicd/src/cicd/ship-cloud-notes.yml
printf '      - run: docker push ghcr.io/diegonmarcos/x:latest\n' >> "$WORK/c/$W"
expect_fail "a new docker push in a workflow" "$W"

fresh; printf '      - run: oras push ghcr.io/o/x:latest f\n' >> "$WORK/c/1_cicd/src/cicd/ship-cloud-notes.yml"
expect_fail "a new oras push in a workflow" "outside the publish module"

fresh; printf '\ngh release upload latest a.apk\n' >> "$WORK/c/1_cicd/src/scripts/cloud-notes-engine.sh"
expect_fail "a new gh release upload in an engine script" "cloud-notes-engine.sh"

fresh; printf '#!/bin/sh\ndocker login ghcr.io\n' > "$WORK/c/1_cicd/src/scripts/cloud-brand-new.sh"
expect_fail "a brand-new script that logs in to ghcr" "cloud-brand-new.sh"

fresh; printf '      - run: echo "${{ secrets.GHCR_TOKEN || secrets.GITHUB_TOKEN }}" | x\n' >> "$WORK/c/1_cicd/src/cicd/ship-cloud-notes.yml"
expect_fail "the PAT fallback pattern comes back" "ship-cloud-notes.yml"

fresh; sed -i '0,/secrets\.GITHUB_TOKEN }}" | oras login/s//secrets.GHCR_TOKEN || secrets.GITHUB_TOKEN }}" | oras login/' "$WORK/c/1_cicd/src/cicd/ship-cloud-c3.yml"
expect_fail "a GHCR_TOKEN || fallback in an existing push login" "GHCR_TOKEN ||. fallback"

fresh; sed -i 's/default: \${{ github.token }}/default: ${{ secrets.GHCR_TOKEN }}/' "$WORK/c/1_cicd/src/actions/publish-ghcr/action.yml"
expect_fail "the module defaults to a PAT" "module"

fresh; sed -i 's/org.opencontainers.image.source/org.opencontainers.image.xsource/g' "$WORK/c/1_cicd/src/actions/publish-ghcr/publish.sh"
expect_fail "the module loses the source label" "lost"

fresh; sed -i 's/verify_visibility/skip_visibility/g' "$WORK/c/1_cicd/src/actions/publish-ghcr/publish.sh"
expect_fail "the module loses the visibility check" "lost"

fresh; rm -rf "$WORK/c/1_cicd/src/actions/publish-ghcr"
expect_fail "the module is deleted" "module missing"

fresh; python3 - "$WORK/c/1_cicd/src/data/publish-module-baseline.json" <<'P'
import json,sys
d=json.load(open(sys.argv[1])); k=sorted(d["files"])[0]; d["files"][k]+=1
json.dump(d,open(sys.argv[1],"w"))
P
expect_fail "a stale (too high) baseline cannot hide a migration" "lower it"

fresh; printf '      - uses: ./.github/actions/publish-ghcr\n        with:\n          token: ${{ secrets.GHCR_TOKEN }}\n' >> "$WORK/c/1_cicd/src/cicd/ship-cloud-notes.yml"
expect_fail "a caller hands the module a PAT" "non-GITHUB_TOKEN"

fresh; printf '      - run: oras push ghcr.io/o/x:latest f\n' >> "$WORK/c/1_cicd/src/cicd/ghcr-delete-package.yml"
expect_fail "the delete workflow may not push" "exempt workflow may not push"

fresh; sed -i 's/in cloud-notes-web|/in cloud-notes-web|cloud-other|/' "$WORK/c/1_cicd/src/cicd/ghcr-delete-package.yml"
expect_fail "the delete allowlist cannot grow" "allowlist grew"

fresh; printf '          X: ${{ secrets.GHCR_TOKEN }}\n' >> "$WORK/c/1_cicd/src/cicd/ghcr-visibility-audit.yml"
expect_fail "the audit may not spread the PAT" "may only appear"

[ "$FAILURES" -eq 0 ] && echo "publish-module-guard.test: all cases ok" || { echo "publish-module-guard.test: $FAILURES FAILED"; exit 1; }
