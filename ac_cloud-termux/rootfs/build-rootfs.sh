#!/bin/sh
# Build the glibc root this terminal ships (#470). Usage: build-rootfs.sh <android-abi> <out-dir>
#
# CI only (docker), on a runner of the same CPU as <android-abi>. Writes the
# four files the APK carries as assets/<asset_dir>/ into <out-dir>:
#   rootfs.tar.zst  the tree, from rootfs.json + the fleet tool belt
#   rootfs.sha256   its digest: the app re-stages and enter.sh re-unpacks when it moves
#   proot           the static Android proot #348 pins in the nix-on-droid bootstrap
#   enter.sh        the login shell that unpacks and enters the tree
# verify-rootfs.sh then proves those exact files work.
set -eu

HERE="$(cd "$(dirname "$0")" && pwd)"
ABI="$1"
mkdir -p "$2"
OUT="$(cd "$2" && pwd)"
STORE_SRC="$(cd "$HERE/../../ab_cloud-terminal-store" && pwd)"
resolved() { python3 "$HERE/resolve.py" get "$1"; }
from_abi() { resolved "$1" | python3 -c 'import json,sys; print(json.load(sys.stdin)[sys.argv[1]][sys.argv[2]])' "$ABI" "$2"; }

python3 "$HERE/resolve.py" check
arch="$(from_abi abis docker_arch)"

W="$(mktemp -d)"
trap 'sudo rm -rf "$W"; [ -z "${cid:-}" ] || docker rm -f "$cid" >/dev/null' EXIT
python3 "$HERE/resolve.py" env "$arch" > "$W/resolved.env"
cp "$HERE/install-in-rootfs.sh" "$W/"

# ── the tree ───────────────────────────────────────────────────────────────
cid="$(docker create --platform "linux/$arch" -v "$W:/work:ro" "$(resolved base_image)" \
        sh /work/install-in-rootfs.sh /work/resolved.env)"
docker start -a "$cid"

# docker export keeps hard links as hard links; enter.sh's --link2symlink
# unpack is what turns them into something Android storage allows.
mkdir "$W/tree"
docker export "$cid" | sudo tar -xf - -C "$W/tree"
# docker bind-mounts these during the run and exports them empty.
resolved nameservers | python3 -c 'import json,sys; print("".join("nameserver %s\n" % n for n in json.load(sys.stdin)), end="")' \
    | sudo tee "$W/tree/etc/resolv.conf" >/dev/null
printf '127.0.0.1 localhost\n::1 localhost\n' | sudo tee "$W/tree/etc/hosts" >/dev/null

# #771 -- the two system files both terminals share from ab_cloud-terminal-store (the nix
# terminal bakes the same sources): git's system config (safe.directory for the shared store),
# and the noexec #! shim, preloaded into every glibc process of the root. The shim is compiled
# in a throwaway container of the SAME pinned base image, so it is linked against the glibc it
# is loaded into and the tree carries no compiler.
mkdir "$W/shim"
docker run --rm --platform "linux/$arch" -v "$STORE_SRC:/src:ro" -v "$W/shim:/out" "$(resolved base_image)" sh -c \
    'apt-get update -qq && apt-get install -y -qq --no-install-recommends gcc libc6-dev >/dev/null \
     && gcc -std=gnu11 -O2 -Wall -shared -fPIC -o /out/libcloud-noexec-shebang.so /src/noexec-shebang.c'
sudo install -D -m 0755 "$W/shim/libcloud-noexec-shebang.so" "$W/tree/usr/local/lib/libcloud-noexec-shebang.so"
echo /usr/local/lib/libcloud-noexec-shebang.so | sudo tee "$W/tree/etc/ld.so.preload" >/dev/null
sudo install -m 0644 "$STORE_SRC/gitconfig" "$W/tree/etc/gitconfig"
sudo tar -C "$W/tree" --numeric-owner --exclude='./dev/*' --exclude='./proc/*' --exclude='./sys/*' -cf - . \
    | zstd -T0 -12 --long=27 -q -o "$OUT/rootfs.tar.zst" -f
sha256sum "$OUT/rootfs.tar.zst" | cut -d' ' -f1 > "$OUT/rootfs.sha256"

# ── proot, read from the pin #348 already maintains ─────────────────────────
url="$(from_abi proot_bootstrap url)"
sha="$(from_abi proot_bootstrap sha256)"
curl -fsSL --retry 3 -o "$W/bootstrap.zip" "$url"
echo "$sha  $W/bootstrap.zip" | sha256sum -c -
python3 -c 'import sys, zipfile; open(sys.argv[3], "wb").write(zipfile.ZipFile(sys.argv[1]).read(sys.argv[2]))' \
    "$W/bootstrap.zip" "$(resolved proot_entry)" "$OUT/proot"
chmod 0755 "$OUT/proot"

cp "$HERE/enter.sh" "$OUT/enter.sh"

# ── #644: the declarative link store, staged BESIDE enter.sh, not inside the
# tarball. The store itself is built in $HOME (which enter.sh binds as /root and
# which survives a rootfs replacement); only the engine, its rendered declaration
# and the login wiring are shipped, and shipping them as APK assets means an
# engine fix rides an app-code update instead of rebuilding ~400 MB of Debian
# (#618's rule). enter.sh binds this directory onto /usr/lib/cloud-store, the
# same path the nix terminal extracts its copy to, so ONE declaration and ONE
# engine serve two apps that stay separate.
mkdir -p "$OUT/cloud-store"
cp "$STORE_SRC/cloud-store" "$STORE_SRC/login-init.sh" "$STORE_SRC/login-exec" "$OUT/cloud-store/"
python3 "$STORE_SRC/render-store.py" termux "$OUT/cloud-store/declaration.sh"
chmod 0755 "$OUT/cloud-store/cloud-store" "$OUT/cloud-store/login-exec"

ls -l "$OUT" "$OUT/cloud-store"
