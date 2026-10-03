#!/bin/sh
# The rootfs tester (#470). Usage: verify-rootfs.sh <artefact-dir>
#
# <artefact-dir> is what build-rootfs.sh produced and what the APK ships as
# assets/<asset_dir>/: rootfs.tar.zst, rootfs.sha256, proot, enter.sh. This
# stages those exact files the way the app does, then logs in through
# enter.sh with the shipped static proot, so it exercises the phone's path:
# first-start unpack with --link2symlink, root's login shell, env -i, binds.
# CI runs it on a runner of the SAME CPU as the APK's ABI, natively.
#
# Every expectation is read, never written here: the binaries, the default
# shell and the smoke commands come from rootfs.json merged with the fleet
# tool belt by resolve.py.
set -eu

HERE="$(cd "$(dirname "$0")" && pwd)"
ART="$(cd "$1" && pwd)"
resolved() { python3 "$HERE/resolve.py" get "$1"; }

W="$(mktemp -d)"
trap 'rm -rf "$W"' EXIT
STAGE="$W/usr/var/lib/$(resolved asset_dir)"
mkdir -p "$STAGE" "$W/home"
for f in rootfs.tar.zst rootfs.sha256 proot enter.sh; do
    [ -f "$ART/$f" ] || { echo "FAIL artefact $f is missing from $ART"; exit 1; }
    cp "$ART/$f" "$STAGE/"
done
chmod 0700 "$STAGE/proot" "$STAGE/enter.sh"
# #644 — the link store ships beside enter.sh (not in the tarball), so stage it
# the way the APK does or enter.sh will correctly decline to wire it and this
# tester would pass over a store that was never there.
[ -d "$ART/cloud-store" ] || { echo "FAIL $ART/cloud-store is missing: build-rootfs.sh did not stage the #644 link store"; exit 1; }
cp -R "$ART/cloud-store" "$STAGE/cloud-store"
chmod 0700 "$STAGE/cloud-store/cloud-store" "$STAGE/cloud-store/login-exec"

# A failed unpack or a missing root entry must fail here, not drop into bash.
enter() { HOME="$W/home" CLOUD_ROOTFS_FALLBACK=false sh "$STAGE/enter.sh" "$@"; }

fail=0

echo "── proot is static: no PT_INTERP, so bionic and glibc hosts both run it ──"
if readelf -l "$STAGE/proot" | grep -q 'INTERP'; then
    echo "FAIL proot has a PT_INTERP: $(readelf -l "$STAGE/proot" | grep -A1 INTERP | tail -1)"; fail=1
else
    echo "ok   $(file -b "$STAGE/proot" | cut -d, -f1-4)"
fi

echo "── first login unpacks the rootfs and starts the declared default shell ──"
want_shell="$(resolved default_shell)"
# The login shell's child reads which executable its parent is. Asking the
# shell itself ($SHELL, `status`) would only repeat what enter.sh told it.
got_shell="$(enter -c "sh -c 'readlink /proc/\$PPID/exe'" | tail -1)"
if [ "$got_shell" = "$want_shell" ]; then
    echo "ok   login shell is $got_shell"
else
    echo "FAIL login shell is '$got_shell', rootfs.json::default_shell is '$want_shell'"; fail=1
fi
passwd_shell="$(sed -n 's/^root:[^:]*:[^:]*:[^:]*:[^:]*:[^:]*://p' "$STAGE/rootfs/etc/passwd")"
[ "$passwd_shell" = "$want_shell" ] && echo "ok   rootfs /etc/passwd gives root $passwd_shell" \
    || { echo "FAIL rootfs /etc/passwd gives root '$passwd_shell', expected '$want_shell'"; fail=1; }
[ -f "$STAGE/rootfs.tar.zst" ] && { echo "FAIL the tarball was not removed after a good unpack"; fail=1; } || true

echo "── every declared binary: present, PT_INTERP inside the root, loads, executes ──"
bins="$(resolved binaries | python3 -c 'import json,sys; print(" ".join(json.load(sys.stdin)))')"
cp "$HERE/verify-inside.sh" "$STAGE/rootfs/tmp/cloud-verify-inside.sh"
enter -c "sh /tmp/cloud-verify-inside.sh $bins" || fail=1
echo "   ($(echo "$bins" | wc -w) binaries declared)"

echo "── smoke: each declared command prints something under proot ──"
for name in $(resolved smoke | python3 -c 'import json,sys; print(" ".join(k for k in json.load(sys.stdin)))'); do
    cmd="$(resolved smoke | python3 -c 'import json,shlex,sys; print(shlex.join(json.load(sys.stdin)[sys.argv[1]]))' "$name")"
    if out="$(enter -c "$cmd" 2>&1 </dev/null)" && [ -n "$out" ]; then
        echo "ok   $name: $(echo "$out" | head -1)"
    else
        echo "FAIL $name: \`$cmd\` printed '$(echo "$out" | head -3)'"; fail=1
    fi
done

echo "── #612/#730: no shared storage here, so no mount point may be left that reads as an empty store ──"
# The runner has no readable /storage/emulated/0 — exactly a fresh phone before
# the storage grant. enter.sh must say so and must NOT leave an empty
# ~/cloud-drive-shared-store (an empty mount point is how a fresh phone's
# missing grant was misread as an empty store). Pre-create both empty, the
# state an earlier enter.sh left on upgraded phones, so this also proves they
# are removed. The readable branch (bind at the guest's ~) is pinned by
# ac_cloud-drive/test/test-drive-fresh-phone.sh.
mkdir -p "$W/home/emulated" "$W/home/cloud-drive-shared-store"
notice="$(enter -c true 2>&1 >/dev/null || true)"
for d in emulated cloud-drive-shared-store; do
    if [ -e "$W/home/$d" ]; then
        echo "FAIL \$HOME/$d is left behind with storage unreadable — it reads as an empty store"; fail=1
    else
        echo "ok   \$HOME/$d is not left behind while storage is unreadable"
    fi
done
case "$notice" in
    *"storage access is not granted"*) echo "ok   enter.sh says the store is not mounted and why" ;;
    *) echo "FAIL enter.sh printed no storage notice: '$(echo "$notice" | head -2)'"; fail=1 ;;
esac

echo "── #736: with shared storage readable, the GUEST shell lists the real storage and store ──"
# The phone's report: ~/emulated and ~/cloud-drive-shared-store listed empty while
# the file manager showed both full. Stage /storage/emulated/0 on the runner (the
# probe path is the phone's own), leave the state an older enter.sh left behind
# (empty mount-point dirs, termux-setup-storage's ~/storage tree), log in through
# the shipped proot and list from INSIDE the root. A mutant without the guest bind
# runs the same check and must go red: that is what proves an absolute symlink only
# resolves in the guest because of that bind, and that this check can fail at all.
S0=/storage/emulated/0
if [ -e /storage ]; then
    echo "FAIL /storage already exists on this runner; the readable branch cannot be staged without clobbering it"; fail=1
elif ! sudo -n mkdir -p "$S0/CloudDrive/git/cloud-probe-repo" "$S0/Download" 2>/dev/null \
        || ! sudo -n chown -R "$(id -u):$(id -g)" /storage; then
    echo "FAIL cannot stage $S0 on this runner (no passwordless sudo): the readable branch is unproven"; fail=1
else
    readable_listing() {  # $1 = enter.sh to log in through; prints what the guest lists
        rm -rf "$W/home/emulated" "$W/home/cloud-drive-shared-store" "$W/home/storage"
        mkdir -p "$W/home/emulated" "$W/home/cloud-drive-shared-store" "$W/home/storage"
        ln -s "$S0/DCIM" "$W/home/storage/dcim"
        # sh -c inside the login shell, as above: the listing must not depend on fish syntax.
        HOME="$W/home" CLOUD_ROOTFS_FALLBACK=false sh "$1" -c \
            "sh -c 'ls ~/emulated; echo SEP; ls ~/cloud-drive-shared-store/git'" 2>/dev/null </dev/null || true
    }
    readable_ok() {  # $1 = the listing
        case "$1" in *CloudDrive*Download*SEP*cloud-probe-repo*) return 0 ;; esac
        return 1
    }
    got="$(readable_listing "$STAGE/enter.sh")"
    if readable_ok "$got"; then echo "ok   the guest lists ~/emulated and ~/cloud-drive-shared-store/git with real content"
    else echo "FAIL the guest listing is not the real storage: '$(echo "$got" | tr '\n' ' ')'"; fail=1; fi
    for d in emulated cloud-drive-shared-store; do
        [ -L "$W/home/$d" ] && echo "ok   ~/$d is a symlink -> $(readlink "$W/home/$d")" \
            || { echo "FAIL ~/$d is not a symlink (an empty directory left in place reads as empty storage)"; fail=1; }
    done
    [ ! -e "$W/home/storage" ] && echo "ok   the upstream ~/storage tree is gone: ~/emulated is the one shared-storage entry" \
        || { echo "FAIL ~/storage is still there: a second entry for shared storage"; fail=1; }
    sed 's|^    binds="$binds -b /storage/emulated/0"$|    :|' "$STAGE/enter.sh" > "$STAGE/enter-mutant.sh"
    if cmp -s "$STAGE/enter.sh" "$STAGE/enter-mutant.sh"; then
        echo "FAIL MUTATION DID NOT APPLY: enter.sh has no guest bind line to remove"; fail=1
    elif readable_ok "$(readable_listing "$STAGE/enter-mutant.sh")"; then
        echo "FAIL MUTATION SURVIVED: without the guest bind the listing still passed — the check proves nothing"; fail=1
    else
        echo "ok   mutation proved: without the guest bind the same listing goes red"
    fi
    rm -f "$STAGE/enter-mutant.sh"
    sudo -n rm -rf /storage
fi

echo "── #741: a shell resolves through the app's bridge, never a server of its own ──"
# The phone's path end to end except the bridge's own upstream: the tree's
# resolv.conf, glibc's resolver, enter.sh's proot flags, and a responder where
# the app's SystemDnsBridge listens (libs/sysdns data/sysdns.json::bridge_port).
# It answers every A query with 192.0.2.53, so the address proves which server
# the guest reached; the runner's own DNS would answer NXDOMAIN for this name.
ns="$(sed -n 's/^nameserver[[:space:]]*//p' "$STAGE/rootfs/etc/resolv.conf" | tr '\n' ' ')"
[ "$ns" = "127.0.0.1 " ] && echo "ok   the tree's resolv.conf names only the loopback bridge" \
    || { echo "FAIL the tree's resolv.conf names '$ns': a shell would resolve past the SuperApp's DNS menu"; fail=1; }
bridge_port="$(resolved dns_bridge_port)"
python3 "$HERE/verify-dns-responder.py" "$bridge_port" > "$W/dns.ready" 2>&1 &
dns_pid=$!
for _ in 1 2 3 4 5 6 7 8 9 10; do [ -s "$W/dns.ready" ] && break; sleep 0.5; done
probe() {  # $1 = the enter.sh to run; prints the address the guest resolved
    HOME="$W/home" CLOUD_ROOTFS_FALLBACK=false sh "$1" -c "getent ahostsv4 fleet-dns-probe.test" 2>/dev/null </dev/null \
        | awk '$1 ~ /^[0-9.]+$/ {print $1; exit}' || true
}
got="$(probe "$STAGE/enter.sh")"
if [ "$got" = "192.0.2.53" ]; then echo "ok   getent in the guest answered from the bridge on 127.0.0.1:$bridge_port ($got)"
else echo "FAIL getent in the guest got '$got', not the bridge's 192.0.2.53: the shell does not resolve through Android"; fail=1; fi
sed 's|--sysvipc -p -0 -r|--sysvipc -0 -r|' "$STAGE/enter.sh" > "$STAGE/enter-mutant.sh"
if cmp -s "$STAGE/enter.sh" "$STAGE/enter-mutant.sh"; then
    echo "FAIL MUTATION DID NOT APPLY: enter.sh has no '--sysvipc -p -0 -r' to remove -p from"; fail=1
elif [ "$(probe "$STAGE/enter-mutant.sh")" = "192.0.2.53" ]; then
    echo "FAIL MUTATION SURVIVED: without -p the guest still reached the bridge — the check proves nothing"; fail=1
else
    echo "ok   mutation proved: without -p the same lookup misses the bridge"
fi
rm -f "$STAGE/enter-mutant.sh"
kill "$dns_pid" 2>/dev/null || true

echo "── #790: a rootfs update re-unpacks the tree and keeps \$HOME, agent logins included ──"
# 2026-10-03 a phone reported both terminals logged out after a rootfs update. Plant the agent
# CLIs' state in $HOME (credentials, configs, keys, history -- fixtures, never real values), make
# enter.sh see a new rootfs digest so it deletes and re-extracts the tree, and require every file
# intact on the host AND at /root inside the guest. Two mutants prove the check can fail: an
# enter.sh that no longer binds $HOME as /root (home inside the replaced tree), and one whose
# unpack also clears ~/.claude (an update that wipes the login). The agent-auth env file
# (store.json::agent_auth.env_file) is planted the way AgentAuth.java writes it, so the selftest
# below also proves the login sources it: `claude auth status` passes only with it.
AUTH_ENV="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["agent_auth"]["env_file"])' "$HERE/../../ab_cloud-terminal-store/store.json")"
AGENT_FILES=".claude/.credentials.json .claude.json .config/goose/config.yaml .hermes/.env .ssh/id_ed25519 .gitconfig .local/share/fish/fish_history .bash_history $AUTH_ENV"
plant_agent_files() {
    for f in $AGENT_FILES; do mkdir -p "$(dirname "$W/home/$f")"; echo "ci-fixture $f" > "$W/home/$f"; done
    printf "export CLAUDE_CODE_OAUTH_TOKEN='ci-fixture-not-a-token'\nexport OPENROUTER_API_KEY='ci-fixture-not-a-key'\n" > "$W/home/$AUTH_ENV"
    chmod 0600 "$W/home/$AUTH_ENV"
}
plant_agent_files
want_home="$(cd "$W/home" && cat $AGENT_FILES)"
reunpack_view() {  # $1 = enter.sh; forces the update path, prints what the guest reads at /root
    cp "$ART/rootfs.tar.zst" "$STAGE/rootfs.tar.zst"
    echo stale-digest > "$STAGE/rootfs/.cloud-rootfs.sha256"
    HOME="$W/home" CLOUD_ROOTFS_FALLBACK=false sh "$1" -c "sh -c 'cd /root && cat $AGENT_FILES'" 2>/dev/null </dev/null || true
}
home_kept() {  # $1 = the guest's view; both views must equal what was planted
    [ "$1" = "$want_home" ] && [ "$(cd "$W/home" && cat $AGENT_FILES 2>/dev/null)" = "$want_home" ]
}
got="$(reunpack_view "$STAGE/enter.sh")"
if [ "$(cat "$STAGE/rootfs/.cloud-rootfs.sha256")" != "$(cat "$STAGE/rootfs.sha256")" ]; then
    echo "FAIL the forced update did not re-unpack the tree: the test exercised nothing"; fail=1
elif home_kept "$got"; then
    echo "ok   after a re-unpack every planted file is intact in \$HOME and at /root in the guest ($(echo $AGENT_FILES | wc -w) files)"
else
    echo "FAIL a re-unpack lost agent state: the guest read '$(echo "$got" | head -3 | tr '\n' ' ')'"; fail=1
fi
home_mutant() {  # $1 = sed expression applied to enter.sh, $2 = what it breaks
    sed "$1" "$STAGE/enter.sh" > "$STAGE/enter-mutant.sh"
    if cmp -s "$STAGE/enter.sh" "$STAGE/enter-mutant.sh"; then
        echo "FAIL MUTATION DID NOT APPLY: $2"; fail=1
    elif home_kept "$(reunpack_view "$STAGE/enter-mutant.sh")"; then
        echo "FAIL MUTATION SURVIVED: $2, and the re-unpack check still passed"; fail=1
    else
        echo "ok   mutation proved: $2 goes red"
    fi
    rm -f "$STAGE/enter-mutant.sh"
    plant_agent_files
}
home_mutant 's| -b "$HOME:/root"||' "an enter.sh that does not bind \$HOME as /root"
home_mutant 's|^    rm -rf "$ROOTFS"$|    rm -rf "$ROOTFS" "$HOME/.claude"|' "an unpack that clears ~/.claude"

echo "── #771: the app's own selftest, every check, against a noexec, foreign-owned shared store ──"
# The phone's measured store: FUSE-mounted noexec, owned by a media uid that is not the
# terminal's, so git refused it as dubious and ./script was Permission denied. Staged the same
# way here: a noexec tmpfs at /storage/emulated/0/CloudDrive/git holding a repo owned by another
# uid (proot -0 shows the runner's own files as root's, so a foreign uid stays foreign). Then
# app/src/main/assets/terminal-selftest.json runs check by check through enter.sh, exactly as
# /api/terminal/selftest hands each one to the login shell, and every check must exit 0. Two
# mutants prove the shared-store checks can fail: without /etc/gitconfig git status goes red,
# without /etc/ld.so.preload the ./script check does.
SELFTEST="$HERE/../app/src/main/assets/terminal-selftest.json"
S0=/storage/emulated/0
G="$S0/CloudDrive/git"
if [ -e /storage ]; then
    echo "FAIL /storage already exists on this runner; the #771 store cannot be staged without clobbering it"; fail=1
elif ! sudo -n mkdir -p "$G" "$S0/Download" 2>/dev/null \
        || ! sudo -n mount -t tmpfs -o noexec,mode=0777 tmpfs "$G"; then
    echo "FAIL cannot stage a noexec $G on this runner (no passwordless sudo/mount): #771 is unproven"; fail=1
else
    git init -q "$G/selftest-repo"
    git -C "$G/selftest-repo" -c user.name=ci -c user.email=ci@localhost commit -q --allow-empty -m init
    sudo -n chown -R 4242:4242 "$G/selftest-repo"
    sudo -n chmod -R a+rwX "$G/selftest-repo"
    selftest_failures() {  # one line per check that does not exit 0
        python3 -c 'import json,sys; print("\n".join(json.load(open(sys.argv[1]))["checks"]))' "$SELFTEST" \
            | while IFS= read -r check; do
                out="$(enter -c "$check" 2>&1 </dev/null)" \
                    || echo "$check => $(echo "$out" | tail -2 | tr '\n' ' ')"
            done
    }
    n="$(python3 -c 'import json,sys; print(len(json.load(open(sys.argv[1]))["checks"]))' "$SELFTEST")"
    failed="$(selftest_failures)"
    if [ -z "$failed" ]; then
        echo "ok   all $n terminal-selftest.json checks exit 0 under the shipped proot, store noexec and foreign-owned"
    else
        echo "$failed" | sed 's/^/FAIL selftest: /'; fail=1
    fi
    mutant() {  # $1 = file under the root to take away, $2 = what must then fail, $3 = why
        mv "$STAGE/rootfs/$1" "$STAGE/rootfs/$1.off"
        got="$(selftest_failures)"
        mv "$STAGE/rootfs/$1.off" "$STAGE/rootfs/$1"
        if echo "$got" | grep -q -- "$2"; then echo "ok   mutation proved: without /$1 $3 goes red"
        else echo "FAIL MUTATION SURVIVED: without /$1 the selftest still passed $3 — the check proves nothing"; fail=1; fi
    }
    mutant etc/gitconfig "git status" "git status in the foreign-owned repo"
    mutant etc/ld.so.preload './\$f' "running ./script on the noexec store"
    # #790: the auth checks pass because the login sourced the Account's credentials file; without it
    # claude reports logged out and neither goose nor hermes finds a provider key.
    mv "$W/home/$AUTH_ENV" "$W/home/$AUTH_ENV.off"
    got="$(selftest_failures)"
    mv "$W/home/$AUTH_ENV.off" "$W/home/$AUTH_ENV"
    if echo "$got" | grep -q "claude auth status" && [ "$(echo "$got" | grep -c OPENROUTER_API_KEY)" -eq 2 ]; then
        echo "ok   mutation proved: without the agent-auth file claude, goose and hermes all go red"
    else
        echo "FAIL MUTATION SURVIVED: without the agent-auth file the auth checks still passed — they prove nothing"; fail=1
    fi
    sudo -n umount "$G"
    sudo -n rm -rf /storage
fi

echo "── #795: a session in a real pty, under the shipped proot and Android's tty ioctl policy ──"
# ab_cloud-terminal-store/pty-selftest.json, check by check, each in a pty whose child is a session
# leader on the pts (termux.c's create_subprocess), under a seccomp filter that denies what Android's
# SELinux denies. The selftest of #771 above runs over pipes and was green on the phone that drew no
# prompt. Two mutants prove these checks can fail: the 24.05 proot this replaced (termios2 must go
# red: the phone's EACCES, from a glibc that issues TCGETS2), and a policy that also denies TCGETS,
# the ioctl this tree's own glibc 2.36 issues (tty, stty and claude-tui must go red: the phone's
# three symptoms, including claude's --print refusal).
STORE_SRC="$HERE/../../ab_cloud-terminal-store"
got="$(sha256sum "$STAGE/proot" | cut -d' ' -f1)"
abi="$(python3 -c 'import json,sys; print(" ".join(a for a, p in json.load(open(sys.argv[1]))["archs"].items() if p["sha256"] == sys.argv[2]))' \
    "$STORE_SRC/proot.json" "$got")"
if [ -n "$abi" ]; then echo "ok   the staged proot is proot.json's $abi pin (sha256 $got)"
else echo "FAIL the staged proot (sha256 $got) is no ab_cloud-terminal-store/proot.json pin"; fail=1; fi
pty_selftest() {  # $1 = the checks json
    HOME="$W/home" CLOUD_ROOTFS_FALLBACK=false python3 "$STORE_SRC/pty-selftest.py" "$1" termux -- sh "$STAGE/enter.sh" 2>&1
}
if out="$(pty_selftest "$STORE_SRC/pty-selftest.json")"; then
    echo "$out"; echo "ok   every pty-selftest.json check passes under the shipped proot"
else
    echo "$out" | sed 's/^FAIL /FAIL pty: /'; fail=1
fi
pty_mutant() {  # $1 = the checks json, $2 = why, then the check names that must go red
    got="$(pty_selftest "$1" || true)"; why="$2"; shift 2
    for n in "$@"; do
        if echo "$got" | grep -q "^FAIL $n:"; then echo "ok   mutation proved: $why turns $n red"
        else echo "FAIL MUTATION SURVIVED: $why left $n green — the check cannot see the phone's failure"; fail=1; fi
    done
}
old="$W/proot-24.05"
if [ -z "$abi" ]; then
    echo "FAIL cannot tell the staged proot's ABI, so the 24.05 mutant cannot be fetched"; fail=1
else
    url="$(resolved proot_bootstrap | python3 -c 'import json,sys; print(json.load(sys.stdin)[sys.argv[1]]["url"])' "$abi")"
    sha="$(resolved proot_bootstrap | python3 -c 'import json,sys; print(json.load(sys.stdin)[sys.argv[1]]["sha256"])' "$abi")"
    curl -fsSL --retry 3 -o "$W/bootstrap-24.05.zip" "$url"
    echo "$sha  $W/bootstrap-24.05.zip" | sha256sum -c - >/dev/null
    python3 -c 'import sys, zipfile; open(sys.argv[3], "wb").write(zipfile.ZipFile(sys.argv[1]).read(sys.argv[2]))' \
        "$W/bootstrap-24.05.zip" "$(resolved proot_entry)" "$old"
    rm -f "$W/bootstrap-24.05.zip"
    chmod 0700 "$old"
    mv "$STAGE/proot" "$STAGE/proot.shipped"; cp "$old" "$STAGE/proot"
    pty_mutant "$STORE_SRC/pty-selftest.json" "the 24.05 proot" termios2
    mv -f "$STAGE/proot.shipped" "$STAGE/proot"
fi
python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); d["android_denied_tty_ioctls"]["TCGETS"]="0x5401"; json.dump(d, open(sys.argv[2], "w"))' \
    "$STORE_SRC/pty-selftest.json" "$W/pty-tcgets-denied.json"
pty_mutant "$W/pty-tcgets-denied.json" "denying TCGETS" tty stty claude-tui

echo "── sizes ──"
echo "   tarball $(wc -c < "$ART/rootfs.tar.zst") bytes, unpacked $(du -sk "$STAGE/rootfs" | cut -f1) KiB"
exit "$fail"
