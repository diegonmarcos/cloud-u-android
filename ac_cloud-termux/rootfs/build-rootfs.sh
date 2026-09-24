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
ls -l "$OUT"
