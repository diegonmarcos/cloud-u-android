#!/bin/bash
. "$(dirname "$0")/_lib.sh"
if printf '%s' "$CMD" | grep -Eq '(^|[;&|(`]|\s)(pkill|pgrep)\s([^;&|]*\s)?-[A-Za-z]*f'; then
  block no-pkill-pgrep-f 'pkill -f / pgrep -f matches the shell'"'"'s own command line and kills the session (exit 144). Find the PID with ps, then kill <pid>.'
fi
exit 0
