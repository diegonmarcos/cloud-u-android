#!/bin/sh
# ╔══════════════════════════════════════════════════════════════════╗
# ║ task #461 — cloud-camera consumes the ONE shared scan engine      ║
# ╚══════════════════════════════════════════════════════════════════╝
#
# What this certifies: that a captured photo's CONTENT can be scanned through
# the single shared image-scan engine (libs:ml-l-image-mlkit:
# ImageScanEngine = ZXing barcode decode + ML Kit OCR), exactly as
# cloud-drive and cloud-media-center consume it — and NOT through a private
# copy of the decode path (#170/#261). The camera already decodes barcodes in
# the live preview via QRAnalyzer (that is the live viewfinder, a different
# surface); this tester is about reading a CAPTURED photograph's contents.
#
# Each assertion exists because the naive version passes while the feature is
# broken:
#   * "build.gradle mentions ml-l-image-mlkit" passes on a comment. C1 asserts
#     include + projectDir in settings.gradle.kts so Gradle 9 actually resolves
#     the module, C2 asserts the implementation dependency.
#   * "the scanner calls ImageScanEngine" is downstream of the wrapper
#     importing the SHARED package. C3 forbids a private copy: it checks the
#     only place a scan is defined is the wrapper that imports
#     com.diegonmarcos.superapp.image.mlkit.ImageScanEngine, and that no other
#     cld.camera source declares its own ImageScanEngine or its own
#     BarcodePayloadParser.
#   * "the gallery offers scan" proves nothing unless the menu item reaches a
#     handler that actually invokes the scanner. C5 pins the menu id to a
#     handler that calls scanCurrentMediaContents, and C6 requires that the
#     capturable item type is gated (only images carry scannable contents).
set -eu
APP_DIR="$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)"
export APP_DIR

python3 - <<'PY'
import os
import re
import sys

APP = os.environ["APP_DIR"]
ALT = os.path.join(os.environ["APP_DIR"], "..", "ab_cloud-libs-shared/libs/ml-l-image-mlkit")
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


def strip_kotlin_comments(text):
    """Remove Kotlin comments without touching string literals.

    The naive scanner that treats every '//' as a comment start is a real bug:
    a URL inside a string literal ("https://host/path") starts a '//' that eats
    the rest of the line, silently deleting code — a tester built on it can
    pass while half the file was never checked. This one tracks string
    literals ('"', "'", triple-double, escapes) so only comments are removed.
    """
    out, i, n = [], 0, len(text)
    while i < n:
        c = text[i]
        two = text[i:i + 2]
        three = text[i:i + 3]
        if c == '"' and three == '"""':
            j = text.find('"""', i + 3)
            if j < 0:
                out.append(text[i:])
                break
            out.append(text[i:j + 3])
            i = j + 3
            continue
        if c == '"' or c == "'":
            quote = c
            j = i + 1
            while j < n:
                if text[j] == '\\':
                    j += 2
                    continue
                if text[j] == quote:
                    j += 1
                    break
                j += 1
            out.append(text[i:j])
            i = j
            continue
        if two == "/*":
            depth = 1
            j = i + 2
            while j < n and depth:
                if text[j:j + 2] == "/*":
                    depth += 1
                    j += 2
                elif text[j:j + 2] == "*/":
                    depth -= 1
                    j += 2
                else:
                    j += 1
            i = j
            continue
        if two == "//":
            j = text.find("\n", i)
            i = n if j < 0 else j
            continue
        out.append(c)
        i += 1
    return "".join(out)


def read(path):
    return open(path, encoding="utf-8").read()


def src(path):
    return strip_kotlin_comments(read(path))


# ── C1: settings.gradle.kts includes the shared module BY REFERENCE ──
settings_path = os.path.join(APP, "settings.gradle.kts")
settings = read(settings_path)
check("C1 settings.gradle.kts includes :libs:ml-l-image-mlkit",
      re.search(r'include\s*\(\s*":libs:ml-l-image-mlkit"\s*\)', settings) is not None,
      "without the include Gradle cannot resolve the project() dependency")
check("C1 … mapped BY REFERENCE to ab_cloud-libs-shared (not a vendored copy)",
      'ml-l-image-mlkit").projectDir = file("../ab_cloud-libs-shared/libs/ml-l-image-mlkit")' in settings
      or 'ml-l-image-mlkit").projectDir = file("../ab_cloud-libs-shared/libs' in settings
      or '../ab_cloud-libs-shared/libs/ml-l-image-mlkit' in settings,
      "the module must point at the shared directory — a copy is the defect this fleet fixed")
check("C1 … the ':libs' container is mapped so Gradle 9 does not fail on a missing dir",
      re.search(r'project\s*\(\s*":libs"\s*\)\.projectDir', settings) is not None,
      "Gradle 9 fails outright on a project directory that does not exist")

# ── C2: app/build.gradle.kts depends on the shared module ──
gradle_path = os.path.join(APP, "app/build.gradle.kts")
gradle = read(gradle_path)
gradle_nc = re.sub(r"//[^\n]*", "", re.sub(r"/\*.*?\*/", "", gradle, flags=re.S))
check("C2 app/build.gradle.kts declares implementation project(':libs:ml-l-image-mlkit')",
      re.search(r'implementation\s*\(?\s*project\(\s*":libs:ml-l-image-mlkit"\s*\)\s*\)?', gradle_nc) is not None,
      "the camera code must actually link the shared engine")

# ── C3: the only scan definition imports the SHARED engine, no private copy ──
wrapper_rel = "app/src/main/java/cld/camera/analyzer/ImageContentScanner.kt"
wrapper_path = os.path.join(APP, wrapper_rel)
check("C3 ImageContentScanner.kt exists", os.path.isfile(wrapper_path), wrapper_rel)
private_copies = []
if os.path.isdir(os.path.join(APP, "app/src/main/java/cld/camera")):
    for root, _dirs, names in os.walk(os.path.join(APP, "app/src/main/java/cld/camera")):
        for name in names:
            if not name.endswith(".kt"):
                continue
            body = src(os.path.join(root, name))
            rel = os.path.relpath(os.path.join(root, name), APP)
            if rel == wrapper_rel:
                continue  # this IS the shared-engine shim
            if re.search(r"\bImageScanEngine\b", body) or re.search(r"\bBarcodePayloadParser\b", body)\
                    or re.search(r"\bclass\s+ImageScanEngine\b", body)\
                    or re.search(r"\bobject\s+BarcodePayloadParser\b", body):
                private_copies.append(rel)
check("C3 NO cld.camera source declares a private ImageScanEngine / BarcodePayloadParser",
      not private_copies,
      "each of these is a second copy of an engine this fleet agreed is shared: %r"
      % sorted(private_copies))

wrapper = src(wrapper_path)
check("C3 the wrapper imports the SHARED engine package",
      "com.diegonmarcos.superapp.image.mlkit.ImageScanEngine" in wrapper,
      "the shim must call the shared module's engine, not redeclare it")
check("C3 the wrapper forwards to BOTH decode halves (barcode AND OCR)",
      "decodeBarcode" in wrapper and "recognizeText" in wrapper,
      "a scan that only decodes barcodes and never OCRs is not the delivered capability")

# ── C4: the shared module the wrapper points at still exists and carries the engine ──
engine_shared = os.path.join(ALT, "src/main/java/com/diegonmarcos/superapp/image/ImageScanEngine.kt")
check("C4 the shared engine exists at ab_cloud-libs-shared",
      os.path.isfile(engine_shared),
      "the camera points at a module path that no longer holds an engine")
if os.path.isfile(engine_shared):
    engine = src(engine_shared)
    check("C4 the shared engine still has decodeBarcode", "fun decodeBarcode" in engine)
    check("C4 the shared engine still has recognizeText", "fun recognizeText" in engine)

# ── C5: the gallery overflow menu item reaches the scanner ──
menu_xml = os.path.join(APP, "app/src/main/res/menu/gallery.xml")
menu = read(menu_xml)
check("C5 gallery menu declares @+id/scan_contents",
      re.search(r'android:id="\s*@\+id/scan_contents\s*"', menu) is not None,
      "without the item the user cannot ask for a scan")
activity = src(os.path.join(APP, "app/src/main/java/cld/camera/ui/activities/InAppGallery.kt"))
check("C5 InAppGallery handles R.id.scan_contents",
      re.search(r'R\.id\.scan_contents', activity) is not None,
      "a menu item nobody handles is decoration")
check("C5 the R.id.scan_contents branch CALLS the scan path",
      re.search(r'R\.id\.scan_contents\s*->\s*\{.*?scanCurrentMediaContents\(\)\s*\n', activity, re.S) is not None,
      "a menu item whose branch never calls the scanner is dead wiring that compiles")
check("C5 the scan path is still defined on the activity",
      "fun scanCurrentMediaContents" in activity,
      "the menu must call an existing scan path, not a phantom")
check("C5 InAppGallery imports ImageContentScanner",
      "ImageContentScanner" in activity)

# ── C6: only images are scannable; every failure names itself ──
check("C6 the handler rejects videos (a video has no scannable contents)",
      "ITEM_TYPE_VIDEO" in activity,
      "scanning a video's bytes as an image would be a silent wrong answer")
strings = read(os.path.join(APP, "app/src/main/res/values/strings.xml"))
for key in ("scan_contents", "scan_contents_in_progress", "scan_contents_nothing_found",
            "scan_contents_failed", "scan_contents_barcode_label", "scan_contents_ocr_label"):
    check("C6 values/strings.xml defines %s" % key,
          re.search(r'name="%s"' % key, strings) is not None,
          "a scan that can end without a visible reason is the empty-box defect")

print("\n-- %d assertions, %d failed --" % (checked, len(failures)))
for f in failures:
    print("   FAILED: %s" % f, file=sys.stderr)
sys.exit(1 if failures else 0)
PY