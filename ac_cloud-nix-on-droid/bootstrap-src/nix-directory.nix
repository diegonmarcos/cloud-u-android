# #847 -- replaces upstream pkgs/nix-directory.nix. Upstream unpacks the nix-2.20.5 binary
# tarball; this takes nix, bash and cacert from the SAME nixpkgs (the bake pin) for the target
# system, so the store paths the zip ships are the ones the baked profile reuses.
{ config
, lib
, stdenvNoCC
, closureInfo
, prootTermux
, nix
, system
, nixpkgs
}:

let
  buildRootDirectory = "root-directory";
  target = import nixpkgs { inherit system; };

  # Upstream registers the store by running a static nix-store under proot with the tree as /.
  # At this pin nix-store is a symlink into another store path proot's root cannot see, so the
  # tree is opened as a chroot store instead: same /nix/store paths in the db, no proot.
  nixStore = "${nix}/bin/nix-store --store $PWD/${buildRootDirectory}";

  targetClosure = closureInfo { rootPaths = [ target.nix target.bash target.cacert ]; };
  prootTermuxClosure = closureInfo { rootPaths = [ prootTermux ]; };
in

stdenvNoCC.mkDerivation {
  name = "nix-directory";
  dontUnpack = true;

  buildPhase = ''
    mkdir --parents ${buildRootDirectory}/nix/var/nix/db ${buildRootDirectory}/nix/store
    for i in $(< ${targetClosure}/store-paths) $(< ${prootTermuxClosure}/store-paths); do
      [ -e "${buildRootDirectory}$i" ] || cp --archive "$i" "${buildRootDirectory}$i"
    done

    HOME=$TMPDIR USER=${config.user.userName} ${nixStore} --load-db < ${targetClosure}/registration
    HOME=$TMPDIR USER=${config.user.userName} ${nixStore} --load-db < ${prootTermuxClosure}/registration

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
