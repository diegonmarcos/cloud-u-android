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

echo "── sizes ──"
echo "   tarball $(wc -c < "$ART/rootfs.tar.zst") bytes, unpacked $(du -sk "$STAGE/rootfs" | cut -f1) KiB"
exit "$fail"
