# #847 -- replaces upstream pkgs/nix-directory.nix. Upstream unpacks the nix-2.20.5 binary
# tarball; this takes nix, bash and cacert from the SAME nixpkgs (the bake pin) for the target
# system, so the store paths the zip ships are the ones the baked profile reuses.
{ config
, lib
, stdenvNoCC
, closureInfo
, prootTermux
, proot
, pkgsStatic
, system
, nixpkgs
}:

let
  buildRootDirectory = "root-directory";
  target = import nixpkgs { inherit system; };

  prootCommand = lib.concatStringsSep " " [
    "${proot}/bin/proot"
    "-b ${pkgsStatic.nix}:/static-nix"
    "-b /proc:/proc"
    "-r ${buildRootDirectory}"
    "-w /"
  ];

  targetClosure = closureInfo { rootPaths = [ target.nix target.bash target.cacert ]; };
  prootTermuxClosure = closureInfo { rootPaths = [ prootTermux ]; };
in

stdenvNoCC.mkDerivation {
  name = "nix-directory";
  dontUnpack = true;

  PROOT_NO_SECCOMP = 1;

  buildPhase = ''
    mkdir --parents ${buildRootDirectory}/nix/var/nix/db ${buildRootDirectory}/nix/store
    for i in $(< ${targetClosure}/store-paths) $(< ${prootTermuxClosure}/store-paths); do
      [ -e "${buildRootDirectory}$i" ] || cp --archive "$i" "${buildRootDirectory}$i"
    done

    USER=${config.user.userName} ${prootCommand} "/static-nix/bin/nix-store" --init
    USER=${config.user.userName} ${prootCommand} "/static-nix/bin/nix-store" --load-db < ${targetClosure}/registration
    USER=${config.user.userName} ${prootCommand} "/static-nix/bin/nix-store" --load-db < ${prootTermuxClosure}/registration

    cat > package-info.nix <<EOF2
    {
      bash = "${target.bash}";
      cacert = "${target.cacert}/etc/ssl/certs/ca-bundle.crt";
      nix = "${target.nix}";
    }
    EOF2
  '';

  installPhase = ''
    mkdir $out
    cp --recursive ${buildRootDirectory}/nix/store $out/store
    cp --recursive ${buildRootDirectory}/nix/var $out/var
    install -D -m 0644 package-info.nix $out/nix-support/package-info.nix
  '';

  fixupPhase = "true";
}
