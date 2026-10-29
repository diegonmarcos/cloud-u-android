#!/bin/bash
# Blocks `git commit` / `git push` that would wipe a repo (empty or no-checkout index): a commit that drops >30% of the
# parent's tracked files or leaves <10% of them. Net file count, so moves/renames pass. Parents under 10 files are ignored.
# Override: put [mass-delete-ok] in the commit message.
. "$(dirname "$0")/_lib.sh"
MIN=10
D="$(printf '%s' "$CMD" | grep -Eo 'git\s+-C\s+\S+' | head -1 | awk '{print $3}')"
[ -n "$D" ] || D="$(printf '%s' "$CMD" | grep -Eo '(^|[;&|(]\s*)cd\s+[^;&|[:space:]]+' | head -1 | awk '{print $NF}')"
[ -n "$D" ] || D=.
wipe() { # parent_count new_count -> 0 when the drop is a wipe
  [ "$1" -ge "$MIN" ] && { [ $(( ($1 - $2) * 100 )) -gt $(( $1 * 30 )) ] || [ $(( $2 * 100 )) -lt $(( $1 * 10 )) ]; }
}
msg() { echo "check 'git status' and the index (an empty or no-checkout index commits as a mass delete). Restore with 'git reset' / 'git checkout HEAD -- .', or add [mass-delete-ok] to the commit message if intended."; }
if printf '%s' "$CMD" | grep -Eq '(^|[;&|(]\s*)git(\s+-C\s+\S+)?\s+commit(\s|$)'; then
  if ! printf '%s' "$CMD" | grep -Fq '[mass-delete-ok]' && git -C "$D" rev-parse -q --verify HEAD >/dev/null 2>&1; then
    p=$(git -C "$D" ls-tree -r HEAD | wc -l); n=$(git -C "$D" ls-files | wc -l)
    if printf '%s' "$CMD" | grep -Eq 'commit\s+(.*\s)?-[a-zA-Z]*a|--all'; then n=$(( n - $(git -C "$D" ls-files --deleted | wc -l) )); fi
    wipe "$p" "$n" && block no-mass-delete "this commit would take the repo from $p to $n tracked files. $(msg)"
  fi
fi
if printf '%s' "$CMD" | grep -Eq '(^|[;&|(]\s*)git(\s+-C\s+\S+)?\s+push(\s|$)'; then
  for c in $(git -C "$D" rev-list --max-count=200 HEAD --not --remotes 2>/dev/null); do
    par=$(git -C "$D" rev-parse -q --verify "$c^" 2>/dev/null) || continue
    git -C "$D" log -1 --format=%B "$c" | grep -Fq '[mass-delete-ok]' && continue
    p=$(git -C "$D" ls-tree -r "$par" | wc -l); n=$(git -C "$D" ls-tree -r "$c" | wc -l)
    wipe "$p" "$n" && block no-mass-delete "commit ${c:0:9} would push the repo from $p to $n tracked files. $(msg) Fix the commit (git reset --soft HEAD~1) before pushing."
  done
fi
exit 0
