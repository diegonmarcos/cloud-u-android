#!/usr/bin/env bash
# Tester: the INDEX of Configs > Launcher > One-Hand (a sticky row of icon anchors that jump to the page's sections).
#
#   I1  build.json::onehand_page.sections are, in order, Stars | Edge menu | Floating menu | Home Gestures | Status Bar
#   I2  every anchor icon is a name of the libs:ui-icons catalog (the only icons a declaration may name)
#   I3  the page draws its sections FROM that list (one loop over the declaration, one branch per id, no heading literal),
#       so the anchors and the sections are the same list and cannot drift
#   I4  the index is STICKY: it sits outside the scrolling column, above it, and a tap scrolls to the section's recorded top
#   I5  the declaration reaches the app: app/build.gradle bakes ONEHAND_PAGE_B64 and fails the build without the block
#
# Usage: ./test-onehand-index.sh   (static only, no network, no device)
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"
exec python3 - "$APP" <<'PY'
import json, re, sys
app = sys.argv[1]
fails = []
def ok(c, good, bad):
    print(("ok   " if c else "FAIL ") + (good if c else bad))
    if not c: fails.append(bad)
def read(p): return open(f"{app}/{p}", encoding="utf-8").read()
b = json.load(open(f"{app}/build.json", encoding="utf-8"))
secs = b.get("onehand_page", {}).get("sections", [])
labels = [s["label"] for s in secs]
ok(labels == ["Stars", "Edge menu", "Floating menu", "Home Gestures", "Status Bar"],
   f"I1: the anchors are {labels}", f"I1: the anchors are {labels}, not Stars | Edge menu | Floating menu | Home Gestures | Status Bar")
ok(len({s["id"] for s in secs}) == len(secs), "I1: section ids are unique", "I1: duplicate section id")
cat = set(re.findall(r'"([a-z_0-9]+)"\s+to\s+(?:Icons\.|assistantMark)', read("../ab_cloud-libs-shared/libs/ui-icons/src/main/kotlin/com/diegonmarcos/superapp/uiicons/UiIcons.kt")))
missing = [s["icon"] for s in secs if s["icon"] not in cat]
ok(not missing and len(cat) > 100, f"I2: all {len(secs)} icons are in the catalog ({len(cat)} names)", f"I2: icons not in the ui-icons catalog: {missing} (catalog read: {len(cat)})")
frag = read("app/src/main/java/com/diegonmarcos/superapp/configs/OneHandFragment.kt")
decl = read("app/src/main/java/com/diegonmarcos/superapp/configs/OneHandPageDecl.kt")
consts = dict(re.findall(r'const val ([A-Z_]+) = "([a-z_]+)"', decl))
ids = [s["id"] for s in secs]
ok(sorted(consts.values()) == sorted(ids), "I3: OneHandPageDecl's id constants are the declared ids", f"I3: constants {sorted(consts.values())} != declared {sorted(ids)}")
branches = re.findall(r'OneHandPageDecl\.([A-Z_]+) ->', frag)
ok(sorted(consts[c] for c in branches if c in consts) == sorted(ids), "I3: the page has one branch per declared section", f"I3: page branches {branches} do not cover {ids}")
ok("for (sec in OneHandPageDecl.sections)" in frag and "IndexRow(OneHandPageDecl.sections)" in frag,
   "I3: sections and index both loop over the declaration", "I3: sections or index do not loop over OneHandPageDecl.sections")
ok(not re.search(r'infoSection\("', frag), "I3: no section heading is a literal in the page", "I3: infoSection(\"literal\") is back; headings come from the declaration")
ok("sec.heading" in frag, "I3: the heading is the declared one", "I3: the heading is not read from the declaration")
i_index = frag.find("IndexRow(OneHandPageDecl.sections)"); i_scroll = frag.find("verticalScroll(scroll)")
ok(0 < i_index < i_scroll, "I4: the index row is drawn before, outside, the scrolling column", "I4: the index is not above the scrolling column (not sticky)")
ok("animateScrollTo(y)" in frag and "sectionTops[sec.id]" in frag, "I4: a tap scrolls to the recorded top of the section", "I4: no scroll-to-section wiring")
g = read("app/build.gradle")
ok("ONEHAND_PAGE_B64" in g and "declaredB64('onehand_page')" in g, "I5: the declaration is baked into the build", "I5: ONEHAND_PAGE_B64 is not baked from build.json::onehand_page")
sys.exit(1 if fails else 0)
PY
