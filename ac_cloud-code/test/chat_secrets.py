"""Static proof that cloud-code Chat's OpenRouter token is stored encrypted and never logged,
never handed to the WebView and never sent anywhere but OpenRouter. Run by test/test-chat.sh on
the real tree AND on mutated scratch copies (each must turn it red).

usage: chat_secrets.py <app-root>   prints `CHECK ok|bad <text>` lines
"""
import os
import re
import sys

root = sys.argv[1]
plug = os.path.join(root, "src", "plugins", "cloudchat")
out = []


def check(ok, text):
    out.append("CHECK %s %s" % ("ok" if ok else "bad", text))


def code(path):
    text = open(path, encoding="utf-8", errors="replace").read()
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return re.sub(r"//[^\n]*", "", text)


java = {f: code(os.path.join(plug, "src", f)) for f in sorted(os.listdir(os.path.join(plug, "src"))) if f.endswith(".java")}
sec = java.get("ChatSecrets.java", "")
plugin = java.get("CloudChatPlugin.java", "")

check(re.search(r"EncryptedSharedPreferences\.create\(\s*app,\s*PREFS,\s*key,", sec) is not None
      and "PrefValueEncryptionScheme.AES256_GCM" in sec and "PrefKeyEncryptionScheme.AES256_SIV" in sec
      and re.search(r"new MasterKey\.Builder\(app\)\.setKeyScheme\(MasterKey\.KeyScheme\.AES256_GCM\)", sec) is not None,
      "the token is kept in EncryptedSharedPreferences under a Keystore AES-256-GCM master key")
check(re.search(r"getSharedPreferences\(", sec) is None and "putString(TOKEN" in sec,
      "it is written only through the encrypted store (no plain SharedPreferences)")
logs = [f for f, t in java.items() if re.search(r"\bLog\.[a-z]+\(|System\.(out|err)\.|printStackTrace\(|Logger\.", t)]
check(not logs, "no plugin source logs anything (%s)" % (", ".join(logs) or "none"))
leaks = re.findall(r"\.put\(\s*\"[^\"]+\"\s*,\s*(local|token|t|secrets\.resolve\(\)|secrets\.local\(\)|secrets\.fromAccount\(\))\s*\)", plugin)
check(not leaks, "no plugin reply carries the token itself (only its mask): %s" % (leaks or "none"))
check("ChatSecrets.mask(local)" in plugin and "TokenMask.of(token)" in sec, "the status the page receives carries the mask")
actions = set(re.findall(r'case\s+"(\w+)"\s*:', plugin))
check(not any(re.search(r"get|reveal|read", a, re.I) and "token" in a.lower() for a in actions),
      "no plugin action returns the token (%s)" % ", ".join(sorted(a for a in actions if "token" in a.lower())))
check(re.search(r'!"https"\.equals\(scheme\)\s*\|\|\s*!OPENROUTER_HOST\.equals\(u\.getHost\(\)\)\) throw new SecurityException', plugin) is not None
      and 'OPENROUTER_HOST = "openrouter.ai"' in plugin,
      "the token is only ever sent to https://openrouter.ai")
check(re.search(r'"authorization"\.equalsIgnoreCase\(k\)\)\s*continue', plugin) is not None,
      "a page-supplied Authorization header is dropped")

web = []
for d, _, fs in os.walk(os.path.join(root, "src", "cloud")):
    for f in fs:
        if f.endswith((".js", ".mjs", ".ts")):
            web.append(os.path.join(d, f))
web.append(os.path.join(plug, "www", "cloudchat.js"))
bad_store, bad_log = [], []
for p in web:
    t = code(p)
    rel = os.path.relpath(p, root)
    if re.search(r"(localStorage|sessionStorage|indexedDB)[^\n;]*token", t, re.I) or re.search(r"store\(\s*['\"][^'\"]*token", t, re.I):
        bad_store.append(rel)
    if re.search(r"console\.(log|info|warn|error|debug)\([^)]*(token|tokenSet|input\.value)", t, re.I):
        bad_log.append(rel)
check(not bad_store, "no web code keeps a token in browser storage (%s)" % (", ".join(bad_store) or "none"))
check(not bad_log, "no web code logs a token (%s)" % (", ".join(bad_log) or "none"))
prof = code(os.path.join(root, "src", "cloud", "profile.js"))
check(re.search(r'const v = input\.value;\s*input\.value = "";', prof) is not None and 'type: "password"' in prof,
      "Profile & Config takes the token in a password field and clears it before handing it to the plugin")

print("\n".join(out))
