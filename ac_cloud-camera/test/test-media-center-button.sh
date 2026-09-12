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
#   * "both buttons are 48dp" proves two numbers match, not that the pair looks
#     alike. A8 resolves each button's style and asserts the two effective
#     attribute sets are EQUAL apart from id, icon and description, so a
#     background or a padding added to one alone fails.
#   * "the not-installed string is still in the source" is what the previous
#     version of this file asserted, and it stayed green while that string was
#     the answer to three different questions. A9 asserts the string is
#     unreachable except behind an explicit installed check, and absent from
#     the catch block entirely.
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

# ── style resolution ───────────────────────────────────────────────
#
# The pair's appearance lives in a style, not in the layout, so reading
# layout attributes alone now answers None for every one of them — an
# assertion that "the height is 48dp" would silently stop testing anything.
themes_path = os.path.join(APP, "app/src/main/res/values/themes.xml")
styles = {}
for st in ET.parse(themes_path).getroot().findall("style"):
    styles[st.get("name")] = {
        it.get("name"): (it.text or "").strip() for it in st.findall("item")
    }


def effective(el):
    """A view's attributes with its style resolved underneath them.

    Inline attributes win over style items, which is what the inflater does.
    Keys are bare names: 'android:layout_width' and android:layout_width from
    the layout namespace both land on 'layout_width'.
    """
    out = {}
    style_ref = el.get("style")
    if style_ref:
        name = style_ref.split("/")[-1]
        seen = set()
        chain = []
        while name and name in styles and name not in seen:
            seen.add(name)
            chain.append(name)
            name = (styles.get(name) or {}).get("parent") or None
        for name in reversed(chain):
            for k, v in styles[name].items():
                if k == "parent":
                    continue
                out[k.split(":")[-1]] = v
    for k, v in el.attrib.items():
        if k.startswith(ANDROID):
            out[k[len(ANDROID):]] = v
    return out


# ── A1: BOTH buttons exist — necessary, nowhere near sufficient ────────────
video = by_id.get("open_media_center_video")
photo = by_id.get("open_media_center_photo")
check("A1 layout declares @+id/open_media_center_video", video is not None)
check("A1 layout declares @+id/open_media_center_photo", photo is not None)

thumb = by_id.get("third_option")
check("A1 layout still declares @+id/third_option", thumb is not None)

check("A1 layout still declares @+id/third_circle (the gallery icon button)",
      by_id.get("third_circle") is not None)

# ── A2: BELOW the thumbnail — document order inside a vertical column ──
for label, button in (("video", video), ("photo", photo)):
    if button is None or thumb is None:
        continue
    col_b = parent.get(button)
    col_t = parent.get(thumb)
    same = col_b is not None and col_b is col_t
    check("A2 %s button and thumbnail share one parent column" % label, same,
          "button parent is not the thumbnail's parent")

    if same:
        check("A2 that column stacks vertically",
              attr(col_b, "orientation") == "vertical",
              "parent orientation is %r — a horizontal parent puts the button "
              "BESIDE the thumbnail, not below it" % attr(col_b, "orientation"))

        kids = list(col_b)
        check("A2 %s button comes after the thumbnail in that column" % label,
              kids.index(button) > kids.index(thumb),
              "index %d vs thumbnail %d — earlier means ABOVE"
              % (kids.index(button), kids.index(thumb)))

    # A tap target smaller than 48dp is a miss on a crowded viewfinder.
    eff = effective(button)
    h, w = dp(eff.get("layout_height")), dp(eff.get("layout_width"))
    check("A2 %s button is at least a 48dp tap target" % label,
          h is not None and w is not None and h >= 48 and w >= 48,
          "height=%r width=%r" % (eff.get("layout_height"),
                                  eff.get("layout_width")))

# He asked for the video button ABOVE the photo one.
if video is not None and photo is not None and parent.get(video) is parent.get(photo):
    kids = list(parent[video])
    check("A2 the video button sits above the photo button",
          kids.index(video) < kids.index(photo),
          "video index %d, photo index %d"
          % (kids.index(video), kids.index(photo)))

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

    for key in ("media_center_not_installed", "media_center_outdated",
                "media_center_open_failed", "media_center_folder_unavailable"):
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
    "open_media_center_photo",
    "open_media_center_video",
    "media_center_not_installed",
    "media_center_outdated",
    "media_center_open_failed",
    "media_center_folder_unavailable",
    "install",
    "update",
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

# A7: shipping the translation is not the same as the phone receiving it.
#
# androidResources.localeFilters is a HARD FILTER applied at package time. With
# `listOf("en")` — which is what this app shipped until 2026-09-11 — values-es/
# is compiled and then stripped out of resources.arsc, so every assertion above
# passes, the build is green, the i18n guard is green, and the owner still reads
# English. Source-only checks structurally cannot see this; this one reads the
# build file that decides it.
gradle_path = os.path.join(APP, "app/build.gradle.kts")
gradle = open(gradle_path, encoding="utf-8").read()
gradle = re.sub(r"//[^\n]*", "", re.sub(r"/\*.*?\*/", "", gradle, flags=re.S))

m = re.search(r"localeFilters\s*\+?=\s*listOf\(([^)]*)\)", gradle)
if m is None:
    # No filter at all is fine — every locale ships.
    check("A7 no localeFilters, so every locale ships", True)
else:
    kept = set(re.findall(r'"([^"]+)"', m.group(1)))
    check("A7 localeFilters keeps 'es', so values-es survives packaging",
          "es" in kept,
          "localeFilters=%s strips values-es out of resources.arsc at package "
          "time; the label ships English no matter what values-es says"
          % sorted(kept))

# ── A8: the pair is indistinguishable except by icon ──────────────────
#
# "same size and style" is not two numbers agreeing. It is every visual
# attribute agreeing, and the only honest way to check that is to diff the two
# resolved attribute sets. A background, a padding or a tint added to one
# button alone fails here — which is exactly the drift that produced a 96dp
# thumbnail sitting above a 48dp button in the first place.
MAY_DIFFER = {"id", "src", "contentDescription", "tooltipText"}

if video is not None and photo is not None:
    ev, ep = effective(video), effective(photo)

    check("A8 both buttons carry the same style",
          video.get("style") is not None
          and video.get("style") == photo.get("style"),
          "video style=%r photo style=%r — two copies of the same attributes "
          "drift the first time one is edited alone"
          % (video.get("style"), photo.get("style")))

    style_name = (video.get("style") or "").split("/")[-1]
    check("A8 that style is defined in themes.xml",
          style_name in styles,
          "style %r not found; effective() then silently resolves nothing and "
          "every attribute below reads as absent" % style_name)

    differing = sorted(
        k for k in set(ev) | set(ep)
        if k not in MAY_DIFFER and ev.get(k) != ep.get(k)
    )
    check("A8 the two buttons differ in nothing but icon and description",
          not differing,
          "these attributes differ: %s" % ", ".join(
              "%s (%r vs %r)" % (k, ev.get(k), ep.get(k)) for k in differing))

    # ... and they DO differ by icon, or they are the same button twice.
    check("A8 the two buttons carry different icons",
          ev.get("src") is not None and ev.get("src") != ep.get("src"),
          "video src=%r photo src=%r" % (ev.get("src"), ep.get("src")))

    check("A8 the two buttons are announced differently",
          ev.get("contentDescription") is not None
          and ev.get("contentDescription") != ep.get("contentDescription"),
          "a screen reader would read the pair as one button said twice")

    for label, eff in (("video", ev), ("photo", ep)):
        check("A8 the %s icon exists as a drawable" % label,
              os.path.isfile(os.path.join(
                  APP, "app/src/main/res/drawable",
                  (eff.get("src") or "").split("/")[-1] + ".xml")),
              "src=%r" % eff.get("src"))

# ── A9: both buttons open the SAME folder, on purpose ────────────────
#
# The split into a video folder and a photo folder is a later change he asked
# NOT to have yet. Until then, a second code path is a second thing to get
# wrong, so assert there is exactly one.
setup = function_body(ma_src, "openMediaCenterVideo = binding.openMediaCenterVideo")
if setup is None:
    # Not inside its own function: fall back to the whole file, but only for
    # locating the two bindings, never for the behaviour below.
    setup = ma_src

for field in ("binding.openMediaCenterVideo", "binding.openMediaCenterPhoto"):
    check("A9 %s is bound" % field, field in ma_src,
          "an unbound lateinit throws on the first rotation")

check("A9 exactly one call site opens the folder",
      ma_src.count("openCaptureFolderInMediaCenter()") == 2,
      "expected the declaration plus one shared call; found %d occurrences "
      "— two call sites means two code paths to keep in step, and the folder "
      "breakdown he asked to defer has to change one target, not two"
      % ma_src.count("openCaptureFolderInMediaCenter()"))

# ── A10: "not installed" is never said unless the package IS absent ─────
#
# THIS is the bug the owner reported. resolveActivity() returning null was
# reported as "not installed", which is true only sometimes and was false for
# him: Media Center was on the phone, just older than the intent-filter. The
# previous version of this tester asserted the string existed and went green
# on exactly that code.
if body is not None:
    check("A10 the failure branch asks whether the package is installed",
          "isPackageInstalled" in body,
          "resolveActivity() answers 'nothing handles this intent'. It does "
          "not answer 'the app is absent'. Only getPackageInfo does.")

    def block_after(src, keyword):
        """The braced block introduced by `keyword`, by brace depth."""
        i = src.find(keyword)
        if i < 0:
            return None
        j = src.find("{", i)
        if j < 0:
            return None
        depth = 0
        for k in range(j, len(src)):
            if src[k] == "{":
                depth += 1
            elif src[k] == "}":
                depth -= 1
                if depth == 0:
                    return src[j:k + 1]
        return None

    catch = block_after(body, "catch (")
    check("A10 the function has a catch block", catch is not None)
    if catch is not None:
        check("A10 the catch block does not claim the app is missing",
              "media_center_not_installed" not in catch,
              "startActivity() failing AFTER the intent resolved is not "
              "absence; it was reported as 'not installed' by this route too")
        check("A10 the catch block says what actually failed",
              "media_center_open_failed" in catch)

    # "installed but too old" must be tied to the installed answer, not floating
    # loose as a third message somebody can reach in any state.
    idx_check = body.find("isPackageInstalled")
    idx_outdated = body.find("media_center_outdated")
    check("A10 the outdated message comes after the installed check",
          idx_check >= 0 and idx_outdated > idx_check,
          "isPackageInstalled at %d, media_center_outdated at %d"
          % (idx_check, idx_outdated))

# ── A11: the way out is offered, and only when it exists ─────────────
recovery = function_body(
    ma_src, "private fun showMediaCenterRecovery(")
check("A11 showMediaCenterRecovery() exists", recovery is not None)

if recovery is not None:
    check("A11 it offers the fleet installer",
          "getLaunchIntentForPackage" in recovery
          and "CONSTELLATION_PACKAGE" in recovery,
          "the artefact exists and is not on his phone; naming the problem "
          "without offering the fix leaves him to find Constellation himself")

    check("A11 it drops the action when the installer is absent",
          "== null" in recovery or "?:" in recovery,
          "a snackbar action that throws ActivityNotFoundException is one "
          "more thing lying about what is on the phone")

check("A11 manifest declares <queries> visibility of Constellation",
      "com.diegonmarcos.superapp" in queried,
      "without it getLaunchIntentForPackage() returns null on targetSdk 30+ "
      "even when Constellation IS installed, and the action never appears; "
      "found %r" % sorted(queried))

# ── A12: the old gallery circle is gone, and cannot creep back ───────
# Two Media Center buttons now open the folder the big circle used to open, so
# the circle is hidden. It is not deleted, because opening the gallery was only
# one of its three jobs: while a video records it is the take-a-still shutter,
# and the capture-intent activities use the same frame as the preview they hand
# back to the app that asked for a picture. That is why this is a visibility
# contract and not a removal, and why it needs guarding — half a dozen places
# mean "put the normal camera UI back" and any one of them hardcoding VISIBLE
# would put the circle back on the idle screen.
check("A12 the layout hides the circle outright",
      effective(thumb).get("visibility") == "gone",
      "gone, not invisible: invisible keeps its 96dp and would leave a blank "
      "gap above the Media Center buttons; found %r"
      % effective(thumb).get("visibility"))

check("A12 MainActivity owns one overridable idle visibility",
      re.search(r"open val thirdOptionIdleVisibility[^\n]*View\.GONE", ma_src)
      is not None,
      "every restore-the-normal-UI site reads this instead of hardcoding a "
      "value, which is the only reason the set of those sites can grow safely")

capture_src = strip_kotlin_comments(open(os.path.join(
    APP, "app/src/main/java/cld/camera/ui/activities/CaptureActivity.kt"),
    encoding="utf-8").read())
check("A12 CaptureActivity keeps the space reserved instead",
      re.search(r"override val thirdOptionIdleVisibility[^\n]*View\.INVISIBLE",
                capture_src) is not None,
      "on a capture-intent screen the frame is the preview and is about to "
      "appear; GONE there would shove the confirm row down when it does")

# The actual regression guard. Only two files may force the circle visible: the
# recorder, where it becomes the in-video shutter, and VideoCaptureActivity,
# which shows its own preview in it.
MAY_SHOW = {
    "app/src/main/java/cld/camera/capturer/VideoCapturer.kt",
    "app/src/main/java/cld/camera/ui/activities/VideoCaptureActivity.kt",
}
offenders = []
for root, _dirs, names in os.walk(os.path.join(APP, "app/src/main/java")):
    for name in names:
        if not name.endswith(".kt"):
            continue
        path = os.path.join(root, name)
        rel = os.path.relpath(path, APP)
        body = strip_kotlin_comments(open(path, encoding="utf-8").read())
        if re.search(r"thirdOption\.visibility\s*=\s*View\.VISIBLE", body) \
                and rel not in MAY_SHOW:
            offenders.append(rel)

check("A12 nothing else forces the circle visible",
      not offenders,
      "each of these puts the removed button back on the idle camera screen; "
      "they should assign thirdOptionIdleVisibility instead: %r"
      % sorted(offenders))

print("\n-- %d assertions, %d failed --" % (checked, len(failures)))
for f in failures:
    print("   FAILED: %s" % f, file=sys.stderr)
sys.exit(1 if failures else 0)
PY
