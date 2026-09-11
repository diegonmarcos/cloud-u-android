#!/bin/sh
# ╔══════════════════════════════════════════════════════════════════╗
# ║ the "open capture folder in Media Center" button                 ║
# ╚══════════════════════════════════════════════════════════════════╝
#
# WHAT THIS REFUSES TO CERTIFY. Each assertion below exists because the
# obvious version of it passes while the feature is broken:
#
#   * "the button id is in the layout" proves XML parsed, not that the button
#     is BELOW the gallery thumbnail. Two views can both exist with one
#     entirely on top of the other. A2 asserts document position inside the
#     column instead.
#   * "the capture button is still in the layout" proves nothing about where
#     it is. A3 computes its centre from its own height and margin and
#     asserts the number, so a changed margin fails.
#   * "DCIM/Camera appears in the source" proves a string is present, not that
#     it is the folder the camera writes into. A4 asserts the button reads the
#     SAME symbol ImageSaver writes with, and forbids the literal.
#   * "an Intent is constructed" proves nothing resolves. A5 asserts the
#     <queries> entry without which resolveActivity() returns null on
#     targetSdk 30+ even when Media Center is installed.
#
# The XML assertions parse the document. They do not grep it — a grep for an
# attribute matches that attribute inside a comment, and this repository has
# already shipped a guard that was satisfied by its own prose.
#
# Runtime behaviour (the intent actually resolving on the phone, the album
# actually opening) is NOT provable here and this tester does not pretend to:
# see the report for what was left unverified.
set -eu

APP_DIR="$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)"
export APP_DIR

python3 - <<'PY'
import os
import re
import sys
import xml.etree.ElementTree as ET

APP = os.environ["APP_DIR"]
ANDROID = "{http://schemas.android.com/apk/res/android}"

failures = []
checked = 0


def check(label, condition, detail=""):
    global checked
    checked += 1
    if condition:
        print("  ok    %s" % label)
    else:
        print("  FAIL  %s%s" % (label, (" — " + detail) if detail else ""))
        failures.append(label)


def attr(el, name):
    return el.get(ANDROID + name)


def ident(el):
    """The bare id of a view, e.g. '@+id/third_option' -> 'third_option'."""
    raw = attr(el, "id")
    return raw.split("/")[-1] if raw else None


def dp(value):
    """'16dp' -> 16.0; anything that is not a dp literal -> None."""
    if not value:
        return None
    m = re.fullmatch(r"(-?[0-9]+(?:\.[0-9]+)?)dp", value.strip())
    return float(m.group(1)) if m else None


def strip_kotlin_comments(text):
    """Remove // and /* */ so an assertion cannot be satisfied by prose.

    Kotlin block comments nest, so this counts depth rather than matching the
    first close — a naive regex stops at the wrong `*/` and leaves code hidden
    inside what it thinks is a comment.
    """
    out, i, n, depth = [], 0, len(text), 0
    while i < n:
        two = text[i:i + 2]
        if depth:
            if two == "/*":
                depth += 1; i += 2
            elif two == "*/":
                depth -= 1; i += 2
            else:
                i += 1
            continue
        if two == "/*":
            depth += 1; i += 2
        elif two == "//":
            j = text.find("\n", i)
            i = n if j < 0 else j
        else:
            out.append(text[i]); i += 1
    return "".join(out)


layout_path = os.path.join(APP, "app/src/main/res/layout/activity_main.xml")
layout = ET.parse(layout_path)

# Parent map: ElementTree gives no upward links.
parent = {c: p for p in layout.iter() for c in p}

by_id = {}
for el in layout.iter():
    name = ident(el)
    if name:
        by_id[name] = el

# ── A1: the button exists at all (necessary, nowhere near sufficient) ──
button = by_id.get("open_media_center")
check("A1 layout declares @+id/open_media_center", button is not None)

thumb = by_id.get("third_option")
check("A1 layout still declares @+id/third_option", thumb is not None)

check("A1 layout still declares @+id/third_circle (the gallery icon button)",
      by_id.get("third_circle") is not None)

# ── A2: BELOW the thumbnail — document order inside a vertical column ──
if button is not None and thumb is not None:
    col_b = parent.get(button)
    col_t = parent.get(thumb)
    same = col_b is not None and col_b is col_t
    check("A2 button and thumbnail share one parent column", same,
          "button parent is not the thumbnail's parent")

    if same:
        check("A2 that column stacks vertically",
              attr(col_b, "orientation") == "vertical",
              "parent orientation is %r — a horizontal parent puts the button "
              "BESIDE the thumbnail, not below it" % attr(col_b, "orientation"))

        kids = list(col_b)
        check("A2 button comes after the thumbnail in that column",
              kids.index(button) > kids.index(thumb),
              "index %d vs thumbnail %d — earlier means ABOVE"
              % (kids.index(button), kids.index(thumb)))

    # A tap target smaller than 48dp is a miss on a crowded viewfinder.
    h, w = dp(attr(button, "layout_height")), dp(attr(button, "layout_width"))
    check("A2 button is at least a 48dp tap target",
          h is not None and w is not None and h >= 48 and w >= 48,
          "height=%r width=%r" % (attr(button, "layout_height"),
                                  attr(button, "layout_width")))

# ── A3: the capture ring and flip button did not move ──
#
# The row's BOTTOM edge is pinned (layout_above the mode tabs), so a child's
# distance from that edge is what fixes it on screen. Both buttons were
# centred in a 96dp row, i.e. their centres sat 48dp above the row's bottom.
# The third column is taller than 96dp now, so `center` would drift upward by
# half the growth; bottom-anchoring reproduces the old position exactly and
# stays correct however tall the column becomes.
EXPECTED_CENTRE_ABOVE_ROW_BOTTOM = 48.0

row = by_id.get("three_buttons")
check("A3 layout still declares @+id/three_buttons", row is not None)

if row is not None:
    capture = by_id.get("capture_button")
    frames = {
        "flip button": by_id.get("flip_camera_circle"),
        "capture ring": parent.get(capture) if capture is not None else None,
    }
    for label, frame in frames.items():
        if frame is None:
            check("A3 %s frame found" % label, False)
            continue

        gravity = attr(frame, "layout_gravity") or ""
        check("A3 %s is anchored to the row's bottom edge" % label,
              "bottom" in gravity,
              "layout_gravity=%r — `center` drifts upward as the row grows"
              % gravity)

        height = dp(attr(frame, "layout_height"))
        margin = dp(attr(frame, "layout_marginBottom")) or 0.0
        centre = (height / 2 + margin) if height is not None else None
        check("A3 %s centre is %.0fdp above the row bottom, as before"
              % (label, EXPECTED_CENTRE_ABOVE_ROW_BOTTOM),
              centre == EXPECTED_CENTRE_ABOVE_ROW_BOTTOM,
              "height=%r marginBottom=%r gives %r"
              % (attr(frame, "layout_height"),
                 attr(frame, "layout_marginBottom"), centre))

# ── A4: the folder is the one the camera actually writes to ──
media_center = os.path.join(APP, "app/src/main/java/cld/camera/util/MediaCenter.kt")
mc_src = strip_kotlin_comments(open(media_center, encoding="utf-8").read())

check("A4 resolver reads the capture path symbol, not a copy of its value",
      "DEFAULT_MEDIA_STORE_CAPTURE_PATH" in mc_src,
      "MediaCenter.kt must use the same constant ImageSaver writes as "
      "RELATIVE_PATH, so the two cannot drift apart")

check("A4 resolver hardcodes no folder literal",
      "DCIM/Camera" not in mc_src,
      "a literal here is a second source of truth that survives the setting "
      "being changed")

check("A4 resolver branches on the configured storage location",
      "storageLocation" in mc_src or "isEmpty()" in mc_src,
      "the save location is a user setting; the button must follow it")

# ── A5: it resolves, and it never dead-ends ──
main_activity = os.path.join(
    APP, "app/src/main/java/cld/camera/ui/activities/MainActivity.kt")
ma_src = strip_kotlin_comments(open(main_activity, encoding="utf-8").read())


def function_body(src, signature):
    """The braced body of one function, by brace depth.

    Scoped deliberately. Searching the whole file for "resolveActivity" passes
    on this file no matter what the button does: MainActivity already imports a
    resolveActivity helper and already calls it, 700 lines away, for opening a
    scanned QR link. A whole-file search was this tester's own false green,
    caught by mutating the guard away and watching it stay green.
    """
    start = src.find(signature)
    if start < 0:
        return None
    i = src.find("{", start)
    if i < 0:
        return None
    depth = 0
    for j in range(i, len(src)):
        if src[j] == "{":
            depth += 1
        elif src[j] == "}":
            depth -= 1
            if depth == 0:
                return src[i:j + 1]
    return None


body = function_body(ma_src, "private fun openCaptureFolderInMediaCenter()")
check("A5 openCaptureFolderInMediaCenter() exists", body is not None)

if body is not None:
    check("A5 that function checks the intent resolves before starting it",
          "resolveActivity" in body,
          "an explicit intent to a missing app throws "
          "ActivityNotFoundException; the button must not dead-end")

    check("A5 that function does start the activity",
          "startActivity" in body)

    for key in ("media_center_not_installed", "media_center_folder_unavailable"):
        check("A5 that function has a message for R.string.%s" % key,
              key in body,
              "every failure branch must say something; a silent no-op is "
              "indistinguishable from success")

manifest_path = os.path.join(APP, "app/src/main/AndroidManifest.xml")
manifest = ET.parse(manifest_path)

queried = {
    attr(p, "name")
    for q in manifest.iter("queries")
    for p in q.findall("package")
}
check("A5 manifest declares <queries> visibility of Media Center",
      "com.diegonmarcos.mediacenter" in queried,
      "without it resolveActivity() returns null on targetSdk 30+ even when "
      "Media Center IS installed, and the button reports 'not installed' "
      "forever; found %r" % sorted(queried))

# ── A6: the owner's phone is in Spanish ──
NEW_KEYS = (
    "open_media_center",
    "media_center_not_installed",
    "media_center_folder_unavailable",
)
for values_dir in ("values", "values-es"):
    path = os.path.join(APP, "app/src/main/res", values_dir, "strings.xml")
    if not os.path.isfile(path):
        check("A6 %s/strings.xml exists" % values_dir, False, path)
        continue
    names = {s.get("name") for s in ET.parse(path).getroot().findall("string")}
    for key in NEW_KEYS:
        check("A6 %s/strings.xml defines %s" % (values_dir, key),
              key in names,
              "an English label on a Spanish phone is a regression on arrival")

print("\n-- %d assertions, %d failed --" % (checked, len(failures)))
for f in failures:
    print("   FAILED: %s" % f, file=sys.stderr)
sys.exit(1 if failures else 0)
PY
