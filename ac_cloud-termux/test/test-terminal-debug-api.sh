#!/bin/sh
# #747 — /api/terminal/exec and /api/terminal/selftest are wired, and run what a session runs.
#
# The route exists to test this terminal from outside the UI, so it is only worth anything while
# it (a) is registered from the application start path, which runs whenever the process does —
# no activity, no session; (b) enters through $PREFIX/bin/login, the entry a typed session
# takes, not a shell picked by hand; (c) bootstraps headlessly through the same installer the
# activity uses; and (d) runs the declared selftest list, which both terminals carry and must
# keep equal. Each assertion below goes red when one of those is undone.
#
# Static, offline, no Android SDK: it reads source only.
set -u

DIR="$(cd "$(dirname "$0")/.." && pwd)"
APP="$DIR/app/src/main/java/com/termux/app"
API="$APP/TerminalDebugApi.java"
SELFTEST="$DIR/app/src/main/assets/terminal-selftest.json"
SIBLING="$DIR/../ac_cloud-nix-on-droid/app/src/main/assets/terminal-selftest.json"

fails=0
ok()  { echo "  ok   — $1"; }
bad() { echo "::error::FAIL — $1"; fails=$((fails + 1)); }

echo "── #747 terminal debug API assertions [$(basename "$DIR")] ──"

grep -q 'TerminalDebugApi.register(this)' "$APP/TermuxApplication.java" \
    && ok "registered from TermuxApplication.onCreate (every process start, no activity needed)" \
    || bad "TermuxApplication does not register TerminalDebugApi: the routes never exist"

grep -q 'AppDebugServer.INSTANCE.route("terminal"' "$API" \
    && ok "a group on the shared fleet debug server (its token gate, no second server)" \
    || bad "TerminalDebugApi does not register the 'terminal' group on AppDebugServer"

for op in '"exec"' '"selftest"'; do
    grep -q "case $op:" "$API" && ok "serves $op" || bad "does not serve $op"
done

grep -q 'TERMUX_BIN_PREFIX_DIR_PATH + "/login"' "$API" \
    && ok "enters through \$PREFIX/bin/login, the entry a typed session takes" \
    || bad "the command does not go through login: it would run outside the session's rootfs/env"

grep -q 'TermuxInstaller.ensureInstalled(app)' "$API" \
    && grep -q 'static void ensureInstalled(Context context)' "$APP/TermuxInstaller.java" \
    && ok "bootstraps headlessly through TermuxInstaller.ensureInstalled" \
    || bad "no headless bootstrap: the route fails on a phone where the terminal was never opened"

grep -q 'synchronized (INSTALL_LOCK)' "$APP/TermuxInstaller.java" \
    && [ "$(grep -c 'synchronized (INSTALL_LOCK)' "$APP/TermuxInstaller.java")" -ge 2 ] \
    && ok "the activity's install and the route's share one lock" \
    || bad "the activity and the route can extract into one \$PREFIX at once"

grep -q 'PARTIAL_WAKE_LOCK' "$API" && grep -q 'startForegroundService' "$API" \
    && ok "holds a wake lock and the foreground service while a command runs (screen locked)" \
    || bad "nothing keeps the CPU or the process up with the screen locked"

python3 - "$SELFTEST" "$SIBLING" <<'PY' && ok "selftest declares the #747 checks, identical in both terminals" || bad "terminal-selftest.json is wrong or the two terminals' copies differ"
import json, sys
want = ["claude --version", "node --version", "gh --version", "git --version", "zsh --version",
        "ls ~/emulated | head", "ls ~/cloud-drive-shared-store/git | head", "test ! -e ~/storage"]
mine, sibling = (json.load(open(p))["checks"] for p in sys.argv[1:3])
for name, got in (("this app", mine), ("sibling", sibling)):
    if got != want:
        print(f"  {name}: {got}")
        sys.exit(1)
PY

[ "$fails" -eq 0 ] || { echo "$fails assertion(s) failed"; exit 1; }
