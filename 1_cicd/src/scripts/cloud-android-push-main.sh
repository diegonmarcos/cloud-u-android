#!/usr/bin/env bash
# cloud-android-push-main — push the commit(s) a workflow just made on HEAD to main, surviving a busy main.
#
# Agents push to main every minute or two, so a bare `git push` from a CI job loses the race and fails the run
# (ship-superapp-data-regen did, 2026-10-22). This is the ONE implementation every workflow that pushes to main uses:
# fetch the new main, rebase the job's commit(s) onto it, push; on a lost race back off and repeat (5 tries).
# A rebase conflict is not retried (the same files moved under the job): it is aborted and fails, naming itself.
#
#   usage: bash 1_cicd/dist/scripts/cloud-android-push-main.sh        (run in the checkout holding the commit)
#   env:   PUSH_MAIN_TRIES (default 5), PUSH_MAIN_BACKOFF seconds (default 5, multiplied by the try number)
# The checkout may be shallow; fetch deepens only as far as the rebase needs. Commits pushed with GITHUB_TOKEN start
# no workflow, so this cannot loop.
set -u
tries="${PUSH_MAIN_TRIES:-5}"; backoff="${PUSH_MAIN_BACKOFF:-5}"
export GIT_COMMITTER_NAME="${GIT_COMMITTER_NAME:-${GIT_AUTHOR_NAME:-github-actions[bot]}}"
export GIT_COMMITTER_EMAIL="${GIT_COMMITTER_EMAIL:-${GIT_AUTHOR_EMAIL:-noreply@anthropic.com}}"
for try in $(seq 1 "$tries"); do
  if git push origin HEAD:main; then echo "push-main: pushed (try $try)"; exit 0; fi
  echo "push-main: try $try lost the race; rebasing onto the new main"
  sleep $((try * backoff))
  git fetch --no-tags --depth=50 origin main || continue
  if ! git rebase origin/main; then
    git rebase --abort 2>/dev/null || true
    echo "::error::push-main: the rebase onto the new main conflicts, so these files moved under this job; not retrying"
    exit 1
  fi
done
echo "::error::push-main: could not push after $tries tries - main kept moving"
exit 1
