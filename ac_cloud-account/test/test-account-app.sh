#!/usr/bin/env bash
# #867 Cloud Account is the Account page, split out of cloud-superapp.
#
# What this holds, statically (no build, no device, no network):
#  1. Cloud Account hosts libs:account's ProfileFragment (no copy of the account
#     code in this app), owns the data (read-through off) and exports it ONLY
#     through a provider that is exported behind the fleet's signature permission.
#  2. The provider is read-only, re-checks its caller, and answers two methods (the export
#     and, #874, one granted secret), each behind a grant; the
#     copy into another host never overwrites a store that host already holds, and
#     is marked done only after the provider answered.
#  3. SuperApp opens Cloud Account from its Account entry (extapp:cloud-account, a
#     page that is NOT an action), has an external_apps row that resolves, keeps the
#     page working through AccountHandoff until the app is installed, and wires the
#     one-shot copy and the read-through.
#  4. One package name: SuperApp's hand-off, the library, this app's build.json, the
#     external_apps row and the fleet manifest row agree, and the OPEN action SuperApp
#     sends is the one this manifest declares.
#
# Then it plants each mutation below in a scratch copy and requires the checks to go
# red, so a check that cannot fail does not count as a check.
#
# Usage: ./test-account-app.sh   (static, no network)
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"

ACC="$ROOT/ac_cloud-account"
LIB="$ROOT/ab_cloud-libs-shared/libs/account/src/main/java/com/diegonmarcos/superapp"
SUPER="$ROOT/aa_cloud-superapp/app/src/main/java/com/diegonmarcos/superapp"
FLEET="$ROOT/aa_cloud-superapp/data/constellation-fleet.json"
SPBJ="$ROOT/aa_cloud-superapp/build.json"

strip() {
  python3 - "$1" <<'PY'
import re, sys
s = open(sys.argv[1], encoding='utf-8').read()
s = re.sub(r'/\*.*?\*/', lambda m: re.sub(r'[^\n]', ' ', m.group(0)), s, flags=re.S)
sys.stdout.write(re.sub(r'//[^\n]*', '', s))
PY
}

# check <acc-dir> <lib-dir> <superapp-dir> <fleet.json> <superapp build.json>  → PASS/FAIL lines, exit = #fails
check() {
  local A="$1" L="$2" P="$3" F="$4" B="$5" fails=0
  ok()  { echo "  PASS: $1"; }
  bad() { echo "  FAIL: $1"; fails=$((fails+1)); }
  local MA="$A/app/src/main/java/com/diegonmarcos/cloudaccount/MainActivity.kt"
  local AP="$A/app/src/main/java/com/diegonmarcos/cloudaccount/App.kt"
  local MF="$A/app/src/main/AndroidManifest.xml"
  local AD="$L/profile/AccountData.kt" HK="$L/profile/AccountHost.kt" CP="$L/settings/ConfigsPrefs.kt"
  local HO="$P/apps/AccountHandoff.kt" SA="$P/App.kt" SP="$P/launcher/SectionPages.kt"
  for f in "$MA" "$AP" "$MF" "$AD" "$HK" "$CP" "$HO" "$SA" "$SP" "$A/build.json" "$A/app/build.gradle" "$F" "$B"; do
    [ -f "$f" ] || { bad "missing $f"; return 99; }
  done

  # 1. hosts the lib's page, owns no account code, owns the data, exports it behind the signature permission
  strip "$MA" | grep -Eq 'AndroidFragment<ProfileFragment>' && ok "Cloud Account shows ProfileFragment (the four tabs)" || bad "Cloud Account does not show ProfileFragment"
  grep -q "project(':libs:account')" "$A/app/build.gradle" && ok "app links :libs:account" || bad "app does not link :libs:account"
  if find "$A/app/src" -name '*.kt' | xargs grep -l "^package com.diegonmarcos.superapp.profile" 2>/dev/null | grep -q .; then
    bad "a copy of libs:account code lives in ac_cloud-account"; else ok "no copy of the account code in this app"; fi
  strip "$AP" | grep -Eq 'readThrough *= *false' && ok "Cloud Account owns the data (read-through off)" || bad "Cloud Account reads through to another host"
  python3 - "$MF" <<'PY' && ok "provider AccountData\$Provider: exported, authority \${applicationId}.accountdata, behind CONSTELLATION_DATA" || bad "provider missing, wrongly named, or not behind the signature permission"
import re, sys
m = open(sys.argv[1], encoding='utf-8').read()
for p in re.findall(r'<provider\b.*?/>', m, flags=re.S):
    if 'AccountData$Provider' in p:
        ok = ('android:exported="true"' in p and 'android:authorities="${applicationId}.accountdata"' in p
              and 'android:permission="com.diegonmarcos.cloud.permission.CONSTELLATION_DATA"' in p)
        sys.exit(0 if ok else 1)
sys.exit(1)
PY

  # 2. the provider: read-only, caller re-checked, one method; the copy never overwrites
  local ad; ad="$(strip "$AD")"
  printf '%s' "$ad" | grep -q 'checkCallingPermission(FleetConfig.PERMISSION)' && ok "provider re-checks the caller (call() is not permission-checked by the framework)" || bad "provider does not re-check its caller"
  printf '%s' "$ad" | grep -q 'METHOD_EXPORT -> {' && printf '%s' "$ad" | grep -q 'METHOD_SECRET -> secretFor(' && printf '%s' "$ad" | grep -q 'else -> error("unknown method $method")' && ok "provider answers the export and one secret, nothing else" || bad "provider answers more (or less) than the export and one secret"
  printf '%s' "$ad" | grep -q '!AccountVault(ctx).grants.allow(who, GRANT_EXPORT)' && printf '%s' "$ad" | grep -q 'caller != ctx.packageName && !vault.grants.allow(caller, path)' && ok "both methods are behind the Secrets grants (#874)" || bad "a provider method is not behind a grant"
  printf '%s' "$ad" | sed -n '/class Provider/,$p' | grep -Eq 'ConfigsPrefs|\.json *=|store\.write|\.edit\(|AccountData\.apply|apply\(ctx' && bad "provider writes or reads outside the export" || ok "provider does not write"
  printf '%s' "$ad" | grep -q 'if (prefs.localJson.isBlank())' && ok "configs copied only into an empty blob" || bad "configs copy can overwrite"
  printf '%s' "$ad" | grep -q 'if (model.store.read(s) != null) continue' && ok "slots copied only into an empty slot" || bad "slot copy can overwrite"
  printf '%s' "$ad" | grep -q 'if (sp.all.keys.any { it !in generated }) return false' && ok "profile copied only into an empty profile" || bad "profile copy can overwrite"
  printf '%s' "$ad" | sed -n '/fun migrate(/,/^    }/p' | python3 -c "
import sys
s = sys.stdin.read(); f = s.find('val b = fetch(ctx) ?: return emptyList()'); d = s.find('putBoolean(K_DONE, true)')
sys.exit(0 if 0 <= f < d else 1)" \
    && ok "migration is marked done only after the provider answered" || bad "migration can be marked done without an answer"
  printf '%s' "$ad" | grep -q 'ctx.packageName == PKG' && ok "Cloud Account never reads from itself" || bad "Cloud Account could read from itself"
  strip "$CP" | grep -q 'AccountData.configsOrRemote(appContext, localJson)' && ok "ConfigsPrefs.json reads through when its own blob is empty" || bad "ConfigsPrefs does not read through"
  strip "$HK" | grep -q 'var readThrough: Boolean = false' && ok "read-through defaults off" || bad "read-through is not off by default"

  # 3. SuperApp: entry, row, hand-off, copy
  python3 - "$B" <<'PY' && ok "Configs Account entry launches extapp:cloud-account and is not an action" || bad "Account entry does not launch extapp:cloud-account (or is_action moves it to the Actions row)"
import json, sys
b = json.load(open(sys.argv[1]))
pg = [p for s in b['ui']['sections'] if s['id'] == 'config' for p in s['pages'] if p['id'] == 'profile']
sys.exit(0 if len(pg) == 1 and pg[0].get('action') == 'extapp:cloud-account' and not pg[0].get('is_action') else 1)
PY
  python3 - "$B" "$A/build.json" <<'PY' && ok "external_apps[cloud-account] resolves: package, folder and APK name match the app" || bad "external_apps[cloud-account] missing or disagrees with ac_cloud-account/build.json"
import json, sys
b = json.load(open(sys.argv[1])); a = json.load(open(sys.argv[2]))
r = [e for e in b['ui']['external_apps'] if e['id'] == 'cloud-account']
ok = (len(r) == 1 and r[0]['hub_package'] == r[0]['install_package'] == a['android']['application_id']
      and r[0]['folder'] == 'sys_settings' and r[0]['install_apk_url'].endswith('/' + a['release']['gh_release']['asset_name']))
sys.exit(0 if ok else 1)
PY
  strip "$SP" | grep -q 'pageId == "profile" *-> *com.diegonmarcos.superapp.apps.AccountHandoff.page()' && ok "SuperApp's Account page goes through the hand-off" || bad "SuperApp's Account page bypasses the hand-off"
  strip "$HO" | grep -q 'AndroidFragment(clazz = cls' && strip "$HO" | grep -q 'EMBEDDED: Class<out Fragment> = ProfileFragment::class.java' && ok "hand-off embeds the Account page until Cloud Account is installed" || bad "hand-off no longer embeds the Account page"
  local sa; sa="$(strip "$SA")"
  printf '%s' "$sa" | grep -q 'readThrough *= *true' && ok "SuperApp reads through to Cloud Account" || bad "SuperApp does not read through"
  printf '%s' "$sa" | grep -q 'AccountData.migrate(applicationContext)' && ok "SuperApp runs the one-shot copy at start" || bad "SuperApp never runs the copy"

  # 4. one package name, one OPEN action
  local appid pkg libpkg action
  appid="$(python3 -c "import json,sys;print(json.load(open(sys.argv[1]))['android']['application_id'])" "$A/build.json")"
  libpkg="$(printf '%s' "$ad" | sed -n 's/.*const val PKG = "\([^"]*\)".*/\1/p')"
  [ -n "$appid" ] && [ "$appid" = "$libpkg" ] && ok "library package = build.json application_id ($appid)" || bad "library package '$libpkg' != application_id '$appid'"
  strip "$HO" | grep -q 'const val PKG = AccountData.PKG' && ok "hand-off uses the library's package" || bad "hand-off names its own package"
  grep -q "\"package\":\"$appid\"" "$F" && ok "fleet manifest lists $appid (installable and updated by the Store)" || bad "fleet manifest has no row for $appid"
  action="$(strip "$HO" | sed -n 's/.*ACTION_OPEN = "\([^"]*\)".*/\1/p')"
  [ "$action" = '$PKG.OPEN' ] && action="$appid.OPEN"
  grep -q "<action android:name=\"$action\" */>" "$MF" && ok "manifest answers SuperApp's $action" || bad "manifest does not declare the action SuperApp sends ('$action')"

  # 5. #868 the fleet nav pattern: the account tabs are build.json::ui.sections, the bar is the shared island
  python3 - "$A/build.json" "$B" <<'PY' && ok "ui.sections are the account tabs (same ids, same order as SuperApp's ui.profile.tabs); every one is on the island" || bad "build.json ui.sections/bottom_nav/default_section do not match ui.profile.tabs"
import json, sys
ui = json.load(open(sys.argv[1], encoding='utf-8')).get('ui') or {}
tabs = [t['id'] for t in json.load(open(sys.argv[2], encoding='utf-8'))['ui']['profile']['tabs']]
ids = [s.get('id') for s in ui.get('sections') or []]
sys.exit(0 if ids == tabs and ui.get('bottom_nav') == tabs and ui.get('default_section') in tabs else 1)
PY
  local bg; bg="$(cat "$A/app/build.gradle")"
  printf '%s' "$bg" | grep -q 'UI_BOTTOM_NAV' && printf '%s' "$bg" | grep -q 'UI_SECTIONS_B64' && printf '%s' "$bg" | grep -q "project(':libs:bottomnav')" && ok "build.gradle bakes the declaration and links libs:bottomnav" || bad "build.gradle does not bake UI_BOTTOM_NAV/UI_SECTIONS_B64 or link libs:bottomnav"
  local ma; ma="$(strip "$MA")"
  printf '%s' "$ma" | grep -q 'BottomNavHost(' && printf '%s' "$ma" | grep -q 'NavDecl.fromBuildConfig(' && printf '%s' "$ma" | grep -q 'islandEntries' && ok "MainActivity draws the island from the baked NavDecl" || bad "MainActivity does not feed the shared island from NavDecl"
  printf '%s' "$ma" | grep -q 'ARG_EXTERNAL_STRIP to true' && printf '%s' "$ma" | grep -q 'selectTab(' && printf '%s' "$ma" | grep -q 'onTabShown' && ok "the island drives ProfileFragment's tab and follows it back" || bad "the island and ProfileFragment's tab are not wired both ways"
  local pf; pf="$(strip "$L/profile/ProfileFragment.kt")"
  printf '%s' "$pf" | grep -q 'val external = arguments?.getBoolean(ARG_EXTERNAL_STRIP) == true' && printf '%s' "$pf" | grep -q '} else visibility = View.GONE' && printf '%s' "$pf" | grep -q 'fun selectTab(id: String)' && [ "$(printf '%s' "$pf" | grep -c 'onTabShown?.invoke')" -ge 2 ] && ok "ProfileFragment hides its strip for a host that draws the tabs, and reports the tab on screen" || bad "ProfileFragment lost the external-strip contract"
  printf '%s' "$pf" | grep -q 'PageTabsView(ctx)' && ! printf '%s' "$pf" | grep -q 'AccountHost.styleTabs' && ok "the in-fragment strip is libs:bottomnav's PageTabsView (no host styling hook)" || bad "ProfileFragment's strip is not PageTabsView, or still goes through AccountHost.styleTabs"

  # 6. #873/#874 the vault, the pages, the setup contract
  local VT="$L/settings/AccountVault.kt" PL="$L/profile/SetupPlan.kt" FS="$L/profile/FleetSetup.kt" MG="$L/profile/AccountMigrate.kt"
  for f in "$VT" "$PL" "$FS" "$MG" "$L/profile/AccountVaultTabs.kt" "$L/profile/FleetSetupTab.kt"; do [ -f "$f" ] || { bad "missing $f"; return 99; }; done
  python3 - "$A/build.json" "$L/profile/ProfileFragment.kt" <<'PY' && ok "every page build.json declares under a section is a ProfileFragment page that reports that section" || bad "a declared page has no ProfileFragment column (or reports another section)"
import json, re, sys
ui = json.load(open(sys.argv[1], encoding='utf-8'))['ui']
pf = open(sys.argv[2], encoding='utf-8').read()
want = {}
for sec in ui['sections']:
    for pg in sec.get('pages') or []:
        if pg['id'] != sec['id']:
            want[pg['id']] = sec['id']
consts = dict(re.findall(r'const val (PAGE_\w+) = "(\w+)"', pf))
mapped = {consts[k]: v for k, v in re.findall(r'(PAGE_\w+) to "(\w+)"', pf) if k in consts}
sys.exit(0 if want and want == mapped and len(want) >= 5 else 1)
PY
  local vt; vt="$(strip "$VT")"
  printf '%s' "$vt" | grep -q 'AES/GCM/NoPadding' && printf '%s' "$vt" | grep -q 'PBKDF2WithHmacSHA256' && printf '%s' "$vt" | grep -q 'if (v > AccountVault.VERSION) throw Refused' && [ "$(printf '%s' "$vt" | grep -o 'updateAAD(aad(' | wc -l)" -ge 2 ] && ok "the bundle is AES-GCM under a PBKDF2 key, versioned, its header authenticated" || bad "the bundle cipher lost a property (GCM, PBKDF2, the version gate, the AAD)"
  printf '%s' "$vt" | grep -q 'class SecretGrants' && printf '%s' "$vt" | grep -q 'fun revoke(' && printf '%s' "$vt" | grep -q '!secret || caller == c.packageName || grants.allow(caller, "$store.$key")' && ok "Secrets: per-package grants, revocable, enforced by the setup authorizer" || bad "the grants are not revocable or not enforced by the setup authorizer"
  if grep -En 'Log\.[a-z]\(|println\(|printStackTrace' "$VT" "$PL" "$FS" "$MG" "$L/profile/AccountVaultTabs.kt" "$L/profile/FleetSetupTab.kt" >/dev/null 2>&1; then bad "a vault or setup file logs (values must never be logged)"; else ok "no vault or setup file logs"; fi
  strip "$AP" | grep -q 'AccountVault.install(this)' && strip "$AP" | grep -q 'AccountMigrate.run(this)' && ok "App installs the grant-aware authorizer and takes in what SuperApp kept" || bad "App.kt does not install the vault authorizer or run the migration"
  local FB="$P/cloud/FleetBearerProvider.kt"
  [ -f "$FB" ] && strip "$FB" | grep -q 'AccountData.secret(ctx, "fleet.bearer")' && ok "SuperApp's fleet bearer reads through Cloud Account first" || bad "SuperApp's FleetBearerProvider does not read the fleet bearer through Cloud Account"
  return $fails
}

echo "── real tree ──"
check "$ACC" "$LIB" "$SUPER" "$FLEET" "$SPBJ"; REAL=$?

# ── mutations: each must turn the checks red ──
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
mutate() {  # mutate <label> <file-under-copy-root> <old> <new>
  local label="$1" which="$2" old="$3" new="$4" d="$TMP/m"
  rm -rf "$d"; mkdir -p "$d"
  cp -r "$ACC" "$d/acc"; cp -r "$LIB" "$d/lib"; cp -r "$SUPER" "$d/super"; cp "$FLEET" "$d/fleet.json"; cp "$SPBJ" "$d/build.json"
  python3 - "$d/$which" "$old" "$new" <<'PY' || { echo "  MUTATION NOT APPLIED: $label"; return 1; }
import sys
p, old, new = sys.argv[1:4]
s = open(p, encoding='utf-8').read()
if old not in s: sys.exit(1)
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PY
  if check "$d/acc" "$d/lib" "$d/super" "$d/fleet.json" "$d/build.json" >/dev/null; then echo "  MUTATION SURVIVED: $label"; return 1; fi
  echo "  mutation caught: $label"; return 0
}
M=0
D=lib/profile/AccountData.kt
mutate "provider drops the permission" acc/app/src/main/AndroidManifest.xml 'android:permission="com.diegonmarcos.cloud.permission.CONSTELLATION_DATA" />' '/>' || M=$((M+1))
mutate "provider caller check removed" "$D" 'ctx.checkCallingPermission(FleetConfig.PERMISSION) != PackageManager.PERMISSION_GRANTED' 'false' || M=$((M+1))
mutate "provider answers any method" "$D" 'else -> error("unknown method $method")' 'else -> Bundle()' || M=$((M+1))
mutate "export without a grant" "$D" '!AccountVault(ctx).grants.allow(who, GRANT_EXPORT)' 'false' || M=$((M+1))
mutate "secret without a grant" "$D" 'caller != ctx.packageName && !vault.grants.allow(caller, path)' 'false' || M=$((M+1))
mutate "grants no longer revocable" lib/settings/AccountVault.kt 'fun revoke(' 'fun revokeX(' || M=$((M+1))
mutate "setup authorizer lets a secret through" lib/settings/AccountVault.kt '!secret || caller == c.packageName || grants.allow(caller, "$store.$key")' 'true' || M=$((M+1))
mutate "bundle loses its version gate" lib/settings/AccountVault.kt 'if (v > AccountVault.VERSION) throw Refused' 'if (false) throw Refused' || M=$((M+1))
mutate "bundle loses its authenticated header" lib/settings/AccountVault.kt 'updateAAD(aad(AccountVault.VERSION))' '' || M=$((M+1))
mutate "a vault file logs a value" lib/settings/AccountVault.kt 'fun connections(): JSONObject = parse(prefs.json)' 'fun connections(): JSONObject = parse(prefs.json).also { android.util.Log.d("x", it.toString()) }' || M=$((M+1))
mutate "App stops installing the authorizer" acc/app/src/main/java/com/diegonmarcos/cloudaccount/App.kt 'AccountVault.install(this)' 'Unit' || M=$((M+1))
mutate "a page loses its section" lib/profile/ProfileFragment.kt 'PAGE_SECRETS to "runtime"' 'PAGE_SECRETS to "drift"' || M=$((M+1))
mutate "a declared page leaves the declaration" acc/build.json '          {
            "id": "secrets",
            "label": "Secrets",
            "icon": "runtime"
          },
' '' || M=$((M+1))
mutate "FleetBearerProvider stops reading through" super/cloud/FleetBearerProvider.kt 'AccountData.secret(ctx, "fleet.bearer")' '""' || M=$((M+1))
mutate "configs copy overwrites" "$D" 'if (prefs.localJson.isBlank())' 'if (true)' || M=$((M+1))
mutate "slot copy overwrites" "$D" 'if (model.store.read(s) != null) continue' '' || M=$((M+1))
mutate "profile copy overwrites" "$D" 'if (sp.all.keys.any { it !in generated }) return false' '' || M=$((M+1))
mutate "done set before the answer" "$D" 'val b = fetch(ctx) ?: return emptyList()' 'ctx.getSharedPreferences(MIGRATION_FILE, Context.MODE_PRIVATE).edit().putBoolean(K_DONE, true).apply(); val b = fetch(ctx) ?: return emptyList()' || M=$((M+1))
mutate "Cloud Account reads through" acc/app/src/main/java/com/diegonmarcos/cloudaccount/App.kt 'readThrough = false' 'readThrough = true' || M=$((M+1))
mutate "Account page bypasses hand-off" super/launcher/SectionPages.kt 'AccountHandoff.page()' 'ProfileFragment.newInstance()' || M=$((M+1))
mutate "SuperApp never copies" super/App.kt 'AccountData.migrate(applicationContext)' 'Unit' || M=$((M+1))
mutate "Account entry loses its action" build.json '"action": "extapp:cloud-account",' '' || M=$((M+1))
mutate "package name drifts" lib/profile/AccountData.kt 'const val PKG = "com.diegonmarcos.cloudaccount"' 'const val PKG = "com.diegonmarcos.account"' || M=$((M+1))
mutate "OPEN action dropped" acc/app/src/main/AndroidManifest.xml '<action android:name="com.diegonmarcos.cloudaccount.OPEN" />' '' || M=$((M+1))
mutate "page tab host dropped" acc/app/src/main/java/com/diegonmarcos/cloudaccount/MainActivity.kt 'AndroidFragment<ProfileFragment>' 'AndroidFragment<androidx.fragment.app.Fragment>' || M=$((M+1))
mutate "island replaced by the fragment's own strip" acc/app/src/main/java/com/diegonmarcos/cloudaccount/MainActivity.kt 'BottomNavHost(' 'Box(' || M=$((M+1))
mutate "island no longer fed by NavDecl" acc/app/src/main/java/com/diegonmarcos/cloudaccount/MainActivity.kt 'NavDecl.fromBuildConfig(' 'NavDeclx.fromBuildConfig(' || M=$((M+1))
mutate "fragment is not told to hide its strip" acc/app/src/main/java/com/diegonmarcos/cloudaccount/MainActivity.kt 'ProfileFragment.ARG_EXTERNAL_STRIP to true' 'ProfileFragment.ARG_EXTERNAL_STRIP to false' || M=$((M+1))
mutate "a tab leaves the declaration" acc/build.json '"runtime",
      "drift"' '"runtime"' || M=$((M+1))
mutate "gradle stops baking the sections" acc/app/build.gradle '"UI_SECTIONS_B64"' '"UI_SECTIONS"' || M=$((M+1))
mutate "the fragment stops reporting its tab" lib/profile/ProfileFragment.kt 'tabIds.getOrNull(index)?.let { onTabShown?.invoke(sectionOf(it)) }' '' || M=$((M+1))
mutate "the fragment keeps its strip visible" lib/profile/ProfileFragment.kt '} else visibility = View.GONE' '}' || M=$((M+1))

echo "== RESULT: real tree $REAL failure(s), $M mutation(s) not caught =="
[ "$REAL" -eq 0 ] && [ "$M" -eq 0 ]
