#!/bin/sh
# ╔══════════════════════════════════════════════════════════════════════════╗
# ║ #790 — an update replaces the tree, never $HOME; logins come from Account ║
# ╚══════════════════════════════════════════════════════════════════════════╝
#
# 2026-10-03 both terminals answered "Not logged in · Please run /login" after a
# rootfs update. An update deletes and re-extracts $PREFIX (TermuxInstaller's
# installBootstrap, and for the termux terminal enter.sh's rm -rf of the rootfs,
# which rootfs/verify-rootfs.sh exercises under the shipped proot). This holds
# the Java half: $HOME is a sibling of $PREFIX, the installer deletes nothing
# but $PREFIX and its staging dir, and AgentAuth turns the Account's agent-auth
# store into the 0600 file login-init.sh sources, from every app start, without
# logging a value. Each rule is then broken once in a scratch copy (mutants) and
# must go red, so a pass means the rules can fail.
#
# Static, offline, no Android SDK. Byte-identical in ac_cloud-termux and
# ac_cloud-nix-on-droid; it reads the tree it sits in.
set -u

DIR="$(cd "$(dirname "$0")/.." && pwd)"
J="$DIR/app/src/main/java/com/termux"
FILES="$(find "$DIR/termux-shared" -path '*/com/termux/shared/termux/TermuxConstants.java' | head -1)
$J/app/TermuxInstaller.java
$J/cloud/AgentAuth.java
$J/app/TermuxApplication.java"

check() {  # $1 = constants, $2 = installer, $3 = AgentAuth, $4 = TermuxApplication; prints violations
    python3 - "$@" <<'PY'
import re, sys
consts, inst, auth, app = (open(p).read() for p in sys.argv[1:5])
bad = []
def defn(name):
    m = re.search(r"String %s = (\w+) \+ \"([^\"]+)\"" % name, consts)
    return (m.group(1), m.group(2)) if m else (None, None)
home, prefix = defn("TERMUX_HOME_DIR_PATH"), defn("TERMUX_PREFIX_DIR_PATH")
if home[0] != "TERMUX_FILES_DIR_PATH" or prefix[0] != "TERMUX_FILES_DIR_PATH" or home[1].startswith(prefix[1] + "/") or home == prefix:
    bad.append("H1 $HOME %s is not a sibling of $PREFIX %s under files/: an update would replace it" % (home, prefix))
deleted = re.findall(r"FileUtils\.deleteFile\([^,]*,\s*(\w+)", inst)
if not deleted:
    bad.append("H2 no FileUtils.deleteFile in the installer: the update path moved and this tester reads nothing")
for d in deleted:
    if d not in ("TERMUX_PREFIX_DIR_PATH", "TERMUX_STAGING_PREFIX_DIR_PATH"):
        bad.append("H2 the installer deletes %s: an update must delete only $PREFIX and its staging dir" % d)
if re.search(r"TERMUX_HOME_DIR\w*[^;]*\.delete\(|delete\w*\([^;]*TERMUX_HOME_DIR", inst):
    bad.append("H2 the installer deletes something under $HOME")
if 'new File(TermuxConstants.TERMUX_HOME_DIR_PATH, BuildConfig.CLOUD_AGENT_AUTH_ENV)' not in auth:
    bad.append("A1 AgentAuth does not write $HOME/<store.json::agent_auth.env_file>, the file login-init.sh sources")
w = auth.find("out.write(body")
c = auth.find("Os.chmod(tmp.getAbsolutePath(), 0600)")
if c < 0 or w < 0 or c > w:
    bad.append("A2 the credentials file is not made 0600 before the values are written into it")
if 'getSharedPreferences(STORE' not in auth or 'String STORE = "agent-auth"' not in auth:
    bad.append("A3 AgentAuth does not read the fleet-config.json agent-auth store")
for line in re.findall(r"Logger\.log\w+\(([^;]*)\);", auth):
    if re.search(r"\bbody\b|\bvalues\b|getValue|\bv\b|e\.getMessage|\+ e\s*[)+]", line):
        bad.append("A4 AgentAuth logs something that can carry a value: " + line.strip())
if not re.search(r"public void onCreate\(\)[\s\S]*AgentAuth\.provision\(this\)", app):
    bad.append("A5 TermuxApplication.onCreate does not call AgentAuth.provision: an Account import (which restarts the app) would never reach the file")
print("\n".join(bad))
PY
}

fails=0
# shellcheck disable=SC2086 # FILES is a newline list of paths without spaces
set -- $FILES
for f in "$@"; do [ -f "$f" ] || { echo "FAIL missing $f"; exit 1; }; done
out="$(check "$@")"
if [ -z "$out" ]; then echo "ok   \$HOME survives an update and AgentAuth writes the Account's logins safely ($(basename "$DIR"))"
else echo "$out" | sed 's/^/FAIL /'; fails=$((fails + 1)); fi

T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
mutant() {  # $1 = which file (1-4), $2 = sed expression, $3 = rule that must catch it
    i=0; args=""
    for f in $FILES; do
        i=$((i + 1)); cp "$f" "$T/$i"
        [ "$i" = "$1" ] && sed "$2" "$f" > "$T/$i"
        args="$args $T/$i"
    done
    if cmp -s "$T/$1" "$(echo "$FILES" | sed -n "${1}p")"; then echo "FAIL MUTATION DID NOT APPLY: $2"; fails=$((fails + 1)); return; fi
    # shellcheck disable=SC2086
    if check $args | grep "^$3 " >/dev/null; then echo "ok   mutation caught by $3: $2"
    else echo "FAIL MUTATION SURVIVED ($3 silent): $2"; fails=$((fails + 1)); fi
}
mutant 1 's|TERMUX_HOME_DIR_PATH = TERMUX_FILES_DIR_PATH + "/home"|TERMUX_HOME_DIR_PATH = TERMUX_FILES_DIR_PATH + "/usr/home"|' H1
mutant 2 's|deleteFile("termux prefix directory", TERMUX_PREFIX_DIR_PATH|deleteFile("termux home", TERMUX_HOME_DIR_PATH|' H2
mutant 3 's|Os.chmod(tmp.getAbsolutePath(), 0600);||' A2
mutant 3 's|Logger.logInfo(LOG_TAG, "Wrote the agent credentials to " + file);|Logger.logInfo(LOG_TAG, "Wrote " + body);|' A4
mutant 4 's|AgentAuth.provision(this);||' A5

[ "$fails" -eq 0 ] || { echo "$fails assertion(s) failed"; exit 1; }
