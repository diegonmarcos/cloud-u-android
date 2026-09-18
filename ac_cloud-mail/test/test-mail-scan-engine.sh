#!/bin/sh
# ╔══════════════════════════════════════════════════════════════════╗
# ║ task #461 — cloud-mail consumes the ONE shared scan engine        ║
# ╚════════════════════════════════════════════━━━━━━━━━━━━━━━━━━━━━━╝
#
# What this certifies: that an image attachment's CONTENT can be scanned
# through the single shared image-scan engine (libs:ml-l-image-mlkit:
# ImageScanEngine = ZXing barcode decode + ML Kit OCR), exactly as
# cloud-drive, cloud-media-center and cloud-camera consume it — and NOT
# through a private copy of the decode path (#170/#261).
#
# Each assertion exists because the naive version passes while the feature is
# broken:
#   * "build.json mentions ml-l-image-mlkit" passes on a comment. M1 asserts
#     the module is declared in build.json (which is what drives settings.gradle's
#     include loop AND the ship workflow's path triggers), M2 asserts the app
#     build script links it.
#   * "MessageViewModel calls the engine" is downstream of the engine import.
#     M4 forbids a private copy anywhere under the app, M5 pins the shared
#     module's File overloads the mail path uses.
#   * "the attachment row has a scan button" proves nothing unless the button
#     reaches a handler that invokes the scanner. M8 pins the onScan wiring
#     from the screen down to viewModel::scanAttachment, and requires the
#     action to be gated to image mime — a row that scans a PDF as pixels is a
#     silent wrong answer.
#   * "strings exist" is the non-silence guarantee: every scan outcome is said,
#     including "no barcode and no text" — the empty-box defect this fleet bans.
set -eu
APP_DIR="$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)"
export APP_DIR

python3 - <<'PY'
import json
import os
import re
import sys

APP = os.environ["APP_DIR"]
ALT = os.path.join(APP, "..", "ab_cloud-libs-shared/libs/ml-l-image-mlkit")
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

build_json = json.load(open(os.path.join(APP, "build.json"), encoding="utf-8"))

# ── M1: build.json declares the shared module BY REFERENCE ──
mod = (build_json.get("modules") or {}).get("libs:ml-l-image-mlkit")
check("M1 build.json::modules[libs:ml-l-image-mlkit] exists",
      isinstance(mod, dict),
      "settings.gradle's include loop and the ship workflow both read this map")
check("M1 … mapped BY REFERENCE to ab_cloud-libs-shared (not a vendored copy)",
      isinstance(mod, dict) and "../ab_cloud-libs-shared/libs/ml-l-image-mlkit" in str(mod.get("dir", "")),
      "the module must point at the shared directory — a copy is the defect this fleet fixed")
settings = read(os.path.join(APP, "settings.gradle"))
check("M1 settings.gradle drives includes from build.json::modules",
      re.search(r"buildJson\.modules\.each", settings) is not None,
      "a literal include list here would be a second copy of the module graph")

# ── M2: app/build.gradle.kts depends on the shared module ──
gradle_path = os.path.join(APP, "app/build.gradle.kts")
gradle = read(gradle_path)
gradle_nc = re.sub(r"//[^\n]*", "", re.sub(r"/\*.*?\*/", "", gradle, flags=re.S))
check("M2 app/build.gradle.kts declares implementation project(':libs:ml-l-image-mlkit')",
      re.search(r'implementation\s*\(\s*project\(\s*":libs:ml-l-image-mlkit"\s*\)\s*\)', gradle_nc) is not None,
      "the mail code must actually link the shared engine")

# ── M4: the only scan definition imports the SHARED engine, no private copy ──
private_copies = []
app_kotlin_root = os.path.join(APP, "app/src/main/kotlin")
if os.path.isdir(app_kotlin_root):
    for root, _dirs, names in os.walk(app_kotlin_root):
        for name in names:
            if not name.endswith(".kt"):
                continue
            body = src(os.path.join(root, name))
            if re.search(r"\bclass\s+ImageScanEngine\b", body) \
                    or re.search(r"\bobject\s+BarcodePayloadParser\b", body) \
                    or re.search(r"\sImageScanEngine\s*=\s*[\w.]+", body):
                private_copies.append(os.path.relpath(os.path.join(root, name), APP))
check("M4 NO mail source declares a private ImageScanEngine / BarcodePayloadParser",
      not private_copies,
      "each of these is a second copy of an engine this fleet agreed is shared: %r"
      % sorted(private_copies))

# ── M5: the shared module the app points at still exists and carries the File API ──
engine_shared = os.path.join(ALT, "src/main/java/com/diegonmarcos/superapp/image/ImageScanEngine.kt")
check("M5 the shared engine exists at ab_cloud-libs-shared",
      os.path.isfile(engine_shared),
      "mail points at a module path that no longer holds an engine")
if os.path.isfile(engine_shared):
    engine = src(engine_shared)
    check("M5 the shared engine has the File overload of decodeBarcode",
          "fun decodeBarcode(file: File)" in engine,
          "the mail path scans a cached attachment FILE, not a raw byte array")
    check("M5 the shared engine has the File overload of recognizeText",
          "fun recognizeText(file: File)" in engine)

# ── M6: MessageViewModel runs BOTH halves off the main thread, guarded ──
vm_path = os.path.join(APP, "app/src/main/kotlin/app/sterna/ui/message/MessageViewModel.kt")
vm = src(vm_path)
check("M6 MessageViewModel.scanAttachment exists",
      "fun scanAttachment(part: EmailBodyPart" in vm or "fun scanAttachment" in vm,
      "the button must reach a handler that exists")
check("M6 scanAttachment imports/calls the shared engine",
      "ImageScanEngine" in vm and "decodeBarcode(file)" in vm and "recognizeText(file)" in vm,
      "the handler must call the shared module, not reimplement OCR")
check("M6 scanAttachment runs off the main thread",
      "Dispatchers.IO" in vm,
      "ML Kit and ZXing are blocking; the tap must not freeze the reader")
check("M6 scanAttachment is guarded against re-entry",
      "scanningAttachment" in vm,
      "a second tap while a download is in flight would start the whole thing again")

# ── M7: every scan outcome names itself (no silent empty box) ──
for key in ("scan_contents_in_progress", "scan_contents_nothing_found", "scan_contents_failed",
            "scan_contents_barcode_label", "scan_contents_ocr_label"):
    check("M7 MessageViewModel references R.string.%s" % key,
          "R.string.%s" % key in vm,
          "a scan that can end without a visible reason is the empty-box defect")
strings = read(os.path.join(APP, "app/src/main/res/values/strings.xml"))
for key in ("scan_contents_in_progress", "scan_contents_nothing_found", "scan_contents_failed",
            "scan_contents_barcode_label", "scan_contents_ocr_label", "message_scan_attachment"):
    check("M7 values/strings.xml defines %s" % key,
          re.search(r'name="%s"' % key, strings) is not None,
          "a scan that can end without a visible reason is the empty-box defect")

# ── M8: the attachment row offers scan, gated to image mime, wired to the VM ──
screen = src(os.path.join(APP, "app/src/main/kotlin/app/sterna/ui/message/MessageScreen.kt"))
check("M8 AttachmentSection has an onScan slot",
      re.search(r"AttachmentSection\s*\(", screen) is not None
      and "onScan: (EmailBodyPart) -> Unit" in screen,
      "the section must accept the action before any row can offer it")
check("M8 the scan action is gated to image mime only",
      "att.type?.startsWith(\"image/\") == true" in screen
      or "att.type.startsWith(\"image/\")" in screen,
      "scanning a PDF's bytes as an image would be a silent wrong answer")
check("M8 the scan action reaches viewModel::scanAttachment",
      "onScanAttachment = viewModel::scanAttachment" in screen,
      "the button must call the scan path, not just exist")
check("M8 the screen passes onScan through ConversationBody and MessageHeader",
      screen.count("onScanAttachment") >= 3,
      "the wiring must run screen -> body -> header -> row, not die in the middle")

print("\n-- %d assertions, %d failed --" % (checked, len(failures)))
for f in failures:
    print("   FAILED: %s" % f, file=sys.stderr)
sys.exit(1 if failures else 0)
PY