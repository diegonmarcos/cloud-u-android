#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════════════╗
# ║ #767 Cloud Calc — the declaration and the code that renders it agree,     ║
# ║ the microphone is asked for in one place, and no maths lives in the app   ║
# ╚══════════════════════════════════════════════════════════════════════════╝
#
#   C1  every declared tab has a mode, every mode names a declared tab, ids unique; #770 every
#       section (ui.sections) has a page (tab); #868 ui.bottom_nav is the island's section ids.
#   C2  the kinds build.json declares and the kinds ModeScreen's `when (mode.kind)`
#       dispatches are the same set, both ways: a declared kind with no renderer
#       draws the "no renderer" line, a renderer no mode uses is dead code.
#   C3  every declared tab and section icon has a branch in IconCatalog — a misspelt name
#       would silently draw the fallback.
#   C4  RECORD_AUDIO: the manifest declares it and exactly one Kotlin file asks for it
#       (#772: ui/SoundScreens.kt's MicGate, which every listening sound mode composes
#       through), so no other mode can ever pop the dialog.
#   C5  the engine stays an engine: no gradle file, settings or module map of this
#       app names libs:calc, and no app source loads a native library — every
#       calculation crosses to Cloud-Lib-Calc.
#   C6  no app or jev/ source opens a URL or a socket: OpenRouter's Decisions client is
#       libs:decisions (#772, shared with the image engine), and jev/…/Decisions.kt — the only
#       file that hands it an address — spells no URL: every address comes from build.json::jev.
#       The engine's rate download stays in libs:calc.
#   C7  the debug API registers eval, modes and info under build.json::ui.debug_api.group.
#   C8  #768 the Clock's platform contract, in the manifest: exact alarms by the alarm-clock
#       path (USE_EXACT_ALARM; SCHEDULE_EXACT_ALARM capped at API 32), a non-exported receiver
#       for its own wakeups, an exported one re-planning on BOOT_COMPLETED, MY_PACKAGE_REPLACED,
#       TIME_SET and TIMEZONE_CHANGED, a specialUse foreground service with its permission, and
#       a full-screen ring; POST_NOTIFICATIONS asked for by ui/ClockScreens.kt alone.
#   C9  one scheduler: only clock/ClockEngine.kt calls AlarmManager's set*, and a user's alarm
#       is set as an alarm CLOCK (setAlarmClock), the one kind Doze never defers.
#   C10 /api/<clock_group>/ documents and answers status, timer_start and timer_cancel, under
#       build.json::ui.debug_api.clock_group, and never names an op `state` (GET /api/state's key).
#   C11 #770 the Jev section: every routing tool in build.json::jev names a declared mode (and a
#       form a mode declares), on_error's fallback_mode and every ask key are modes; the token
#       is kept in ONE file (decide/JevStore.kt alone touches EncryptedSharedPreferences and
#       revealAiKey); nothing under decide/ or jev/ calls android.util.Log (a token must never
#       reach logcat); /api/<jev_group>/ documents and answers route and config.
#   C12 #772 the Sound tools: /api/<sound_group>/ documents and answers generate, analyze and
#       status and never names an op `state`; ONE microphone reader and ONE player
#       (audio/Audio.kt alone constructs AudioRecord / AudioTrack); every played buffer went
#       through the safe-volume guard (Player.play is called by audio/SoundFlow.kt alone, which
#       calls Generator.guard); the declared guard is sane (0 < loud_amplitude <= max_amplitude
#       <= 1); jev.identify.sound and a `sound` model use are declared.
#   C13 #772 the Camera tools: CAMERA is declared and asked for by ui/CameraScreens.kt alone
#       (CameraGate); ARCore is declared OPTIONAL (required would make the app uninstallable
#       where ARCore is not); one door to the image engine (camera/Vision.kt alone constructs
#       ImageScanEngine) and one ARCore session owner (camera/ArMeasureActivity.kt);
#       /api/<camera_group>/status and /api/<image_group>/recognize documented and answered, no
#       `state` op; every reference object has a positive length and a unique id, and OCR
#       numbers go to a declared expression mode.
#   C14 the keypad never moves (owner bug): ExpressionMode keeps every text- or result-dependent view
#       inside one weight(1f) display (// DISPLAY BEGIN..END) above the
#       keys, and ModeScreens.kt does not call AskAboutResult( (the "Ask about this result" element).
#   MUT each property, broken on a copy (and the edit proven to have landed), goes red.
#
# OWN-SOURCE ONLY: reads ac_cloud-calc and nothing else. python3 + grep.
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP="$(cd "$HERE/.." && pwd)"
for f in "$APP/build.json" "$APP/app/src/main/AndroidManifest.xml" \
         "$APP/app/src/main/java/com/diegonmarcos/cloudcalc/ui/ModeScreens.kt" \
         "$APP/app/src/main/java/com/diegonmarcos/cloudcalc/ui/IconCatalog.kt" \
         "$APP/app/src/main/java/com/diegonmarcos/cloudcalc/debugapi/CalcDebugApi.kt" \
         "$APP/jev/src/main/kotlin/com/diegonmarcos/cloudcalc/jev/Decisions.kt"; do
    [ -f "$f" ] || { echo "ERROR missing source: $f — this tester is unrun, not passing"; exit 1; }
done

CHECK="$(mktemp)"
trap 'rm -f "$CHECK"; rm -rf "${WORK:-}"' EXIT
cat > "$CHECK" <<'PY'
import glob, json, os, re, sys

app = sys.argv[1]
bad = []
src = os.path.join(app, "app", "src", "main", "java", "com", "diegonmarcos", "cloudcalc")

def code(p):
    """The file with comment lines dropped: prose may NAME what code must not do."""
    return "\n".join(l for l in open(p, encoding="utf-8").read().split("\n")
                     if not re.match(r"\s*(\*|//|/\*)", l))

kts = sorted(glob.glob(os.path.join(src, "**", "*.kt"), recursive=True))
jsrc = os.path.join(app, "jev", "src", "main", "kotlin", "com", "diegonmarcos", "cloudcalc", "jev")
jkts = sorted(glob.glob(os.path.join(jsrc, "**", "*.kt"), recursive=True))
bj = json.load(open(os.path.join(app, "build.json"), encoding="utf-8"))
modes = bj["ui"]["modes"]

# C1  #868 the nav is ONE declaration: ui.bottom_nav (<= 5 section ids) + ui.sections[].pages (the
# old tabs) + ui.default_section. A page is a tab; it names its section by sitting in its pages.
sections = bj["ui"].get("sections", [])
tabs = [dict(p, section=s["id"]) for s in sections for p in s.get("pages", [])]
sec_ids = [x["id"] for x in sections]
if not sec_ids:
    bad.append("C1 build.json::ui.sections is empty — no tab could be shown")
bar = bj["ui"].get("bottom_nav", [])
if not bar or len(bar) > 5:
    bad.append("C1 ui.bottom_nav must hold one to five section ids, found %s" % bar)
for b in bar:
    if b not in sec_ids:
        bad.append("C1 ui.bottom_nav names %s, which is not a ui.sections id" % b)
if bj["ui"].get("default_section") not in bar:
    bad.append("C1 ui.default_section %r is not one of ui.bottom_nav" % bj["ui"].get("default_section"))
for k in ("tabs", "default_tab"):
    if k in bj["ui"]:
        bad.append("C1 ui.%s is back: the nav is ui.bottom_nav + ui.sections[].pages (#868)" % k)
for x in sec_ids:
    if not any(t.get("section") == x for t in tabs):
        bad.append("C1 section %s has no page — its top strip would be empty" % x)
tab_ids = [t["id"] for t in tabs]
for t in tab_ids:
    if not any(m["tab"] == t for m in modes):
        bad.append("C1 tab %s has no mode — the nav would open an empty page" % t)
for m in modes:
    if m["tab"] not in tab_ids:
        bad.append("C1 mode %s names tab %s, which is not declared — it can never be reached" % (m["id"], m["tab"]))
for kind, ids in (("tab", tab_ids), ("mode", [m["id"] for m in modes])):
    if len(ids) != len(set(ids)):
        bad.append("C1 duplicate %s ids: %s" % (kind, sorted(i for i in set(ids) if ids.count(i) > 1)))

# C2
ms = code(os.path.join(src, "ui", "ModeScreens.kt"))
m = re.search(r"when \(mode\.kind\) \{(.*?)\n        \}", ms, re.S)
dispatched = set(re.findall(r'^\s*"(\w+)" ->', m.group(1), re.M)) if m else set()
if not dispatched:
    bad.append("C2 ModeScreen has no `when (mode.kind)` this tester can read")
declared = {m["kind"] for m in modes}
for k in sorted(declared - dispatched):
    bad.append("C2 kind %s is declared but ModeScreen renders no such kind" % k)
for k in sorted(dispatched - declared):
    bad.append("C2 ModeScreen renders kind %s, which no mode declares — dead renderer" % k)

# C3
ic = code(os.path.join(src, "ui", "IconCatalog.kt"))
icons = set(re.findall(r'^\s*"([\w-]+)" ->', ic, re.M))
for t in tabs + sections:
    if t.get("icon") not in icons:
        bad.append("C3 tab %s icon %r has no IconCatalog branch — it would draw the fallback" % (t["id"], t.get("icon")))

for m_ in modes:
    if m_.get("icon") and m_["icon"] not in icons:
        bad.append("C3 mode %s icon %r has no IconCatalog branch — it would draw the fallback" % (m_["id"], m_["icon"]))

# C4
manifest = open(os.path.join(app, "app", "src", "main", "AndroidManifest.xml"), encoding="utf-8").read()
if 'android.permission.RECORD_AUDIO' not in manifest:
    bad.append("C4 the manifest does not declare RECORD_AUDIO — the meter could never be granted")
ask_mic = re.compile(r"\.launch\(\s*(android\.)?Manifest\.permission\.RECORD_AUDIO\s*\)")
askers = sorted(os.path.relpath(p, src) for p in kts if ask_mic.search(code(p)))
if askers != ["ui/SoundScreens.kt"]:
    bad.append("C4 RECORD_AUDIO must be asked for by ui/SoundScreens.kt alone (MicGate), found in %s" % askers)

# C5
names_engine = re.compile(r"libs[:/]calc\b")
for f in [os.path.join(app, "build.json"), os.path.join(app, "settings.gradle"), os.path.join(app, "build.gradle"),
          os.path.join(app, "app", "build.gradle")]:
    if os.path.isfile(f):
        text = open(f, encoding="utf-8").read() if f.endswith(".json") else code(f)
        if f.endswith(".json"):  # module ids and dirs only: the _doc prose says libs:calc is NOT there
            text = " ".join(k + " " + str(v.get("dir", "")) for k, v in bj.get("modules", {}).items() if isinstance(v, dict))
        if names_engine.search(text):
            bad.append("C5 %s names libs:calc — the app would compile the engine" % os.path.relpath(f, app))
for p in kts:
    if re.search(r"System\.loadLibrary|external fun ", code(p)):
        bad.append("C5 %s loads native code — the maths belongs to Cloud-Lib-Calc" % os.path.relpath(p, src))

# C6
net = re.compile(r"java\.net\.|HttpURLConnection|okhttp3|\bSocket\(|URL\(")
decisions = os.path.join(jsrc, "Decisions.kt")
for p in kts + jkts:
    hit = net.search(code(p))
    if hit:
        bad.append("C6 %s uses the network (%s) — the app opens nothing itself; libs:decisions is the Decisions client" % (os.path.basename(p), hit.group(0)))
if os.path.isfile(decisions) and re.search(r'"[a-z]+://', code(decisions)):
    bad.append("C6 jev/Decisions.kt spells a URL — every address it opens must come from build.json::jev")

# C7
api = code(os.path.join(src, "debugapi", "CalcDebugApi.kt"))
if "BuildConfig.DEBUG_API_GROUP" not in api:
    bad.append("C7 CalcDebugApi does not register under build.json::ui.debug_api.group")
for op in ("eval", "modes", "info"):
    if not re.search(r'AppDebugServer\.Op\("%s"' % op, api) or not re.search(r'"%s" ->' % op, api):
        bad.append("C7 /api/<group>/%s is not both documented and answered" % op)
if not bj["ui"].get("debug_api", {}).get("group"):
    bad.append("C7 build.json::ui.debug_api.group is missing")

# C8
for perm in ("android.permission.USE_EXACT_ALARM", "android.permission.RECEIVE_BOOT_COMPLETED",
             "android.permission.FOREGROUND_SERVICE_SPECIAL_USE", "android.permission.POST_NOTIFICATIONS",
             "android.permission.USE_FULL_SCREEN_INTENT"):
    if 'android:name="%s"' % perm not in manifest:
        bad.append("C8 the manifest does not declare %s" % perm)
if not re.search(r'android\.permission\.SCHEDULE_EXACT_ALARM"\s+android:maxSdkVersion="32"', manifest):
    bad.append("C8 SCHEDULE_EXACT_ALARM must be capped at maxSdkVersion 32 (USE_EXACT_ALARM covers 33+)")
def element(tag, name):
    m = re.search(r'<%s\s[^>]*android:name="%s"[^>]*?(/>|>.*?</%s>)' % (tag, re.escape(name), tag), manifest, re.S)
    return m.group(0) if m else ""
rx = element("receiver", ".clock.ClockReceiver")
if 'android:exported="false"' not in rx:
    bad.append("C8 .clock.ClockReceiver must exist and not be exported — another app could dismiss an alarm")
boot = element("receiver", ".clock.ClockBootReceiver")
for action in ("BOOT_COMPLETED", "MY_PACKAGE_REPLACED", "TIME_SET", "TIMEZONE_CHANGED"):
    if 'android.intent.action.%s"' % action not in boot:
        bad.append("C8 .clock.ClockBootReceiver does not re-plan on %s — alarms would be lost or shifted" % action)
svc = element("service", ".clock.ClockService")
if 'android:foregroundServiceType="specialUse"' not in svc or "PROPERTY_SPECIAL_USE_FGS_SUBTYPE" not in svc:
    bad.append("C8 .clock.ClockService must be a specialUse foreground service with its subtype property")
if 'android:showWhenLocked="true"' not in element("activity", ".clock.RingActivity"):
    bad.append("C8 .clock.RingActivity must show over the lock screen")
if 'android.permission.INTERNET' in manifest:
    bad.append("C8 the app manifest asks for INTERNET — Calc and Clock are offline (libs:devtools merges the INTERNET its loopback server and the Jev section's one network file need)")
asked = sorted(os.path.relpath(p, src) for p in kts if "POST_NOTIFICATIONS" in code(p))
if asked != ["ui/ClockScreens.kt"]:
    bad.append("C8 POST_NOTIFICATIONS must be asked for by ui/ClockScreens.kt alone, found in %s" % asked)

# C9
setters = re.compile(r"\b(setAlarmClock|setExactAndAllowWhileIdle|setAndAllowWhileIdle|setExact|setInexactRepeating|setRepeating|setWindow)\(")
callers = sorted(os.path.relpath(p, src) for p in kts if setters.search(code(p)))
if callers != ["clock/ClockEngine.kt"]:
    bad.append("C9 AlarmManager must be set by clock/ClockEngine.kt alone, found in %s" % callers)
engine = os.path.join(src, "clock", "ClockEngine.kt")
if os.path.isfile(engine) and not re.search(r"w\.clock && exact -> AlarmManagerCompat\.setAlarmClock\(", code(engine)):
    bad.append("C9 a user's alarm is not set with setAlarmClock — Doze could defer it")

# C10
capi_path = os.path.join(src, "debugapi", "ClockDebugApi.kt")
capi = code(capi_path) if os.path.isfile(capi_path) else ""
if "BuildConfig.DEBUG_API_CLOCK_GROUP" not in capi:
    bad.append("C10 ClockDebugApi does not register under build.json::ui.debug_api.clock_group")
for op in ("status", "timer_start", "timer_cancel"):
    if not re.search(r'AppDebugServer\.Op\("%s"' % op, capi) or not re.search(r'"%s" ->' % op, capi):
        bad.append("C10 /api/<clock_group>/%s is not both documented and answered" % op)
if re.search(r'"state" ->', capi):
    bad.append("C10 ClockDebugApi answers an op named state — that key is GET /api/state's (update-ack guard)")
if not bj["ui"].get("debug_api", {}).get("clock_group"):
    bad.append("C10 build.json::ui.debug_api.clock_group is missing")

# C11
jev = bj.get("jev") or {}
modes_by_id = {m["id"]: m for m in modes}
route = jev.get("route") or {}
for tid, t in (route.get("tools") or {}).items():
    if tid.startswith("_") or t.get("action") == "timer":
        continue
    m = modes_by_id.get(t.get("mode"))
    if m is None:
        bad.append("C11 jev tool %s names mode %s, which is not declared" % (tid, t.get("mode")))
    elif t.get("action") == "form" and t.get("form") not in [f["id"] for f in m.get("forms", [])]:
        bad.append("C11 jev tool %s names form %s, which mode %s does not declare" % (tid, t.get("form"), m["id"]))
if route.get("on_error") == "expression" and route.get("fallback_mode") not in modes_by_id:
    bad.append("C11 jev route.fallback_mode %s is not a declared mode — the offline fallback would compute nothing" % route.get("fallback_mode"))
for k in (jev.get("ask") or {}):
    if not k.startswith("_") and k != "*" and k not in modes_by_id:
        bad.append("C11 jev ask.%s is not a declared mode" % k)
dkts = [p for p in kts if os.sep + "decide" + os.sep in p]
for needle in ("EncryptedSharedPreferences", "revealAiKey"):
    holders = sorted(os.path.relpath(p, src) for p in kts + jkts if needle in code(p))
    if holders != ["decide/JevStore.kt"]:
        bad.append("C11 %s must be used by decide/JevStore.kt alone (the token's one store), found in %s" % (needle, holders))
for p in dkts + jkts:
    if re.search(r"\bLog\.[a-z]+\(|android\.util\.Log\b", code(p)):
        bad.append("C11 %s logs — nothing that holds the token may write to logcat" % os.path.basename(p))
japi_path = os.path.join(src, "debugapi", "JevDebugApi.kt")
japi = code(japi_path) if os.path.isfile(japi_path) else ""
if "BuildConfig.DEBUG_API_JEV_GROUP" not in japi:
    bad.append("C11 JevDebugApi does not register under build.json::ui.debug_api.jev_group")
for op in ("route", "config"):
    if not re.search(r'AppDebugServer\.Op\("%s"' % op, japi) or not re.search(r'"%s" ->' % op, japi):
        bad.append("C11 /api/<jev_group>/%s is not both documented and answered" % op)
if not bj["ui"].get("debug_api", {}).get("jev_group"):
    bad.append("C11 build.json::ui.debug_api.jev_group is missing")

# C12
sapi_path = os.path.join(src, "debugapi", "SoundDebugApi.kt")
sapi = code(sapi_path) if os.path.isfile(sapi_path) else ""
if "BuildConfig.DEBUG_API_SOUND_GROUP" not in sapi:
    bad.append("C12 SoundDebugApi does not register under build.json::ui.debug_api.sound_group")
for op in ("generate", "analyze", "status"):
    if not re.search(r'AppDebugServer\.Op\("%s"' % op, sapi) or not re.search(r'"%s" ->' % op, sapi):
        bad.append("C12 /api/<sound_group>/%s is not both documented and answered" % op)
if re.search(r'"state" ->', sapi):
    bad.append("C12 SoundDebugApi answers an op named state — that key is GET /api/state's (update-ack guard)")
if not bj["ui"].get("debug_api", {}).get("sound_group"):
    bad.append("C12 build.json::ui.debug_api.sound_group is missing")
for cls in ("AudioRecord", "AudioTrack"):
    makers = sorted(os.path.relpath(p, src) for p in kts if re.search(r"\b%s(\.Builder)?\(" % cls, code(p)))
    if makers != ["audio/Audio.kt"]:
        bad.append("C12 %s must be constructed by audio/Audio.kt alone, found in %s" % (cls, makers))
players = sorted(os.path.relpath(p, src) for p in kts if re.search(r"\bPlayer\.play\(", code(p)))
if players != ["audio/SoundFlow.kt"]:
    bad.append("C12 Player.play must be called by audio/SoundFlow.kt alone (the guarded path), found in %s" % players)
flow = os.path.join(src, "audio", "SoundFlow.kt")
if os.path.isfile(flow) and "Generator.guard(" not in code(flow):
    bad.append("C12 audio/SoundFlow.kt plays without Generator.guard — the safe-volume limit would be skipped")
gen = (bj.get("sound") or {}).get("generator") or {}
mx, loud = gen.get("max_amplitude"), gen.get("loud_amplitude")
if not (isinstance(mx, (int, float)) and isinstance(loud, (int, float)) and 0 < loud <= mx <= 1):
    bad.append("C12 build.json::sound.generator needs 0 < loud_amplitude <= max_amplitude <= 1 (got %s, %s)" % (loud, mx))
if "sound" not in ((jev.get("identify") or {})) or "sound" not in (jev.get("uses") or {}):
    bad.append("C12 build.json::jev must declare identify.sound and a sound model use")

# C13
if 'android.permission.CAMERA' not in manifest:
    bad.append("C13 the manifest does not declare CAMERA — the camera tools could never be granted")
ask_cam = re.compile(r"\.launch\(\s*(android\.)?Manifest\.permission\.CAMERA\s*\)")
cam_askers = sorted(os.path.relpath(p, src) for p in kts if ask_cam.search(code(p)))
if cam_askers != ["ui/CameraScreens.kt"]:
    bad.append("C13 CAMERA must be asked for by ui/CameraScreens.kt alone (CameraGate), found in %s" % cam_askers)
if not re.search(r'<meta-data\s+android:name="com\.google\.ar\.core"\s+android:value="optional"', manifest):
    bad.append("C13 ARCore must be declared optional — required would refuse every phone without it")
for needle, owner in (("ImageScanEngine(", "camera/Vision.kt"), ("Session(this)", "camera/ArMeasureActivity.kt")):
    holders = sorted(os.path.relpath(p, src) for p in kts if needle in code(p))
    if holders != [owner]:
        bad.append("C13 %s must be made by %s alone, found in %s" % (needle, owner, holders))
for grp_key, api_file, ops in (("camera_group", "CameraDebugApi.kt", ("status",)), ("image_group", "CameraDebugApi.kt", ("recognize",))):
    cpath = os.path.join(src, "debugapi", api_file)
    ctext = code(cpath) if os.path.isfile(cpath) else ""
    if "BuildConfig.DEBUG_API_%s" % grp_key.upper() not in ctext:
        bad.append("C13 %s does not register under build.json::ui.debug_api.%s" % (api_file, grp_key))
    for op in ops:
        if not re.search(r'AppDebugServer\.Op\("%s"' % op, ctext) or not re.search(r'"%s" ->' % op, ctext):
            bad.append("C13 /api/<%s>/%s is not both documented and answered" % (grp_key, op))
    if not bj["ui"].get("debug_api", {}).get(grp_key):
        bad.append("C13 build.json::ui.debug_api.%s is missing" % grp_key)
    if re.search(r'"state" ->', ctext):
        bad.append("C13 %s answers an op named state — that key is GET /api/state's (update-ack guard)" % api_file)
cam = bj.get("camera") or {}
refs = cam.get("references") or []
if not refs:
    bad.append("C13 build.json::camera.references is empty — the photo route could scale by nothing")
if len({r.get("id") for r in refs}) != len(refs):
    bad.append("C13 build.json::camera.references ids are not unique")
for r in refs:
    if not (isinstance(r.get("mm"), (int, float)) and r["mm"] > 0):
        bad.append("C13 reference %s has no positive mm" % r.get("id"))
send_to = (cam.get("ocr") or {}).get("send_to_mode")
if (modes_by_id.get(send_to) or {}).get("kind") != "expression":
    bad.append("C13 build.json::camera.ocr.send_to_mode %s is not an expression mode" % send_to)

# C14 the keypad never moves: the calculator's result-dependent views sit in ONE fixed-height display
# above it, never loose over the keys, and "Ask about this result" is not on a calculator screen.
raw = open(os.path.join(src, "ui", "ModeScreens.kt"), encoding="utf-8").read()
if re.search(r"\bAskAboutResult\(", code(os.path.join(src, "ui", "ModeScreens.kt"))):
    bad.append("C14 ModeScreens.kt calls AskAboutResult( — an element that appears with a result shifts the calculator layout")
em = re.search(r"private fun ExpressionMode\(.*?\n\}\n", raw, re.S)
body = em.group(0) if em else ""
b0, b1, k0 = body.find("// DISPLAY BEGIN"), body.find("// DISPLAY END"), body.find("mode.keys.forEach")
if not body or min(b0, b1, k0) < 0 or not (b0 < b1 < k0):
    bad.append("C14 ExpressionMode must hold // DISPLAY BEGIN ... // DISPLAY END above mode.keys.forEach")
else:
    box = body[b0:b1]
    if ".weight(1f)" not in box.split("\n", 2)[1]:
        bad.append("C14 the display of ExpressionMode must open with weight(1f), so the keypad is anchored below it")
    c0 = body.find("Column(Modifier.fillMaxSize()")
    loose = body[max(c0, 0):b0] + body[b1:k0]
    for tok in ("ResultBlock(", "bases", "suggestions", "result", "AskAboutResult("):
        if tok in "\n".join(l for l in loose.split("\n") if not re.match(r"\s*(//|\*)", l)):
            bad.append("C14 ExpressionMode: %s sits outside the display, above the keypad" % tok)
theme = open(os.path.join(src, "ui", "CalcTheme.kt"), encoding="utf-8").read()
if not re.search(r"val displayMinHeight: Dp = [1-9]\d*\.dp", theme):
    bad.append("C14 CalcMetrics.displayMinHeight is not a positive dp")

for b in bad:
    print("  FAIL  " + b)
sys.exit(1 if bad else 0)
PY

FAILURES=0
echo "── C1-C14 against the tree ──"
if python3 "$CHECK" "$APP"; then echo "  PASS  C1-C14"; else FAILURES=$((FAILURES + 1)); fi

# ── mutations: each must go red, for the right reason ─────────────────────────
WORK="$(mktemp -d)"
mutate() {  # name, file (relative to the app), python expression over s, expected message fragment
    local name="$1" rel="$2" expr="$3" want="$4" copy="$WORK/$1"
    mkdir -p "$copy"
    cp -r "$APP/build.json" "$APP/app" "$APP/jev" "$copy/"
    for f in settings.gradle build.gradle; do [ -f "$APP/$f" ] && cp "$APP/$f" "$copy/"; done
    if ! python3 - "$copy/$rel" "$expr" <<'PY'
import sys
p, expr = sys.argv[1], sys.argv[2]
s = open(p, encoding="utf-8").read()
t = eval(expr)
if t == s:
    sys.exit(1)
open(p, "w", encoding="utf-8").write(t)
PY
    then echo "  VOID  MUT $name: the edit did not land — the mutation targets text that moved"; FAILURES=$((FAILURES + 1)); return; fi
    local out
    out="$(python3 "$CHECK" "$copy" 2>&1)"
    if [ $? -eq 0 ]; then
        echo "  FAIL  MUT $name: the check passed a broken tree"; FAILURES=$((FAILURES + 1))
    elif [[ "$out" != *"$want"* ]]; then
        echo "  FAIL  MUT $name: red for the wrong reason: $out"; FAILURES=$((FAILURES + 1))
    else
        echo "  PASS  MUT $name"
    fi
}
J='app/src/main/java/com/diegonmarcos/cloudcalc'
mutate tab-without-mode build.json 's.replace("\"tab\": \"graph\"", "\"tab\": \"calc\"")' "C1 tab graph has no mode"
mutate mode-orphan-tab build.json 's.replace("\"tab\": \"history\"", "\"tab\": \"nowhere\"", 1)' "names tab nowhere"
mutate kind-without-renderer "$J/ui/ModeScreens.kt" 's.replace("\"plot\" -> PlotMode(mode)", "")' "C2 kind plot is declared"
mutate dead-renderer "$J/ui/ModeScreens.kt" 's.replace("\"history\" -> HistoryMode(mode)", "\"history\" -> HistoryMode(mode)\n            \"abacus\" -> HistoryMode(mode)")' "C2 ModeScreen renders kind abacus"
mutate mode-icon-misspelt build.json 's.replace("\"icon\": \"science\"", "\"icon\": \"sciense\"")' "C3 mode scientific icon"
mutate icon-misspelt build.json 's.replace("\"icon\": \"chart\"", "\"icon\": \"chrat\"")' "C3 tab graph icon"
mutate mic-elsewhere "$J/ui/ModeScreens.kt" 's + "\nprivate fun nag(l: androidx.activity.result.ActivityResultLauncher<String>) = l.launch(android.Manifest.permission.RECORD_AUDIO)\n"' "C4 RECORD_AUDIO must be asked for"
mutate mic-undeclared app/src/main/AndroidManifest.xml 's.replace("<uses-permission android:name=\"android.permission.RECORD_AUDIO\" />", "")' "C4 the manifest does not declare"
mutate engine-in-module-map build.json 's.replace("\"libs:bottomnav\": {", "\"libs:calc\": {\"dir\": \"../ab_cloud-libs-shared/libs/calc\"},\n    \"libs:bottomnav\": {")' "C5 build.json names libs:calc"
mutate engine-compiled app/build.gradle 's.replace("implementation project(\x27:libs:bottomnav\x27)", "implementation project(\x27:libs:bottomnav\x27)\n    implementation project(\x27:libs:calc\x27)")' "C5 app/build.gradle names libs:calc"
mutate native-in-app "$J/Logic.kt" 's + "\nprivate object Q { init { System.loadLibrary(\"qalc\") } }\n"' "C5 Logic.kt loads native code"
mutate app-online "$J/Logic.kt" 's + "\nprivate val u = java.net.URL(\"https://example.org\")\n"' "C6 Logic.kt uses the network"
JV='jev/src/main/kotlin/com/diegonmarcos/cloudcalc/jev'
mutate router-online "$JV/JevRouter.kt" 's + "\nprivate fun leak() = java.net.Socket(\"x\", 1)\n"' "C6 JevRouter.kt uses the network"
mutate url-in-decisions "$JV/Decisions.kt" 's.replace("post(cfg.endpoint, cfg.timeoutMs,", "post(\"https://evil.example\", cfg.timeoutMs,")' "C6 jev/Decisions.kt spells a URL"
mutate decisions-online "$JV/Decisions.kt" 's + "\nprivate fun leak() = java.net.URL(\"x\").openConnection()\n"' "C6 Decisions.kt uses the network"
mutate bottom-nav-orphan build.json 's.replace("\"bottom_nav\": [\"calculator\", \"measure\", \"jev\"]", "\"bottom_nav\": [\"calculator\", \"measure\", \"nowhere\"]")' "C1 ui.bottom_nav names nowhere"
mutate bottom-nav-six build.json 's.replace("\"bottom_nav\": [\"calculator\", \"measure\", \"jev\"]", "\"bottom_nav\": [\"calculator\", \"measure\", \"jev\", \"a\", \"b\", \"c\"]")' "C1 ui.bottom_nav must hold one to five"
mutate default-off-bar build.json 's.replace("\"default_section\": \"calculator\"", "\"default_section\": \"history\"")' "C1 ui.default_section"
mutate section-icon build.json 's.replace("\"icon\": \"psychology\"", "\"icon\": \"psycho\"")' "C3 tab jev icon"
mutate jev-tool-no-mode build.json 's.replace("\"mode\": \"units\"", "\"mode\": \"unitz\"")' "C11 jev tool units names mode unitz"
mutate jev-tool-no-form build.json 's.replace("\"form\": \"dbsum\"", "\"form\": \"dbsun\"")' "names form dbsun"
mutate jev-fallback-mode build.json 's.replace("\"fallback_mode\": \"standard\"", "\"fallback_mode\": \"basic\"")' "C11 jev route.fallback_mode basic"
mutate jev-ask-key build.json 's.replace("\"acoustics\": [", "\"acoustix\": [")' "C11 jev ask.acoustix"
mutate token-second-store "$J/decide/JevFlow.kt" 's + "\nprivate val k = androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV\n"' "C11 EncryptedSharedPreferences must be used by decide/JevStore.kt alone"
mutate token-logged "$J/decide/JevStore.kt" 's.replace("val r = c.revealAiKey(provider)", "val r = c.revealAiKey(provider)\n        android.util.Log.d(\"jev\", r.text.orEmpty())")' "C11 JevStore.kt logs"
mutate jev-op-dropped "$J/debugapi/JevDebugApi.kt" 's.replace("\"config\" -> config(app).toString()", "")' "C11 /api/<jev_group>/config"
mutate debug-op-dropped "$J/debugapi/CalcDebugApi.kt" 's.replace("\"modes\" -> modesJson()", "")' "C7 /api/<group>/modes"
mutate no-use-exact-alarm app/src/main/AndroidManifest.xml 's.replace("<uses-permission android:name=\"android.permission.USE_EXACT_ALARM\" />", "")' "C8 the manifest does not declare android.permission.USE_EXACT_ALARM"
mutate exact-uncapped app/src/main/AndroidManifest.xml 's.replace(" android:maxSdkVersion=\"32\"", "")' "C8 SCHEDULE_EXACT_ALARM must be capped"
mutate fire-exported app/src/main/AndroidManifest.xml 's.replace("android:name=\".clock.ClockReceiver\"\n            android:exported=\"false\"", "android:name=\".clock.ClockReceiver\"\n            android:exported=\"true\"")' "C8 .clock.ClockReceiver must exist and not be exported"
mutate no-boot-replan app/src/main/AndroidManifest.xml 's.replace("<action android:name=\"android.intent.action.BOOT_COMPLETED\" />", "")' "C8 .clock.ClockBootReceiver does not re-plan on BOOT_COMPLETED"
mutate no-zone-replan app/src/main/AndroidManifest.xml 's.replace("<action android:name=\"android.intent.action.TIMEZONE_CHANGED\" />", "")' "does not re-plan on TIMEZONE_CHANGED"
mutate fgs-untyped app/src/main/AndroidManifest.xml 's.replace("android:foregroundServiceType=\"specialUse\"", "")' "C8 .clock.ClockService must be a specialUse"
mutate app-internet app/src/main/AndroidManifest.xml 's.replace("<uses-permission android:name=\"android.permission.WAKE_LOCK\" />", "<uses-permission android:name=\"android.permission.WAKE_LOCK\" />\n    <uses-permission android:name=\"android.permission.INTERNET\" />")' "C8 the app manifest asks for INTERNET"
mutate notify-elsewhere "$J/ui/ModeScreens.kt" 's + "\nprivate val nag = android.Manifest.permission.POST_NOTIFICATIONS\n"' "C8 POST_NOTIFICATIONS must be asked for"
mutate second-scheduler "$J/Logic.kt" 's + "\nprivate fun x(am: android.app.AlarmManager, p: android.app.PendingIntent) = am.setExact(0, 0L, p)\n"' "C9 AlarmManager must be set by clock/ClockEngine.kt alone"
mutate alarm-not-clock "$J/clock/ClockEngine.kt" 's.replace("w.clock && exact -> AlarmManagerCompat.setAlarmClock(am, w.at, openIntent(ctx, ClockDecl.modeId(\"alarms\")), pi)", "w.clock && exact -> AlarmManagerCompat.setExactAndAllowWhileIdle(am, AlarmManager.RTC_WAKEUP, w.at, pi)")' "alarm is not set with setAlarmClock"
mutate clock-op-dropped "$J/debugapi/ClockDebugApi.kt" 's.replace("\"timer_cancel\" -> {", "\"timer_kill\" -> {")' "C10 /api/<clock_group>/timer_cancel"
mutate clock-op-state "$J/debugapi/ClockDebugApi.kt" 's.replace("\"status\" -> status(ctx).toString()", "\"status\", \"state\" -> status(ctx).toString()\n        \"state\" -> status(ctx).toString()")' "C10 ClockDebugApi answers an op named state"

mutate sound-op-dropped "$J/debugapi/SoundDebugApi.kt" 's.replace("\"analyze\" -> analyze(app, q).toString()", "")' "C12 /api/<sound_group>/analyze"
mutate sound-op-state "$J/debugapi/SoundDebugApi.kt" 's.replace("\"status\" -> status(app).toString()", "\"state\" -> status(app).toString()")' "C12 SoundDebugApi answers an op named state"
mutate second-mic "$J/ui/SoundScreens.kt" 's + "\nprivate fun mic() = android.media.AudioRecord(1, 44100, 16, 2, 4096)\n"' "C12 AudioRecord must be constructed by audio/Audio.kt alone"
mutate unguarded-play "$J/ui/SoundScreens.kt" 's.replace("OutlinedButton(onClick = { Player.stop() })", "OutlinedButton(onClick = { Player.play(ShortArray(44100) { 32767 }, 44100) })")' "C12 Player.play must be called by audio/SoundFlow.kt alone"
mutate guard-skipped "$J/audio/SoundFlow.kt" 's.replace("val g = Generator.guard(spec, cfg.limits, cfg.sampleRate, Player.deviceVolume(ctx))", "val g = Generator.Guarded(spec, emptyList())")' "C12 audio/SoundFlow.kt plays without Generator.guard"
mutate guard-loud build.json 's.replace("\"max_amplitude\": 0.5", "\"max_amplitude\": 1.5")' "C12 build.json::sound.generator needs"
mutate no-sound-use build.json 's.replace("\"sound\": \"typesafe/jev-1.13\"", "\"noise\": \"typesafe/jev-1.13\"")' "C12 build.json::jev must declare identify.sound"

mutate camera-undeclared app/src/main/AndroidManifest.xml 's.replace("<uses-permission android:name=\"android.permission.CAMERA\" />", "")' "C13 the manifest does not declare CAMERA"
mutate camera-elsewhere "$J/ui/ModeScreens.kt" 's + "\nprivate fun peek(l: androidx.activity.result.ActivityResultLauncher<String>) = l.launch(android.Manifest.permission.CAMERA)\n"' "C13 CAMERA must be asked for by ui/CameraScreens.kt alone"
mutate arcore-required app/src/main/AndroidManifest.xml 's.replace("android:name=\"com.google.ar.core\" android:value=\"optional\"", "android:name=\"com.google.ar.core\" android:value=\"required\"")' "C13 ARCore must be declared optional"
mutate second-engine-door "$J/ui/CameraScreens.kt" 's + "\nprivate fun door(c: android.content.Context) = com.diegonmarcos.superapp.image.mlkit.ImageScanEngine(c)\n"' "C13 ImageScanEngine( must be made by camera/Vision.kt alone"
mutate image-op-dropped "$J/debugapi/CameraDebugApi.kt" 's.replace("\"recognize\" -> recognize(app, q).toString()", "")' "C13 /api/<image_group>/recognize"
mutate camera-op-state "$J/debugapi/CameraDebugApi.kt" 's.replace("\"status\" -> status(app).toString()", "\"state\" -> status(app).toString()")' "C13 CameraDebugApi.kt answers an op named state"
mutate reference-zero build.json 's.replace("\"mm\": 85.60", "\"mm\": 0")' "C13 reference card_long has no positive mm"
mutate ocr-nowhere build.json 's.replace("\"send_to_mode\": \"standard\"", "\"send_to_mode\": \"units\"")' "C13 build.json::camera.ocr.send_to_mode units"

mutate ask-above-keys "$J/ui/ModeScreens.kt" 's.replace("        // DISPLAY END", "        // DISPLAY END\n        AskAboutResult(mode.id, text, text)")' "C14 ModeScreens.kt calls AskAboutResult("
mutate result-above-keys "$J/ui/ModeScreens.kt" 's.replace("        // DISPLAY END", "        // DISPLAY END\n        ResultBlock(result)")' "C14 ExpressionMode: ResultBlock( sits outside the display"
mutate display-unfixed "$J/ui/ModeScreens.kt" 's.replace(".weight(1f).heightIn(min = CalcMetrics.displayMinHeight)", ".wrapContentHeight()")' "C14 the display of ExpressionMode must open with weight(1f)"
mutate display-zero "$J/ui/CalcTheme.kt" 's.replace("val displayMinHeight: Dp = 96.dp", "val displayMinHeight: Dp = 0.dp")' "C14 CalcMetrics.displayMinHeight is not a positive dp"

echo "── C1-C14 + mutations: $FAILURES failure(s) ──"
[ "$FAILURES" -eq 0 ]
