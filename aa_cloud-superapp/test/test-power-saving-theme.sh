#!/usr/bin/env bash
# Tester: Cloud Power Saving is a theme the user can SEE and UNDO.
#
# WHY THIS EXISTS. The Power Saving theme changed nine device states and only
# one of them had a switch anywhere in the app — and that one it did not
# actually write, so "All animations" read ON while the theme had stopped every
# animation. Worse, `background_pause` cancelled the ntfy poll, which means
# picking a theme silently stopped notification delivery with nothing on screen
# saying so. A user could not tell what the theme had done, and could not put it
# back without abandoning the theme.
#
# The fix is data: a theme declares `toggles` beside its `features`, picking a
# theme applies both in one action, and every feature key must be accounted for
# by a toggle that exposes it (or declared non-toggleable with a reason). These
# assertions are what keep that true, because every one of them is the kind of
# thing only a device could otherwise report — and only after the user noticed.
#
# Static tester (no device, no build): build.json is read as data and the Kotlin
# is checked for the contracts that data relies on.
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"          # → aa_cloud-superapp
BJ="$APP/build.json"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
check() { if [ "$1" = "OK" ]; then ok "$2"; else bad "$2 — $1"; fi; }

THEMES="$APP/app/src/main/java/com/diegonmarcos/superapp/settings/LauncherConfigFragment.kt"
PREFS="$APP/app/src/main/java/com/diegonmarcos/superapp/settings/LauncherSettingsPrefs.kt"
NAV="$APP/app/src/main/java/com/diegonmarcos/superapp/launcher/LauncherNavController.kt"
PANE="$APP/app/src/main/java/com/diegonmarcos/superapp/launcher/PowerSavingFragment.kt"
ORCH="$APP/app/src/main/java/com/diegonmarcos/superapp/system/BackgroundOrchestrator.kt"
GRADLE="$APP/app/build.gradle"

echo "== T1: the twelve Power Saving home apps each resolve to a DECLARED package =="
# A tile that resolves to nothing is invisible until someone taps it, so this is
# the assertion that has to fail in CI rather than at 11pm on the phone. Both
# grammars are resolved through the CENTRAL identity records and nothing else:
#   extapp:<id>   → ui.external_apps[].id
#   app:<package> → a ui.phone_folders `pkg:` / `pkg^` keyword, or a package one
#                   of our own ui.external_apps entries owns.
while IFS=$'\t' read -r slot label target verdict; do
  [ "$verdict" = "OK" ] && ok "slot $slot ($label) → $target" \
                        || bad "slot $slot ($label) → $target : $verdict"
done < <(python3 - "$BJ" <<'PY'
import json, sys
d = json.load(open(sys.argv[1]))
ui = d["ui"]
ext = {e["id"]: e for e in ui["external_apps"]}
owned = set()
for e in ui["external_apps"]:
    for k in ("hub_package", "alt_package", "install_package"):
        if e.get(k): owned.add(e[k].lower())
    for v in (e.get("forks") or {}).values():
        if v: owned.add(v.lower())
exact, prefixes = set(), []
for f in ui["phone_folders"]:
    for kw in (f.get("match_keywords") or []):
        k = kw.lower()
        if k.startswith("pkg:"):   exact.add(k[4:])
        elif k.startswith("pkg^"): prefixes.append(k[4:])

def declared(pkg):
    p = pkg.lower()
    return p in exact or p in owned or any(p.startswith(x) for x in prefixes)

ps = next(t for t in ui["launcher_themes"] if t["id"] == "cloud_power_saving")
for i, s in enumerate(ps["home_apps"]):
    t = s["target"]
    if t.startswith("extapp:"):
        i_d = t[len("extapp:"):].split("#")[0].split("/")[0]
        verdict = "OK" if i_d in ext else "no ui.external_apps entry with id %r" % i_d
    elif t.startswith("app:"):
        pkg = t[len("app:"):]
        verdict = "OK" if declared(pkg) else (
            "package %r is declared nowhere — add pkg:%s to a ui.phone_folders "
            "entry or give it a ui.external_apps record" % (pkg, pkg))
    else:
        verdict = "target grammar %r is neither extapp: nor app:" % t
    print("\t".join([str(i), s.get("label", "?"), t, verdict]))
PY
)

echo "== T2: the grid is twelve slots, two rows of six, with unique ids =="
check "$(python3 - "$BJ" <<'PY'
import json, sys
ui = json.load(open(sys.argv[1]))["ui"]
ps = next(t for t in ui["launcher_themes"] if t["id"] == "cloud_power_saving")
slots = ps["home_apps"]
ids = [s["id"] for s in slots]
problems = []
if len(slots) != 12: problems.append("%d slots, want 12" % len(slots))
if ps["features"].get("grid") != "6x2":
    problems.append("features.grid = %r, want '6x2' (the pane renders 6 per row)" % ps["features"].get("grid"))
dupes = {i for i in ids if ids.count(i) > 1}
if dupes: problems.append("duplicate slot ids: %s" % sorted(dupes))
print("; ".join(problems) or "OK")
PY
)" "twelve slots, grid 6x2, ids unique"

echo "== T3: every toggle id is unique, and names a declared group =="
# Ids are flat and must be unique across the WHOLE list, not merely within a
# group: the fragment looks a toggle up by id alone, and two switches sharing
# one id would read and write the same preference key while claiming to be
# different settings.
check "$(python3 - "$BJ" <<'PY'
import json, sys
ls = json.load(open(sys.argv[1]))["ui"]["launcher_settings"]
ids = [t["id"] for t in ls["toggles"]]
groups = {g["id"] for g in ls["toggle_groups"]}
problems = []
dupes = sorted({i for i in ids if ids.count(i) > 1})
if dupes: problems.append("duplicate toggle ids across groups: %s" % dupes)
for t in ls["toggles"]:
    if not t.get("group"):
        problems.append("toggle %r declares no group" % t["id"])
    elif t["group"] not in groups:
        problems.append("toggle %r names group %r, which no toggle_groups entry defines" % (t["id"], t["group"]))
empty = [g["id"] for g in ls["toggle_groups"] if not any(t.get("group") == g["id"] for t in ls["toggles"])]
if empty: problems.append("group(s) with no toggles, so the box never renders: %s" % empty)
print("; ".join(problems) or "OK")
PY
)" "$(python3 -c "
import json;ls=json.load(open('$BJ'))['ui']['launcher_settings']
print('%d toggle ids, all unique, across %d groups: %s' % (len(ls['toggles']), len(ls['toggle_groups']), ', '.join(g['label'] for g in ls['toggle_groups'])))")"

echo "== T4: every state a theme changes has a toggle that exposes it =="
# THE ASSERTION THIS FILE IS FOR. Walk every feature key of every theme; each
# must either map to a real toggle in feature_exposure, or be declared in
# feature_not_toggleable with a reason. A new feature flag that skips both fails
# here rather than shipping invisible.
while IFS=$'\t' read -r key how verdict; do
  [ "$verdict" = "OK" ] && ok "feature '$key' $how" || bad "feature '$key': $verdict"
done < <(python3 - "$BJ" <<'PY'
import json, sys
ui = json.load(open(sys.argv[1]))["ui"]
ls = ui["launcher_settings"]
toggles = {t["id"] for t in ls["toggles"]}
exposure = ls["feature_exposure"]
excused  = ls["feature_not_toggleable"]
keys = sorted({k for t in ui["launcher_themes"] for k in (t.get("features") or {})})
for k in keys:
    if k in exposure:
        tid = exposure[k]
        how = "→ toggle '%s'" % tid
        v = "OK" if tid in toggles else ("maps to toggle %r, which does not exist" % tid)
    elif k in excused:
        how = "is declared NON-TOGGLEABLE with a reason"
        v = "OK" if str(excused[k]).strip() else "is excused with an empty reason"
    else:
        # A placeholder, never "": `read` with IFS=tab collapses consecutive
        # tabs, so an empty column shifts the verdict into $how and the one case
        # this check exists for would print with no reason attached. The same
        # trap is documented in test-app-identity-resolves.sh T8a.
        how = "-"
        v = ("is changed by a theme but has neither a toggle in feature_exposure "
             "nor an entry in feature_not_toggleable — it would change the device "
             "with nothing on screen saying so")
    print("%s\t%s\t%s" % (k, how, v))
PY
)
# ...and the same for the background work background_pause tears down.
while IFS=$'\t' read -r job verdict; do
  [ "$verdict" = "OK" ] && ok "background job '$job' has a toggle" || bad "background job '$job': $verdict"
done < <(python3 - "$BJ" <<'PY'
import json, sys
ls = json.load(open(sys.argv[1]))["ui"]["launcher_settings"]
toggles = {t["id"] for t in ls["toggles"]}
for job, tid in ls["background_jobs"].items():
    print("%s\t%s" % (job, "OK" if tid in toggles else "maps to toggle %r, which does not exist" % tid))
PY
)
# The declared job list must not fall behind the orchestrator's real one. A tag
# cancelled by code but absent from build.json is a job the user cannot see stop.
while IFS= read -r tag; do
  grep -q "\"$tag\"" "$BJ" \
    && ok "orchestrator tag $tag is declared in ui.launcher_settings.background_jobs" \
    || bad "BackgroundOrchestrator cancels $tag but build.json::ui.launcher_settings.background_jobs does not list it — no switch shows it stopping"
done < <(sed -n '/TAGGED_JOBS = setOf(/,/)/p' "$ORCH" | grep -o '"[a-z-]*"' | tr -d '"')

echo "== T5: the edge-menu toggle is USER-OWNED and no theme may force it =="
# Part four's constraint. Power saving must be able to run with edge menus ON,
# so a theme may not carry this id at all — and the guard is at PARSE time, not
# at the call sites, so there is one place to get right instead of one per
# caller.
check "$(python3 - "$BJ" <<'PY'
import json, sys
ui = json.load(open(sys.argv[1]))["ui"]
edge = next((t for t in ui["launcher_settings"]["toggles"] if t["id"] == "edge_menus"), None)
problems = []
if edge is None:
    problems.append("there is no edge_menus toggle at all")
else:
    if not edge.get("user_owned"):
        problems.append("edge_menus is not marked user_owned, so a theme may write it")
    if edge.get("store") != "onehand":
        problems.append("edge_menus store = %r, want 'onehand' — it must read the same "
                        "key the One-Hand tab writes, not a second copy" % edge.get("store"))
for t in ui["launcher_themes"]:
    if "edge_menus" in (t.get("toggles") or {}):
        problems.append("theme %r names edge_menus in its toggle mapping" % t["id"])
print("; ".join(problems) or "OK")
PY
)" "edge_menus is user-owned, onehand-backed, and named by no theme"

grep -q 'if (key in userOwned) continue' "$THEMES" \
  && ok "LauncherThemes.parseToggles drops user-owned ids at parse time" \
  || bad "user-owned ids are not dropped when the theme mapping is parsed — a theme could force edge menus off"
grep -q 'val userOwnedIds' "$PREFS" \
  && ok "the user-owned set is derived from build.json, not a Kotlin list" \
  || bad "userOwnedIds missing — the parse-time guard has nothing to consult"

echo "== T6: picking a theme applies the UI and the toggles TOGETHER =="
grep -q 'fun apply(ctx: android.content.Context, theme: LauncherTheme)' "$THEMES" \
  && ok "LauncherThemes.apply is the one door that sets theme + toggles" \
  || bad "no single apply() — the chrome and the device state can be set apart"
# The mapping must be DATA. A `when (theme)` over toggle values would be the
# second place to edit that this change exists to remove.
if grep -qE 'when *\( *theme *\)' "$THEMES" && \
   sed -n '/fun parseToggles/,/getOrDefault/p' "$THEMES" | grep -qE 'when *\( *theme *\)'; then
  bad "the theme → toggle mapping branches on the theme in Kotlin instead of reading build.json"
else
  ok "the theme → toggle mapping is read from build.json, not a Kotlin when-block"
fi
grep -q 'fun isModified' "$THEMES" \
  && ok "a hand-flipped toggle is reported as 'modified' rather than silently claiming the theme" \
  || bad "no isModified — the picker would keep claiming a theme the device no longer matches"

echo "== T7: Power Saving renders its OWN home pane =="
# Without this it falls through to Home3DFragment, which is the colourful house
# design — the exact defect: a power-saving mode that looks expensive.
grep -q 'LauncherTheme.CloudPowerSaving *-> *PowerSavingFragment.newInstance()' "$NAV" \
  && ok "goHome routes CloudPowerSaving to PowerSavingFragment" \
  || bad "CloudPowerSaving has no home pane branch — it falls through to the 3D cube"
[ -f "$PANE" ] && ok "PowerSavingFragment exists" || bad "PowerSavingFragment is missing"
# Both of these used to be greps for a literal in the pane — `Color.BLACK` and
# a `private const val COLUMNS = 6`. Neither is there any more, and their
# absence is the fix rather than a regression: a colour written at the view
# cannot follow a theme, and a column count written in the pane is a second
# constant that has to agree with the editor's. The pane now reads both from
# the theme record, so these assert the VALUES that record carries — which is
# what "black" and "six per row" actually meant all along.
grep -q 'setBackgroundResource(palette.windowRes)' "$PANE" \
  && ok "the pane paints the theme's declared window" \
  || bad "the pane does not take its background from the palette"
check "$(python3 - "$BJ" "$APP/app/src/main/res/values/colors.xml" <<'PYB'
import json, re, sys
ui = json.load(open(sys.argv[1]))["ui"]
ps = next(t for t in ui["launcher_themes"] if t["id"] == "cloud_power_saving")
tok = dict(re.findall(r'<color name="([^"]+)">#([0-9A-Fa-f]{8})<', open(sys.argv[2]).read()))
name = ps["palette"]["window"]
hexv = tok.get(name)
if hexv is None:
    print("the window role names %r, which is not a colors.xml token" % name)
elif hexv[2:].upper() != "000000":
    print("the window is #%s, not true black — an OLED panel spends power on "
          "every lit subpixel, so a near-black saves nothing this mode claims" % hexv[2:].upper())
else:
    print("OK")
PYB
)" "the pane's declared window is TRUE black"
grep -q 'gridColumnsFor' "$PANE" \
  && ok "the pane lays out the theme's declared grid width, not its own constant" \
  || bad "the pane hardcodes its column count instead of reading features.grid"

echo "== T8: the baked constants stay clear of javac's 65,535-byte cap =="
# ui.sections is the one that has actually broken a build (BuildConfig.java:
# "constant string too long"), and this change deliberately puts none of its
# payload there. The two launcher blobs are checked too, because they are where
# it went instead.
while IFS=$'\t' read -r name size headroom verdict; do
  [ "$verdict" = "OK" ] && ok "$name = $size bytes, $headroom to spare" \
                        || bad "$name = $size bytes, only $headroom to spare — $verdict"
done < <(python3 - "$BJ" <<'PY'
import json, sys, base64
CAP, MARGIN = 65535, 4096
d = json.load(open(sys.argv[1]))
def strip(o):
    if isinstance(o, dict):  return {k: strip(v) for k, v in o.items() if not k.startswith("_doc")}
    if isinstance(o, list):  return [strip(v) for v in o]
    return o
# Mirrors app/build.gradle: stripDocs, then compact JSON, then base64.
for const, key in (("UI_SECTIONS_JSON_B64", "sections"),
                   ("UI_LAUNCHER_THEMES_B64", "launcher_themes"),
                   ("UI_LAUNCHER_SETTINGS_B64", "launcher_settings")):
    n = len(base64.b64encode(json.dumps(strip(d["ui"][key]), separators=(",", ":"),
                                        ensure_ascii=False).encode()))
    head = CAP - n
    v = "OK" if head >= MARGIN else "under the %d-byte safety margin; split it into its own constant" % MARGIN
    print("\t".join([const, str(n), str(head), v]))
PY
)
# The two launcher blobs must go through stripDocs like ui.sections does, or the
# prose above ships in the APK and counts against the same cap.
for c in launcher_themes launcher_settings; do
  grep -q "stripDocs(buildJson.ui.$c" "$GRADLE" \
    && ok "ui.$c is emitted through stripDocs" \
    || bad "ui.$c is baked with its _doc prose — dead weight against the 65,535-byte cap"
done

echo
echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
