#!/bin/bash
. "$(dirname "$0")/_lib.sh"
if printf '%s' "$CMD" | grep -Eq '(^|[;&|(]\s*)git(\s+-C\s+\S+)?\s+(checkout\s+(-[A-Za-z]*b|-B|--orphan)|switch\s+(-[A-Za-z]*c|-C|--create|--orphan)|worktree\s+add\s+([^;&|]*\s)?-[bB]\s|branch\s+(-[cCm]\s+)?[A-Za-z0-9_./-]+(\s|$)|branch\s+(--copy|--move))'; then
  if ! printf '%s' "$CMD" | grep -Eq 'git(\s+-C\s+\S+)?\s+branch\s+(-d|-D|--delete|-r|-a|-v|--list|--show-current|--merged|--no-merged|-l)\b'; then
    block no-branch-create 'no branches: work on main only and push direct to main (owner rule). Use a worktree on disk with main checked out if you need isolation.'
  fi
fi
exit 0
