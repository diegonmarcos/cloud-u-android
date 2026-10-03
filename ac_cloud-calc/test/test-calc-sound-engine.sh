#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════════════╗
# ║ #798 Cloud Calc — "What is this sound?" answers ON DEVICE by default,     ║
# ║ through the shared sound engine; the decision model stays a choice        ║
# ╚══════════════════════════════════════════════════════════════════════════╝
#
#   S1  the sound CONTRACT (libs:ml-l-sound) is in the module map by reference and the app
#       depends on it; the ENGINE (ml-l-sound-yamnet) and TFLite appear nowhere in this app.
#   S2  the screen offers the DECLARED routes (SoundConfig.routes), starts on the user's
#       (SoundPrefs.route, whose default is the declaration's) and saves a pick (SoundPrefs.set).
#   S3  the on-device route answers through SoundFlow.identifyOnDevice, which is the contract's
#       SoundEngine.classify; the decision model is asked ONLY on the other route.
#   S4  every class the engine heard is shown with its probability, a failure with its reason.
#   S5  /api/<sound_group>/classify is documented and answered (ms | path | test | generator)
#       and reports the engine's readiness; /api/<image_group>/detect refuses an undeclared mode.
# Own-source only (this app's files). Each check is then run against planted mutations.
set -euo pipefail
APP="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export APP

python3 - <<'PY'
import json, os, re, sys

APP = os.environ["APP"]
K = os.path.join(APP, "app/src/main/java/com/diegonmarcos/cloudcalc")
FILES = {
    "build": os.path.join(APP, "build.json"),
    "gradle": os.path.join(APP, "app/build.gradle"),
    "screen": os.path.join(K, "ui/SoundScreens.kt"),
    "flow": os.path.join(K, "audio/SoundFlow.kt"),
    "sound_api": os.path.join(K, "debugapi/SoundDebugApi.kt"),
    "camera_api": os.path.join(K, "debugapi/CameraDebugApi.kt"),
    "vision": os.path.join(K, "camera/Vision.kt"),
}
for p in FILES.values():
    if not os.path.isfile(p):
        print("ERROR missing source %s — this tester is unrun, not passing" % p); sys.exit(1)

def code(t):
    return "\n".join(l for l in t.split("\n") if not re.match(r"\s*(\*|//|/\*)", l))

def all_sources():
    out = ""
    for root, _d, names in os.walk(os.path.join(APP, "app", "src", "main")):
        for n in names:
            if n.endswith((".kt", ".java")):
                out += code(open(os.path.join(root, n), encoding="utf-8").read())
    return out

src = {k: open(p, encoding="utf-8").read() for k, p in FILES.items()}
SOURCES = all_sources()

def checks(s, sources):
    bad = []
    def need(label, ok):
        if not ok: bad.append(label)
    try:
        b = json.loads(s["build"])
    except ValueError:
        b = {"modules": {}}
    mods = b.get("modules", {})
    # S1
    need("S1 libs:ml-l-sound is in the module map by reference",
         mods.get("libs:ml-l-sound", {}).get("dir") == "../ab_cloud-libs-shared/libs/ml-l-sound")
    need("S1 the app depends on libs:ml-l-sound", "libs:ml-l-sound" in mods.get("app", {}).get("depends_on", []))
    need("S1 app/build.gradle compiles the contract", "implementation project(':libs:ml-l-sound')" in code(s["gradle"]))
    need("S1 the engine is never compiled", "ml-l-sound-yamnet" not in s["build"] + code(s["gradle"]))
    need("S1 no TFLite in this app", "org.tensorflow" not in sources + code(s["gradle"]) and "litert" not in code(s["gradle"]))
    sc = code(s["screen"])
    # S2
    need("S2 the routes are the declared ones", "SoundConfig.routes().forEach { (id, label) ->" in sc)
    need("S2 the screen starts on the user's route", "mutableStateOf(SoundPrefs.route(ctx))" in sc)
    need("S2 a pick is saved", "SoundPrefs.set(ctx, id)" in sc)
    # S3
    need("S3 the on-device route asks the engine",
         "if (route == SoundConfig.ML) heard = io { SoundFlow.identifyOnDevice(ctx, pcm, cfg.sampleRate) }" in sc)
    need("S3 the decision model only on the other route",
         re.search(r"else decision = io \{ SoundFlow\.identify\(ctx, pcm, cfg\.sampleRate, r, knobs\) \}", sc) is not None)
    fl = code(s["flow"])
    need("S3 identifyOnDevice is the contract's classify", "fun identifyOnDevice(ctx: Context, pcm: ShortArray, sampleRate: Int): Recognition = engine(ctx).classify(pcm, sampleRate)" in fl)
    # S4
    need("S4 every heard class is shown with its probability", re.search(r"h\.labels\.forEach \{ o ->[\s\S]{0,400}Math\.round\(o\.p \* 100\)", sc) is not None)
    need("S4 a failure shows its reason", "R.string.sound_identify_unavailable, h.error.orEmpty()" in sc)
    need("S4 the heard bars are drawn", "heard?.let { h -> HeardBars(h) }" in sc)
    # S5
    sa = code(s["sound_api"])
    need("S5 classify is documented", 'AppDebugServer.Op("classify"' in sa)
    need("S5 classify is answered", '"classify" -> classify(app, q).toString()' in sa)
    need("S5 classify takes ms, path, test and the generator", all(x in sa for x in ['q["path"]', 'q["test"]', 'q["ms"]', 'q["source"] == "generator"']))
    need("S5 classify reports the engine's readiness", 'put("engine", SoundFlow.engineStatus(ctx) ?: "ready")' in sa)
    ca = code(s["camera_api"])
    need("S5 detect is documented and answered", 'AppDebugServer.Op("detect"' in ca and '"detect" -> detect(app, q).toString()' in ca)
    need("S5 an undeclared detect mode is refused", "mode !in RecognitionConfig.detectModes()" in ca)
    need("S5 detect is a single photo, never a stream", "RecognitionConfig.detectRequest(mode, live = false)" in code(s["vision"]))
    return bad

print("── S1-S5 against the tree ──")
bad = checks(src, SOURCES)
for f in bad:
    print("  FAIL  " + f)
print("  %d failure(s)" % len(bad))

MUTATIONS = [
    ("not-by-reference", "build", '"dir": "../ab_cloud-libs-shared/libs/ml-l-sound"', '"dir": "libs/ml-l-sound"'),
    ("not-a-dependency", "build", '"libs:ml-l-image",\n        "libs:ml-l-sound"', '"libs:ml-l-image"'),
    ("contract-dropped", "gradle", "implementation project(':libs:ml-l-sound')", "// none"),
    ("engine-compiled", "gradle", "implementation project(':libs:ml-l-sound')", "implementation project(':libs:ml-l-sound')\n    implementation project(':libs:ml-l-sound-yamnet')"),
    ("tflite-in-app", "gradle", "implementation project(':libs:ml-l-sound')", "implementation project(':libs:ml-l-sound')\n    implementation 'com.google.ai.edge.litert:litert:1.4.0'"),
    ("routes-literal", "screen", "SoundConfig.routes().forEach { (id, label) ->", 'mapOf("ml" to "On device").forEach { (id, label) ->'),
    ("starts-on-decision", "screen", "mutableStateOf(SoundPrefs.route(ctx))", "mutableStateOf(SoundConfig.OPENROUTER)"),
    ("pick-forgotten", "screen", "SoundPrefs.set(ctx, id)", "Unit"),
    ("on-device-skipped", "screen", "if (route == SoundConfig.ML) heard = io { SoundFlow.identifyOnDevice(ctx, pcm, cfg.sampleRate) }",
     "if (route == SoundConfig.ML) heard = null"),
    ("decision-always", "screen", "else decision = io { SoundFlow.identify(ctx, pcm, cfg.sampleRate, r, knobs) }",
     "decision = io { SoundFlow.identify(ctx, pcm, cfg.sampleRate, r, knobs) }"),
    ("flow-bypass", "flow", "= engine(ctx).classify(pcm, sampleRate)", "= Recognition.failed(\"ml\", \"off\")"),
    ("no-probability", "screen", 'Text("${Math.round(o.p * 100)}%")\n            }\n        }\n        if (h.segments', 'Text("")\n            }\n        }\n        if (h.segments'),
    ("failure-hidden", "screen", "R.string.sound_identify_unavailable, h.error.orEmpty()", "R.string.sound_identify_unavailable, \"\""),
    ("bars-gone", "screen", "heard?.let { h -> HeardBars(h) }", "heard?.let { }"),
    ("classify-unanswered", "sound_api", '"classify" -> classify(app, q).toString()', '"classified" -> classify(app, q).toString()'),
    ("no-readiness", "sound_api", 'put("engine", SoundFlow.engineStatus(ctx) ?: "ready")', 'put("engine", "ready")'),
    ("detect-any-mode", "camera_api", "mode !in RecognitionConfig.detectModes()", "false"),
    ("detect-streams", "vision", "RecognitionConfig.detectRequest(mode, live = false)", "RecognitionConfig.detectRequest(mode, live = true)"),
]
print("── planted mutations ──")
mut_fail = 0
for name, key, old, new in MUTATIONS:
    if src[key].count(old) != 1:
        print("  STALE MUT %s — its anchor is not in %s exactly once" % (name, key)); mut_fail += 1; continue
    m = dict(src); m[key] = src[key].replace(old, new)
    if checks(m, SOURCES):
        print("  PASS  MUT " + name)
    else:
        print("  FAIL  MUT %s — survived every check" % name); mut_fail += 1
print("── S1-S5 + %d mutations: %d failure(s) ──" % (len(MUTATIONS), len(bad) + mut_fail))
sys.exit(1 if bad or mut_fail else 0)
PY
