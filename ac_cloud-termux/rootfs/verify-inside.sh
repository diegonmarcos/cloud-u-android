#!/bin/sh
# Runs INSIDE the rootfs, under proot, as a child of the login shell.
# Arguments: every binary the declaration says the rootfs carries (resolve.py).
# One line per binary; exit 1 if any fails.
#
# For each one: it resolves on PATH; the file is executable; if it is a
# dynamically linked ELF, the interpreter in its PT_INTERP exists IN THIS ROOT
# and that interpreter can map every library the binary needs (ld.so --list);
# if it is a script, its #! interpreter resolves here too; and it actually
# executes. Architecture alone proves nothing: an aarch64 bionic binary and an
# aarch64 glibc binary are the same CPU and different worlds.
fail=0

bad() { echo "FAIL $1: $2"; fail=1; }

check() {
    b="$1"
    p="$(command -v "$b")" || { bad "$b" "not on PATH"; return; }
    r="$(readlink -f "$p")"
    [ -f "$r" ] && [ -x "$r" ] || { bad "$b" "$p -> $r is not an executable file"; return; }

    if [ "$(head -c 4 "$r" | od -An -c | tr -d ' \n')" = '177ELF' ]; then
        interp="$(file -bL "$r" | sed -n 's/.*interpreter \([^ ,]*\).*/\1/p')"
        if [ -n "$interp" ]; then
            [ -e "$interp" ] || { bad "$b" "PT_INTERP $interp is not inside the rootfs"; return; }
            if ! "$interp" --list "$r" >/dev/null 2>&1; then
                bad "$b" "$interp cannot load it: $("$interp" --list "$r" 2>&1 | grep -m3 'not found\|error' | tr '\n' ' ')"
                return
            fi
            how="ELF, PT_INTERP $interp present, libraries resolve"
        else
            how="static ELF, no PT_INTERP"
        fi
    else
        IFS= read -r first < "$r" || first=""
        case "$first" in
            '#!'*) ;;
            *) bad "$b" "$r is neither ELF nor a #! script"; return ;;
        esac
        # shellcheck disable=SC2086 # split the #! line into interpreter + argument
        set -- ${first#??}
        if [ "$1" = /usr/bin/env ]; then
            command -v "$2" >/dev/null || { bad "$b" "#! wants $2 via env and it is not on PATH"; return; }
            how="script via env $2"
        else
            [ -x "$1" ] || { bad "$b" "#! interpreter $1 is not inside the rootfs"; return; }
            how="script via $1"
        fi
    fi

    # 126/127 are the loader's and the shell's "could not execute"; any other
    # status, including a tool rejecting --version or timing out, means it ran.
    timeout 20 "$p" --version </dev/null >/dev/null 2>&1 && rc=0 || rc=$?
    case "$rc" in 126|127) bad "$b" "could not be executed (exit $rc)"; return ;; esac
    echo "ok   $b: $how, executes"
}

for b in "$@"; do check "$b"; done
exit "$fail"
