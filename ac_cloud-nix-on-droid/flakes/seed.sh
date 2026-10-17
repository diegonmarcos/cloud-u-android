#!/bin/sh
# Seed $HOME/<home_dir>/{system,hm} from the flakes baked at this script's directory.
# Idempotent: a file that already exists (or a dangling link) is never touched, so
# the user's edits survive every login and every app update. Never fails a login.
#   seed.sh <home_dir>
src="$(cd "$(dirname "$0")" && pwd)" || exit 0
dst="$HOME/${1:-flakes}"
for sub in system hm; do
    [ -d "$src/$sub" ] || continue
    mkdir -p "$dst/$sub" 2>/dev/null || exit 0
    for f in "$src/$sub"/*; do
        [ -f "$f" ] || continue
        t="$dst/$sub/${f##*/}"
        if [ ! -e "$t" ] && [ ! -L "$t" ]; then
            cp "$f" "$t" && chmod u+w "$t"
            case "$t" in */android-host-exec) chmod u+x "$t" ;; esac
        fi
    done
done
exit 0
