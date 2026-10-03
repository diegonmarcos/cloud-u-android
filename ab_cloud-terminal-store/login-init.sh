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

if [ -r "$CLOUD_STORE_INSTALL_DIR/declaration.sh" ]; then
    # shellcheck disable=SC1091 # generated, and its path is the one literal here
    . "$CLOUD_STORE_INSTALL_DIR/declaration.sh"
    PATH="${HOME:-/root}/$CLOUD_STORE_ROOT/$CLOUD_STORE_CURRENT/$CLOUD_STORE_BIN:$PATH"
    export PATH
    # #790 the agent CLIs' credentials: the app writes its agent-auth store (Account ▸ settings,
    # FleetConfig) to this file on every start, one `export NAME='value'` per key, and this puts
    # them in every session's environment. Silent: no value reaches the terminal or a log.
    if [ -n "${CLOUD_AGENT_AUTH_ENV:-}" ] && [ -r "${HOME:-/root}/$CLOUD_AGENT_AUTH_ENV" ]; then
        # shellcheck disable=SC1090 # written by the app, path from declaration.sh
        . "${HOME:-/root}/$CLOUD_AGENT_AUTH_ENV" >/dev/null 2>&1 \
            || echo "⚠ agent credentials could not be read; claude/goose/hermes start logged out" >&2
    fi
    # Rule 1, enforced rather than hoped: `ensure` forks the engine and, behind
    # it, dozens of subprocesses -- under single-threaded ptrace proot that once
    # wedged the nix terminal right after the welcome banner, forever, silently.
    # Bounded, and LOUD on the skip (the #612 probe-then-skip shape). coreutils
    # `timeout` is on PATH here: the profile PATH export precedes this line in
    # both terminals, and coreutils is a baked attr.
    timeout 10 "$CLOUD_STORE_INSTALL_DIR/$CLOUD_STORE_ENGINE" ensure >/dev/null 2>&1 \
        || echo "⚠ cloud-store init skipped (timed out or failed); shell continues" >&2
fi
