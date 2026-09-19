#!/usr/bin/env bash
# Tester: there is ONE notification centre, ONE producer per install event, and
# no declaration anywhere still offers the surface that was deleted (#515).
#
# THE FAILURE THIS EXISTS FOR.
#
# The notification centre was built twice. The launcher had its own drop-down
# shade (NotificationCenterFragment, opened from the dynamic island and from a
# home action) and the Notify page had the declared `notification_center` panel.
# Both read core.NotificationStore and both called NotificationManager
# .cancelAll() on render, so whichever the owner opened first silently destroyed
# the dismissal state the other would have shown. #497 diagnosed it; Diego's
# call was to delete the shade. Separately, every update left TWO entries in the
# feed: PackageInstallerReceiver recorded the install, and Application.onCreate
# announced the same install again on the next launch.
#
# WHY THIS TESTER RESOLVES EVERYTHING AND MATCHES ALMOST NOTHING BY NAME.
#
# #511 is the warning: two bottom-nav testers went blind when a class was
# subclassed, because they asserted on a bare class name. A name a refactor can
# move out from under the assertion is not an assertion. So every subject here
# is RESOLVED from a declaration first — the Application class from the
# manifest, the renderer from the panel kinds build.json declares, the island
# vocabulary from build.json, the Push renderer from the call that reads the
# badge declaration — and a declared path that resolves to NOTHING is a FAILURE,
# never a skip. The counting assertions (T4, T5) are the ones that survive a
# rename: they say "exactly one", whatever it is called.
#
# Nothing here touches a device. Every assertion is over declared data or over
# source this container can honestly read.
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"          # → aa_cloud-superapp
BJ="$APP/build.json"
SRC="$APP/app/src/main/java/com/diegonmarcos/superapp"
MANIFEST="$APP/app/src/main/AndroidManifest.xml"
GRADLE="$APP/app/build.gradle"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }

command -v jq >/dev/null || { echo "ERROR: jq required" >&2; exit 2; }
for f in "$BJ" "$MANIFEST" "$GRADLE"; do
    [ -f "$f" ] || { echo "ERROR: declaration not found at $f" >&2; exit 2; }
done
[ -d "$SRC" ] || { echo "ERROR: app sources not found at $SRC" >&2; exit 2; }

echo "== the dynamic-island vocabulary is declared, and every form it offers has a branch =="

# T1. The vocabulary is DATA (build.json::ui._vocab_dynamic_island_action). The
# dispatcher is resolved from the BuildConfig field the gradle file bakes that
# value into — not from a function or class name — so renaming the dispatcher
# cannot make this go quiet. Both directions are failures: a declared form with
# no branch is a dead tap (that is exactly what `notifications` became), and a
# branch for a form nobody declares is a surface reachable by editing one JSON
# line.
DISPATCHER="$(grep -rl 'BuildConfig\.UI_DYNAMIC_ISLAND_ACTION' --include='*.kt' "$SRC")"
if [ -z "$DISPATCHER" ]; then
    bad "T1 nothing in the app reads BuildConfig.UI_DYNAMIC_ISLAND_ACTION — the declaration is baked and then dropped on the floor, so ui.dynamic_island_action controls nothing"
elif [ "$(printf '%s\n' "$DISPATCHER" | wc -l)" -ne 1 ]; then
    bad "T1 $(printf '%s\n' "$DISPATCHER" | wc -l) files read BuildConfig.UI_DYNAMIC_ISLAND_ACTION — one binding, one reader, or they will disagree: $(printf '%s ' $DISPATCHER)"
else
    python3 - "$BJ" "$DISPATCHER" <<'PY'
import io, json, re, sys
declared = json.load(io.open(sys.argv[1], encoding="utf-8"))["ui"].get("_vocab_dynamic_island_action")
src = io.open(sys.argv[2], encoding="utf-8").read()
# Comments necessarily NAME the form that was removed, in the note explaining
# why it must never come back. Strip them, or the tester reads the warning as
# the thing it warns about.
code = re.sub(r"/\*.*?\*/", "", src, flags=re.S)
code = re.sub(r"^[ \t]*//.*$", "", code, flags=re.M)
fails = []
if not declared:
    fails.append("build.json::ui._vocab_dynamic_island_action is absent — the supported "
                 "forms are undeclared, so nothing can be validated and any string reaches the dispatcher")
    print("  FAIL: T1 " + fails[0]); sys.exit(1)
forms = [f.rstrip(":") for f in declared]
# The dispatcher splits the value on ':' and switches on the scheme. Take the
# FIRST `when` block after the BuildConfig read and brace-match it, so the other
# `when`s in the same file are not mistaken for island branches.
start = code.find("BuildConfig.UI_DYNAMIC_ISLAND_ACTION")
wh = code.find("when (", start)
if start < 0 or wh < 0:
    print("  FAIL: T1 the file that reads BuildConfig.UI_DYNAMIC_ISLAND_ACTION does not switch on "
          "it — the declared value is read and then not dispatched")
    sys.exit(1)
depth, i = 0, code.index("{", wh)
end = i
while i < len(code):
    if code[i] == "{":
        depth += 1
    elif code[i] == "}":
        depth -= 1
        if depth == 0:
            end = i
            break
    i += 1
block = code[wh:end]
branches = set(re.findall(r'^\s*"([a-z_]+)"\s*->', block, flags=re.M))
missing = [f for f in forms if f not in branches]
extra = [b for b in branches if b not in forms]
if missing:
    fails.append("declared form(s) %s have no branch in %s — a tap on them does nothing"
                 % (missing, sys.argv[2].rsplit("/", 1)[-1]))
if extra:
    fails.append("branch(es) %s are not in ui._vocab_dynamic_island_action — an undeclared "
                 "surface one JSON edit away from being reachable" % extra)
if fails:
    for f in fails:
        print("  FAIL: T1 " + f)
    sys.exit(1)
print("  PASS: T1 %d declared form(s) %s, each with exactly one branch, and no branch that "
      "is not declared" % (len(forms), forms))
PY
    [ $? -eq 0 ] && PASS=$((PASS+1)) || FAIL=$((FAIL+1))
fi

# T2 — the value itself is present and is one of the declared forms.
python3 - "$BJ" <<'PY'
import io, json, sys
ui = json.load(io.open(sys.argv[1], encoding="utf-8"))["ui"]
vocab = ui.get("_vocab_dynamic_island_action") or []
value = (ui.get("dynamic_island_action") or "").strip()
if not value:
    print("  FAIL: T2 build.json::ui.dynamic_island_action is absent or blank, and it has no "
          "default on purpose — the build must fail rather than pick a surface for the owner")
    sys.exit(1)
if not any(value.startswith(v) for v in vocab):
    print("  FAIL: T2 ui.dynamic_island_action = %r is not one of the declared forms %s" % (value, vocab))
    sys.exit(1)
print("  PASS: T2 ui.dynamic_island_action = %r, which is a declared form" % value)
PY
[ $? -eq 0 ] && PASS=$((PASS+1)) || FAIL=$((FAIL+1))

echo
echo "== the build refuses an absent key instead of defaulting to a deleted surface =="

# T3 — THE TRAP THIS TICKET WAS ABOUT. app/build.gradle used to read
#   def dynamicIslandAction = (buildJson.ui.dynamic_island_action ?: 'notifications')
# A default is reached only when the declaration is missing, which is the one
# moment nobody is watching; that one re-armed the shade being deleted. The
# assertion is on the SHAPE of the read (no elvis default on that key) plus the
# presence of a hard failure, so writing any new fallback is RED.
# The comment block above the read necessarily QUOTES the elvis default it
# explains the file no longer has, so the prose must not be searchable here.
GRADLE_CODE="$(awk '{ sub(/\/\/.*$/, ""); print }' "$GRADLE")"
ISLAND_READ="$(printf '%s\n' "$GRADLE_CODE" | grep -n 'buildJson\.ui\.dynamic_island_action')"
if [ -z "$ISLAND_READ" ]; then
    bad "T3 app/build.gradle no longer reads buildJson.ui.dynamic_island_action at all — the declaration is not what the APK is built from"
elif printf '%s\n' "$ISLAND_READ" | grep -q '?:'; then
    bad "T3 app/build.gradle defaults ui.dynamic_island_action with an elvis operator: ${ISLAND_READ}. A fallback here decides the island binding whenever the key is absent, which is how 'notifications' outlived the surface it opened"
else
    ok "T3 app/build.gradle reads ui.dynamic_island_action with no fallback default"
fi
# Structural, not prose: each of the three ways the declaration can be wrong has
# to reach a `throw`. Asserting on the CONDITIONS survives a reworded message.
missing_guard=""
printf '%s\n' "$GRADLE_CODE" | grep -q 'if (!islandVocab) throw new GradleException' \
    || missing_guard="$missing_guard no-vocabulary"
printf '%s\n' "$GRADLE_CODE" | grep -q 'if (!dynamicIslandAction) throw new GradleException' \
    || missing_guard="$missing_guard no-value"
printf '%s\n' "$GRADLE_CODE" | grep -q 'if (!islandVocab.any {' \
    || missing_guard="$missing_guard scheme-outside-vocabulary"
if [ -z "$missing_guard" ]; then
    ok "T3 the build throws on all three: no vocabulary, no value, and a value outside the vocabulary"
else
    bad "T3 app/build.gradle does not fail hard on:$missing_guard — an unguarded case silently picks the island binding instead of stopping the build"
fi

echo
echo "== one store, one reader, one cancelAll =="

# T4 — the in-app feed has exactly ONE renderer, and it is the file that draws a
# panel kind build.json actually declares. Both halves matter: the count is what
# catches a second shade whatever it is named, and the kind cross-check is what
# stops the count passing because the ONE reader left is some unrelated file.
KINDS="$(jq -r '[.ui.sections[]? | to_entries[] | select(.key|startswith("stack_")) |
                 .value | if type=="array" then .[] else empty end | .kind? // empty]
                | unique | .[]' "$BJ" 2>/dev/null)"
READERS="$(grep -rl 'NotificationStore\.all(' --include='*.kt' "$SRC")"
n_readers=$(printf '%s' "$READERS" | grep -c . )
if [ "$n_readers" -ne 1 ]; then
    bad "T4 $n_readers surface(s) read core.NotificationStore — the duplicate this ticket deleted is back, and both will cancelAll() over each other: $(printf '%s ' $READERS)"
elif [ -z "$KINDS" ]; then
    bad "T4 build.json declares no panel kinds under any section's stack_* — the declared path resolved to nothing, so this tester cannot see what the one reader is supposed to be drawing"
else
    matched=""
    for k in $KINDS; do
        grep -q "\"$k\"" "$READERS" && matched="$matched $k"
    done
    if [ -n "$matched" ]; then
        ok "T4 exactly one surface reads core.NotificationStore ($(basename "$READERS")), and it dispatches declared panel kind(s):$matched"
    else
        bad "T4 the one core.NotificationStore reader ($(basename "$READERS")) dispatches none of the panel kinds build.json declares — the feed is drawn by something no declaration points at"
    fi
fi

# T5 — cancelAll() destroys the system dismissal state for the whole app. Two
# surfaces calling it was half of why #497 was a bug. One is the correct end
# state; zero means the launcher badge stops clearing.
n_cancel=$(grep -rh 'cancelAll()' --include='*.kt' "$SRC" | grep -c .)
if [ "$n_cancel" -eq 1 ]; then
    ok "T5 exactly one NotificationManager.cancelAll() in the app sources"
else
    bad "T5 $n_cancel call(s) to NotificationManager.cancelAll() — one is the whole app's dismissal state, so a second caller destroys what the first would have shown (zero means the badge never clears)"
fi

# T6 — the deleted shade resolves to nothing, anywhere: no source file, no
# import, no reference. This one names the thing on purpose — it is a ban, and
# a rename cannot make a ban falsely pass, because T4 and T5 count the shape.
LEFTOVER="$(grep -rn 'NotificationCenterFragment\|openNotificationCenter\|open_notification_center' \
    --include='*.kt' --include='*.xml' --include='*.json' --include='*.gradle' "$APP")"
if [ -z "$LEFTOVER" ]; then
    ok "T6 the drop-down shade resolves to nothing — no file, no import, no call site, no home action"
else
    bad "T6 the deleted shade still has references: $(printf '%s' "$LEFTOVER" | head -5)"
fi

echo
echo "== one install event, one entry in the feed =="

# T7 — the Application class is resolved from the MANIFEST, so renaming App.kt
# moves this assertion with it instead of blinding it. A push from
# Application.onCreate has no install session behind it: it fires on every first
# launch after a versionCode change, and versionCode here is minted from wall-
# clock minutes (app/build.gradle), so every CI build is a "version bump". The
# same install is already recorded by PackageInstallerReceiver, under the same
# source. Two entries, one event — that is the badge that kept coming back.
APP_CLASS="$(grep -o 'android:name="\.[A-Za-z0-9_.]*"' "$MANIFEST" | head -1 | sed 's/.*"\.\(.*\)"/\1/')"
APP_KT="$SRC/$(printf '%s' "$APP_CLASS" | tr '.' '/').kt"
if [ -z "$APP_CLASS" ]; then
    bad "T7 AndroidManifest declares no <application android:name> — the Application class cannot be resolved, so this assertion has no subject"
elif [ ! -f "$APP_KT" ]; then
    bad "T7 the manifest declares application class '.$APP_CLASS' and it resolves to no file at $APP_KT — a declared path that resolves to nothing is a failure, not a skip"
elif grep -n 'NotificationStore\.push' "$APP_KT"; then
    bad "T7 $APP_CLASS pushes into core.NotificationStore from the Application lifecycle — that is a second announcement of an install PackageInstallerReceiver already recorded, on every process start that follows a versionCode change"
else
    ok "T7 the manifest's application class ($APP_CLASS) holds no core.NotificationStore producer"
fi

echo
echo "== the KDE badge did not go down with the shade =="

# T8 — the shade was the only surface drawing the live "Cloud SA - KDE" status.
# Deleting it is only safe because Configs ▸ Panel ▸ Push renders it, and Push
# renders exactly the producers declared with badge=true. Resolved end to end:
# the producer is declared, its owner file exists, and the renderer that filters
# on badge=true is present — so this fails if the badge is dropped from the
# declaration OR if the page stops deriving from it.
PUSH_RENDERER="$(grep -rl 'BadgeDeclaration\.badges(' --include='*.kt' "$SRC")"
python3 - "$BJ" "$APP" <<'PY'
import io, json, os, sys
bj, app = sys.argv[1], sys.argv[2]
producers = json.load(io.open(bj, encoding="utf-8"))["ui"].get("notification_center", {}).get("producers", [])
kde = [p for p in producers if p.get("id") == "kde_status"]
if not kde:
    print("  FAIL: T8 no producer with id 'kde_status' in ui.notification_center.producers — the live "
          "KDE status the deleted shade used to draw is now declared nowhere")
    sys.exit(1)
p = kde[0]
if not p.get("badge"):
    print("  FAIL: T8 the kde_status producer is declared with badge=false — Configs > Panel > Push "
          "renders only badge=true producers, so the KDE status has no surface at all")
    sys.exit(1)
owner = os.path.join(app, "app/src/main/java/com/diegonmarcos/superapp", p.get("owner", ""))
if not p.get("owner") or not os.path.isfile(owner):
    print("  FAIL: T8 the kde_status producer names owner %r, which resolves to no file at %s"
          % (p.get("owner"), owner))
    sys.exit(1)
print("  PASS: T8 kde_status is declared badge=true, surface %r, owner %s resolves"
      % (p.get("surface"), p.get("owner")))
PY
[ $? -eq 0 ] && PASS=$((PASS+1)) || FAIL=$((FAIL+1))
if [ -n "$PUSH_RENDERER" ]; then
    ok "T8 Configs > Panel > Push derives its boxes from the declaration ($(basename "$PUSH_RENDERER" | head -1))"
else
    bad "T8 nothing calls BadgeDeclaration.badges() — Push no longer derives from ui.notification_center, so a badge declared there reaches no screen"
fi

echo
echo "notification-centre-is-singular: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
