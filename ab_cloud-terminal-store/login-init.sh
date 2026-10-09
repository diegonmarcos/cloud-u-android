# #644 — what a login does about the store, in both terminals.
#
# SOURCED, never executed, so the PATH it exports survives into the shell:
#   nix terminal     usr/lib/login-inner, patched by bake_default_packages.py
#   termux terminal  login-exec beside this file, which enter.sh execs
#
# Two rules it must not break:
#   1. it never blocks a login. A store that cannot be built costs a broken
#      store, not a phone with no shell — #638 is what a boot path that can
#      fail looks like from the owner's end.
#   2. it decides nothing. The store layout, the tool list and the targets all
#      come from declaration.sh, which render-store.py generated.
: "${CLOUD_STORE_INSTALL_DIR:=/usr/lib/cloud-store}"   # MUST equal "/" + store.json::store.install_dir (render-store.py asserts it)

# startup-step: linux_path
# store.json::linux_tools bakes linux-store and linux-account into /usr/local/bin in both images
# (fetch-linux-tools.py). termux's enter.sh already puts that on PATH; the nix login's PATH is the
# profile's, so this makes it the same for both without either knowing about the other.
case ":${PATH:-}:" in
    *:/usr/local/bin:*) ;;
    *) PATH="${PATH:+$PATH:}/usr/local/bin"; export PATH ;;
esac

# startup-step: fish_greeting_path
# ...and the greeting as /usr/share/fish/vendor_functions.d/fish_greeting.fish. fish finds vendor
# functions through XDG_DATA_DIRS, whose default (unset) already lists /usr/share; a login that
# sets it without /usr/share would hide the greeting, so it is added, never replaced.
case ":${XDG_DATA_DIRS:-/usr/local/share:/usr/share}:" in
    *:/usr/share:*) ;;
    *) XDG_DATA_DIRS="${XDG_DATA_DIRS:+$XDG_DATA_DIRS:}/usr/share"; export XDG_DATA_DIRS ;;
esac

if [ -r "$CLOUD_STORE_INSTALL_DIR/declaration.sh" ]; then
    # shellcheck disable=SC1091 # generated, and its path is the one literal here
    . "$CLOUD_STORE_INSTALL_DIR/declaration.sh"
    PATH="${HOME:-/root}/$CLOUD_STORE_ROOT/$CLOUD_STORE_CURRENT/$CLOUD_STORE_BIN:$PATH"
    export PATH
    # startup-step: agent_auth_env
    # #790 the agent CLIs' credentials: the app writes its agent-auth store (Account ▸ settings,
    # FleetConfig) to this file on every start, one `export NAME='value'` per key, and this puts
    # them in every session's environment. Silent: no value reaches the terminal or a log.
    if [ -n "${CLOUD_AGENT_AUTH_ENV:-}" ] && [ -r "${HOME:-/root}/$CLOUD_AGENT_AUTH_ENV" ]; then
        # shellcheck disable=SC1090 # written by the app, path from declaration.sh
        . "${HOME:-/root}/$CLOUD_AGENT_AUTH_ENV" >/dev/null 2>&1 \
            || echo "⚠ agent credentials could not be read; claude/goose/hermes start logged out" >&2
    fi
    # startup-step: store_init
    # Rule 1, enforced rather than hoped: `ensure` forks the engine and, behind
    # it, dozens of subprocesses -- under single-threaded ptrace proot that once
    # wedged the nix terminal right after the welcome banner, forever, silently.
    # Bounded, and LOUD on the skip (the #612 probe-then-skip shape). coreutils
    # `timeout` is on PATH here: the profile PATH export precedes this line in
    # both terminals, and coreutils is a baked attr.
    timeout 10 "$CLOUD_STORE_INSTALL_DIR/$CLOUD_STORE_ENGINE" ensure >/dev/null 2>&1 \
        || echo "⚠ cloud-store init skipped (timed out or failed); shell continues" >&2
fi

# startup-step: linux_first_start
# Both terminals bake `linux-store` (see linux_path). Its first start is prepared here, once:
#   - every prerequisite present  -> `linux-store switch` runs in the BACKGROUND (rule 1: a login
#     is never held up by it), logged to ~/.linux-store/first-switch.log, never retried by itself;
#   - any missing                 -> ONE line on a terminal saying what, and the command to run.
# Prerequisites are the git base, the cloud-me_configs declaration for the configs-user and an age
# key. Only their existence is tested: no key is read, written, generated or logged, and the
# switch itself decrypts with the owner's own key (SOPS_AGE_KEY_FILE or sops's default path).
# Always returns 0 and tolerates `set -eu` in whatever sourced this.
cloud_linux_first_start() {
    command -v linux-store >/dev/null 2>&1 || return 0
    _lh="${HOME:-/root}"
    [ -L "$_lh/.linux-store/current" ] && return 0
    _ltty=0; [ -t 2 ] && _ltty=1
    if [ -e "$_lh/.linux-store/.first-switch-started" ]; then
        [ "$_ltty" = 0 ] || echo "linux-store: first switch not complete (log: ~/.linux-store/first-switch.log); if it is not still running, run: linux-store switch" >&2
        return 0
    fi
    _lbase="${GIT_BASE:-$_lh/cloud-drive-shared-store/git}"
    _lmiss=""
    [ -d "$_lbase" ] || _lmiss="$_lmiss the git base ($_lbase),"
    _ldecl="${LINUX_STORE_DECLARATION:-}"
    if [ -z "$_ldecl" ] && [ -f "$_lbase/cloud-me_configs/configs.json" ] && command -v jq >/dev/null 2>&1; then
        _lup="$(jq -r --arg u "${LINUX_STORE_USER:-diego-admin}" '.configs_users[]? | select(.id == $u) | .path // empty' \
            "$_lbase/cloud-me_configs/configs.json" 2>/dev/null)" || _lup=""
        [ -z "$_lup" ] || _ldecl="$_lbase/cloud-me_configs/$_lup/deb-user-configs/linux-store.json"
    fi
    [ -n "$_ldecl" ] && [ -f "$_ldecl" ] || _lmiss="$_lmiss the cloud-me_configs declaration (deb-user-configs/linux-store.json under $_lbase/cloud-me_configs),"
    _lkey="${SOPS_AGE_KEY_FILE:-${XDG_CONFIG_HOME:-$_lh/.config}/sops/age/keys.txt}"
    [ -s "$_lkey" ] || [ -n "${SOPS_AGE_KEY:-}" ] || _lmiss="$_lmiss the age key ($_lkey),"
    if [ -n "$_lmiss" ]; then
        [ "$_ltty" = 0 ] || echo "linux-store: not switched yet, missing${_lmiss%,}; place it, then run: linux-store switch" >&2
        return 0
    fi
    mkdir -p "$_lh/.linux-store" 2>/dev/null && chmod 700 "$_lh/.linux-store" 2>/dev/null
    : > "$_lh/.linux-store/.first-switch-started" 2>/dev/null || return 0
    [ "$_ltty" = 0 ] || echo "linux-store: first start, switching in the background (log: ~/.linux-store/first-switch.log)" >&2
    ( nohup timeout 1800 linux-store switch >"$_lh/.linux-store/first-switch.log" 2>&1 </dev/null & ) >/dev/null 2>&1
    return 0
}
cloud_linux_first_start || true
