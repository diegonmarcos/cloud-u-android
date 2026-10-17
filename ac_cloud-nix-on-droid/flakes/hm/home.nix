{ config, lib, pkgs, ... }:

{
  # Per-user packages, dotfiles and programs go here.
  home.packages = [ ];

  home.stateVersion = "24.05";
  # The nixpkgs pin is newer than the release-24.05 home-manager nix-on-droid locks.
  home.enableNixpkgsReleaseCheck = false;
}
