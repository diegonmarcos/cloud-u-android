# #771 -- what the nix terminal bakes that a nixpkgs attr cannot give it: goose and hermes at
# the versions the termux terminal ships, and the noexec #! shim both terminals preload.
#
# ONE declaration of goose and hermes: ac_cloud-termux/rootfs/rootfs.json (tarballs.goose,
# pip_venvs.hermes-agent) -- the version, the release url and its per-arch names are read from
# it, never restated. nixpkgs is not that declaration: at the pinned nixpkgs goose-cli is a
# different release (1.47.0, built from source with an X11 closure) and hermes-agent is not
# packaged at all. What this terminal needs on top is the one thing a fixed-output fetch must
# have and rootfs.json does not carry: the content hash, pinned in build.json
# default_packages.extras.pins with the version it was taken for. A rootfs.json bump without a
# re-pin stops evaluation here, by name, instead of fetching bytes nobody checked.
#
# Called by bake_default_packages.py as `(import <this> { pkgs = <pinned nixpkgs for the
# target system>; }).<attr>` for each default_packages.extras.attrs entry, into the same
# profile the nixpkgs attrs go to.
{ pkgs }:
let
  inherit (pkgs) lib;
  rootfs = builtins.fromJSON (builtins.readFile ../../../../../ac_cloud-termux/rootfs/rootfs.json);
  extras = (builtins.fromJSON (builtins.readFile ../../../../build.json)).forks.nixdroid.bootstrap.default_packages.extras;
  pinned = name: version:
    let p = extras.pins.${name}; in
    if p.version == version then p
    else throw ("build.json default_packages.extras.pins.${name} is for ${p.version}, but "
      + "ac_cloud-termux/rootfs/rootfs.json declares ${version}: re-pin its hash for ${version}");

  goose = rootfs.tarballs.goose;
  hermes = rootfs.pip_venvs."hermes-agent";
  # rootfs.json keys its arch_names by docker arch, which is Go's GOARCH (amd64, arm64).
  arch = pkgs.stdenv.hostPlatform.go.GOARCH;
  fill = s: builtins.replaceStrings [ "{version}" "{arch}" ] [ goose.version goose.arch_names.${arch} ] s;
in
{
  # The same glibc release tarball the termux rootfs installs, given nix's loader and libstdc++.
  goose = pkgs.stdenv.mkDerivation {
    pname = "goose";
    inherit (goose) version;
    src = pkgs.fetchurl {
      url = fill goose.url;
      hash = (pinned "goose" goose.version).hash.${arch};
    };
    unpackPhase = "tar -xzf $src";
    nativeBuildInputs = [ pkgs.autoPatchelfHook ];
    buildInputs = [ pkgs.stdenv.cc.cc.lib ];
    installPhase = ''
      install -Dm755 ${lib.optionalString (goose ? extracted_dir) (fill goose.extracted_dir + "/")}${goose.extracted_bin} $out/bin/goose
    '';
    doInstallCheck = true;
    nativeInstallCheckInputs = [ pkgs.versionCheckHook pkgs.writableTmpDirAsHomeHook ];
    versionCheckKeepEnvironment = [ "HOME" ];
    meta.mainProgram = "goose";
  };

  # The same PyPI release the termux rootfs pip-installs into its venv. Its dependencies come
  # from the pinned nixpkgs (Hydra-built, so the arm64 bake substitutes instead of compiling);
  # their exact pins are relaxed, and the runtime-deps check still fails the build if any
  # Requires-Dist name has nothing behind it. The wheel caps Python at <3.14 only because uv
  # found no cp314 wheels for pydantic-core then (its pyproject says so); nixpkgs builds those
  # for its own 3.14, so the default python3Packages is used.
  hermes = pkgs.python3Packages.buildPythonApplication {
    pname = "hermes-agent";
    inherit (hermes) version;
    format = "wheel";
    src = pkgs.python3Packages.fetchPypi {
      pname = "hermes_agent";
      inherit (hermes) version;
      format = "wheel";
      python = "py3";
      dist = "py3";
      hash = (pinned "hermes-agent" hermes.version).hash;
    };
    dependencies =
      with pkgs.python3Packages;
      [
        openai certifi python-dotenv fire httpx rich tenacity pyyaml ruamel-yaml requests jinja2
        pydantic prompt-toolkit croniter packaging markdown pyjwt urllib3 cryptography psutil
        websockets pathspec fastapi uvicorn python-multipart ptyprocess pillow
      ]
      ++ httpx.optional-dependencies.socks
      ++ uvicorn.optional-dependencies.standard
      ++ pyjwt.optional-dependencies.crypto;
    pythonRelaxDeps = true;
    pythonImportsCheck = [ "hermes_cli.main" ];
    doInstallCheck = true;
    nativeInstallCheckInputs = [ pkgs.versionCheckHook pkgs.writableTmpDirAsHomeHook ];
    versionCheckKeepEnvironment = [ "HOME" ];
    versionCheckProgram = "${placeholder "out"}/bin/hermes";
    meta.mainProgram = "hermes";
  };

  # ab_cloud-terminal-store/noexec-shebang.c, the source the termux rootfs compiles too.
  noexec-shebang = pkgs.stdenv.mkDerivation {
    pname = "cloud-noexec-shebang";
    version = "1";
    src = ../../../../../ab_cloud-terminal-store/noexec-shebang.c;
    dontUnpack = true;
    buildPhase = "$CC -std=gnu11 -O2 -Wall -shared -fPIC -o libcloud-noexec-shebang.so $src";
    installPhase = "install -Dm755 libcloud-noexec-shebang.so $out/${extras.preload}";
    doInstallCheck = true;
    # It is preloaded into the bootstrap's own /bin/sh too, whose glibc is 2.37: a symbol
    # version newer than that would make ld.so refuse it in every such process.
    installCheckPhase = ''
      newer="$(objdump -T $out/${extras.preload} | grep -oE 'GLIBC_2\.(3[89]|[4-9][0-9])' | sort -u || true)"
      [ -z "$newer" ] || { echo "the shim needs $newer, newer than the bootstrap's glibc 2.37"; exit 1; }
    '';
  };
}
