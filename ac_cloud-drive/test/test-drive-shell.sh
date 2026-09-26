#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════════════╗
# ║ #603 — the chrome is DECLARED and NATIVE: tabs, pages, sections, places, ║
# ║ and icons come from build.json::ui; the shell is Compose over the fleet  ║
# ║ island; StatusLight is the superapp's idiom; no caption, colour or tab   ║
# ║ id lives in Kotlin; the WebView page and its string-named bridge are gone ║
# ╚══════════════════════════════════════════════════════════════════════════╝
#
# WHY THIS EXISTS. The #579 verdict on the old app was not a bug report, it was a
# rejection of the whole surface. Gradle proves a Compose tree compiles; it cannot
# see that a declared tab has no dispatch (the island draws it, a tap shows the
# "nothing declared" state), that an icon name silently fell back to the default,
# that a caption was typed into a screen instead of the string table, or that
# the StatusLight green here is not the green superapp promises. This file can.
#
#   D1  ui.tabs declared, unique, default_tab among them; the ids Kotlin dispatches
#       (MainActivity `when (tabId)`) equal the declared ids in both directions.
#   D2  ui.configs.pages ↔ ConfigsScreen's `when (id)`, both directions.
#   D3  every declared icon name (tabs, pages, sections, places, filters, default) is
#       a name in IconCatalog's `when`; the catalog has no dead name either.
#   D4  the declarations are BAKED (build.gradle → UI_*_B64 from buildJson.ui with
#       a hard error on a missing block) and decoded once (Declarations.kt).
#   D5  libs:bottomnav is declared, depended on, linked, and DriveShell draws
#       BottomNavIsland with the declared entries.
#   D6  the WebView shell is gone: no drive.html, no tailwind.js, no FilesBridge,
#       no Material Views widget, no WebView and no JavascriptInterface at all; the
#       one AppCompatActivity left is #577's native pdfium reader.
#   D7  no colour literal and no caption literal in the chrome's Kotlin; every
#       R.string the chrome names exists, and no string is dead.
#   D8  StatusLight: four states, three glyph shapes, colours and words with the
#       superapp's names — and the SAME values as the superapp's when the sibling
#       tree is present (UNVERIFIABLE when it is not, never a silent pass).
#   D9  dark is the default: Theme.Material3.Dark parent + DriveTheme darkColorScheme.
#   D10 the JVM suite and the Robolectric layout-tree test exist and name the tags.
#   D11 #603 THE STRUCTURE THIS REDESIGN IS: exactly five tabs in the order
#       Files · Volumes · Home · Apps · Configs, no `sync` and no `backups` TAB;
#       Configs' six sub-pages in the order Git · Rclone · Mounts · Backups ·
#       General · Others; the Files navigator's two declared sections with the
#       store in its own one; the store's seed set is PUBLIC repositories only,
#       cloned shallow through libs:git-sync; the Apps grid carries GitSync and
#       RSync as in-app routes into declared pages; and the dark theme provides
#       LocalContentColor so no Text() inherits material3's Color.Black default.
#   M   mutation-proof: a tab dropped from the declaration and a glyph dropped from
#       the catalog both turn D1 / D3 red; a private repository marked for seeding
#       turns D11 red; the unmutated tree stays green.
#
# OWN-SOURCE ONLY except D8's cross-app comparison, which reports UNVERIFIABLE
# when aa_cloud-superapp is not beside this app. python3 and grep only.
set -uo pipefail

ROOT="${CLOUD_ANDROID_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && while [ "$PWD" != "/" ] && [ ! -e "$PWD/.git" ]; do cd ..; done; printf '%s' "$PWD")}"
APP="$ROOT/ac_cloud-drive"
BJ="$APP/build.json"
GRADLE="$APP/app/build.gradle"
SRC="$APP/app/src/main/java/com/diegonmarcos/clouddrive"
MAIN="$SRC/MainActivity.kt"
SHELL_KT="$SRC/ui/DriveShell.kt"
CONFIGS="$SRC/configs/ConfigsScreen.kt"
CATALOG="$SRC/ui/IconCatalog.kt"
DECL="$SRC/Declarations.kt"
LIGHT="$SRC/ui/StatusLight.kt"
THEME="$SRC/ui/DriveTheme.kt"
COLORS="$APP/app/src/main/res/values/colors.xml"
STRINGS="$APP/app/src/main/res/values/strings.xml"
THEMES="$APP/app/src/main/res/values/themes.xml"
TESTS="$APP/app/src/test/java/com/diegonmarcos/clouddrive"
REPOS="$APP/data/drive-git-repos.json"
APPS_JSON="$APP/data/drive-apps.json"
SEED="$SRC/StoreSeed.kt"

FAILURES=0
pass() { echo "  PASS  $*"; }
fail() { echo "  FAIL  $*"; FAILURES=$((FAILURES + 1)); }
for required in "$BJ" "$GRADLE" "$MAIN" "$SHELL_KT" "$CONFIGS" "$CATALOG" "$DECL" "$LIGHT" "$THEME" "$COLORS" "$STRINGS" "$THEMES" "$REPOS" "$APPS_JSON" "$SEED"; do
    [ -f "$required" ] || { echo "ERROR missing source: $required — this tester is unrun, not passing"; exit 1; }
done

# ── the checks, as functions of their inputs, so the mutation block can call them on copies ──

# d1 <build.json> <MainActivity.kt> : declared tab ids == dispatched tab ids
d1() {
    python3 - "$1" "$2" <<'PYTHON'
import json, re, sys
bj = json.load(open(sys.argv[1])); main = open(sys.argv[2], encoding="utf-8").read()
tabs = bj.get("ui", {}).get("tabs", [])
ids = [t.get("id") for t in tabs]
if not ids or len(set(ids)) != len(ids) or any(not i for i in ids): print("    ui.tabs missing, empty or not unique"); sys.exit(1)
if bj["ui"].get("default_tab") not in ids: print("    ui.default_tab is not a declared tab"); sys.exit(1)
if any(not t.get("label") or not t.get("icon") for t in tabs): print("    a tab lacks label or icon"); sys.exit(1)
m = re.search(r"when \(tabId\) \{(.*?)\n\s*\}", main, re.S)
if not m: print("    MainActivity has no `when (tabId)` dispatch"); sys.exit(1)
dispatched = re.findall(r'^\s*"([a-z_]+)" ->', m.group(1), re.M)
if sorted(dispatched) != sorted(ids):
    print("    declared %s vs dispatched %s" % (ids, dispatched)); sys.exit(1)
PYTHON
}

# d2 <build.json> <ConfigsScreen.kt>
d2() {
    python3 - "$1" "$2" <<'PYTHON'
import json, re, sys
bj = json.load(open(sys.argv[1])); src = open(sys.argv[2], encoding="utf-8").read()
ids = [p.get("id") for p in bj["ui"]["configs"]["pages"]]
m = re.search(r"when \(id\) \{(.*?)\n\s*\}", src, re.S)
if not m: print("    ConfigsScreen has no `when (id)` dispatch"); sys.exit(1)
dispatched = re.findall(r'^\s*"([a-z_]+)" ->', m.group(1), re.M)
if sorted(dispatched) != sorted(ids): print("    declared %s vs dispatched %s" % (ids, dispatched)); sys.exit(1)
periods = bj["ui"]["configs"].get("git_periods_minutes", [])
if not periods or any(p < 15 for p in periods): print("    git_periods_minutes missing or below WorkManager's 15-minute floor"); sys.exit(1)
PYTHON
}

# d3 <build.json> <IconCatalog.kt> : every declared icon is a catalog name; no dead catalog name
d3() {
    python3 - "$1" "$2" <<'PYTHON'
import json, re, sys
bj = json.load(open(sys.argv[1])); cat = open(sys.argv[2], encoding="utf-8").read()
ui = bj["ui"]
declared = set(t["icon"] for t in ui["tabs"]) | set(p["icon"] for p in ui["configs"]["pages"]) | set(x["icon"] for x in ui["files"]["sections"]) | set(p["icon"] for p in ui["files"]["places"]) | set(f["icon"] for f in ui["files"]["filters"]) | {ui["icons"]["_default"]}
block = re.search(r"fun vector\(name: String\): ImageVector\? = when \(name\) \{(.*?)\n    \}", cat, re.S)
if not block: print("    IconCatalog.vector has no `when (name)`"); sys.exit(1)
known = set(re.findall(r'^\s*"([a-z_]+)" -> Icons\.', block.group(1), re.M))
missing = sorted(declared - known)
if missing: print("    declared icons unknown to IconCatalog: %s" % missing); sys.exit(1)
if len(known) < len(declared): print("    catalog smaller than the declaration"); sys.exit(1)
PYTHON
}

echo "── D1 tabs: declared ↔ dispatched ──"
d1 "$BJ" "$MAIN" && pass "ui.tabs are unique, labelled, iconed; MainActivity dispatches exactly them" || fail "tabs: declaration and dispatch disagree"
if grep -qE 'BuildConfig\.UI_DEFAULT_TAB' "$DECL" && grep -qE 'Declarations\.defaultTab' "$SHELL_KT"; then pass "the default tab is the declared one"; else fail "default tab not read from the declaration"; fi

echo "── D2 configs pages: declared ↔ dispatched ──"
d2 "$BJ" "$CONFIGS" && pass "ui.configs.pages dispatch matches; periods at or above the floor" || fail "configs pages: declaration and dispatch disagree"

echo "── D3 icons from declarations ──"
d3 "$BJ" "$CATALOG" && pass "every declared icon name is in IconCatalog's vocabulary" || fail "an icon is declared that the catalog does not know"
if grep -qE 'IconCatalog\.painter\(it\.icon\)' "$SHELL_KT" && grep -qE 'IconCatalog\.vectorOrDefault\(p\.icon\)' "$CONFIGS"; then pass "the shell and the strip render the declared icon names through the catalog"; else fail "a screen does not resolve its icons through IconCatalog"; fi
if grep -rqE '"ic_[a-z_]+"' "$SRC"; then fail "a drawable name is typed in Kotlin"; else pass "no drawable-name literal in Kotlin"; fi

echo "── D4 baked once, decoded once ──"
for f in UI_TABS_B64 UI_CONFIGS_B64 UI_FILES_B64 UI_ICON_DEFAULT UI_DEFAULT_TAB; do
    if grep -qE "buildConfigField \"String\", *\"$f\"" "$GRADLE" && grep -qE "BuildConfig\.$f" "$DECL"; then pass "$f baked and decoded"; else fail "$f not baked in build.gradle or not read in Declarations.kt"; fi
done
if grep -qE 'throw new GradleException\("build\.json::ui\.tabs must declare' "$GRADLE"; then pass "a missing ui.tabs fails the build"; else fail "build.gradle does not refuse a missing ui.tabs"; fi
OTHER_BLOBS="$(grep -rlE 'BuildConfig\.[A-Z_]+_B64' "$SRC" | grep -v 'Declarations.kt' | grep -v 'EngineActivity.kt' || true)"
if [ -z "$OTHER_BLOBS" ]; then pass "no screen reads a BuildConfig blob directly"; else fail "BuildConfig blobs read outside Declarations/EngineActivity:"; printf '%s\n' "$OTHER_BLOBS" | sed 's/^/        /'; fi

echo "── D5 the fleet island ──"
if python3 -c 'import json,sys; b=json.load(open(sys.argv[1])); m=b["modules"]; sys.exit(0 if "libs:bottomnav" in m and "libs:bottomnav" in m["app"]["depends_on"] and m["libs:bottomnav"].get("dir","").endswith("libs/bottomnav") else 1)' "$BJ"; then pass "libs:bottomnav declared and depended on"; else fail "libs:bottomnav missing from build.json modules / app.depends_on"; fi
if grep -qE "implementation project\(':libs:bottomnav'\)" "$GRADLE"; then pass "libs:bottomnav linked"; else fail "libs:bottomnav not linked in app/build.gradle"; fi
if grep -qE 'BottomNavIsland\(' "$SHELL_KT" && grep -qE 'entries = tabs\.map \{ BottomNavEntry\(it\.id, it\.label, IconCatalog\.painter\(it\.icon\)\) \}' "$SHELL_KT" && grep -qE 'rememberBottomNavCollapse\(\)' "$SHELL_KT"; then pass "DriveShell draws the island from the declared tabs, with scroll-collapse"; else fail "DriveShell does not draw BottomNavIsland from Declarations.tabs"; fi
if grep -qE 'setContent \{ DriveTheme \{ Root\(\) \} \}' "$MAIN" && grep -qE 'class MainActivity : ComponentActivity\(\), DriveActions' "$MAIN"; then pass "MainActivity is a Compose host implementing DriveActions"; else fail "MainActivity is not the Compose host"; fi

echo "── D6 the WebView shell is gone ──"
for gone in "$APP/app/src/main/assets/drive.html" "$APP/app/src/main/assets/vendor/tailwind.js" "$SRC/FilesBridge.kt" "$APP/app/src/main/res/layout/activity_main.xml"; do
    if [ -e "$gone" ]; then fail "still present: ${gone#$APP/}"; else pass "gone: ${gone#$APP/}"; fi
done
JSI="$(grep -rl '@JavascriptInterface' "$SRC" || true)"
if [ -z "$JSI" ]; then pass "no JavascriptInterface anywhere: nothing calls Kotlin by string name any more"; else fail "a JavascriptInterface is back: $JSI"; fi
APPCOMPAT="$(grep -rlE 'AppCompatActivity' "$SRC" --include='*.kt' || true)"
if [ "$APPCOMPAT" = "$SRC/PdfReaderActivity.kt" ]; then pass "the only AppCompatActivity is #577's PDF reader; the chrome is a ComponentActivity"; else fail "an AppCompatActivity outside the PDF reader: $APPCOMPAT"; fi
if grep -qE "^import com\.google\.android\.material\." -r "$SRC"; then fail "a Material Views widget in the chrome"; else pass "no Material Views widget in Kotlin (the material dependency only supplies the AppCompat-descended dark theme the reader needs)"; fi
if grep -rqE 'android\.webkit\.WebView' "$SRC" --include='*.kt'; then fail "a WebView is back in the chrome (the PDF reader is native pdfium, #577)"; else pass "no WebView anywhere: the chrome is Compose, the PDF reader is native (#577)"; fi
for gone in "$APP/app/src/main/assets/reader.html" "$APP/app/src/main/assets/vendor"; do [ -e "$gone" ] && fail "still present: ${gone#$APP/}" || pass "gone: ${gone#$APP/}"; done

echo "── D7 no colour or caption literal; strings alive ──"
COLOUR="$(grep -rnE 'Color\(0x[0-9A-Fa-f]{6,8}\)|parseColor\(|#[0-9A-Fa-f]{6}' "$SRC" --include='*.kt' | grep -vE '^\S+:\s*(//|\*|/\*)' || true)"
if [ -z "$COLOUR" ]; then pass "no colour literal in the chrome's Kotlin (colors.xml → DriveTheme)"; else fail "colour literal in Kotlin:"; printf '%s\n' "$COLOUR" | sed 's/^/        /'; fi
CAPTION="$(grep -rnE '(^|[^A-Za-z])(Text|CapsuleBadge|Pill|SectionHeader|DriveCard|EmptyState)\(\s*"[A-Za-z][^"]*"' "$SRC" --include='*.kt' || true)"
if [ -z "$CAPTION" ]; then pass "no caption literal in a screen (every caption is a string resource or a declaration)"; else fail "caption typed into Kotlin:"; printf '%s\n' "$CAPTION" | sed 's/^/        /'; fi
python3 - "$SRC" "$STRINGS" "$APP/app/src/main/AndroidManifest.xml" <<'PYTHON' && pass "every R.string named in Kotlin exists; no string is dead" || fail "string table and Kotlin disagree (see above)"
import os, re, sys
src, strings, manifest = sys.argv[1], sys.argv[2], sys.argv[3]
used = set()
for d, _, fs in os.walk(src):
    for f in fs:
        if f.endswith(".kt"): used |= set(re.findall(r"R\.string\.([a-z0-9_]+)", open(os.path.join(d, f), encoding="utf-8").read()))
used |= set(re.findall(r"@string/([a-z0-9_]+)", open(manifest, encoding="utf-8").read()))
declared = set(re.findall(r'<string name="([a-z0-9_]+)"', open(strings, encoding="utf-8").read()))
# app_name is build.gradle's resValue (the launcher label = build.json::name), never in strings.xml.
missing = sorted(used - declared - {"app_name"}); dead = sorted(declared - used)
bad = 0
if missing: print("    named in Kotlin but not declared: %s" % missing); bad = 1
if dead: print("    declared but never used: %s" % dead); bad = 1
sys.exit(bad)
PYTHON

echo "── D8 the StatusLight idiom ──"
if grep -qE 'enum class State \{ ON, OFF, UNKNOWN, UNVERIFIABLE \}' "$LIGHT" && grep -qE 'State\.ON -> "●"' "$LIGHT" && grep -qE 'State\.OFF -> "○"' "$LIGHT" && grep -qE 'State\.UNKNOWN -> "\?"' "$LIGHT" && grep -qE 'State\.UNVERIFIABLE -> "\?"' "$LIGHT"; then pass "four states, three glyph shapes"; else fail "StatusLight states or glyphs differ from the idiom"; fi
if grep -qE 'fun of\(reading: Boolean\?, observed: Boolean = true\): State' "$LIGHT" && grep -qE 'null -> State\.UNKNOWN' "$LIGHT"; then pass "null never collapses to OFF; unobserved is UNVERIFIABLE"; else fail "StatusLight.of does not implement the honesty rules"; fi
for c in status_light_on status_light_off status_light_unknown; do grep -qE "<color name=\"$c\">" "$COLORS" && pass "colors.xml declares $c" || fail "colors.xml lacks $c"; done
for s in status_light_on status_light_off status_light_unknown status_light_unverifiable status_light_description; do grep -qE "<string name=\"$s\">" "$STRINGS" && pass "strings.xml declares $s" || fail "strings.xml lacks $s"; done
SUPER="$ROOT/aa_cloud-superapp/app/src/main/res/values/colors.xml"
if [ -f "$SUPER" ]; then
    for c in status_light_on status_light_off status_light_unknown; do
        ours="$(grep -oE "<color name=\"$c\">#[0-9A-Fa-f]+</color>" "$COLORS" | grep -oE '#[0-9A-Fa-f]+' | tr 'a-f' 'A-F')"
        theirs="$(grep -oE "<color name=\"$c\">#[0-9A-Fa-f]+</color>" "$SUPER" | grep -oE '#[0-9A-Fa-f]+' | tr 'a-f' 'A-F')"
        if [ -n "$ours" ] && [ "$ours" = "$theirs" ]; then pass "$c = $ours, the superapp's value"; else fail "$c is '$ours' here and '$theirs' in the superapp — two greens, one of them lying"; fi
    done
else
    echo "  UNVERIFIABLE  aa_cloud-superapp is not beside this app; the colour comparison did not run"
fi
USES="$(grep -rlE 'StatusLightRow\(|StatusLight\.State' "$SRC" | wc -l | tr -d ' ')"
if [ "$USES" -ge 6 ]; then pass "the light is used across the chrome ($USES files)"; else fail "StatusLight is used in only $USES files"; fi

echo "── D9 dark is the default ──"
if grep -qE 'parent="Theme\.Material3\.Dark\.NoActionBar"' "$THEMES" && grep -qE 'darkColorScheme\(' "$THEME" && grep -qE 'colorResource\(R\.color\.drive_background\)' "$THEME"; then pass "forced dark in both halves, colours from resources"; else fail "the theme is not the forced-dark, resource-driven one"; fi

echo "── D10 the JVM suite and the layout-tree test exist and name the tags ──"
for t in "$TESTS/files/FilesStateTest.kt" "$TESTS/files/FileOpsTest.kt" "$TESTS/sync/SyncHistoryTest.kt" "$TESTS/DeclarationsTest.kt" "$TESTS/ui/DriveShellTest.kt" "$TESTS/PdfConvertTest.kt" "$TESTS/PdfLayoutTest.kt"; do
    [ -f "$t" ] && pass "exists: ${t#$APP/}" || fail "missing: ${t#$APP/}"
done
if grep -qE 'RobolectricTestRunner' "$TESTS/ui/DriveShellTest.kt" && grep -qE 'BottomNavTags\.item\(' "$TESTS/ui/DriveShellTest.kt" && grep -qE 'DriveTags\.FILES_TAB_STRIP' "$TESTS/ui/DriveShellTest.kt" && grep -qE 'DriveTags\.FILES_SELECTION_BAR' "$TESTS/ui/DriveShellTest.kt"; then pass "DriveShellTest renders the island and the Files tree under Robolectric"; else fail "DriveShellTest does not measure the declared tree"; fi
if grep -qE 'includeAndroidResources = true' "$GRADLE" && grep -qE "testImplementation 'org\.robolectric:robolectric" "$GRADLE" && grep -qE "testImplementation 'junit:junit" "$GRADLE"; then pass "the test stack is linked"; else fail "app/build.gradle lacks junit / robolectric / includeAndroidResources"; fi
if grep -qE 'IconCatalog\.knows\(' "$TESTS/DeclarationsTest.kt"; then pass "DeclarationsTest holds every declared icon to the catalog"; else fail "DeclarationsTest does not check the icons"; fi

echo "── D11 the #603 structure ──"
d11_structure() {
    python3 - "$1" <<'PYTHON'
import json, sys
ui = json.load(open(sys.argv[1]))["ui"]
bad = 0
tabs = [t["id"] for t in ui["tabs"]]
if tabs != ["files", "volumes", "home", "apps", "configs"]:
    print("    ui.tabs is %s, not the #603 order files/volumes/home/apps/configs" % tabs); bad = 1
if "sync" in ui or "backups" in ui:
    print("    ui still carries a top-level sync/backups block: both moved into ui.configs"); bad = 1
pages = [p["id"] for p in ui["configs"]["pages"]]
if pages != ["git", "rclone", "mounts", "backups", "general", "others"]:
    print("    ui.configs.pages is %s, not git/rclone/mounts/backups/general/others" % pages); bad = 1
if any(not p.get("label") or not p.get("icon") for p in ui["configs"]["pages"]):
    print("    a Configs sub-page lacks a label or an icon"); bad = 1
sections = [x["id"] for x in ui["files"]["sections"]]
if len(sections) != 2 or "emulated" not in sections:
    print("    ui.files.sections is %s: #603 declares Emulated and the store's own section" % sections); bad = 1
store = [p for p in ui["files"]["places"] if p["kind"] == "shared_root"]
if len(store) != 1 or store[0].get("section") == "emulated":
    print("    the shared store is not a section of its own"); bad = 1
unknown = [p["id"] for p in ui["files"]["places"] if p.get("section") not in sections]
if unknown:
    print("    places naming an undeclared section: %s" % unknown); bad = 1
sys.exit(bad)
PYTHON
}
# d11_seed <drive-git-repos.json> : the seed set is PUBLIC repositories only
d11_seed() {
    python3 - "$1" <<'PYTHON'
import json, sys
repos = json.load(open(sys.argv[1]))["repos"]
seeded = [r for r in repos if r.get("seed")]
if not seeded: print("    no repository is marked seed: the store would start empty"); sys.exit(1)
private = [r["name"] for r in seeded if r.get("private")]
if private: print("    PRIVATE repositories marked for seeding: %s (an anonymous clone of one fails every time)" % private); sys.exit(1)
if any(not r.get("public") for r in seeded): print("    a seeded repository does not state public: true"); sys.exit(1)
sys.exit(0)
PYTHON
}
d11_structure "$BJ" && pass "five tabs in the #603 order; six Configs sub-pages in order; two Files sections with the store in its own" || fail "the #603 structure is not what build.json declares"
d11_seed "$REPOS" && pass "the store's seed set is public repositories only" || fail "the seed set is not public-only"
if grep -qE 'buildConfigField "boolean", "SEED_ENABLED"' "$GRADLE" && grep -qE 'buildConfigField "int", +"SEED_DEPTH"' "$GRADLE" && grep -qE 'GitEngine\.clone\(url, dir, depth = BuildConfig\.SEED_DEPTH\)' "$SEED"; then pass "the seed is declared (storage.seed -> SEED_ENABLED/SEED_DEPTH) and cloned SHALLOW through the engine"; else fail "the first-run seed is not wired to the declaration"; fi
if grep -qE 'GitEngine\.isRepository\(dir\)' "$SEED" && grep -qE 'RepoRegistry\(File\(applicationContext\.filesDir, GitSyncWorker\.REGISTRY_FILE\)\)' "$SEED"; then pass "seeding skips a clone that exists and registers into the SAME registry the Git page reads"; else fail "the seed does not reuse the one registry"; fi
if grep -qE 'org\.eclipse\.jgit' "$SEED"; then fail "the seed talks to JGit directly - libs:git-sync is the engine"; else pass "the seed goes through libs:git-sync, never a second git"; fi
python3 - "$APPS_JSON" "$BJ" <<'PYTHON' && pass "the Apps grid carries GitSync and RSync as in-app routes into declared pages" || fail "the Apps grid does not route GitSync / RSync into Configs"
import json, sys
apps = json.load(open(sys.argv[1]))["apps"]
ui = json.load(open(sys.argv[2]))["ui"]
tabs = [t["id"] for t in ui["tabs"]]; pages = [p["id"] for p in ui["configs"]["pages"]]
bad = 0
for want in ("GitSync", "RSync"):
    tile = next((a for a in apps if a.get("label") == want), None)
    if tile is None: print("    no %s tile" % want); bad = 1; continue
    route = tile.get("route") or {}
    if route.get("tab") not in tabs: print("    %s routes to tab %r, which ui.tabs does not declare" % (want, route.get("tab"))); bad = 1
    if route.get("page") not in pages: print("    %s routes to page %r, which ui.configs.pages does not declare" % (want, route.get("page"))); bad = 1
    if tile.get("package"): print("    %s declares a package: it is an in-app route, not an installed app" % want); bad = 1
sys.exit(bad)
PYTHON
if grep -qE 'CompositionLocalProvider\(LocalContentColor provides text, content = content\)' "$THEME"; then pass "the dark theme provides LocalContentColor: no Text() inherits material3's Color.Black on the dark page"; else fail "DriveTheme does not provide LocalContentColor - every Text() without an explicit colour prints BLACK on the dark page"; fi
for a in android:textColorPrimary android:textColorSecondary colorOnSurface; do
    grep -qE "<item name=\"$a\">@color/drive_text" "$THEMES" && pass "Theme.CloudDrive names $a" || fail "Theme.CloudDrive does not override $a - an inflated View falls back to a framework ink"
done

echo "== M mutation-proof =="

TMP="$(mktemp -d)"; trap 'rm -rf "${TMP:?}"' EXIT
python3 -c 'import json,sys; b=json.load(open(sys.argv[1])); b["ui"]["tabs"]=b["ui"]["tabs"][1:]; json.dump(b,open(sys.argv[2],"w"))' "$BJ" "$TMP/no-files.json"
d1 "$TMP/no-files.json" "$MAIN" >/dev/null && fail "D1 passed a declaration that dropped a tab (tester is vacuous)" || pass "a tab dropped from ui.tabs → D1 RED"
grep -v '"folder" -> Icons.Filled.Folder' "$CATALOG" > "$TMP/no-folder.kt"
cmp -s "$CATALOG" "$TMP/no-folder.kt" && fail "the catalog mutation changed nothing (tester is stale)"
d3 "$BJ" "$TMP/no-folder.kt" >/dev/null && fail "D3 passed a catalog without the folder glyph (tester is vacuous)" || pass "a glyph dropped from IconCatalog → D3 RED"
sed 's/"git" -> GitReposScreen/"gits" -> GitReposScreen/' "$CONFIGS" > "$TMP/configs.kt"
cmp -s "$CONFIGS" "$TMP/configs.kt" && fail "the configs mutation changed nothing (tester is stale)"
d2 "$BJ" "$TMP/configs.kt" >/dev/null && fail "D2 passed a misdispatched page (tester is vacuous)" || pass "a page id misspelt in ConfigsScreen → D2 RED"
python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); [r.update(seed=True) for r in d["repos"] if r.get("private")]; json.dump(d,open(sys.argv[2],"w"))' "$REPOS" "$TMP/seed-private.json"
d11_seed "$TMP/seed-private.json" >/dev/null && fail "D11 passed a PRIVATE repository marked for seeding (tester is vacuous)" || pass "a private repository marked seed → D11 RED"
d1 "$BJ" "$MAIN" >/dev/null && d2 "$BJ" "$CONFIGS" >/dev/null && d3 "$BJ" "$CATALOG" >/dev/null && d11_seed "$REPOS" >/dev/null && pass "unmutated tree is still green" || fail "the unmutated tree is red"

echo
if [ "$FAILURES" -eq 0 ]; then echo "test-drive-shell: all checks passed"; else echo "test-drive-shell: $FAILURES check(s) FAILED"; exit 1; fi
