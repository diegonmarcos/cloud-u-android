#!/usr/bin/env bash
# Tester: Projects ▸ Inboxes groups by CHANNEL CLASS, and no notification can
# fall out of the page while it does.
#
# BEFORE: the page carried one card per app. It could not lose anything, because
# every card named its own app and the reader could see which apps were there.
#
# NOW: it carries four curated classes — Mail, Chat, Messenger, RSS — and four
# curated classes are not a catch-all. The failure that would actually hurt is
# silent: the owner installs a messaging app, nothing in ui.inbox_classes claims
# it, and its notifications simply stop appearing on the screen they check. No
# error, no empty state, no clue. That is the failure T2 exists for, and it is
# why the catch-all is asserted rather than trusted.
#
#   T1 every declared class resolves to at least one real app the central
#      classification (ui.phone_folders / ui.external_apps) actually knows
#   T2 THE VANISH TEST — a package no class names still reaches a card on the
#      page, by the same precedence rule InboxClasses.classIdOf implements
#   T3 no package is claimed by two classes (a double-claimed notification is
#      drawn twice and counted twice)
#   T4 every card label exists in BOTH values/ and values-es/
#   T5 every panel's `class` is declared, and exactly one class is the catch-all
#   T6 no class names a package the central classification has never heard of
#   T7 the classification is DATA — no package roster in the Kotlin that reads it
#
# No ripgrep. Several testers here were written while rg was absent from the
# runner and were passing on its absence; this one uses grep and python3, both
# of which are in build.json::tests.shell.requires.
set -uo pipefail
APP="$(cd "$(dirname "$0")/.." && pwd)"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  ok: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }

BUILD_JSON="$APP/build.json"
CLASSES_KT="$APP/app/src/main/java/com/diegonmarcos/superapp/apps/InboxClasses.kt"
EN="$APP/app/src/main/res/values/strings.xml"
ES="$APP/app/src/main/res/values-es/strings.xml"

for f in "$BUILD_JSON" "$CLASSES_KT" "$EN" "$ES"; do
    # Checked up front and fatally. A tester that greps a path which does not
    # exist does not fail — it answers from the file's absence, which for a
    # "nothing bad is present" assertion is a false PASS.
    [ -f "$f" ] || { echo "  FAIL: missing $f"; exit 1; }
done

echo "== T1/T2/T3/T5/T6: the classification data =="
python3 - "$BUILD_JSON" <<'PY'
import io, json, sys

b = json.load(io.open(sys.argv[1], encoding="utf-8"))
ui = b["ui"]
classes = ui.get("inbox_classes") or []
folders = ui.get("phone_folders") or []
external = ui.get("external_apps") or []

fails = []
def check(cond, msg):
    if not cond:
        fails.append(msg)

# ── the packages the CENTRAL classification knows ────────────────────
# A class may only name apps this file has already heard of, and a
# folders-backed class inherits whatever that folder claims. Both sides are
# read from ui.*, never restated here, or this tester would be a fourth copy
# of the roster and would go stale with the others.
folder_packages = {}
for f in folders:
    pkgs = set()
    for kw in (f.get("match_keywords") or []):
        if kw.lower().startswith("pkg:"):
            pkgs.add(kw[4:].lower())
    folder_packages[f.get("id", "")] = pkgs

# Our own constellation apps name their folder instead of appearing in a
# keyword list — PhoneFolders.constellationKeywordsByFolder folds them in at
# load time, so the tester has to fold them in too or a folder whose only
# members are ours would read as empty.
external_packages = set()
for a in external:
    fid = a.get("folder", "")
    for key in ("package", "hub_package", "alt_package", "install_package"):
        p = a.get(key)
        if isinstance(p, str) and p.strip():
            external_packages.add(p.lower())
            if fid in folder_packages:
                folder_packages[fid].add(p.lower())
    for p in (a.get("forks") or {}).values():
        if isinstance(p, str) and p.strip():
            external_packages.add(p.lower())
            if fid in folder_packages:
                folder_packages[fid].add(p.lower())

known = set(external_packages)
for pkgs in folder_packages.values():
    known |= pkgs

check(bool(classes), "ui.inbox_classes is empty or absent")

# ── T5: exactly one catch-all ────────────────────────────────────────
catch = [c for c in classes if c.get("catch_all")]
check(len(catch) == 1,
      "expected exactly ONE class with catch_all: true, found %d (%s)"
      % (len(catch), ", ".join(c.get("id", "?") for c in catch)))

# ── T1: every class resolves to at least one real app ────────────────
# The catch-all is exempt BY CONSTRUCTION: it declares no members precisely
# because it is defined as everything the others do not claim. Requiring it to
# name an app would be requiring the one class that must not have a roster to
# have one.
for c in classes:
    cid = c.get("id", "?")
    if c.get("catch_all"):
        continue
    members = set(p.lower() for p in (c.get("packages") or []))
    for fid in (c.get("folders") or []):
        check(fid in folder_packages,
              "class '%s' names folder '%s', which ui.phone_folders does not declare" % (cid, fid))
        members |= folder_packages.get(fid, set())
    resolvable = members & known
    # A class whose material is the ntfy channel stream is allowed to name no
    # phone package at all — its apps are topics, and ui.ntfy is where those
    # are declared. It still has to name SOMETHING.
    if c.get("source") == "c3ntfy":
        check(bool(resolvable) or bool(ui.get("ntfy")),
              "class '%s' declares source c3ntfy but neither names a resolvable "
              "package nor has a ui.ntfy catalog to draw channels from" % cid)
    else:
        check(bool(resolvable),
              "class '%s' resolves to NO app the central classification knows "
              "(declared packages: %s; folders: %s)"
              % (cid, sorted(members) or "none", c.get("folders") or "none"))

# ── T6: no class invents a package ───────────────────────────────────
for c in classes:
    for p in (c.get("packages") or []):
        check(p.lower() in known,
              "class '%s' names package '%s', which no ui.phone_folders keyword and no "
              "ui.external_apps entry declares — this list would be describing an app the "
              "central classification has never heard of" % (c.get("id", "?"), p))

# ── T3: no package in two classes ────────────────────────────────────
# Both axes, because a folder-inherited member collides just as badly as a
# declared one. Explicit `packages` beat `folders` in InboxClasses.classIdOf,
# so a package named by one class and inherited by another is NOT a conflict —
# it is the documented override, and flagging it would make the override
# unusable.
declared = {}
for c in classes:
    for p in (c.get("packages") or []):
        declared.setdefault(p.lower(), []).append(c.get("id", "?"))
for p, owners in sorted(declared.items()):
    check(len(owners) == 1,
          "package '%s' is claimed by %d classes (%s) — it would be drawn and counted "
          "on both cards" % (p, len(owners), ", ".join(owners)))

inherited = {}
for c in classes:
    for fid in (c.get("folders") or []):
        for p in folder_packages.get(fid, set()):
            if p in declared:
                continue          # the documented explicit-beats-folder override
            inherited.setdefault(p, []).append(c.get("id", "?"))
for p, owners in sorted(inherited.items()):
    check(len(owners) == 1,
          "package '%s' is inherited by %d classes through their folders (%s)"
          % (p, len(owners), ", ".join(owners)))

# ── T2: THE VANISH TEST ──────────────────────────────────────────────
# A package nothing has ever classified, run through the SAME precedence
# InboxClasses.classIdOf implements: exact package, then folder, then catch-all.
# It must land on a class that a stack_msgs panel actually draws — landing in a
# class no card renders is the same disappearance with an extra step.
UNCLASSIFIED = "com.example.a.messenger.nobody.has.classified"

def class_of(pkg):
    for c in classes:
        if pkg in set(p.lower() for p in (c.get("packages") or [])):
            return c.get("id", "")
    for c in classes:
        for fid in (c.get("folders") or []):
            if pkg in folder_packages.get(fid, set()):
                return c.get("id", "")
    for c in classes:
        if c.get("catch_all"):
            return c.get("id", "")
    return ""

check(UNCLASSIFIED not in known,
      "the vanish test's sample package is somehow declared — pick another")
landed = class_of(UNCLASSIFIED)
check(landed != "",
      "an app that NO class claims resolves to no class at all: its notifications "
      "would silently stop appearing on this page")

# Which panels actually render, and which classes they name.
panels = []
for sec in ui["sections"]:
    if isinstance(sec, dict) and isinstance(sec.get("stack_msgs"), list):
        panels = [p for p in sec["stack_msgs"] if isinstance(p, dict)]
        break
check(bool(panels), "no section declares a stack_msgs — the page has no cards")

drawn = [p.get("class", "") for p in panels if p.get("kind") == "class_inbox"]
check(landed in drawn,
      "an unclassified app lands in class '%s', which NO card on this page draws "
      "(cards draw: %s) — the notification is lost with an extra step"
      % (landed, ", ".join(drawn) or "none"))

# ── T5 continued: every panel names a declared class ─────────────────
ids = set(c.get("id", "") for c in classes)
for p in panels:
    if p.get("kind") != "class_inbox":
        continue
    cid = p.get("class", "")
    check(cid in ids,
          "panel '%s' names class '%s', which ui.inbox_classes does not declare — "
          "the card would render a permanent 'unavailable'" % (p.get("title", "?"), cid))
    check(bool(p.get("title_res")),
          "panel '%s' declares no title_res, so its name cannot be translated"
          % p.get("title", "?"))

# The four the owner asked for, by name. Not a shape check: the owner named
# these four, and a rename that quietly dropped one would otherwise pass every
# structural assertion above.
for want in ("mail", "chat", "messenger", "rss"):
    check(want in drawn, "the page no longer draws a '%s' card" % want)

for m in fails:
    print("  FAIL: " + m)
print("  ok: %d class(es) checked, %d card(s) on the page" % (len(classes), len(panels)))
sys.exit(1 if fails else 0)
PY
if [ $? -eq 0 ]; then ok "classification data is coherent"; else bad "classification data"; fi

echo "== T4: every card label exists in BOTH locales =="
# Read the resource names out of build.json rather than listing them here: a
# fifth card added tomorrow is covered without editing this tester, and a
# tester that carries its own copy of the list can only ever check the cards
# that existed when it was written.
RES_NAMES="$(python3 - "$BUILD_JSON" <<'PY'
import io, json, sys
b = json.load(io.open(sys.argv[1], encoding="utf-8"))
out = []
for sec in b["ui"]["sections"]:
    if isinstance(sec, dict) and isinstance(sec.get("stack_msgs"), list):
        for p in sec["stack_msgs"]:
            if isinstance(p, dict) and p.get("title_res"):
                out.append(p["title_res"])
for c in (b["ui"].get("inbox_classes") or []):
    if c.get("note_res"):
        out.append(c["note_res"])
print("\n".join(out))
PY
)"
if [ -z "$RES_NAMES" ]; then
    bad "no title_res declared by any card — nothing to check for translation"
else
    MISSING=0
    for name in $RES_NAMES; do
        # -F and the full attribute text, so `inbox_class_mail` cannot be
        # matched by a hypothetical `inbox_class_mailbox`.
        for loc in "$EN" "$ES"; do
            if ! grep -qF "<string name=\"$name\">" "$loc"; then
                bad "string '$name' is missing from $(basename "$(dirname "$loc")")/strings.xml"
                MISSING=$((MISSING+1))
            fi
        done
    done
    [ "$MISSING" -eq 0 ] && ok "every card label and note exists in values/ and values-es/"
fi

echo "== T7: the classification is DATA, not a when(packageName) =="
# The rule this repository is built on, asserted where it would be broken: the
# object that decides a package's class must not carry a roster of packages.
# A literal package id in InboxClasses.kt is the shape that would put the
# owner's phone behind a release to reclassify one app.
#
# Comments are stripped first — the KDoc necessarily NAMES com.Slack while
# explaining why the list is not here, and a tester that could not tell prose
# from code would fail on the explanation.
CODE="$(mktemp)"; trap 'rm -f "$CODE"' EXIT
python3 - "$CLASSES_KT" "$CODE" <<'PY'
import io, re, sys
s = io.open(sys.argv[1], encoding="utf-8").read()
s = re.sub(r"/\*.*?\*/", "", s, flags=re.S)
s = re.sub(r"^[ \t]*//.*$", "", s, flags=re.M)
s = re.sub(r"[ \t]//.*$", "", s, flags=re.M)
io.open(sys.argv[2], "w", encoding="utf-8").write(s)
PY
# A quoted dotted identifier with at least two dots is a package id; the string
# literals this file legitimately carries ("id", "packages", "c3ntfy", …) have
# none. Kept deliberately narrow — a broad "any string" rule would fire on
# every JSON key and would have to be suppressed, which is how a guard becomes
# decoration.
if grep -nE '"[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*){2,}"' "$CODE"; then
    bad "InboxClasses.kt contains a package id in code — the membership belongs in build.json::ui.inbox_classes"
else
    ok "InboxClasses.kt names no package: the roster is entirely in build.json"
fi
if grep -qF "UI_INBOX_CLASSES_B64" "$CODE"; then
    ok "InboxClasses.kt reads the roster from BuildConfig"
else
    bad "InboxClasses.kt does not read UI_INBOX_CLASSES_B64 — where does its data come from?"
fi
# The gradle side of the same contract. Without this the BuildConfig field does
# not exist, the runCatching swallows it, and every card silently renders empty
# — which is exactly the vanish this tester exists to prevent, arriving by the
# build instead of by the data.
if grep -qF "UI_INBOX_CLASSES_B64" "$APP/app/build.gradle"; then
    ok "app/build.gradle bakes ui.inbox_classes into BuildConfig"
else
    bad "app/build.gradle never bakes UI_INBOX_CLASSES_B64 — the roster would never reach the app"
fi

echo
echo "── test-inbox-classes: $PASS passed, $FAIL failed ──"
[ "$FAIL" -eq 0 ]
