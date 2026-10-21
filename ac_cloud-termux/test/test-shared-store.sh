#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════════════╗
# ║ cloud-shared-store (owner decision 2026-10-17): ONE store in cld.termux's║
# ║ private home; nix (and cloud-drive) share its uid; every other fleet app ║
# ║ goes through a signature-guarded provider.                               ║
# ╚══════════════════════════════════════════════════════════════════════════╝
#
#   T1  the provider's permission is SIGNATURE level, the provider is guarded by it
#       (android:permission), and its authority/permission are the declared ones.
#   T2  every member of aa_cloud-superapp/data/fleet-identity.json's terminal group (the ONE
#       membership declaration, in force) carries the SAME android:sharedUserId literal, equal
#       to build.json::shared_store.shared_user_id; none sets sharedUserMaxSdkVersion
#       (the exit hatch documented there, never set silently).
#   T3  the old name (cloud-drive-shared-store) appears nowhere in the tracked tree
#       except the migration code (a line that says legacy_store_migration) and its
#       own declaration in build.json::shared_store.legacy_name. No link/dir of that
#       name is created any more; the migration only moves it into the store.
#   T4  the store is declared ONCE: storage.shared_root / storage.git_subdir and
#       shared_store.dir live in ac_cloud-termux/build.json only; enter.sh's literal
#       and nix's STORE_DIR equal it.
#   T5  SharedStoreFiles (the provider's path guard and the migration restore) is the
#       SAME file in both terminals, and SharedStoreMigration too (package aside).
#   T6  EXECUTED on a plain JVM: resolve() refuses "..", and a symlink leaving the
#       store; restoreTarGz() restores files, dirs, symlinks, exec bits, long names,
#       keeps a newer local file and refuses an entry outside $HOME.
#
# Offline: python3, git, javac.
set -uo pipefail

ROOT="${CLOUD_ANDROID_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && while [ "$PWD" != "/" ] && [ ! -e "$PWD/.git" ]; do cd ..; done; printf '%s' "$PWD")}"
BJ="$ROOT/ac_cloud-termux/build.json"
TMAN="$ROOT/ac_cloud-termux/app/src/main/AndroidManifest.xml"
NMAN="$ROOT/ac_cloud-nix-on-droid/app/src/main/AndroidManifest.xml"
DMAN="$ROOT/ac_cloud-drive/app/src/main/AndroidManifest.xml"
ENTER="$ROOT/ac_cloud-termux/rootfs/enter.sh"
BAKE="$ROOT/ac_cloud-nix-on-droid/app/src/main/cpp/bake_default_packages.py"
TJ="$ROOT/ac_cloud-termux/app/src/main/java/com/termux/cloud"
NJ="$ROOT/ac_cloud-nix-on-droid/app/src/main/java/com/termux/cloud"
fails=0
ok()  { echo "  ok   — $1"; }
bad() { echo "::error::FAIL — $1"; fails=$((fails + 1)); }
for f in "$BJ" "$TMAN" "$NMAN" "$DMAN" "$ENTER" "$BAKE" "$TJ/SharedStoreFiles.java" "$TJ/SharedStoreProvider.java"; do
    [ -f "$f" ] || { echo "::error::FAIL — missing $f (this tester is unrun, not passing)"; exit 1; }
done

echo "── T1 the provider is signature-guarded ──"
python3 - "$BJ" "$TMAN" <<'PY' && ok "SHARED_STORE is protectionLevel=signature and guards the provider at the declared authority" || bad "the store provider is not guarded by a signature-level permission"
import json, sys, xml.etree.ElementTree as ET
A = "{http://schemas.android.com/apk/res/android}"
d = json.load(open(sys.argv[1]))["shared_store"]["provider"]
m = ET.parse(sys.argv[2]).getroot()
perm = [p for p in m.findall("permission") if p.get(A + "name") == d["permission"]]
assert d["protection_level"] == "signature", "declared level"
assert len(perm) == 1 and perm[0].get(A + "protectionLevel") == "signature", "manifest level"
prov = [p for p in m.iter("provider") if p.get(A + "authorities") == d["authority"]]
assert len(prov) == 1, "provider"
p = prov[0]
assert p.get(A + "exported") == "true" and p.get(A + "permission") == d["permission"], "guard"
assert p.get(A + "readPermission") in (None, d["permission"]) and p.get(A + "writePermission") in (None, d["permission"]), "no weaker read/write guard"
assert p.get(A + "grantUriPermissions") != "true", "no uri grants"
PY
grep -q 'checkCallingOrSelfPermission(PERMISSION)' "$TJ/SharedStoreProvider.java" \
    && ok "call() re-checks the caller (the framework does not permission-check call())" \
    || bad "SharedStoreProvider.call() does not re-check the permission"

echo "── T2 one shared uid ──"
python3 - "$BJ" "$ROOT" <<'PY' || bad "the group's manifests do not share the declared sharedUserId"
import json, os, re, sys
A = "{http://schemas.android.com/apk/res/android}"
want = json.load(open(sys.argv[1]))["shared_store"]["shared_user_id"]
root = sys.argv[2]
ident = json.load(open(os.path.join(root, "aa_cloud-superapp/data/fleet-identity.json")))
g = next(x for x in ident["groups"] if x.get("id") == "terminal")
dirs = {m["pkg"]: m.get("dir") for m in json.load(open(os.path.join(root, "aa_cloud-superapp/data/ipc-mesh.json")))["members"]}
bad = []
if g.get("pending"): bad.append("fleet-identity.json still marks the terminal group pending")
if g["shared_user_id"] != {"from": "ac_cloud-termux/build.json", "key": "shared_store.shared_user_id"}: bad.append("the group does not read its value from shared_store.shared_user_id")
if not {"cld.termux", "cld.termux.nix"} <= set(g["members"]): bad.append("the terminals are not both members")
for pkg in g["members"]:
    path = os.path.join(root, dirs.get(pkg) or "?", "app/src/main/AndroidManifest.xml")
    s = open(path).read() if os.path.isfile(path) else ""
    m = re.search(r'<manifest\b[^>]*?android:sharedUserId="([^"]*)"', s, re.S)
    if not m or m.group(1) != want: bad.append("%s: android:sharedUserId=%r, declared %r" % (pkg, m and m.group(1), want))
    if "sharedUserMaxSdkVersion" in s.split("<application")[0].replace("android:sharedUserMaxSdkVersion: on API", ""): bad.append("%s sets sharedUserMaxSdkVersion (the exit hatch is documented in fleet-identity.json, never set silently)" % pkg)
for b in bad: print("    " + b)
if not bad: print("  ok   — every member of fleet-identity.json's terminal group: android:sharedUserId=%s" % want)
sys.exit(1 if bad else 0)
PY

echo "── T3 the old name is gone (migration code aside) ──"
OLD="cloud-drive-""shared-store"
hits="$(cd "$ROOT" && git ls-files -z 2>/dev/null | xargs -0 grep -nI -- "$OLD" 2>/dev/null \
    | grep -vE 'legacy_store_migration' \
    | grep -vE '^ac_cloud-termux/build.json:' \
    | grep -vE '^ac_cloud-termux/test/test-shared-store.sh:' || true)"
if [ -z "$hits" ]; then ok "no '$OLD' outside the migration code"; else bad "the old name is still used:"; printf '%s\n' "$hits" | head -40 | sed 's/^/        /'; fi
python3 - "$BJ" "$OLD" <<'PY' && ok "in build.json the old name lives only under shared_store (legacy_name + its docs)" || bad "build.json names the old store outside shared_store.legacy_name"
import json, sys
d = json.load(open(sys.argv[1])); old = sys.argv[2]
rest = {k: v for k, v in d.items() if k != "shared_store"}
sys.exit(0 if old not in json.dumps(rest) and d["shared_store"]["legacy_name"]["name"] == old else 1)
PY

echo "── T4 the store is declared once ──"
python3 - "$ROOT" <<'PY' || bad "the store is declared more than once (or not at all)"
import glob, json, os, sys
root = sys.argv[1]
own = []
for bj in glob.glob(os.path.join(root, "*", "build.json")):
    try: d = json.load(open(bj))
    except Exception: continue
    s = d.get("storage") or {}
    if isinstance(s, dict) and ("shared_root" in s or "git_subdir" in s): own.append(os.path.relpath(bj, root))
    if "shared_store" in d: own.append(os.path.relpath(bj, root) + "::shared_store")
want = ["ac_cloud-termux/build.json", "ac_cloud-termux/build.json::shared_store"]
print("    declared in: " + ", ".join(sorted(own)))
sys.exit(0 if sorted(own) == sorted(want) else 1)
PY
DIRV="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["shared_store"]["dir"])' "$BJ")"
grep -qxF "CLOUD_STORE=\"\$HOME/$DIRV\"" "$ENTER" && ok "enter.sh's store literal is shared_store.dir ($DIRV)" || bad "enter.sh does not make \$HOME/$DIRV"
LEGV="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["shared_store"]["legacy_name"]["name"])' "$BJ")"
grep -qxF "LEGACY_STORE_NAME = \"$LEGV\"  # legacy_store_migration: mirrors build.json::shared_store.legacy_name" "$BAKE" && ok "nix's LEGACY_STORE_NAME is shared_store.legacy_name" || bad "bake_default_packages.py LEGACY_STORE_NAME differs from shared_store.legacy_name"
grep -qF "legacy_store_migrate \"\$HOME/$LEGV\" \"\$CLOUD_STORE\"  # legacy_store_migration" "$ENTER" && ok "enter.sh migrates the declared legacy name" || bad "enter.sh does not migrate shared_store.legacy_name"
grep -qxF "STORE_DIR = \"$DIRV\"" "$BAKE" && ok "nix's STORE_DIR is shared_store.dir" || bad "bake_default_packages.py STORE_DIR differs from shared_store.dir"

echo "── T5 the two terminals carry the same classes ──"
for c in SharedStoreFiles SharedStoreMigration; do
    if [ -f "$NJ/$c.java" ] && cmp -s "$TJ/$c.java" "$NJ/$c.java"; then ok "$c.java is identical in cld.termux and cld.termux.nix"; else bad "$c.java drifted between the terminals"; fi
done

echo "── T6 executed: path guard + restore (plain JVM) ──"
if ! command -v javac >/dev/null 2>&1; then
    bad "javac is not installed: T6 is unrun, not passing"
else
    W="$(mktemp -d)"; trap 'rm -rf "$W"' EXIT
    mkdir -p "$W/src/com/termux/cloud" "$W/out"
    cp "$TJ/SharedStoreFiles.java" "$W/src/com/termux/cloud/"
    cat > "$W/src/T.java" <<'JAVA'
import com.termux.cloud.SharedStoreFiles;
import java.io.*;
import java.nio.file.*;
public class T {
    static int bad = 0;
    static void check(boolean c, String m) { System.out.println((c ? "  ok   - " : "::error::FAIL - ") + m); if (!c) bad++; }
    public static void main(String[] a) throws Exception {
        File w = new File(a[0]);
        File store = new File(w, "home/cloud-shared-store"); store.mkdirs();
        new File(w, "outside").mkdirs();
        Files.createSymbolicLink(new File(store, "escape").toPath(), new File(w, "outside").toPath());
        check(SharedStoreFiles.resolve(store, "a/b.txt") != null, "resolve: a new file inside the store is allowed");
        check(SharedStoreFiles.resolve(store, "../x") == null, "resolve: '..' is refused");
        check(SharedStoreFiles.resolve(store, "escape/x") == null, "resolve: a symlink leaving the store is refused");
        check(SharedStoreFiles.resolve(store, "") != null, "resolve: the root itself");
        File home = new File(w, "restore"); home.mkdirs();
        File keep = new File(home, "keep.txt");
        try (FileWriter f = new FileWriter(keep)) { f.write("local"); }
        keep.setLastModified(4102444800000L);
        int n;
        try (InputStream in = new FileInputStream(a[1])) { n = SharedStoreFiles.restoreTarGz(in, home); }
        check(new File(home, ".ssh/config").isFile(), "restore: a dotfile in a subdir");
        check(new File(home, "bin/run.sh").canExecute(), "restore: the exec bit");
        check(Files.isSymbolicLink(new File(home, "link").toPath()), "restore: a symlink");
        check(new File(home, "deep/" + "d".repeat(120) + "/file.txt").isFile(), "restore: a long (GNU/pax-free) name");
        check(new String(Files.readAllBytes(keep.toPath())).equals("local"), "restore: a newer local file is kept");
        check(!new File(w, "evil.txt").exists(), "restore: an entry outside $HOME is refused");
        check(n >= 4, "restore: counted " + n + " files");
        System.exit(bad == 0 ? 0 : 1);
    }
}
JAVA
    python3 - "$W/home.tar.gz" <<'PY'
import io, tarfile, sys, time
t = tarfile.open(sys.argv[1], "w:gz", format=tarfile.GNU_FORMAT)
def add(name, data=b"x", mode=0o644, typ=tarfile.REGTYPE, link=""):
    i = tarfile.TarInfo(name); i.mode = mode; i.type = typ; i.mtime = int(time.time()) - 10
    if typ == tarfile.SYMTYPE: i.linkname = link; t.addfile(i)
    elif typ == tarfile.DIRTYPE: t.addfile(i)
    else: i.size = len(data); t.addfile(i, io.BytesIO(data))
add("./.ssh", typ=tarfile.DIRTYPE, mode=0o700)
add("./.ssh/config", b"Host x\n")
add("./bin/run.sh", b"#!/bin/sh\necho hi\n", mode=0o755)
add("./link", typ=tarfile.SYMTYPE, link="bin/run.sh")
add("./deep/" + "d" * 120 + "/file.txt", b"long")
add("./keep.txt", b"from-backup")
add("../evil.txt", b"evil")
t.close()
PY
    if javac -d "$W/out" "$W/src/com/termux/cloud/SharedStoreFiles.java" "$W/src/T.java" 2>"$W/javac.log"; then
        java -cp "$W/out" T "$W" "$W/home.tar.gz" || fails=$((fails + 1))
    else
        bad "SharedStoreFiles.java does not compile on a plain JVM:"; sed 's/^/        /' "$W/javac.log"
    fi
fi

echo "── T7 web: the store is served read-only; writes only into the store, token-gated ──"
want='store_web_env="HTTPD_WRITE=1 HTTPD_WRITE_ROOTS=/root/cloud-shared-store HTTPD_WRITE_TOKEN_FILE=/root/.config/cloud-shared-store/web-token"'
if grep -qxF "    $want" "$ENTER" && grep -q '^if \[ -s "${CLOUD_STORE_CFG:-$HOME/.config/cloud-shared-store}/web-token" \]; then$' "$ENTER" \
   && grep -q 'PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin $store_web_env' "$ENTER"; then
    ok "fleet-webserver writes are confined to the store and need the 0600 token (none without it)"
else
    bad "enter.sh does not confine fleet-webserver writes to the store behind the token"
fi
if [ "$(grep -c 'HTTPD_WRITE_ROOTS=' "$ENTER")" = 1 ]; then ok "no other write root is opened"; else bad "enter.sh opens more than one write root"; fi

echo "── T8 the legacy-store migration: same text in both terminals, EXECUTED, idempotent, never deletes ──"
W8="$(mktemp -d)"; trap 'rm -rf "$W8"' EXIT
sed -n '/^# legacy_store_migration begin/,/^# legacy_store_migration end/p' "$ENTER" > "$W8/termux.sh"
python3 - "$BAKE" "$W8/nix.sh" <<'PY' || bad "could not read LEGACY_STORE_MIGRATION from bake_default_packages.py"
import importlib.util, sys
spec = importlib.util.spec_from_file_location("bake", sys.argv[1]); m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)
open(sys.argv[2], "w").write(m.LEGACY_STORE_MIGRATION.replace("/system/bin/", ""))
PY
if [ -s "$W8/termux.sh" ] && [ -s "$W8/nix.sh" ] && diff -q <(sed 's/ *#.*$//' "$W8/termux.sh" | grep -v '^$') <(sed 's/ *#.*$//' "$W8/nix.sh" | grep -v '^$') >/dev/null; then
    ok "enter.sh and bin/login carry the same migration code (nix only spells commands /system/bin/<cmd>)"
else bad "the migration block differs between enter.sh and bake_default_packages.py"; fi
OLD8="$LEGV"; H8="$W8/home"
mig() { ( HOME="$H8" sh -c ". '$W8/termux.sh'; legacy_store_migrate \"\$HOME/$OLD8\" \"\$HOME/$DIRV\"" ) 2>&1; }
mkdir -p "$H8/$OLD8/git/a" "$H8/$OLD8/git/b" "$H8/$OLD8/.hidden" "$H8/$DIRV/git/a"
echo old-only > "$H8/$OLD8/only-old.txt"; echo same > "$H8/$OLD8/same.txt"; echo same > "$H8/$DIRV/same.txt"
echo old > "$H8/$OLD8/conflict.txt"; echo new > "$H8/$DIRV/conflict.txt"; echo x > "$H8/$OLD8/.hidden/h"
echo a-old > "$H8/$OLD8/git/a/f"; echo a-new > "$H8/$DIRV/git/a/f"; echo b > "$H8/$OLD8/git/b/f"
out="$(mig)"
BK="$(ls -d "$H8/$DIRV"/.legacy-backup-* 2>/dev/null | head -1)"
if [ ! -e "$H8/$OLD8" ] && [ ! -L "$H8/$OLD8" ] \
   && [ "$(cat "$H8/$DIRV/only-old.txt")" = old-only ] && [ "$(cat "$H8/$DIRV/.hidden/h")" = x ] && [ "$(cat "$H8/$DIRV/git/b/f")" = b ] \
   && [ "$(cat "$H8/$DIRV/conflict.txt")" = new ] && [ "$(cat "$BK/conflict.txt")" = old ] \
   && [ "$(cat "$H8/$DIRV/git/a/f")" = a-new ] && [ "$(cat "$BK/git/a/f")" = a-old ] \
   && [ "$(cat "$H8/$DIRV/same.txt")" = same ] && [ ! -e "$BK/same.txt" ]; then
    ok "old dir merged into the store: new content wins in place, differing old files kept in .legacy-backup-<date>, duplicates dropped, old dir removed"
else bad "migration result wrong ($out)"; ( cd "$H8" && find . | sort | head -30 | sed 's/^/        /' ); fi
snap="$(cd "$H8" && find . | sort)"; out="$(mig)"
[ "$snap" = "$(cd "$H8" && find . | sort)" ] && [ -z "$out" ] && ok "a second run changes nothing (idempotent)" || bad "the migration is not idempotent"
ln -s "$DIRV" "$H8/$OLD8"; mig >/dev/null
[ ! -e "$H8/$OLD8" ] && [ ! -L "$H8/$OLD8" ] && [ -f "$H8/$DIRV/only-old.txt" ] && ok "an old symlink (the earlier compat link) is removed, the store untouched" || bad "old symlink not removed"

[ "$fails" -eq 0 ] || { echo "test-shared-store: $fails failure(s)"; exit 1; }
echo "test-shared-store: all passed"
