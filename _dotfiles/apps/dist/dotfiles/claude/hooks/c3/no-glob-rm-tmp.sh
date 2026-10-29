#!/bin/bash
. "$(dirname "$0")/_lib.sh"
if printf '%s' "$CMD" | grep -Eq '(^|[;&|(]|\s)rm\s[^;&|]*(/tmp|/dev/shm)[^[:space:];&|]*[*?[]'; then
  block no-glob-rm-tmp 'glob delete under /tmp or /dev/shm removes the sandbox'"'"'s own files. Delete only paths you created, by full name.'
fi
exit 0
