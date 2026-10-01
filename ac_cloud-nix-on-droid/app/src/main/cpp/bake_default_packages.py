#!/usr/bin/env python3
"""#595 -- bakes agent tooling (git, node, claude) into the nix-on-droid store
this terminal ships, so a fresh install never has to run upstream's
interactive, networked first-login wizard just to get a shell with a package
in it.

Runs AFTER patch_bootstrap_ids.py, on the already id-rewritten zip. Requires
`nix` (flakes + nix-command experimental features) on PATH and network
access to realize the declared attrs -- CI-time only, never on the phone.

This bootstrap format is TermuxInstaller-derived (see that class's extraction
loop): a plain zip entry carries no permission bits at all -- ZipInputStream
ignores them -- so two manifest entries do the real work instead:
  SYMLINKS.txt     "<target>←<link-path-relative-to-root>" per line
  EXECUTABLES.txt  one link-path-relative-to-root per line, chmod 0700 each
Regular files therefore need no special handling; every symlink and every
executable this script adds MUST go through one of those two manifests, or
the installed app will silently ship a plain-file copy of a symlink, or a
binary nothing ever chmods +x.

Usage:
    bake_default_packages.py <input.zip> <output.zip> <nixpkgs_pin> \
        <comma-separated attrs> <profile_link> <fallback_init_script> <app_id> \
        <nix_system, e.g. aarch64-linux> <shared_root_name>

#612 also patches bin/login here (not a separate script): it is the same
"add text to a generated file" job as the login-inner patch above, on a zip
this function already has open.
"""
import importlib.util
import os
import re
import stat
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

# #644 -- the declarative link store, shared with ac_cloud-termux. Located from
# this file rather than passed in, so wiring it needed no new gradle argument and
# no edit to cloud-nix-on-droid-fork-engine.sh (whose vendored build.sh copy must
# stay byte-identical to it).
STORE_SRC = Path(__file__).resolve().parents[5] / "ab_cloud-terminal-store"


def load_render_store():
    """ab_cloud-terminal-store/render-store.py, imported by path (its name has a dash)."""
    path = STORE_SRC / "render-store.py"
    if not path.is_file():
        raise ValueError(f"the shared link store is missing: {path}")
    spec = importlib.util.spec_from_file_location("render_store", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def profile_missing(generation: str, tools) -> list:
    """#644 -- declared tool names with nothing behind them in the realized profile.

    default_packages.binaries is maintained beside attrs and provides and can
    drift from both; a drifted name becomes a link that dangles on every phone,
    which is the #638/#640/#641 shape one layer up. Pure, so the tester proves
    both verdicts without a nix profile.
    """
    return [t for t in tools
            if not os.path.islink(os.path.join(generation, "bin", t))
            and not os.path.exists(os.path.join(generation, "bin", t))]


def store_init_line(install_dir: str) -> str:
    """#644 -- the line that makes a login initialise, verify and repair the store.

    Guarded on readability, so a bootstrap built before this block (or one whose
    store failed to bake) still reaches a shell: the store is worth a terminal's
    tooling, never a terminal's existence. Pure str -> str like every other patch
    here, so the tester asserts the real line.
    """
    return (f'if [ -r "/{install_dir}/login-init.sh" ]; then\n'
            f'  . "/{install_dir}/login-init.sh"\n'
            'fi')

SESSION_INIT_TEMPLATE = (
    '. "/data/data/{app_id}/files/home/.nix-profile/etc/profile.d/'
    'nix-on-droid-session-init.sh"'
)
DROPPED_ENTRY = "etc/static/UNINTIALISED"
# The real file behind the etc/profile symlink (SYMLINKS.txt: /etc/static/profile←etc/profile).
ETC_PROFILE_ENTRY = "etc/static/profile"

# #638 -- the two UNCONDITIONAL execs through /usr/bin/env that upstream's
# generated login-inner carries, verbatim. /usr/bin/env is NOT in the bootstrap
# zip: `usr/bin/` is an empty directory in it and SYMLINKS.txt has no line for
# env. On a stock nix-on-droid install that path only appears once the
# first-login wizard has run `nix-on-droid switch`, which symlinks it into
# coreutils. Dropping DROPPED_ENTRY above is exactly what stops that wizard from
# running, so on a fresh install of THIS app both lines below exec a file that
# does not exist -- `/usr/bin/env: No such file or directory`, exit 127, no
# shell, ever. Guarding the first one lets execution fall through to the
# usershell block right below it, which execs an absolute /nix/store bash path
# this zip really does ship (and really does chmod +x via EXECUTABLES.txt).
ENV_EXEC = "exec /usr/bin/env bash  # otherwise it'll be a limited bash that came with Nix"
ENV_EXEC_ARGV = 'exec /usr/bin/env "$@"'

# #640 -- the guards above keep the TERMINAL booting when /usr/bin/env is absent,
# and they stay: falling through to an absolute /nix/store bash is strictly better
# than an unconditional exec either way. But they fix the boot only. `#!/usr/bin/env
# <interp>` is the most common shebang there is, and with that path absent EVERY
# such script in the shell dies "No such file or directory" -- so the file itself
# has to exist. bin/login binds files/usr/usr onto /usr, so this link path, once
# extracted into $PREFIX/usr, IS /usr/bin/env as the kernel resolves a shebang.
ENV_SYMLINK = "usr/bin/env"


def env_symlink_line(profile_link: str) -> str:
    """The SYMLINKS.txt line that makes /usr/bin/env exist on a fresh install.

    The target is derived from profile_link -- the SAME declaration that produces
    the default-profile symlink below -- so no /nix/store hash is ever written
    here: the nixpkgs pin moves, every hash under it moves, and a literal would
    rot into a dangling link silently.

    Pure str -> str like patch_login_inner, so test/test-bootstrap-baked.sh can
    build a sandbox out of the REAL line and EXECUTE a `#!/usr/bin/env sh` script
    through it, with no nix and no bootstrap zip.
    """
    return f"/{profile_link}/bin/env←{ENV_SYMLINK}"


def env_target_unreachable(env_rel, existing, new_files, new_executables):
    """None when the resolved coreutils `env` really ships AND gets chmod +x.

    A symlink onto an absent or non-executable target is WORSE than no symlink at
    all: it converts a clean "no such file or directory" into a permission error
    nothing in the shell explains. Pure, so the tester can prove both verdicts.
    """
    if env_rel in existing:
        return None  # already shipped by the input zip, with its own manifest lines
    if env_rel not in new_files:
        return f"{env_rel} is not a file entry in the output zip"
    if env_rel not in new_executables:
        return f"{env_rel} is not in EXECUTABLES.txt, so nothing ever chmods it +x"
    return None


def patch_login_inner(login_inner: str, app_id: str, fallback_script: str,
                      profile_link: str, login_shell: str, store_install_dir: str = "") -> str:
    """The text edits #595/#638/#641 make to the generated usr/lib/login-inner.

    Pure str -> str so test/test-bootstrap-baked.sh can run the REAL patch
    offline, with no nix and no bootstrap zip, and then EXECUTE the result.
    Raises ValueError on anything it does not recognise: a silently unpatched
    login-inner is precisely how #638 shipped a boot crash behind a green CI.
    """
    want = SESSION_INIT_TEMPLATE.format(app_id=app_id)
    if want not in login_inner:
        raise ValueError(f"expected session-init line not found in usr/lib/login-inner:\n  {want}")
    head, _, tail = login_inner.rpartition(want)
    fallback_line = f'if [ -e "/data/data/{app_id}/files/home/.nix-profile/etc/profile.d/nix-on-droid-session-init.sh" ]; then\n  {want}\nelse\n  . /{fallback_script}\nfi'
    # #644 -- and the store, UNCONDITIONALLY: outside the if/else above, because
    # the branch that runs depends on whether a real nix-on-droid generation was
    # ever built, while the link store has to be initialised and verified either
    # way. It goes after, so the profile PATH the fallback exports is already set.
    if store_install_dir:
        fallback_line = fallback_line + "\n" + store_init_line(store_install_dir)
    login_inner = head + fallback_line + tail

    # #641 -- this exec GOES, it is not merely guarded any more, and #640 is why:
    # the moment /usr/bin/env exists the #638 guard becomes TRUE, this line fires,
    # and login-inner execs bash and never reaches the usershell block below it.
    # Guarding it kept the terminal alive when env was missing; keeping it now
    # would make bash the permanent login shell and #641's fish unreachable. What
    # replaces it is upstream's OWN shell selection right below -- which still
    # tests -x and still falls back to bash -- so nothing is loosened: the fix is
    # strictly upstream's mechanism instead of upstream's shortcut.
    if login_inner.count(ENV_EXEC) != 1:
        raise ValueError(f"expected exactly one unconditional env exec in usr/lib/login-inner:\n  {ENV_EXEC}")
    login_inner = login_inner.replace(
        ENV_EXEC,
        "# #641: the `exec /usr/bin/env bash` upstream puts here is dropped, so the\n"
        "# usershell block below chooses the login shell (and still falls back to bash).",
        1)

    # The "called with arguments" path (RunCommandService, `login <cmd>`) has the
    # same dependency and no block below it to fall through to. bin/sh here IS
    # bash, whose exec already resolves its argv against PATH, so env adds
    # nothing but the missing file.
    if login_inner.count(ENV_EXEC_ARGV) != 1:
        raise ValueError(f"expected exactly one env exec of the caller's argv:\n  {ENV_EXEC_ARGV}")
    login_inner = login_inner.replace(ENV_EXEC_ARGV, 'exec "$@"', 1)

    require_fish_login_shell(login_shell)
    return probe_usershell_exec(
        retarget_usershell(login_inner, profile_link, login_shell))


# The post-banner hang -- the LAST statement a login runs is upstream's
# `exec -a "-${usershell##*/}" "$usershell"`, and it is UNBOUNDED: upstream's -x
# test only proves the file is executable, not that the shell can run. A fish
# that wedges under the old proot-static (rust fish 4.x has form there) is a
# blank screen forever, after the welcome banner, with nothing printed. Both
# literals below are module constants so the tester can un-apply the probe
# textually and prove its own assertion discriminates.
USERSHELL_EXEC = 'exec -a "-${usershell##*/}" "$usershell"'
# The probe above passed on the phone and fish STILL drew no prompt: `-c exit`
# never reaches the interactive half of fish's startup, which is where the
# device wedged (tty left in cooked mode, no child, forever). So the shell is no
# longer exec'd blind. It runs as a foreground job beside a watchdog, and fish
# proves it reached the reader by writing $CLOUD_LOGIN_PROMPT_SEEN from its first
# fish_prompt event. No proof within the deadline -> one named stderr line, the
# shell is killed, bash. `set -m` (only with a tty) makes bash hand the terminal
# to the job and take it back when the job dies, so the bash after a kill is
# never left in a background process group. The deadline outlasts fish's own
# 10 s wait for a terminal that ignores its startup queries.
USERSHELL_PROMPT_DEADLINE_S = 20
USERSHELL_PROMPT_BOUNDED = (
    'CLOUD_LOGIN_PROMPT_SEEN="${TMPDIR:-/tmp}/.cloud-login-prompt.$$"\n'
    '    export CLOUD_LOGIN_PROMPT_SEEN\n'
    '    rm -f "$CLOUD_LOGIN_PROMPT_SEEN" "$CLOUD_LOGIN_PROMPT_SEEN.pid" "$CLOUD_LOGIN_PROMPT_SEEN.late"\n'
    f'    deadline="${{CLOUD_LOGIN_PROMPT_DEADLINE:-{USERSHELL_PROMPT_DEADLINE_S}}}"\n'
    '    if [ -t 0 ]; then set -m; fi\n'
    '    ( sleep "$deadline"\n'
    '      if [ ! -e "$CLOUD_LOGIN_PROMPT_SEEN" ]; then\n'
    '        : > "$CLOUD_LOGIN_PROMPT_SEEN.late"\n'
    '        kill -KILL "$(cat "$CLOUD_LOGIN_PROMPT_SEEN.pid")" 2>/dev/null\n'
    '      fi ) &\n'
    '    watchdog=$!\n'
    '    rc=0\n'
    # The job is a waiter bash whose own stderr is /dev/null, so bash's "Killed"
    # report about the shell lands there; the shell itself (exec'd one level
    # down, so its pid is known) takes the terminal's stderr back from fd 3.
    # The waiter, not this shell, is what parks stderr: under set -m bash hands
    # the terminal over through its OWN stderr, and redirecting it here left the
    # shell in a background process group (SIGTTIN, status 149).
    '    ( exec 3>&2 2>/dev/null\n'
    '      ( exec 2>&3 3>&-\n'
    '        echo "$BASHPID" > "$CLOUD_LOGIN_PROMPT_SEEN.pid"\n'
    '        exec -a "-${usershell##*/}" "$usershell" --init-command \''
    'function __cloud_prompt_seen --on-event fish_prompt; functions -e __cloud_prompt_seen; '
    'true >$CLOUD_LOGIN_PROMPT_SEEN; set -e CLOUD_LOGIN_PROMPT_SEEN; end\' ) || exit $? ) || rc=$?\n'
    '    kill "$watchdog" 2>/dev/null || true\n'
    '    set +m\n'
    '    if [ -e "$CLOUD_LOGIN_PROMPT_SEEN" ]; then\n'
    '      rm -f "$CLOUD_LOGIN_PROMPT_SEEN" "$CLOUD_LOGIN_PROMPT_SEEN.pid"\n'
    '      exit "$rc"\n'
    '    fi\n'
    '    why="ended (status $rc) before drawing a prompt"\n'
    '    if [ -e "$CLOUD_LOGIN_PROMPT_SEEN.late" ]; then why="drew no prompt within ${deadline}s"; fi\n'
    '    rm -f "$CLOUD_LOGIN_PROMPT_SEEN.pid" "$CLOUD_LOGIN_PROMPT_SEEN.late"\n'
    '    echo "⚠ login shell $usershell $why; falling back to bash" >&2\n'
    '    exec -l bash'
)
USERSHELL_PROBE = (
    'if ! command -v timeout >/dev/null 2>&1 '
    '|| timeout 5 "$usershell" -c exit >/dev/null 2>&1; then\n'
    f'    {USERSHELL_PROMPT_BOUNDED}\n'
    '  fi\n'
    '  echo "⚠ login shell $usershell failed its 5s liveness probe; falling back to bash" >&2\n'
    '  exec -l bash'
)


def probe_usershell_exec(login_inner: str) -> str:
    """Bound the login-shell exec with a liveness probe; bash is the net.

    `timeout 5 "$usershell" -c exit` is the cheapest complete life sign a shell
    has: it must exec, parse and exit. A shell that cannot do that in 5s does
    not get the terminal -- the login says so in ONE stderr line and falls back
    to upstream's own `exec -l bash`, so a prompt ALWAYS arrives. When `timeout`
    itself is absent (a broken profile) the probe abstains rather than exiling a
    healthy shell to bash. Pure str -> str like every patch here, so the tester
    executes both verdicts.
    """
    if login_inner.count(USERSHELL_EXEC) != 1:
        raise ValueError("expected exactly one usershell exec in usr/lib/login-inner:\n"
                         f"  {USERSHELL_EXEC}")
    return login_inner.replace(USERSHELL_EXEC, USERSHELL_PROBE, 1)


def require_fish_login_shell(login_shell: str) -> None:
    """USERSHELL_PROMPT_BOUNDED hands the shell a fish --init-command; any other
    login shell would reject it and land in bash on every login, loudly but
    permanently. Fail the build instead of shipping that."""
    if login_shell != "fish":
        raise ValueError(f"login_shell_attr is {login_shell!r}, but the bounded login-shell "
                         "start speaks fish (--init-command / fish_prompt)")


def retarget_usershell(login_inner: str, profile_link: str, login_shell: str) -> str:
    """#641 -- upstream's generated `usershell=` points at the zip's own bash.

    MEASURED in the pinned zip: usershell="/nix/store/<hash>-bash-5.2-p15/bin/bash",
    and that literal appears again in the "Cannot execute shell ..." fallback
    message, so BOTH move -- a retargeted assignment with a message still naming
    bash is how a user is told the wrong thing about his own shell. The new value
    is derived from profile_link, never a /nix/store literal, for the same reason
    the env link is: every hash moves with the nixpkgs pin.
    """
    m = re.search(r'^usershell="([^"]+)"$', login_inner, re.M)
    if not m:
        raise ValueError('expected one `usershell="..."` assignment in usr/lib/login-inner')
    old = m.group(1)
    new = f"/{profile_link}/bin/{login_shell}"
    if old == new:
        raise ValueError(f"usershell is already {new}; the patch would be a silent no-op")
    return login_inner.replace(old, new)


def patch_etc_profile(etc_profile: str, fallback_script: str) -> str:
    """#641 -- the noise on EVERY login, and it is upstream's file, not ours.

    Diego's phone printed, verbatim:

      -bash: /nix/store/5pmf0nlk34…-nix-on-droid-session-init.sh/etc/profile.d/nix-on-droid-session-init.sh: No such file or directory

    (hash elided to a prefix on purpose -- the tester asserts no full store hash
    survives anywhere in this file, and a quoted one would defeat that check. The
    full line is in test-bootstrap-baked.sh's fixture, where it is the subject.)

    That is the ENTIRE content of etc/static/profile in the pinned zip (symlinked
    as etc/profile, read by the login bash), sourcing a store path that the zip
    does not contain -- `nix-on-droid switch` is what would build it, and skipping
    that wizard is this whole block's purpose. So the line can never succeed on a
    fresh install of this app. It is guarded exactly like login-inner's own
    session-init line, falling back to the baked PATH script, and NOT deleted: a
    phone that later does run a real nix-on-droid generation gets it back.
    """
    body = [l for l in etc_profile.splitlines() if l.strip()]
    if len(body) != 1 or not body[0].lstrip().startswith('. "') or '"' not in body[0][3:]:
        raise ValueError("etc/static/profile is not the single `. \"<path>\"` line this patch "
                         f"knows how to guard:\n  {etc_profile!r}")
    src = body[0].strip()
    path = src.split('"')[1]
    return (f'if [ -e "{path}" ]; then\n  {src}\nelse\n  . /{fallback_script}\nfi\n')


# claude-code carries an unfree license in nixpkgs (Anthropic's own terms,
# not a nixpkgs restriction) -- nix's eval refuses it unless this is set, the
# same override `nixos-rebuild`/`nix-env` users are told to add for it.
NIX_ENV = {**os.environ, "NIXPKGS_ALLOW_UNFREE": "1"}


def run(cmd):
    print("+ " + " ".join(cmd), file=sys.stderr)
    return subprocess.run(cmd, check=True, env=NIX_ENV)


def capture(cmd):
    return subprocess.run(cmd, check=True, capture_output=True, text=True, env=NIX_ENV).stdout


def main() -> int:
    if len(sys.argv) != 11:
        print(
            "usage: bake_default_packages.py <input.zip> <output.zip> "
            "<nixpkgs_pin> <attrs csv> <profile_link> <fallback_init_script> <app_id> <nix_system> "
            "<shared_root_name> <login_shell_attr>",
            file=sys.stderr,
        )
        return 2

    (input_zip, output_zip, pin, attrs_csv, profile_link, fallback_script, app_id, nix_system,
     shared_root_name, login_shell) = sys.argv[1:11]
    attrs = [a for a in attrs_csv.split(",") if a]
    if not attrs:
        print("no attrs given", file=sys.stderr)
        return 2
    if login_shell not in attrs:
        print(f"FAIL: login_shell_attr {login_shell!r} is not in default_packages.attrs "
              f"({attrs}) -- the login shell would be a path nothing bakes", file=sys.stderr)
        return 2

    with tempfile.TemporaryDirectory() as work:
        profile = os.path.join(work, "profile")
        # The CI runner is always x86_64-linux; legacyPackages.<system> (rather
        # than the plain #attr shorthand, which resolves against the CALLER's
        # system) is what lets one runner bake either ABI's binaries, pulling
        # pre-built substitutes for the foreign arch from cache.nixos.org for
        # most attrs. That assumption held for git/nodejs_22 but not for
        # claude-code: cache.nixos.org does not carry an aarch64-linux
        # substitute for every claude-code release, so an arm64 job with no
        # foreign builder registered hard-fails with "platform mismatch"
        # instead of falling back to a (slower, but working) build. Registering
        # binfmt_misc for the foreign arch -- one-time, host-wide, via the
        # already-present docker daemon -- and telling nix that arch is locally
        # buildable turns that hard-fail into a QEMU-emulated build instead.
        host_arch = capture(["uname", "-m"]).strip()
        foreign_arch = nix_system.split("-")[0]
        nix_arch_of_host = {"x86_64": "x86_64", "aarch64": "aarch64", "arm64": "aarch64"}.get(host_arch, host_arch)
        extra_platform_args = []
        if foreign_arch != nix_arch_of_host:
            print(f"host is {host_arch}, baking for {nix_system}: registering QEMU emulation "
                  f"so nix can build (not just substitute) {foreign_arch} derivations", file=sys.stderr)
            run(["docker", "run", "--rm", "--privileged", "tonistiigi/binfmt", "--install", "all"])
            extra_platform_args = ["--extra-platforms", nix_system]

        refs = [f"github:NixOS/nixpkgs/{pin}#legacyPackages.{nix_system}.{a}" for a in attrs]
        run(["nix", "profile", "install", "--profile", profile, *refs, "--impure",
             "--extra-experimental-features", "nix-command flakes", *extra_platform_args])

        generation = capture(["readlink", "-f", profile]).strip()
        if not os.path.exists(generation):
            print(f"FAIL: realized profile generation {generation} does not exist", file=sys.stderr)
            return 1

        closure = capture(["nix-store", "-qR", generation]).split()
        if not closure:
            print("FAIL: empty closure for the realized profile", file=sys.stderr)
            return 1
        print(f"closure: {len(closure)} store paths", file=sys.stderr)

        with zipfile.ZipFile(input_zip) as zin:
            existing = set(zin.namelist())
            login_inner = zin.read("usr/lib/login-inner").decode()
            bin_login = zin.read("bin/login").decode()
            symlinks_txt = zin.read("SYMLINKS.txt").decode()
            executables_txt = zin.read("EXECUTABLES.txt").decode()
            etc_profile = zin.read(ETC_PROFILE_ENTRY).decode()

            # ── #612: auto-mount shared storage + the cloud-drive shared
            # store into $HOME, the same generated-text-injection technique
            # as the session-init patch below. bin/login runs with no -r (it
            # binds individual dirs onto the real Android root, it does not
            # chroot), so $HOME here is already the real on-device path and
            # these two mountpoints just need to exist under it before the
            # exec, guarded like the fakeProcStat/fakeProcUptime binds above
            # them so a missing source (e.g. cloud-drive never opened yet)
            # degrades to no bind instead of a failed one.
            exec_line = f"exec /data/data/{app_id}/files/usr/bin/proot-static \\"
            if bin_login.count(exec_line) != 1:
                print(f"FAIL: expected exactly one proot-static exec line in bin/login:\n  {exec_line}",
                      file=sys.stderr)
                return 1
            # /storage/emulated/0 exists as a directory even without All-Files-Access, but is
            # then not traversable, so binding it would mount an empty tree silently. Probe
            # readability (ls) rather than existence (-d); when it fails, state the fact in ONE
            # line and skip both binds instead of mounting a dark tree. #639: the line does NOT
            # describe a route through Settings -- TermuxActivity.requestManageStorageIfNeeded()
            # deep-links straight to this package's All-Files-Access toggle on every launch that
            # lacks the grant, so prose telling the user to go find it himself is redundant.
            mount_setup = (
                'mkdir -p "$HOME/emulated" "$HOME/cloud-drive-shared-store" 2>/dev/null || true\n'
                'if ls /storage/emulated/0 >/dev/null 2>&1; then\n'
                '  BIND_HOME_EMULATED="-b /storage/emulated/0:$HOME/emulated"\n'
                f'  mkdir -p "/storage/emulated/0/{shared_root_name}" 2>/dev/null || true\n'
                f'  if [ -d "/storage/emulated/0/{shared_root_name}" ]; then\n'
                f'    BIND_HOME_SHARED_STORE="-b /storage/emulated/0/{shared_root_name}:$HOME/cloud-drive-shared-store"\n'
                '  else\n'
                '    BIND_HOME_SHARED_STORE=""\n'
                '  fi\n'
                'else\n'
                '  BIND_HOME_EMULATED=""\n'
                '  BIND_HOME_SHARED_STORE=""\n'
                '  echo "⚠ cloud-drive shared store not mounted: storage access is not granted yet." >&2\n'
                'fi\n\n'
            )
            bin_login = bin_login.replace(
                exec_line,
                mount_setup + exec_line + "\n  $BIND_HOME_EMULATED \\\n  $BIND_HOME_SHARED_STORE \\",
                1,
            )

            # ── #644: the declarative link store, and its login wiring ─────
            try:
                render_store = load_render_store()
                store_decl = render_store.load()["store"]
                store_dir = store_decl["install_dir"]
                store_files = {
                    store_decl["engine"]: ((STORE_SRC / store_decl["engine"]).read_bytes(), True),
                    "login-init.sh": ((STORE_SRC / "login-init.sh").read_bytes(), False),
                    "login-exec": ((STORE_SRC / "login-exec").read_bytes(), True),
                    "declaration.sh": (render_store.render("nix").encode(), False),
                }
                store_tools = render_store.tools_of("nix")
            except ValueError as e:
                print(f"FAIL: {e}", file=sys.stderr)
                return 1

            # ── patch login-inner's session-init line and its env execs ────
            try:
                login_inner = patch_login_inner(login_inner, app_id, fallback_script,
                                                profile_link, login_shell, store_dir)
                etc_profile = patch_etc_profile(etc_profile, fallback_script)
            except ValueError as e:
                print(f"FAIL: {e}", file=sys.stderr)
                return 1

            # ── new manifest lines ─────────────────────────────────────────
            new_symlinks = []
            new_executables = []
            new_files = {}  # zip path -> bytes

            def add_tree(store_path: str):
                base = store_path.lstrip("/")
                if base in existing or base in new_files or any(
                    s.endswith(f"←{base}") for s in new_symlinks
                ):
                    return  # this exact store path already shipped
                for root, dirs, files in os.walk(store_path):
                    rel_root = os.path.relpath(root, store_path)
                    for name in dirs + files:
                        full = os.path.join(root, name)
                        rel = f"{base}/{name}" if rel_root == "." else f"{base}/{rel_root}/{name}"
                        if rel in existing:
                            continue
                        if os.path.islink(full):
                            target = os.readlink(full)
                            new_symlinks.append(f"{target}←{rel}")
                        elif os.path.isdir(full):
                            continue  # created implicitly on extraction
                        else:
                            data = Path(full).read_bytes()
                            new_files[rel] = data
                            if os.access(full, os.X_OK):
                                new_executables.append(rel)

            for store_path in closure:
                add_tree(store_path)

            # the profile generation itself becomes the DEFAULT profile
            new_symlinks.append(f"{generation}←{profile_link}")

            # ── #640: and /usr/bin/env resolves through that same profile ───
            env_in_profile = os.path.join(generation, "bin", "env")
            if not os.path.islink(env_in_profile) and not os.path.exists(env_in_profile):
                print(f"FAIL: the realized profile has no bin/env ({env_in_profile}) -- "
                      f"coreutils is missing from default_packages.attrs ({attrs})", file=sys.stderr)
                return 1
            if ENV_SYMLINK in existing:
                print(f"FAIL: the input zip already carries {ENV_SYMLINK}; a second entry for it "
                      "would make which one wins depend on extraction order", file=sys.stderr)
                return 1
            why = env_target_unreachable(
                os.path.realpath(env_in_profile).lstrip("/"), existing, new_files, new_executables)
            if why:
                print(f"FAIL: /{ENV_SYMLINK} would point at something unrunnable: {why}", file=sys.stderr)
                return 1
            new_symlinks.append(env_symlink_line(profile_link))

            # ── #641: and the login shell login-inner now points at really ships
            shell_in_profile = os.path.join(generation, "bin", login_shell)
            if not os.path.islink(shell_in_profile) and not os.path.exists(shell_in_profile):
                print(f"FAIL: the realized profile has no bin/{login_shell} ({shell_in_profile}) -- "
                      f"login-inner would name a login shell that is not in the store", file=sys.stderr)
                return 1
            why = env_target_unreachable(
                os.path.realpath(shell_in_profile).lstrip("/"), existing, new_files, new_executables)
            if why:
                print(f"FAIL: the {login_shell} login shell would not be runnable: {why}", file=sys.stderr)
                return 1

            # ── #644: every tool the store declares must really be in the profile
            # The store links $HOME/.cloud-store/current/bin/<tool> at profile
            # bin/<tool> for each name in default_packages.binaries. A name in
            # that list with no binary behind it is a link that dangles on every
            # phone -- the #638/#640/#641 shape, one layer up -- and the list is
            # maintained beside attrs and provides, so it CAN drift from them.
            # Proving it here is what makes "adding a tool is a data-only edit"
            # true rather than merely intended.
            store_missing = profile_missing(generation, store_tools)
            if store_missing:
                print(f"FAIL: default_packages.binaries names {store_missing}, which the realized "
                      f"profile does not provide -- the link store would ship dangling links. "
                      f"Add the attr that provides them, or drop the names.", file=sys.stderr)
                return 1

            for name, (data, executable) in store_files.items():
                rel = f"{store_dir}/{name}"
                if rel in existing:
                    print(f"FAIL: the input zip already carries {rel}; a second entry would make "
                          "which one wins depend on extraction order", file=sys.stderr)
                    return 1
                new_files[rel] = data
                if executable:
                    new_executables.append(rel)
            print(f"#644 store: {len(store_files)} files at {store_dir}, "
                  f"{len(store_tools)} declared tools", file=sys.stderr)

            fallback_body = (
                "# #595 -- baked default tooling, sourced by usr/lib/login-inner when\n"
                "# $HOME/.nix-profile does not exist (the wizard that would normally\n"
                "# create it never ran, because the packages are already installed).\n"
                f'export PATH="/{profile_link}/bin:$PATH"\n'
            )
            new_files[fallback_script] = fallback_body.encode()

            if not symlinks_txt.endswith("\n"):
                symlinks_txt += "\n"
            symlinks_txt += "\n".join(new_symlinks) + "\n"
            if not executables_txt.endswith("\n"):
                executables_txt += "\n"
            executables_txt += "\n".join(new_executables) + "\n"

            print(f"adding {len(new_files)} files, {len(new_symlinks)} symlinks, "
                  f"{len(new_executables)} new executables", file=sys.stderr)

            with zipfile.ZipFile(output_zip, "w", zipfile.ZIP_DEFLATED) as zout:
                for info in zin.infolist():
                    name = info.filename
                    if name == DROPPED_ENTRY:
                        continue
                    if name == "usr/lib/login-inner":
                        zout.writestr(info, login_inner)
                    elif name == "bin/login":
                        zout.writestr(info, bin_login)
                    elif name == "SYMLINKS.txt":
                        zout.writestr(info, symlinks_txt)
                    elif name == "EXECUTABLES.txt":
                        zout.writestr(info, executables_txt)
                    elif name == ETC_PROFILE_ENTRY:
                        zout.writestr(info, etc_profile)
                    else:
                        zout.writestr(info, zin.read(name))
                for rel, data in new_files.items():
                    zout.writestr(rel, data)

    print(f"OK: {output_zip} carries {attrs} baked into {profile_link}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
