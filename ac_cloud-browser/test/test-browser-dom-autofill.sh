#!/bin/sh
# ╔══════════════════════════════════════════════════════════════════╗
# ║ Tier 2 DOM autofill: non-secret profile data, Vault keeps secrets ║
# ╚══════════════════════════════════════════════════════════════════╝
#
# a0_docs/eng-specs/autofill-3-tier.md. What this certifies (the host's pure rules are JVM-tested in
# DomAutofillTest):
#   D1 the page engine, run under node against a fake DOM (autofill_js_harness.js): autocomplete
#      tokens first, then EN/ES/PT/FR/DE words; password / OTP / card / username never classified, a
#      site rule cannot override that; React-style controlled inputs see the fill (native setter);
#      input, change, blur dispatched; framework-filled fields deferred; login-form email left to Vault;
#      contact-form email filled; incognito never offers a save; dynamic forms re-scanned;
#   D2 the engine never weakens the page for Android autofill (no autocomplete=off, no attribute writes)
#      and never logs or posts a value except the user's own submitted address, offered for a confirmed save;
#   D3 the host: the WebView keeps exposing its fields to the Android Autofill framework by default, the
#      bridge carries keys and ids only, values are filled only from the chip's tap, a private tab gets
#      no named prompt and no save offer, the SOT is read through libs:autofill under the declared
#      signature permissions, and nothing logs a profile value;
#   D4 the two settings are declared and read.
set -eu
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
export ROOT
cd "$(dirname "$0")"
python3 - <<'PYEOF'
import json, os, re, subprocess, sys
sys.path.insert(0, ".")
from browser_tester import LIB, APP, kotlin, build_json, main

ASSETS = "ab_cloud-libs-shared/libs/browser/src/main/assets/browser"
SOT = "ab_cloud-libs-shared/libs/autofill"

def check(root, ok):
    js = open(os.path.join(root, ASSETS, "autofill_engine.js"), encoding="utf-8").read()
    src = kotlin(root, LIB, APP)
    frag = src.get(LIB + "/BrowserHostFragment.kt", "")
    dom = src.get(LIB + "/DomAutofill.kt", "")
    chip = src.get(LIB + "/BrowserAutofillChip.kt", "")
    # D1
    r = subprocess.run(["node", "autofill_js_harness.js", os.path.join(root, ASSETS)], capture_output=True, text=True)
    lines = r.stdout.splitlines()
    ok(r.returncode == 0 and len(lines) >= 40, "D1 the engine runs under node", r.stderr[-300:])
    for l in lines:
        ok(l.startswith("ok "), "D1 " + l[4:], l)
    # D2
    code = re.sub(r"(?m)^\s*//.*$", "", js)
    ok("setAttribute" not in code and "removeAttribute" not in code, "D2 the engine never rewrites a page attribute (autocomplete stays as the page set it)")
    ok("console." not in code, "D2 the engine logs nothing")
    ok("innerHTML" not in code, "D2 the engine never writes markup")
    ok(re.search(r"Object\.getOwnPropertyDescriptor\(proto, 'value'\)", code) is not None, "D2 values go through the native setter")
    ok(len(re.findall(r"bridge\('submitted'", code)) == 1 and "state.incognito" in code[code.find("function onSubmit"):code.find("function scan")], "D2 the only value that leaves the page is a submitted address, never from incognito")
    focus = code[code.find("function onFocus"):code.find("function nativeSet")]
    ok(".value" not in focus, "D2 the focus report reads no field value")
    # D3
    ok("View.IMPORTANT_FOR_AUTOFILL_YES" in frag and 'browserSettings.bool("autofill_enabled") == false' in frag, "D3 the WebView exposes its fields to Android autofill unless the user turned it off")
    ok("IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS" not in frag + dom, "D3 nothing hides the page's fields from the framework")
    ok("domAutofill.attach(this)" in frag and "domAutofill.inject(it, u, tab.isPrivate)" in frag, "D3 the engine is installed on every page with the tab's privacy")
    ok("BrowserAutofillChip(domAutofill.chip.value, domAutofill.save.value" in frag, "D3 the chip is drawn over the page")
    fill = dom[dom.find("fun fill(target"):dom.find("fun dismiss()")]
    ok("a.frameworkFilled()" in fill and "DomAutofillPlan.values(it.fields, target)" in fill and "DomAutofillPlan.snippetValues(it.fields, s)" in fill,
       "D3 a fill re-checks the framework's fields first and sends only the plan's values (a snippet only into its one field)")
    ok('if (incognito) return "Autofill…"' in dom, "D3 a private tab's chip names no profile")
    ok("if (chip.snippets.isNotEmpty() || chip.incognito || chip.targets.size > 1) open = true" in chip, "D3 a private tab fills only after an explicit pick, a snippet only after one too")
    sub = dom[dom.find("fun submitted("):]
    ok("incognito" in sub and "offerSave()" in sub and "AutofillSotClient.installed(ctx)" in sub, "D3 no save offer from a private tab, with the setting off, or without Cloud Account")
    conf = dom[dom.find("fun confirmSave("):dom.find("private inner class Bridge")]
    ok("if (!yes || incognito) return" in conf and "AutofillSotClient.addAddress" in conf, "D3 an address is written only on the user's yes")
    ok("Fields.isSecretToken(f.key)" in dom, "D3 the host refuses a secret key too, whatever the page reported")
    for f, t in src.items():
        for m in re.finditer(r"Log\.[dviwe]\([^)]*\)", t):
            ok(not re.search(r"profile|value|address|email", m.group(0), re.I), "D3 no log line prints autofill data (%s)" % os.path.basename(f))
    man = open(os.path.join(root, "ac_cloud-browser/app/src/main/AndroidManifest.xml"), encoding="utf-8").read()
    ok("com.diegonmarcos.cloud.permission.AUTOFILL_PROFILE_READ" in man and "com.diegonmarcos.cloud.permission.AUTOFILL_PROFILE_WRITE" in man, "D3 the browser requests the SOT permissions")
    lib = open(os.path.join(root, SOT, "src/main/AndroidManifest.xml"), encoding="utf-8").read()
    ok(len(re.findall(r'android:protectionLevel="signature"', lib)) == 2, "D3 both SOT permissions are signature-level")
    # D4
    st = {s["key"]: s for s in build_json(root)["ui"]["browser"]["settings"]}
    for k in ("profile_fill", "offer_save_address"):
        ok(st.get(k, {}).get("type") == "bool" and st[k].get("default") is True, "D4 setting %s is declared" % k)
        ok('browserSettings.bool("%s")' % k in frag, "D4 setting %s is read" % k)

ENGINE = ASSETS + "/autofill_engine.js"
FRAG = LIB + "/BrowserHostFragment.kt"
DOM = LIB + "/DomAutofill.kt"
main("dom autofill", check, [
    ("the engine fills passwords", ENGINE, "if (type === 'password' || AUTH_AC.test(ac)", "if (false && (type === 'password' || AUTH_AC.test(ac))", "D1"),
    ("a rule beats the secret check", ENGINE, "    var ws = words(e);\n", "    var ws = words(e);\n    if (state.rules.length && matches(e, state.rules[0].field)) return { key: state.rules[0].key };\n", "D1"),
    ("the plain value setter", ENGINE, "if (desc && desc.set) desc.set.call(e, value); else e.value = value;", "e.value = value;", "D1"),
    ("no blur", ENGINE, "fire(e, 'input'); fire(e, 'change'); fire(e, 'blur');", "fire(e, 'input'); fire(e, 'change');", "D1"),
    ("login email filled", ENGINE, "!(login && (m.key === 'email'", "!(false && (m.key === 'email'", "D1"),
    ("an ID field is filled", ENGINE, "if (ID_WORDS.test(ws)) return { key: null, secret: true, id: true };", "", "D1"),
    ("a snippet joins the block", ENGINE, "m.key !== 'x-snippet' && !deferred.has(f)", "!deferred.has(f)", "D1"),
    ("no deference", ENGINE, "if (isFrameworkFilled(e)) deferred.add(e);", "", "D1"),
    ("incognito saves", ENGINE, "if (state.incognito || submittedOnce) return;", "if (submittedOnce) return;", "D1"),
    ("the engine turns autocomplete off", ENGINE, "  function fire(e, type) {", "  function fire(e, type) { e.setAttribute('autocomplete', 'off');", "D2"),
    ("the WebView hides fields from Vault", FRAG, "else View.IMPORTANT_FOR_AUTOFILL_YES", "else View.IMPORTANT_FOR_AUTOFILL_NO", "D3"),
    ("incognito names the profile", DOM, 'if (incognito) return "Autofill…"', 'if (incognito) return "Fill: " + profiles.first().title', "D3"),
    ("a save without the user", DOM, "if (!yes || incognito) return", "if (incognito) return", "D3"),
    ("a snippet inserted on the first tap", LIB + "/BrowserAutofillChip.kt", "if (chip.snippets.isNotEmpty() || chip.incognito", "if (chip.incognito", "D3"),
    ("a log line prints a value", DOM, "    fun dismiss() {", '    fun dump() = android.util.Log.d("x", "profile=" + profiles)\n    fun dismiss() {', "D3"),
])
PYEOF
