#!/bin/sh
# ╔══════════════════════════════════════════════════════════════════════════╗
# ║ Fast shared-store path: bin/login probes the same roots as termux        ║
# ╚══════════════════════════════════════════════════════════════════════════╝
#
# ac_cloud-termux/rootfs/enter.sh binds the first of /data/media/0,
# /mnt/pass_through/0/emulated/0, /mnt/androidwritable/0/emulated/0 and
# /storage/emulated/0 whose CloudDrive is readable and writable (the FUSE view
# stalled git for minutes). bin/login's storage block (storage_setup in
# app/src/main/cpp/bake_default_packages.py) now runs the same ordered probe.
#
#  1. EXECUTED over stub dirs: the generated block runs under POSIX sh with the
#     roots rewritten into a temp dir and /system/bin holding the commands
#     Android ships there. It must pick the FIRST root that passes, skip a
#     missing root and one whose store cannot be written, fall back to the last
#     root (logged "fallback"), and print the "not mounted" notice when even
#     that is unreadable.
#  2. GUARD: SHARED_STORE_PROBE_ROOTS must equal termux's `for cand in` list.
#  3. cloud-shared-store, BOTH paths EXECUTED: direct (the owner's store under the
#     same Android data dir answers -> ~/cloud-shared-store links to it, needs no
#     storage grant, and a script in it executes) and fallback (it does not answer
#     -> the legacy CloudDrive mirror the probe found, plus the SFTP 8023 line).
#     ~/cloud-drive-shared-store is the compat link in both.
#
# Offline, no Android SDK: python3 and source only. Root-independent: an
# unwritable store is a regular FILE where the store dir would be.
set -u

DIR="$(cd "$(dirname "$0")/.." && pwd)"
BAKE_PY="$DIR/app/src/main/cpp/bake_default_packages.py"
ENTER_SH="$DIR/../ac_cloud-termux/rootfs/enter.sh"
NAME=CloudDrive
fails=0
ok()  { echo "  ok   — $1"; }
bad() { echo "::error::FAIL — $1"; fails=$((fails + 1)); }

echo "── shared-store probe assertions [$(basename "$DIR")] ──"

T="$(mktemp -d)" && [ -n "$T" ] && [ -d "$T" ] || { echo "::error::FAIL — mktemp -d failed"; exit 1; }
python3 - "$BAKE_PY" "$T" "$NAME" <<'PY' || bad "could not generate bin/login's storage block"
import importlib.util, sys
spec = importlib.util.spec_from_file_location("bake", sys.argv[1])
m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)
t, name = sys.argv[2], sys.argv[3]
roots = tuple(t + "/r%d" % i for i in range(len(m.SHARED_STORE_PROBE_ROOTS)))
body = m.BIN_LOGIN_TRACE + m.storage_setup(name, roots)
open(t + "/login.sh", "w").write("set -eu\n" + body.replace("/system/bin/", t + "/system/bin/"))
open(t + "/nroots", "w").write(str(len(roots)))
PY
mkdir -p "$T/system/bin" "$T/prefix-bin"
for c in date ls ln rm rmdir mkdir touch; do ln -s "$(command -v $c)" "$T/system/bin/$c"; done
SH="$(command -v sh)"

# The nix app's $HOME and the store's owner home, laid out as Android does (/data/user/0/<pkg>/files/home).
DATA="$T/data"; H="$DATA/cld.termux.nix/files/home"; OWNER="$DATA/cld.termux/files/home/cloud-shared-store"

# run <expected emulated target or -> <expected store target or -> <expected log fragment>
run() {
    rm -rf "$H"; mkdir -p "$H"
    out="$(HOME="$H" PATH="$T/prefix-bin" "$SH" "$T/login.sh" 2>&1)" || { echo "$out"; return 1; }
    if [ "$1" = - ]; then
        [ ! -L "$H/emulated" ] || return 1
    else
        [ "$(readlink "$H/emulated")" = "$1" ] || return 1
    fi
    if [ "$2" = - ]; then
        [ ! -L "$H/cloud-shared-store" ] || return 1
    else
        [ "$(readlink "$H/cloud-shared-store")" = "$2" ] || return 1
    fi
    [ "$(readlink "$H/cloud-drive-shared-store")" = cloud-shared-store ] || return 1   # compat_link
    case "$out" in *"$3"*) return 0 ;; esac
    echo "$out"; return 1
}
probe_clean() { [ -z "$(ls -A "$1/$NAME")" ]; }   # the rw probe file is removed

echo "── fallback path (shared uid NOT in effect: the owner's store does not answer) ──"
# All four roots usable: the first wins, logged fast; the store link is the legacy mirror + SFTP hint.
mkdir -p "$T/r0" "$T/r1" "$T/r2" "$T/r3"
if run "$T/r0" "$T/r0/$NAME" "legacy $NAME mirror: $T/r0 (fast)" && probe_clean "$T/r0" && case "$out" in *"sftp -P 8023"*) true ;; *) false ;; esac
then ok "first usable root wins (fast); store falls back to the legacy mirror and names SFTP 8023"; else bad "first usable root not chosen / no fallback"; fi

# r0 missing, r1's store is a file (unwritable): r2 wins.
rm -rf "$T/r0" "$T/r1"; mkdir -p "$T/r1"; : > "$T/r1/$NAME"
if run "$T/r2" "$T/r2/$NAME" "legacy $NAME mirror: $T/r2 (fast)"; then ok "missing and unwritable roots are skipped in order"; else bad "probe order/skip wrong"; fi
[ ! -e "$T/r0" ] && ok "a missing root is not created" || bad "the probe created a missing root"

# Only the last root: fallback.
rm -rf "$T/r2"; mkdir -p "$T/r2"; : > "$T/r2/$NAME"
if run "$T/r3" "$T/r3/$NAME" "legacy $NAME mirror: $T/r3 (fallback)"; then ok "falls back to the last root (/storage/emulated/0)"; else bad "fallback not taken"; fi

# Nothing usable: the existing notice, no store link at all.
rm -rf "$T/r3"
if run - - "storage access is not granted"; then ok "no root and no shared uid: links removed and the not-mounted notice prints"; else bad "no-root case wrong"; fi

echo "── direct path (shared uid in effect: the owner's store answers) ──"
mkdir -p "$OWNER" "$T/r3"
if run "$T/r3" "$OWNER" "cloud-shared-store: direct"; then ok "~/cloud-shared-store links to cld.termux's home store, derived from \$HOME"; else bad "direct path not taken with the owner's store readable"; fi
case "$out" in *"sftp -P"*) bad "the SFTP fallback line printed although the store is direct" ;; *) ok "no fallback line when direct" ;; esac
rm -rf "$T/r3"
if run - "$OWNER" "cloud-shared-store: direct"; then ok "the direct store needs no storage grant"; else bad "the direct store depended on the storage grant"; fi
# Executable from the nix side (ext4 exec, the reason for the shared uid): a script in the store runs.
printf '#!/bin/sh\necho nix-exec-ok\n' > "$OWNER/probe.sh"; chmod 755 "$OWNER/probe.sh"
[ "$("$H/cloud-shared-store/probe.sh" 2>/dev/null)" = nix-exec-ok ] && ok "a script in the store executes through ~/cloud-shared-store" || bad "the store is not executable through the link"
# Unreadable owner store (the uid differs on a real phone): falls back.
if [ "$(id -u)" != 0 ]; then
    chmod 000 "$OWNER"
    if run - - "sftp -P 8023"; then ok "an unreadable owner store falls back (SFTP hint)"; else bad "an unreadable owner store was linked"; fi
    chmod 755 "$OWNER"
fi
rm -rf "$T"

# Guard: the nix bake mirrors build.json::shared_store (owner package, dir).
python3 - "$BAKE_PY" "$DIR/../ac_cloud-termux/build.json" <<'PY' && ok "STORE_OWNER_PACKAGE / STORE_DIR mirror ac_cloud-termux/build.json::shared_store" || bad "the nix bake's store constants drifted from ac_cloud-termux/build.json::shared_store"
import importlib.util, json, sys
spec = importlib.util.spec_from_file_location("bake", sys.argv[1]); m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)
d = json.load(open(sys.argv[2]))["shared_store"]
sys.exit(0 if (m.STORE_OWNER_PACKAGE, m.STORE_DIR) == (d["owner_package"], d["dir"]) else 1)
PY

# Guard: the nix list equals termux's.
termux="$(sed -n 's/^for cand in \(.*\); do$/\1/p' "$ENTER_SH" | head -n1)"
nix="$(python3 -c "
import importlib.util, sys
spec = importlib.util.spec_from_file_location('bake', sys.argv[1])
m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)
print(' '.join(m.SHARED_STORE_PROBE_ROOTS))" "$BAKE_PY")"
if [ -z "$termux" ]; then
    bad "no 'for cand in ...; do' probe list found in $ENTER_SH"
elif [ "$termux" = "$nix" ]; then
    ok "probe list matches termux: $nix"
else
    bad "probe lists drifted — termux: '$termux', nix: '$nix'"
fi

[ "$fails" -eq 0 ] || { echo "$fails failure(s)"; exit 1; }
echo "all passed"
