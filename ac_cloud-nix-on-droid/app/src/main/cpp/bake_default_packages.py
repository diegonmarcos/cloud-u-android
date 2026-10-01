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
    fallback_line = (
        'cloud_trace "session init: sourcing"\n'
        f'if [ -e "/data/data/{app_id}/files/home/.nix-profile/etc/profile.d/nix-on-droid-session-init.sh" ]; then\n  {want}\nelse\n  . /{fallback_script}\nfi\n'
        'cloud_trace "session init: done"')
    # #644 -- and the store, UNCONDITIONALLY: outside the if/else above, because
    # the branch that runs depends on whether a real nix-on-droid generation was
    # ever built, while the link store has to be initialised and verified either
    # way. It goes after, so the profile PATH the fallback exports is already set.
    if store_install_dir:
        fallback_line = (fallback_line + '\ncloud_trace "store init: start"\n'
                         + store_init_line(store_install_dir) + '\ncloud_trace "store init: done"')
    login_inner = head + fallback_line + tail
    login_inner = add_login_trace(login_inner)

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
# The bash tier needs no terminal handshake, so it gets half the time.
BASH_PROMPT_DEADLINE_S = 10
USERSHELL_PROMPT_BOUNDED = (
    f'cloud_try_shell "$usershell" "${{CLOUD_LOGIN_PROMPT_DEADLINE:-{USERSHELL_PROMPT_DEADLINE_S}}}" bash '
    '"-${usershell##*/}" "$usershell" --init-command \''
    'echo "[fish] config read, init-command ran" >>$CLOUD_LOGIN_LOG; '
    'function __cloud_prompt_seen --on-event fish_prompt; functions -e __cloud_prompt_seen; '
    'echo "[fish] first prompt" >>$CLOUD_LOGIN_LOG; '
    'true >$CLOUD_LOGIN_PROMPT_SEEN; set -e CLOUD_LOGIN_PROMPT_SEEN; end\''
)
# #715 -- v0.3.11 fell back to `exec -l bash` and the phone showed no bash prompt
# either, so the blocker is not fish. Two more tiers, each a bisection step:
#   2. bash with NO profile and NO rc, bounded like fish (PROMPT_COMMAND is the
#      marker, and with --norc nothing can override it). If this prompts, the
#      blocker is in the profile/env layer; if it does not, it is below any
#      shell's own scripts.
#   3. rescue: bash running /dev/stdin as a SCRIPT. Non-interactive, so it skips
#      every interactive-only step (job-control handshake, tty modes, line
#      editing) and simply executes each line typed. If even this does not run
#      a typed line, the blocker is below the shell (proot / the kernel).
USERSHELL_FALLBACK = (
    'export PROMPT_COMMAND=\': >"$CLOUD_LOGIN_PROMPT_SEEN"; unset PROMPT_COMMAND CLOUD_LOGIN_PROMPT_SEEN\'\n'
    f'  cloud_try_shell bash "${{CLOUD_LOGIN_BASH_DEADLINE:-{BASH_PROMPT_DEADLINE_S}}}" "rescue mode" '
    '-bash bash --noprofile --norc -i\n'
    '  unset PROMPT_COMMAND\n'
    '  trap \'\' TTOU; stty sane 2>/dev/null || true; trap - TTOU\n'
    '  cloud_trace "rescue: $(cloud_proc $$)"\n'
    '  echo "⚠ rescue mode: no shell drew a prompt; each line typed still runs (no prompt, no editing). Log: $CLOUD_LOGIN_LOG" >&2\n'
    '  exec bash --noprofile --norc /dev/stdin'
)
USERSHELL_PROBE = (
    'cloud_trace "probe: $usershell -c exit (bounded 5s)"\n'
    '  if ! command -v timeout >/dev/null 2>&1 '
    '|| timeout 5 "$usershell" -c exit >/dev/null 2>&1; then\n'
    '    cloud_trace "probe: passed"\n'
    f'    {USERSHELL_PROMPT_BOUNDED}\n'
    '  else\n'
    '    echo "⚠ login shell $usershell failed its 5s liveness probe; falling back to bash" >&2\n'
    '  fi\n'
    f'  {USERSHELL_FALLBACK}'
)

# #715 -- the trace every step above writes through, plus the bounded start each
# shell tier goes through. Inserted right after login-inner's `set -eo pipefail`
# (bin/sh there is bash 5.2, so EPOCHREALTIME and printf %()T are builtins and
# nothing here needs PATH, which is not set yet).
#
# cloud_proc reads /proc/<pid>/stat: pgrp vs tpgid (the tty's FOREGROUND process
# group) is the job-control question. A shell whose pgrp is not the tty's
# foreground pgrp stops itself with SIGTTIN before its prompt -- and under proot
# its kill(0, SIGTTIN) stops proot too. Measured off-device with the shipped
# proot-static: that gives exactly the phone's screen (typed line echoed by the
# cooked tty, never run, shell with no child). state/wchan/syscall/signal masks
# at the deadline then say whether a hung shell is STOPPED (T/t: job control) or
# SLEEPING in a syscall (S + the syscall number: what it is blocked on).
#
# Screen lines only for an interactive login ($# = 0); a `login <cmd>` caller
# (RunCommandService) gets its command's output and nothing else, while the file
# still gets the lines.
LOGIN_TRACE_ANCHOR = "set -eo pipefail\n"
LOGIN_TRACE = r"""
CLOUD_LOGIN_LOG="${CLOUD_LOGIN_LOG:-${HOME:-/tmp}/.cloud-login.log}"
export CLOUD_LOGIN_LOG
if [ "$#" -eq 0 ]; then CLOUD_LOGIN_SCREEN=1; else CLOUD_LOGIN_SCREEN=; fi
cloud_trace() {
  local t
  printf -v t '%(%H:%M:%S)T.%s' -1 "${EPOCHREALTIME#*.}"
  printf '[login %s] %s\n' "${t:0:12}" "$*" >> "$CLOUD_LOGIN_LOG" 2>/dev/null || true
  if [ -n "$CLOUD_LOGIN_SCREEN" ]; then printf '[login %s] %s\n' "${t:0:12}" "$*" >&2 || true; fi
}
cloud_proc() {
  local st="" w="?" sc="?" k v sig="" fg
  { read -r st < "/proc/$1/stat"; } 2>/dev/null || { echo "pid $1: gone"; return 0; }
  set -- "$1" ${st##*) }
  { read -r w < "/proc/$1/wchan"; } 2>/dev/null || true
  { read -r sc _ < "/proc/$1/syscall"; } 2>/dev/null || true
  while read -r k v; do
    case "$k" in State:|SigPnd:|ShdPnd:|SigBlk:|SigIgn:|TracerPid:) sig="$sig ${k%:}=${v%% *}";; esac
  done 2>/dev/null < "/proc/$1/status" || true
  if [ "$7" = "-1" ]; then fg="NO-CONTROLLING-TTY"
  elif [ "$4" = "$7" ]; then fg="FOREGROUND"
  else fg="BACKGROUND(tty foreground pgrp $7)"; fi
  echo "pid $1 ppid=$3 pgrp=$4 sid=$5 tpgid=$7 $fg wchan=${w:-?} syscall=${sc:-?}$sig"
}
cloud_tty() {
  local - m o=""
  set -f
  m="$(stty -a 2>&1)" || { echo "tty: stty failed: $m"; return 0; }
  for w in $m; do case "$w" in icanon|-icanon|echo|-echo|isig|-isig) o="$o $w";; esac; done
  echo "tty modes:$o"
}
# $1 the shell's name in messages, $2 the deadline (s), $3 the next tier's name,
# then exec -a's argv0 and the command. Returns only when the shell never drew a
# prompt; a shell that did ends the login with its own status.
cloud_try_shell() {
  local name="$1" deadline="$2" next="$3" argv0="$4" rc=0 watchdog why
  shift 4
  CLOUD_LOGIN_PROMPT_SEEN="${TMPDIR:-/tmp}/.cloud-login-prompt.$$"
  export CLOUD_LOGIN_PROMPT_SEEN
  rm -f "$CLOUD_LOGIN_PROMPT_SEEN" "$CLOUD_LOGIN_PROMPT_SEEN.pid" "$CLOUD_LOGIN_PROMPT_SEEN.late"
  # set -m (only with a tty) makes bash hand the terminal to the job and take it
  # back when the job dies, so the next tier is never left in a background pgrp.
  if [ -t 0 ]; then set -m; fi
  # TTOU ignored: with TOSTOP on, a background write to the tty would stop the
  # watchdog itself, and a stopped watchdog never kills the hung shell.
  ( trap '' TTOU
    sleep "$deadline"
    if [ ! -e "$CLOUD_LOGIN_PROMPT_SEEN" ]; then
      : > "$CLOUD_LOGIN_PROMPT_SEEN.late"
      p="$(cat "$CLOUD_LOGIN_PROMPT_SEEN.pid" 2>/dev/null)" || p=""
      cloud_trace "$name: no prompt after ${deadline}s: $(cloud_proc "$p"); $(cloud_tty)"
      kill -KILL "$p" 2>/dev/null || true
    fi ) &
  watchdog=$!
  # The job is a waiter bash whose own stderr is /dev/null, so bash's "Killed"
  # report about the shell lands there; the shell itself (exec'd one level
  # down, so its pid is known) takes the terminal's stderr back from fd 3.
  # The waiter, not this shell, is what parks stderr: under set -m bash hands
  # the terminal over through its OWN stderr, and redirecting it here left the
  # shell in a background process group (SIGTTIN, status 149).
  ( exec 3>&2 2>/dev/null
    ( exec 2>&3 3>&-
      me=$BASHPID
      echo "$me" > "$CLOUD_LOGIN_PROMPT_SEEN.pid"
      cloud_trace "$name: starting, $(cloud_proc "$me")"
      exec -a "$argv0" "$@" ) || exit $? ) || rc=$?
  kill "$watchdog" 2>/dev/null || true
  set +m
  if [ -e "$CLOUD_LOGIN_PROMPT_SEEN" ]; then
    rm -f "$CLOUD_LOGIN_PROMPT_SEEN" "$CLOUD_LOGIN_PROMPT_SEEN.pid"
    cloud_trace "$name: drew its prompt, session ended with status $rc"
    exit "$rc"
  fi
  why="ended (status $rc) before drawing a prompt"
  if [ -e "$CLOUD_LOGIN_PROMPT_SEEN.late" ]; then why="drew no prompt within ${deadline}s"; fi
  # A shell STOPPED by job control (status 149: SIGTTIN) is still alive; the next tier must not inherit it.
  kill -KILL "$(cat "$CLOUD_LOGIN_PROMPT_SEEN.pid" 2>/dev/null)" 2>/dev/null || true
  rm -f "$CLOUD_LOGIN_PROMPT_SEEN.pid" "$CLOUD_LOGIN_PROMPT_SEEN.late"
  cloud_trace "$name: $why"
  echo "⚠ login shell $name $why; falling back to $next" >&2
}
cloud_trace "login-inner: start, $(cloud_proc $$); stdin is $([ -t 0 ] || printf 'NOT ')a tty"
cloud_trace "login-inner: parent (proot) $(cloud_proc $PPID)"
"""


# #715 -- the Android-side half of the trace (bin/login is /system/bin/sh, i.e.
# mksh, and runs BEFORE proot). It starts the log fresh for each interactive
# login, records whether the session leader owns the tty before proot is even
# exec'd, and states the storage probe's real error instead of only a verdict.
BIN_LOGIN_TRACE = r"""CLOUD_LOGIN_LOG="$HOME/.cloud-login.log"
if [ "$#" -eq 0 ]; then CLOUD_LOGIN_SCREEN=1; : > "$CLOUD_LOGIN_LOG" 2>/dev/null || true; else CLOUD_LOGIN_SCREEN=; fi
cloud_trace() {
  m="[login $(/system/bin/date +%H:%M:%S 2>/dev/null || echo '?') android] $*"
  echo "$m" >> "$CLOUD_LOGIN_LOG" 2>/dev/null || true
  if [ -n "$CLOUD_LOGIN_SCREEN" ]; then echo "$m" >&2; fi
}
cloud_jobctl() {
  st=""
  { read -r st < "/proc/$1/stat"; } 2>/dev/null || { echo "pid $1: gone"; return 0; }
  set -- "$1" ${st##*) }
  echo "pid $1 state=$2 pgrp=$4 sid=$5 tpgid=$7"
}
cloud_trace "bin/login: start, $(cloud_jobctl $$)"
"""


def storage_setup(shared_root_name: str) -> str:
    """#612/#736: bin/login's shared-storage block. ~/emulated and ~/cloud-drive-shared-store are
    SYMLINKS to /storage/emulated/0 and its <shared_root_name> store. bin/login runs proot with no
    -r (it binds individual dirs onto the real Android root, it does not chroot), so $HOME and
    /storage are the same paths inside the session as outside it and a plain link needs no bind;
    the binds this replaces only ever showed the terminal what the mount step happened to leave.

    /storage/emulated/0 exists as a directory even without the storage grant, but is then not
    traversable, so readability (ls), not existence, is probed; when it fails ls's own error is
    traced and ONE line says why. #730: nothing is left that reads as an empty STORE -- only our
    link or an EMPTY directory is ever removed (rmdir never removes content). The upstream ~/storage
    tree (termux-setup-storage's links) is a second entry for the same storage, so its symlinks go.

    #748: EVERY command here that is not a shell builtin is named by its /system/bin path, as
    upstream's own bin/login does for pgrep and mv. bin/login is mksh on the Android host, before
    proot, and its PATH is the session's $PREFIX/bin -- which in this rootfs holds only login,
    proot-static and an sh symlink into /nix/store that does not resolve outside proot. A bare `rm`
    is "inaccessible or not found", exit 127, and under set -e that ends every login: the phone
    showed `usr/bin/login[62]: rm: inaccessible or not found` for every command once an older
    install's ~/storage tree gave the cleanup loop a link to remove."""
    store = f"/storage/emulated/0/{shared_root_name}"
    return (
        'cloud_storage_link() {\n'
        '  [ -L "$1" ] || /system/bin/rmdir "$1" 2>/dev/null || true\n'
        '  if [ -e "$1" ] && [ ! -L "$1" ]; then\n'
        '    echo "⚠ $1 is a directory with content, so it is not replaced by the link to $2" >&2\n'
        '  else\n'
        '    /system/bin/ln -sfn "$2" "$1"\n'
        '  fi\n'
        '}\n'
        'cloud_storage_unlink() {\n'
        '  if [ -L "$1" ]; then /system/bin/rm -f "$1"; else /system/bin/rmdir "$1" 2>/dev/null || true; fi\n'
        '}\n'
        'if [ -d "$HOME/storage" ] && [ ! -L "$HOME/storage" ]; then\n'
        '  for l in "$HOME/storage"/*; do [ ! -L "$l" ] || /system/bin/rm -f "$l"; done\n'
        '  /system/bin/rmdir "$HOME/storage" 2>/dev/null || true\n'
        'fi\n'
        'if CLOUD_LS_ERR="$(/system/bin/ls /storage/emulated/0 2>&1 >/dev/null)"; then\n'
        '  cloud_trace "storage: /storage/emulated/0 is readable, linking ~/emulated"\n'
        '  cloud_storage_link "$HOME/emulated" /storage/emulated/0\n'
        f'  /system/bin/mkdir -p "{store}" 2>/dev/null || true\n'
        f'  if [ -d "{store}" ]; then\n'
        f'    cloud_storage_link "$HOME/cloud-drive-shared-store" "{store}"\n'
        '  else\n'
        f'    cloud_trace "storage: could not create {store}"\n'
        '  fi\n'
        'else\n'
        '  cloud_trace "storage: ls /storage/emulated/0 failed: $CLOUD_LS_ERR"\n'
        '  cloud_storage_unlink "$HOME/emulated"\n'
        '  cloud_storage_unlink "$HOME/cloud-drive-shared-store"\n'
        '  echo "⚠ cloud-drive shared store not mounted: storage access is not granted. Allow it on the Cloud Terminal prompt, then open a new session." >&2\n'
        'fi\n'
    )


def add_login_trace(login_inner: str) -> str:
    """#715 -- define the trace and the bounded shell start, and log the start.

    Pure str -> str like every patch here; raises on a drifted anchor rather
    than shipping a login-inner whose cloud_trace calls are undefined (which,
    under login-inner's set -e, would end every login at its first step).
    """
    if login_inner.count(LOGIN_TRACE_ANCHOR) != 1:
        raise ValueError(f"expected exactly one {LOGIN_TRACE_ANCHOR.strip()!r} in usr/lib/login-inner")
    return login_inner.replace(LOGIN_TRACE_ANCHOR, LOGIN_TRACE_ANCHOR + LOGIN_TRACE, 1)


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

            # ── #612/#736: shared storage + the cloud-drive shared store in
            # $HOME (storage_setup), the same generated-text-injection
            # technique as the session-init patch below, run before the exec.
            exec_line = f"exec /data/data/{app_id}/files/usr/bin/proot-static \\"
            if bin_login.count(exec_line) != 1:
                print(f"FAIL: expected exactly one proot-static exec line in bin/login:\n  {exec_line}",
                      file=sys.stderr)
                return 1
            mount_setup = BIN_LOGIN_TRACE + storage_setup(shared_root_name) + 'cloud_trace "exec proot-static"\n\n'
            bin_login = bin_login.replace(exec_line, mount_setup + exec_line, 1)

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
