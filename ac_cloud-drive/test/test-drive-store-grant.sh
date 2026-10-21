#!/usr/bin/env bash
# Cloud Drive owns the terminal's store: it must run in the terminals' uid (declared ONCE in
# fleet-identity.json group "terminal"), carry the same sharedUserId literal, and the seed must
# create/verify the store, import the legacy clones and fix permissions itself (no manual cp).
set -uo pipefail
ROOT="${CLOUD_ANDROID_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && while [ "$PWD" != "/" ] && [ ! -e "$PWD/.git" ]; do cd ..; done; printf '%s' "$PWD")}"
SRC="$ROOT/ac_cloud-drive/app/src/main/java/com/diegonmarcos/clouddrive"
fails=0; ok(){ echo "  PASS  $1"; }; bad(){ echo "  FAIL  $1"; fails=$((fails+1)); }
python3 - "$ROOT" <<'PY' && ok "drive is a member of the terminal uid group and its manifest carries the group's literal" || bad "drive does not hold the terminals' shared uid"
import json, re, sys
r = sys.argv[1]
fi = json.load(open(f"{r}/aa_cloud-superapp/data/fleet-identity.json"))
g = next(g for g in fi["groups"] if g["id"] == "terminal")
assert "com.diegonmarcos.clouddrive" in g["members"], "drive not in group members"
ref = g["shared_user_id"]
lit = json.load(open(f"{r}/{ref['from']}"))
for k in ref["key"].split("."): lit = lit[k]
for m in ("ac_cloud-drive", "ac_cloud-termux"):
    mf = open(f"{r}/{m}/app/src/main/AndroidManifest.xml").read()
    assert re.search(r'android:sharedUserId="%s"' % re.escape(lit), mf), m + " manifest literal differs"
PY
S="$SRC/StoreSeed.kt"
grep -q 'StoreReady.verify' "$S" && ok "the seed verifies/creates the store before any clone" || bad "no store verification in the seed"
grep -q 'LegacyImport.run' "$S" && ok "the seed imports the legacy clones itself" || bad "no automatic legacy import"
grep -q 'chmod -R u+rwX' "$SRC/StoreReady.kt" && ok "permissions inside the terminal home are fixed by the app" || bad "no permission fix"
grep -q 'cp -a /storage' "$ROOT/ac_cloud-termux/rootfs/enter.sh" && bad "terminal still tells the owner to cp by hand" || ok "no manual cp instruction"
SY="$SRC/sync/SyncScreen.kt"
grep -q 'IslandAction(Icons.Filled.Terminal' "$SY" && grep -q 'TerminalIslandAction(actions' "$SY" && ok "the terminal shortcut is a shared-island action in Sync's top row" || bad "no terminal island action in Sync"
grep -q 'StoreReady.wake' "$SY" && grep -q 'StoreAccess.ownerPackage()' "$SY" && ok "it starts the terminal and its store service first, package from the declaration" || bad "shortcut does not wake the terminal or hardcodes the package"
grep -q 'Settings\|RUN_COMMAND' "$SY" && bad "shortcut uses a custom path" || ok "no one-off launch path"
grep -q 'ExecListing.list' "$SRC/files/FilesController.kt" && grep -q 'viaTerminal' "$SRC/files/Places.kt" && ok "Terminal roots stay offered and browsable through CLOUD_EXEC" || bad "Terminal roots vanish in EXEC mode"
[ $fails -eq 0 ] && echo "test-drive-store-grant: all checks passed" || { echo "test-drive-store-grant: $fails FAILED"; exit 1; }
