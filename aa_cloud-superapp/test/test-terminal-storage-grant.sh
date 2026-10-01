#!/usr/bin/env bash
# Tester (#736): once the privileged plane is armed, the SuperApp grants the fleet
# terminals the shared-storage pair, and that grant can neither miss nor overreach.
#
# THE FAILURE THIS EXISTS FOR. The terminals bind ~/emulated and
# ~/cloud-drive-shared-store only when /storage/emulated/0 is readable, and a
# fresh install has no storage grant. On the owner's phone neither terminal saw
# anything in either directory until it was granted by hand over a shell. The
# plane that already pm-grants the fleet (PrivilegedGrants) now carries the
# terminals' pair in build.json::ui.permissions.privileged.
#
#   T1 every package named for a storage permission is a constellation-fleet
#      package whose own manifest declares that permission (a typo, a renamed
#      app id or an undeclared perm is a pm grant that fails on every pass).
#   T2 both halves of the legacy pair are declared: at target 28 READ is what
#      lists the store and WRITE what lets git write into it.
#   T3 staleGrants revokes ONLY development-flagged permissions. A runtime
#      permission the user granted through the dialog (cloud-drive's storage)
#      would otherwise be revoked, and the app force-stopped, on every pass.
#   T4 the explicit apps list skips packages that are not installed.
#
# Each assertion is run once more against a planted defect and must go red there.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
APP="$(cd "$HERE/.." && pwd)"
REPO="$(cd "$APP/.." && pwd)"
BJ="$APP/build.json"
FLEET="$APP/data/constellation-fleet.json"
PG="$APP/app/src/main/java/com/diegonmarcos/superapp/system/PrivilegedGrants.kt"
STORAGE='android.permission.READ_EXTERNAL_STORAGE android.permission.WRITE_EXTERNAL_STORAGE'
fail=0
ok()  { echo "  ok   $*"; }
bad() { echo "  FAIL $*"; fail=1; }

# $1 = build.json, $2 = repo root holding each app's manifest -> problems, one per line
pairs_problems() {
    python3 - "$1" "$FLEET" "$2" "$STORAGE" <<'PY'
import json, re, sys, os
bj, fleet, root, storage = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4].split()
entries = json.load(open(bj))["ui"]["permissions"]["privileged"]
dirs = {}
for a in json.load(open(fleet))["apps"]:
    m = re.search(r"/tree/main/([^/]+)$", a.get("repo_url") or "")
    if a.get("kind") == "app" and m:
        dirs[a["package"]] = m.group(1)
seen = set()
for e in entries:
    perm = e.get("perm")
    if perm not in storage:
        continue
    for pkg in e.get("apps") or []:
        seen.add(perm)
        if pkg not in dirs:
            print(f"{pkg}: not a constellation-fleet app package ({perm})"); continue
        man = os.path.join(root, dirs[pkg], "app/src/main/AndroidManifest.xml")
        if not os.path.isfile(man) or f'android:name="{perm}"' not in open(man).read():
            print(f"{pkg}: {dirs[pkg]}'s manifest does not declare {perm}")
for perm in storage:
    if perm not in seen:
        print(f"no privileged entry grants {perm} to any terminal")
PY
}

echo "── T1/T2: the declared storage pairs are real fleet packages that declare the permission ──"
probs="$(pairs_problems "$BJ" "$REPO")"
[ -z "$probs" ] && ok "READ and WRITE_EXTERNAL_STORAGE are declared for fleet terminals whose manifests request them" \
                || bad "storage grant declaration is wrong: $probs"
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
# mutation 1: a renamed / typo'd app id
sed 's/"cld\.termux\.nix"/"cld.termux.nixx"/' "$BJ" > "$tmp/bj1.json"
# mutation 2: the READ half dropped (WRITE-only grant never lists the store)
python3 - "$BJ" "$tmp/bj2.json" <<'PY'
import json, sys
d = json.load(open(sys.argv[1]))
p = d["ui"]["permissions"]
p["privileged"] = [e for e in p["privileged"] if e.get("perm") != "android.permission.READ_EXTERNAL_STORAGE"]
json.dump(d, open(sys.argv[2], "w"))
PY
# mutation 3: a terminal manifest that no longer declares READ
for d in ac_cloud-termux ac_cloud-nix-on-droid; do
    mkdir -p "$tmp/repo/$d/app/src/main"
    cp "$REPO/$d/app/src/main/AndroidManifest.xml" "$tmp/repo/$d/app/src/main/"
done
grep -v 'android.permission.READ_EXTERNAL_STORAGE' "$REPO/ac_cloud-termux/app/src/main/AndroidManifest.xml" \
    > "$tmp/repo/ac_cloud-termux/app/src/main/AndroidManifest.xml"
for m in 1 2 3; do
    case $m in
        1) src="$tmp/bj1.json"; root="$REPO";      what="a typo'd terminal app id";;
        2) src="$tmp/bj2.json"; root="$REPO";      what="the READ half dropped";;
        3) src="$BJ";           root="$tmp/repo";  what="a terminal manifest without READ";;
    esac
    if [ "$m" = 1 ] && cmp -s "$BJ" "$src"; then bad "MUTATION DID NOT APPLY: $what"; continue; fi
    if [ "$m" = 3 ] && cmp -s "$REPO/ac_cloud-termux/app/src/main/AndroidManifest.xml" "$tmp/repo/ac_cloud-termux/app/src/main/AndroidManifest.xml"; then
        bad "MUTATION DID NOT APPLY: $what"; continue; fi
    [ -n "$(pairs_problems "$src" "$root")" ] && ok "mutation proved: $what turns T1/T2 red" \
                                              || bad "MUTATION SURVIVED: $what left T1/T2 green"
done

echo "── T3: staleGrants never revokes a permission the user could have granted ──"
stale_dev_only() {  # $1 = PrivilegedGrants.kt
    awk '/fun staleGrants\(/ {on=1} on {print} on && /^    }/ {exit}' "$1" \
        | grep -A1 'val curated = curatedEntries()' | grep -q 'isDevelopmentPermission(ctx, '
}
stale_dev_only "$PG" && ok "staleGrants only considers development-flagged permissions" \
                     || bad "staleGrants would revoke runtime permissions (cloud-drive's user-granted storage) and force-stop the app"
sed 's/ \&\& isDevelopmentPermission(ctx, p)//' "$PG" > "$tmp/pg1.kt"
if cmp -s "$PG" "$tmp/pg1.kt"; then bad "MUTATION DID NOT APPLY: development filter removal"
elif stale_dev_only "$tmp/pg1.kt"; then bad "MUTATION SURVIVED: removing the development filter left T3 green"
else ok "mutation proved: removing the development filter turns T3 red"; fi

echo "── T4: the explicit apps list skips packages this phone does not have ──"
explicit_skips_absent() {  # $1 = PrivilegedGrants.kt
    awk '/val arr = e.optJSONArray\("apps"\)/ {on=1} on && /out\[pkg to perm\]/ {exit} on {print}' "$1" \
        | grep -q 'getPackageInfo(pkg, 0) }.isFailure) continue'
}
explicit_skips_absent "$PG" && ok "explicit apps are granted only when installed" \
                            || bad "an absent terminal is pm-granted (and fails) on every pass"
grep -v 'getPackageInfo(pkg, 0) }.isFailure) continue' "$PG" > "$tmp/pg2.kt"
if cmp -s "$PG" "$tmp/pg2.kt"; then bad "MUTATION DID NOT APPLY: installed check removal"
elif explicit_skips_absent "$tmp/pg2.kt"; then bad "MUTATION SURVIVED: removing the installed check left T4 green"
else ok "mutation proved: removing the installed check turns T4 red"; fi

[ "$fail" -eq 0 ] && echo "PASS test-terminal-storage-grant" || { echo "FAIL test-terminal-storage-grant"; exit 1; }
