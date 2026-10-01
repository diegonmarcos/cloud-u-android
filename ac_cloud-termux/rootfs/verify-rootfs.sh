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

echo "── sizes ──"
echo "   tarball $(wc -c < "$ART/rootfs.tar.zst") bytes, unpacked $(du -sk "$STAGE/rootfs" | cut -f1) KiB"
exit "$fail"
