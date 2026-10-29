#!/bin/bash
. "$(dirname "$0")/_lib.sh"
CMD="$(printf '%s' "$CMD" | sed -E 's/[0-9]*>&[0-9-]+//g; s/[0-9]*>>?\s*[^[:space:];&|]+//g')"
if printf '%s' "$CMD" | grep -Eq '(^|[;&|(]\s*)git(\s+-C\s+\S+)?\s+push(\s|$)'; then
  seg="$(printf '%s' "$CMD" | grep -Eo 'git(\s+-C\s+\S+)?\s+push[^;&|]*' | head -1)"
  case "$seg" in *--delete*|*" -d "*) exit 0;; esac
  if printf '%s' "$seg" | grep -Eq '(--all|--mirror|--tags)'; then block main-only-push 'push to main only (no --all/--mirror/--tags).'; fi
  args="$(printf '%s' "$seg" | sed -E 's/^git(\s+-C\s+\S+)?\s+push//' | tr ' ' '\n' | grep -Ev '^(-.*|)$')"
  # first positional is the remote; the rest are refspecs
  refs="$(printf '%s\n' "$args" | tail -n +2)"
  for r in $refs; do
    case "$r" in main|HEAD:main|refs/heads/main|HEAD:refs/heads/main|+main|+HEAD:main) ;; :*) ;; *) block main-only-push "refspec '$r' is not main. The owner works on main only: git push origin main.";; esac
  done
fi
exit 0
