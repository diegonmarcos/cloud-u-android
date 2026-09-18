#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════╗
# ║ task #461 — cloud-office consumes the ONE shared scan engine      ║
# ╚══════════════════════════════════════════════════════════════════╝
#
# What this certifies: that the office patch series wires the ONE shared
# image-scan engine (libs:ml-l-image-mlkit: ImageScanEngine = ZXing barcode
# decode + ML Kit OCR) into the Collabora tree BY REFERENCE — never by
# vendoring a copy (#170/#261) — and that a phone can reach it: a menu row in
# every mobile*-array the app's document_menu declares, a live-in-read-only
# entry, and a browser handler that captures the current view and posts it to
# the Android shell, which answers every outcome with a Toast.
#
# OFFLINE on purpose: it reasons about the patch series and build.json that
# ARE in this repository. The SERIES-STILL-APPLIES question is
# tests/patches-apply.sh's and the MENU-REACHES-A-PHONE question is
# test-menu-entries-reach-the-mobile-app.sh's — both run against the fetched
# pin in CI. This one fails fast and cheap on the parts that never need a
# 1.9 GB upstream.
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
APP="$(cd "$HERE/.." && pwd)"
BJ="$APP/build.json"

PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); printf '  \033[0;32mok\033[0m: %s\n' "$1"; }
bad() { FAIL=$((FAIL+1)); printf '  \033[0;31mFAIL\033[0m: %s\n' "$1"; }

command -v jq >/dev/null 2>&1 || { echo "ERROR: jq is not on PATH" >&2; exit 2; }
command -v python3 >/dev/null 2>&1 || { echo "ERROR: python3 is not on PATH" >&2; exit 2; }
[ -f "$BJ" ] || { echo "ERROR: $BJ missing" >&2; exit 2; }

PATCH="$APP/patches/0005-cloud-office-content-scan-through-the-shared-scan-engine.patch"
[ -f "$PATCH" ] || { echo "ERROR: patch 0005 missing at $PATCH" >&2; exit 2; }

python3 - "$APP" "$PATCH" <<'PY'
import json
import os
import re
import sys

app, patch = sys.argv[1], sys.argv[2]
spec = json.load(open(os.path.join(app, "build.json"), encoding="utf-8"))
patch_text = open(patch, encoding="utf-8").read()
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

# ── O1: build.json declares the shared module, by reference, materialized ──
mod = ((spec.get("build") or {}).get("modules") or {}).get("ml-l-image-mlkit")
check("O1 build.json::build.modules[ml-l-image-mlkit] exists",
      isinstance(mod, dict))
check("O1 … dir points at ab_cloud-libs-shared (not a vendored copy)",
      isinstance(mod, dict) and "../ab_cloud-libs-shared/libs/ml-l-image-mlkit" == mod.get("dir"),
      "a copy is the defect this fleet fixed")
check("O1 … materialize_to places it where settings.gradle expects it",
      isinstance(mod, dict) and mod.get("materialize_to") == "android/ml-l-image-mlkit")

# ── O2: patch 0005 includes the module in the gradle build, by reference ──
check("O2 patch adds the settings.gradle include",
      re.search(r'include \':app\', \':lib\', \':text-tools\', \':ml-l-image-mlkit\'', patch_text) is not None
      or re.search(r"\+include ':app', ':lib', ':text-tools', ':ml-l-image-mlkit'", patch_text) is not None,
      "patch 0005 must extend the existing include line")
check("O2 patch points the project at settingsDir/ml-l-image-mlkit",
      re.search(r"project\(':ml-l-image-mlkit'\)\.projectDir = new File\(settingsDir, 'ml-l-image-mlkit'\)", patch_text) is not None,
      "the module must resolve to the materialized shared dir")

# ── O3: the lib links the module ──
check("O3 patch adds implementation project(':ml-l-image-mlkit') to lib/build.gradle",
      re.search(r"implementation project\(':ml-l-image-mlkit'\)", patch_text) is not None,
      "the office code must actually link the shared engine")

# ── O4: the Java surface imports the SHARED engine and asks BOTH halves ──
check("O4 patch adds CloudContentScan.java (the only scan definition)",
      "CloudContentScan.java" in patch_text and "new file mode" in patch_text)
check("O4 it imports the SHARED engine package",
      "com.diegonmarcos.superapp.image.mlkit.ImageScanEngine" in patch_text,
      "the shim must call the shared module's engine, not redeclare it")
check("O4 it forwards to BOTH decode halves (barcode AND OCR)",
      "decodeBarcode(" in patch_text and "recognizeText(" in patch_text,
      "a scan that only decodes barcodes and never OCRs is not the delivered capability")
check("O4 NO vendored engine copy: the patch adds no com/diegonmarcos/superapp path",
      "com/diegonmarcos/superapp/image/ImageScanEngine.kt" not in patch_text
      and "+package com.diegonmarcos.superapp" not in patch_text
      and "+class ImageScanEngine" not in patch_text,
      "a new engine file in the patch is the duplicate-engine defect")
check("O4 every scan outcome is said (non-silent)",
      "No barcode and no readable text" in patch_text and "Could not read this page view" in patch_text,
      "an empty result box with no reason is the defect this fleet bans")

# ── O5: the Android shell dispatches the browser message to the scanner ──
check("O5 patch wires LOActivity: MESSAGE constant",
      "static final String MESSAGE = \"CLOUD_CONTENT_SCAN\"" in patch_text)
check("O5 patch wires LOActivity: beforeMessageFromWebView case",
      re.search(r"case CloudContentScan\.MESSAGE:", patch_text) is not None
      and "getCloudContentScan().onPagePng(" in patch_text,
      "the web message must reach the scanner, not core")
check("O5 patch wires LOActivity: lazy CloudContentScan holder",
      "getCloudContentScan()" in patch_text)

# ── O6: the entry a phone opens actually carries the row (document_menu spec) ──
entries = ((spec.get("document_menu") or {}).get("entries") or [])
mine = [e for e in entries if e.get("id") == "cloudscancontents"]
check("O6 document_menu declares the cloudscancontents entry",
      len(mine) == 1, "build.json::document_menu must name the entry the patch adds")
if mine:
    e = mine[0]
    arrays = e.get("must_appear_in") or []
    check("O6 entry names at least one mobile array",
          any(a.startswith("mobile") for a in arrays),
          "a row that only exists for the desktop menubar is unreachable on a phone")
    # The patch must add the row once per declared array: the exact menu-row
    # literal, which the patch carries in its added lines.
    for a in arrays:
        check("O6 patch places cloudscancontents in %s" % a,
              re.search(r"id: 'cloudscancontents'", patch_text) is not None,
              "entry must appear in every array document_menu names")
    # Must be view-mode-safe when declared so, exactly like cloudsettings:
    # _beforeShow greys out actions not named in allowedViewModeActions.
    if e.get("must_be_view_mode_action"):
        check("O6 read-only documents keep the entry (allowedViewModeActions)",
              re.search(r"^\+[ \t]*'cloudsettings',\n\+[ \t]*'cloudscancontents'", patch_text, re.M) is not None,
              "a view-mode action not allowed in read-only is a row that dies on a PDF — "
              "the patch must add the bare id beside 'cloudsettings' in allowedViewModeActions")

# ── O7: the browser captures the current view and posts it ──
check("O7 patch adds the JS handler for cloudscancontents",
      re.search(r"id === 'cloudscancontents'", patch_text) is not None)
check("O7 the handler captures #document-canvas (what the user sees)",
      "document.getElementById('document-canvas')" in patch_text,
      "a capture that reads nothing is a button that lies")
check("O7 the handler casts the capture to HTMLCanvasElement",
      "as HTMLCanvasElement" in patch_text,
      "getElementById returns HTMLElement; width/height/getContext/toDataURL are canvas-only — "
      "a missing cast is the TS2339 build failure this app already shipped once")
check("O7 the handler posts CLOUD_CONTENT_SCAN to the shell",
      "postMobileMessage('CLOUD_CONTENT_SCAN '" in patch_text
      or "postMobileMessage(\"CLOUD_CONTENT_SCAN \"" in patch_text)
check("O7 capture failure names itself (no-canvas / tainted)",
      "error=no-canvas" in patch_text and "error=tainted" in patch_text,
      "a refusal with no reason is the silent-box defect one layer up")
check("O7 the downscale 2d context is null-guarded (strict tsc)",
      "const ctx = out.getContext('2d')" in patch_text and "if (!ctx)" in patch_text,
      "getContext returns CanvasRenderingContext2D | null; the strict tsc pass failed on exactly "
      "this (TS2531) before the guard existed")

print("\n-- %d assertions, %d failed --" % (checked, len(failures)))
for f in failures:
    print("   FAILED: %s" % f)
sys.exit(1 if failures else 0)
PY

[ "$FAIL" -eq 0 ] || { echo "FAILED" >&2; exit 1; }
exit 0