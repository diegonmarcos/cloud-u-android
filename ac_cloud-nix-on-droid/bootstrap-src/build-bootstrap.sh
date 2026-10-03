#!/usr/bin/env bash
# #847 -- build the nix-on-droid bootstrap zip for one arch from the bake's nixpkgs pin.
#   build-bootstrap.sh <aarch64|x86_64> <out.zip>
# Reads the pin from ../build.json (the ONE declaration bake_default_packages.py also reads)
# and the upstream rev from bootstrap-src.json. See bootstrap-src.json::_doc.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
arch="$1"; out="$2"
case "$arch" in aarch64|x86_64) ;; *) echo "unknown arch $arch" >&2; exit 2;; esac
pin="$(jq -er '.forks.nixdroid.bootstrap.default_packages.nixpkgs_pin' "$here/../build.json")"
repo="$(jq -er .upstream_repo "$here/bootstrap-src.json")"
rev="$(jq -er .upstream_rev "$here/bootstrap-src.json")"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
git -C "$work" init -q src
git -C "$work/src" fetch -q --depth=1 "$repo" "$rev"
git -C "$work/src" checkout -q FETCH_HEAD
src="$work/src"

cp "$here/nix-directory.nix" "$src/pkgs/nix-directory.nix"
cp "$here/proot-mutant.nix" "$here/bootstrap-src.json" "$src/pkgs/cross-compiling/"
sed -i 's|callPackage ./cross-compiling/proot-termux.nix { }|callPackage ./cross-compiling/proot-mutant.nix { }|' "$src/pkgs/default.nix"
grep -q 'proot-mutant.nix' "$src/pkgs/default.nix" || { echo "FAIL: prootTermux override did not apply" >&2; exit 1; }
git -C "$src" add -A   # a flake only sees tracked files

echo "building bootstrapZip-$arch from upstream $rev with nixpkgs-for-bootstrap=$pin" >&2
nix build --impure --print-build-logs --no-link --print-out-paths \
  --extra-experimental-features 'nix-command flakes' \
  --override-input nixpkgs-for-bootstrap "github:NixOS/nixpkgs/$pin" \
  "path:$src#bootstrapZip-$arch" > "$work/outpath"
zip="$(cat "$work/outpath")/bootstrap-$arch.zip"
[ -f "$zip" ] || { echo "FAIL: no $zip" >&2; exit 1; }
cp "$zip" "$out"
echo "built $out: $(stat -c %s "$out") B sha256 $(sha256sum "$out" | cut -d' ' -f1)" >&2
