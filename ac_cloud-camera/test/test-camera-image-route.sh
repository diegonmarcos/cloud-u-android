#!/bin/sh
# ╔══════════════════════════════════════════════════════════════════╗
# ║ task #772 — cloud-camera identifies through the ONE image engine  ║
# ╚══════════════════════════════════════════════════════════════════╝
#
# What this certifies, statically (this app has no JVM suite; CI builds it):
#   R1 the gallery's Scan contents still asks barcode AND OCR on their own
#      contract-1 calls, and ALSO recognises through ImageScanEngine.recognize
#      with the identify request (so an old engine keeps scanning).
#   R2 the identify request turns ocr and barcode off — they already ran.
#   R3 the route and model are the USER'S (RecognitionPrefs), never a literal:
#      OpenRouter is reached only when More settings picked it.
#   R4 More settings has the "Image recognition route" row, wired to a picker
#      over the DECLARED routes that saves through RecognitionPrefs.set, and a
#      model picker fed by the engine's live catalogue (through the scanner: C3 of
#      test-camera-scan-engine.sh keeps ImageScanEngine behind that one door).
#   R5 /api/<image_group>/recognize: provider in the manifest, not exported,
#      group declared in build.json and baked, op `recognize` (never `state`,
#      which update-ack-guard owns).
#   R6 the gallery shows the labels with their probabilities.
# Each check is then run against planted mutations of the real sources; a
# mutation that leaves every check green fails this tester.
set -eu
APP_DIR="$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)"
export APP_DIR

python3 - <<'PY'
import json, os, re, sys
import xml.etree.ElementTree as ET

APP = os.environ["APP_DIR"]
J = os.path.join(APP, "app/src/main/java/cld/camera")
FILES = {
    "scanner": os.path.join(J, "analyzer/ImageContentScanner.kt"),
    "gallery": os.path.join(J, "ui/activities/InAppGallery.kt"),
    "settings": os.path.join(J, "ui/activities/MoreSettings.kt"),
    "provider": os.path.join(J, "debugapi/ImageDebugApiProvider.kt"),
    "layout": os.path.join(APP, "app/src/main/res/layout/more_settings.xml"),
    "manifest": os.path.join(APP, "app/src/main/AndroidManifest.xml"),
    "gradle": os.path.join(APP, "app/build.gradle.kts"),
    "build": os.path.join(APP, "build.json"),
}
ANDROID = "{http://schemas.android.com/apk/res/android}"


def code(text):
    """Kotlin with comments removed (string literals kept), so prose cannot satisfy a check."""
    out, i, n = [], 0, len(text)
    while i < n:
        if text.startswith('"""', i):
            j = text.find('"""', i + 3); j = n if j < 0 else j + 3
            out.append(text[i:j]); i = j; continue
        c = text[i]
        if c == '"':
            j = i + 1
            while j < n and text[j] != '"':
                j += 2 if text[j] == "\\" else 1
            out.append(text[i:j + 1]); i = j + 1; continue
        if text.startswith("//", i):
            j = text.find("\n", i); i = n if j < 0 else j; continue
        if text.startswith("/*", i):
            j = text.find("*/", i + 2); i = n if j < 0 else j + 2; continue
        out.append(c); i += 1
    return "".join(out)


def fn(src, name):
    """The body of fun <name>, up to the next fun at the same or outer indent."""
    m = re.search(r"\n(\s*)(?:private |internal )?fun " + re.escape(name) + r"\b", src)
    if not m:
        return ""
    rest = src[m.end():]
    nxt = re.search(r"\n" + m.group(1) + r"(?:private |internal |override )?fun |\n" + m.group(1)[:-4] + r"\S", rest) if len(m.group(1)) >= 4 else re.search(r"\n" + m.group(1) + r"(?:private |internal |override )?fun ", rest)
    return rest[: nxt.start()] if nxt else rest


def checks(s):
    fails = []
    def need(label, ok):
        if not ok:
            fails.append(label)
    sc, ga, se, pr = (code(s[k]) for k in ("scanner", "gallery", "settings", "provider"))

    scan = fn(sc, "scan")
    need("R1 scan keeps decodeBarcode", "engine.decodeBarcode(uri)" in scan)
    need("R1 scan keeps recognizeText", "engine.recognizeText(uri)" in scan)
    need("R1 scan recognises with the identify request", re.search(r"engine\.recognize\(uri,\s*identifyRequest\(ctx\)\)", scan) is not None)

    ident = fn(sc, "identifyRequest")
    need("R2 identify turns OCR off", re.search(r'put\("ocr",\s*false\)', ident) is not None)
    need("R2 identify turns barcode off", re.search(r'put\("barcode",\s*false\)', ident) is not None)

    req = fn(sc, "request")
    need("R3 request uses the user's route", "RecognitionPrefs.route(ctx)" in req)
    need("R3 request uses the user's model", "RecognitionPrefs.model(ctx)" in req)
    need("R3 the scan path never names OpenRouter",
         not re.search(r'"openrouter"|OPENROUTER', fn(sc, "scan") + fn(sc, "identifyRequest") + req + ga))

    try:
        root = ET.fromstring(s["layout"])
        ids = {e.get(ANDROID + "id") for e in root.iter()}
    except ET.ParseError:
        ids = set()
    need("R4 the settings row exists", "@+id/image_route_setting" in ids and "@+id/image_route_subtitle" in ids)
    need("R4 the row opens the picker", re.search(r"binding\.imageRouteSetting\.setOnClickListener\s*\{\s*pickImageRoute\(\)\s*\}", se) is not None)
    pick = fn(se, "pickImageRoute")
    need("R4 the picker lists the declared routes", "RecognitionConfig.routes()" in pick)
    need("R4 the picker saves the choice", "RecognitionPrefs.set(" in pick)
    need("R4 OpenRouter asks for a model", re.search(r"RecognitionConfig\.OPENROUTER\)\s*pickImageModel\(\)", pick) is not None)
    model = fn(se, "pickImageModel")
    need("R4 the model picker reads the live catalogue", "ImageContentScanner(applicationContext).models()" in model and ".decisionModels(" in fn(sc, "models"))
    need("R4 the model picker saves OpenRouter + the model", re.search(r"RecognitionPrefs\.set\(this,\s*RecognitionConfig\.OPENROUTER,\s*field\.text", model) is not None)

    try:
        man = ET.fromstring(s["manifest"])
        provs = [p for p in man.iter("provider") if p.get(ANDROID + "name") == ".debugapi.ImageDebugApiProvider"]
    except ET.ParseError:
        provs = []
    need("R5 the provider is in the manifest", len(provs) == 1)
    need("R5 the provider is not exported", bool(provs) and provs[0].get(ANDROID + "exported") == "false")
    try:
        group = json.loads(s["build"])["debug_api"]["image_group"]
    except (ValueError, KeyError, TypeError):
        group = ""
    need("R5 build.json declares the image group", bool(group))
    need("R5 the group is baked from build.json", 'buildConfigField("String", "DEBUG_API_IMAGE_GROUP", "\\"$debugImageGroup\\"")' in s["gradle"]
         and 'get("image_group")' in s["gradle"])
    need("R5 the route registers under the baked group", "BuildConfig.DEBUG_API_IMAGE_GROUP" in pr)
    need("R5 the op is recognize", re.search(r'op == "recognize"\)\s*recognize\(', pr) is not None)
    need("R5 no state op", '"state"' not in pr)
    need("R5 an unknown route is refused", re.search(r"route !in RecognitionConfig\.routes\(\)", pr) is not None)

    need("R6 the gallery shows the labels", "ImageContentScanner.labels(content.recognition)" in ga)
    lab = fn(sc, "labels")
    need("R6 every label carries its probability", re.search(r"it\.label\}.*it\.p\s*\*\s*100", lab) is not None)
    return fails


src = {k: open(p, encoding="utf-8").read() for k, p in FILES.items()}
print("── R1-R6 against the tree ──")
bad = checks(src)
for f in bad:
    print("  FAIL  " + f)
print("  %d failure(s)" % len(bad))

MUTATIONS = [
    ("no-barcode", "scanner", "val barcode = engine.decodeBarcode(uri)", "val barcode: BarcodeScan? = null"),
    ("no-ocr", "scanner", "val ocr = engine.recognizeText(uri)", "val ocr = OcrResult(\"\", null)"),
    ("no-recognize", "scanner", "engine.recognize(uri, identifyRequest(ctx))", "Recognition.failed(\"ml\", \"off\")"),
    ("ocr-twice", "scanner", '.put("ocr", false)', '.put("ocr", true)'),
    ("hardcoded-route", "scanner", "route ?: RecognitionPrefs.route(ctx)", "route ?: \"openrouter\""),
    ("default-model", "scanner", "RecognitionPrefs.model(ctx))", "RecognitionConfig.defaultModel())"),
    ("row-gone", "layout", 'android:id="@+id/image_route_setting"', 'android:id="@+id/image_route_settings"'),
    ("row-dead", "settings", "binding.imageRouteSetting.setOnClickListener { pickImageRoute() }", "binding.imageRouteSetting.setOnClickListener { }"),
    ("choice-lost", "settings", "else { RecognitionPrefs.set(this, routes[i].key", "else { (routes[i].key"),
    ("no-model-step", "settings", "RecognitionConfig.OPENROUTER) pickImageModel()", "RecognitionConfig.OPENROUTER) showImageRoute()"),
    ("no-catalogue", "settings", "ImageContentScanner(applicationContext).models()", "emptyList<String>()"),
    ("scan-openrouter", "scanner", "RecognitionConfig.request(route ?: RecognitionPrefs.route(ctx)", "RecognitionConfig.request(route ?: RecognitionConfig.OPENROUTER"),
    ("provider-exported", "manifest", 'android:authorities="${applicationId}.imagedebugapi"\n            android:exported="false"', 'android:authorities="${applicationId}.imagedebugapi"\n            android:exported="true"'),
    ("provider-gone", "manifest", 'android:name=".debugapi.ImageDebugApiProvider"', 'android:name=".debugapi.Other"'),
    ("group-literal", "provider", "BuildConfig.DEBUG_API_IMAGE_GROUP,", '"image",'),
    ("state-op", "provider", 'if (op == "recognize")', 'if (op == "state")'),
    ("any-route", "provider", "if (route != null && route !in RecognitionConfig.routes())", "if (false)"),
    ("labels-hidden", "gallery", "ImageContentScanner.labels(content.recognition)", "\"\""),
    ("no-probability", "scanner", '"${it.label} ${Math.round(it.p * 100)}%"', '"${it.label}"'),
    ("group-undeclared", "build", '"image_group": "image"', '"image_grp": "image"'),
]
print("── planted mutations ──")
mut_fail = 0
for name, key, old, new in MUTATIONS:
    if src[key].count(old) != 1:
        print("  STALE MUT %s — its anchor is not in %s exactly once" % (name, key)); mut_fail += 1; continue
    m = dict(src); m[key] = src[key].replace(old, new)
    if checks(m):
        print("  PASS  MUT " + name)
    else:
        print("  FAIL  MUT %s — survived every check" % name); mut_fail += 1
print("── R1-R6 + mutations: %d failure(s) ──" % (len(bad) + mut_fail))
sys.exit(1 if bad or mut_fail else 0)
PY
