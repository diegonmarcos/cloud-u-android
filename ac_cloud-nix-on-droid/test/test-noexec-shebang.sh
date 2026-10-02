#!/bin/sh
# #771 — ab_cloud-terminal-store/noexec-shebang.c runs `#!` scripts on a noexec mount, and
# changes nothing else.
#
# Both terminals preload that one source (the termux rootfs and this app's rootfs-extras.nix
# compile it), so its logic is proven once, here, natively: compiled with the runner's cc and
# preloaded into real shells, python and node against a real noexec mount. The termux rootfs
# job additionally runs the app selftest through the shipped proot against a noexec store
# (ac_cloud-termux/rootfs/verify-rootfs.sh). Every positive assertion below is first shown to
# FAIL without the preload, so none of them can pass for a reason other than the shim.
set -u

DIR="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$DIR/../ab_cloud-terminal-store/noexec-shebang.c"

fails=0
ok()  { echo "  ok   — $1"; }
bad() { echo "::error::FAIL — $1"; fails=$((fails + 1)); }

echo "── #771 noexec #! shim assertions ──"
T="$(mktemp -d)"
NX=""
MOUNTED=""
cleanup() {
    [ -z "$MOUNTED" ] || sudo -n umount "$NX"
    [ -z "$NX" ] || rm -rf "$NX"
    rm -rf "$T"
}
trap cleanup EXIT

if ! cc -std=gnu11 -O2 -Wall -Werror -shared -fPIC -o "$T/shim.so" "$SRC" 2>"$T/cc.err"; then
    bad "noexec-shebang.c does not compile with -Wall -Werror: $(head -5 "$T/cc.err")"
    exit 1
fi
ok "noexec-shebang.c compiles warning-free"

# A writable noexec mount: one the runner already has, else a tmpfs mounted for the run.
for m in $(awk '$4 ~ /(^|,)noexec(,|$)/ && $4 ~ /(^|,)rw(,|$)/ {print $2}' /proc/mounts); do
    if mkdir "$m/cloud-noexec-test.$$" 2>/dev/null; then NX="$m/cloud-noexec-test.$$"; break; fi
done
if [ -z "$NX" ] && sudo -n true 2>/dev/null; then
    NX="$(mktemp -d)"
    sudo -n mount -t tmpfs -o noexec,mode=0777 tmpfs "$NX" && MOUNTED=1 || NX=""
fi
[ -n "$NX" ] || { bad "no writable noexec mount and no passwordless sudo to make one: the shim is unproven"; exit 1; }

printf '#!/bin/sh\necho "ran $0 $*"\n' > "$NX/s.sh"
printf '#!/usr/bin/env sh\necho env-ran\n' > "$NX/e.sh"
printf 'echo no-shebang\n' > "$NX/n.sh"
cp /usr/bin/env "$NX/elf"   # any external ELF; /usr/bin/env exists wherever #!/usr/bin/env works
printf '#!/bin/sh\necho not-executable\n' > "$T/plain.sh"   # exec-capable fs, no x bit
chmod 0755 "$NX/s.sh" "$NX/e.sh" "$NX/n.sh" "$NX/elf"
chmod 0644 "$T/plain.sh"

with() { LD_PRELOAD="$T/shim.so" "$@"; }

# 1. the mount really refuses exec, so every positive below needs the shim
if (cd "$NX" && sh -c './s.sh' >/dev/null 2>&1); then
    bad "$NX is not noexec after all: ./s.sh ran without the shim, so nothing below proves anything"
else
    ok "without the shim ./s.sh on the noexec mount is refused (the baseline every check below must beat)"
fi

# 2. each shell runs ./script with the kernel's argv rule
for shell in sh bash dash zsh; do
    command -v "$shell" >/dev/null || continue
    got="$(cd "$NX" && with "$shell" -c './s.sh a b' 2>&1)"
    [ "$got" = "ran ./s.sh a b" ] && ok "$shell runs ./s.sh a b as \`/bin/sh ./s.sh a b\`" \
        || bad "$shell: ./s.sh a b printed '$got'"
done

# 3. #!/usr/bin/env, PATH lookup, test -x
got="$(cd "$NX" && with sh -c './e.sh' 2>&1)"
[ "$got" = env-ran ] && ok "#!/usr/bin/env sh resolves its interpreter through env" || bad "./e.sh printed '$got'"
got="$(with bash -c "PATH='$NX':\$PATH; s.sh via-path" 2>&1)"
[ "$got" = "ran $NX/s.sh via-path" ] && ok "a noexec script found on PATH runs (npm's node_modules/.bin shape)" \
    || bad "PATH lookup printed '$got'"
(cd "$NX" && with sh -c 'test -x ./s.sh && ! test -x ./n.sh') \
    && ok "test -x is yes for a noexec #! script and still no for one without #!" \
    || bad "test -x disagrees with what now runs"

# 4. python (posix_spawn, access) and node (libuv execve)
if command -v python3 >/dev/null; then
    got="$(cd "$NX" && with python3 -u -c '
import os, subprocess
print(subprocess.run(["./s.sh", "py"], capture_output=True, text=True).stdout.strip())
pid = os.posix_spawn("./s.sh", ["./s.sh", "spawn"], os.environ); os.waitpid(pid, 0)
print(os.access("./s.sh", os.X_OK), os.access("./n.sh", os.X_OK))' 2>&1)"
    [ "$got" = "$(printf 'ran ./s.sh py\nran ./s.sh spawn\nTrue False')" ] \
        && ok "python subprocess, os.posix_spawn and os.access agree with the shim" \
        || bad "python printed '$(echo "$got" | tr '\n' '|')'"
fi
if command -v node >/dev/null; then
    got="$(cd "$NX" && with node -e 'process.stdout.write(require("child_process").execFileSync("./s.sh", ["node"]))' 2>&1)"
    [ "$got" = "ran ./s.sh node" ] && ok "node child_process runs it" || bad "node printed '$got'"
fi

# 5. and nothing else changes
(cd "$NX" && with sh -c './n.sh' >/dev/null 2>&1) \
    && bad "a noexec file WITHOUT #! ran: the shim invented an interpreter" \
    || ok "a noexec file without #! is still refused (the kernel would not run it either)"
[ "$(head -c 4 "$NX/elf" | od -An -c | tr -d ' \n')" = '177ELF' ] || bad "no ELF staged on the noexec mount"
(cd "$NX" && with sh -c './elf' >/dev/null 2>&1) \
    && bad "an ELF on the noexec mount ran: the test mount is not what the phone has" \
    || ok "an ELF on the noexec mount is still refused (the documented limit: scripts only)"
(cd "$T" && with sh -c './plain.sh' >/dev/null 2>&1) \
    && bad "a non-executable script on an exec-capable fs ran: the shim widened permissions beyond noexec" \
    || ok "a non-executable script elsewhere is still Permission denied"

[ "$fails" -eq 0 ] || { echo "$fails assertion(s) failed"; exit 1; }
