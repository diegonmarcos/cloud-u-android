# #847 -- replaces upstream pkgs/cross-compiling/proot-termux.nix. See bootstrap-src.json::_doc_proot.
{ lib, stdenvNoCC, fetchurl, zstd, nix, targetSystem }:

let
  decl = builtins.fromJSON (builtins.readFile ./bootstrap-src.json);
  arch = lib.strings.removeSuffix "-linux" targetSystem;
  pin = decl.proot_mutant.${arch};
in
stdenvNoCC.mkDerivation {
  name = "proot-termux-static-mutant-${arch}";
  src = fetchurl {
    url = "${decl.proot_mutant.cache}/${pin.nar}";
    sha256 = pin.file_sha256;
  };
  nativeBuildInputs = [ zstd nix ];
  dontUnpack = true;
  installPhase = ''
    zstd -dc $src | nix-store --restore unpacked
    install -D -m 0755 unpacked/bin/proot-static $out/bin/proot-static
  '';
  dontFixup = true;
}
