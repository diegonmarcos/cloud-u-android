#!/usr/bin/env bash
# Tester: the manifest declares every component Android requires of a default SMS app (ROLE_SMS),
# each with the permission that stops anything but the platform from calling it, and each one is a
# real class. Without all four, the system never offers the role and Cloud Dialer silently stays a
# read-only SMS viewer.
#
#   1. BroadcastReceiver  SMS_DELIVER        permission BROADCAST_SMS
#   2. BroadcastReceiver  WAP_PUSH_DELIVER   permission BROADCAST_WAP_PUSH, mime application/vnd.wap.mms-message
#   3. Service            RESPOND_VIA_MESSAGE permission SEND_RESPOND_VIA_MESSAGE, schemes sms/smsto/mms/mmsto
#   4. Activity           SENDTO and SEND    schemes sms/smsto/mms/mmsto, category DEFAULT
#
# MUTATION: after the real manifest passes, each requirement is broken in a throwaway copy, one at
# a time, and the check must go red on every one.
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"
command -v python3 >/dev/null 2>&1 || { echo "ERROR: python3 required" >&2; exit 2; }
exec python3 -I - "$APP" <<'PY'
import os, re, shutil, sys, tempfile
import xml.etree.ElementTree as ET

APP = sys.argv[1]
A = "{http://schemas.android.com/apk/res/android}"
MANIFEST = "app/src/main/AndroidManifest.xml"
SRC = "app/src/main/kotlin/org/fossify/phone/"
SCHEMES = {"sms", "smsto", "mms", "mmsto"}
PERMS = ["READ_SMS", "SEND_SMS", "RECEIVE_SMS", "RECEIVE_MMS", "RECEIVE_WAP_PUSH"]

def filters(el):
    for f in el.findall("intent-filter"):
        yield (
            {a.get(A + "name") for a in f.findall("action")},
            {c.get(A + "name") for c in f.findall("category")},
            {d.get(A + "scheme") for d in f.findall("data") if d.get(A + "scheme")},
            {d.get(A + "mimeType") for d in f.findall("data") if d.get(A + "mimeType")},
        )

def component(app, tag, suffix):
    for el in app.findall(tag):
        if el.get(A + "name", "").endswith(suffix):
            return el
    return None

def check(root):
    bad = []
    try:
        tree = ET.parse(os.path.join(root, MANIFEST)).getroot()
    except (OSError, ET.ParseError) as e:
        return ["manifest unreadable: %s" % e]
    app = tree.find("application")
    uses = {p.get(A + "name") for p in tree.findall("uses-permission")}
    for p in PERMS:
        if "android.permission." + p not in uses:
            bad.append("missing <uses-permission> " + p)

    def need(tag, suffix, label, permission, action, schemes=None, mime=None, category=None, exported=True):
        el = component(app, tag, suffix)
        if el is None:
            bad.append("%s: no <%s> named *%s" % (label, tag, suffix)); return
        if exported and el.get(A + "exported") != "true":
            bad.append("%s: %s is not exported" % (label, suffix))
        if permission and el.get(A + "permission") != "android.permission." + permission:
            bad.append("%s: %s is not protected by %s" % (label, suffix, permission))
        hits = [f for f in filters(el) if action in f[0]]
        if not hits:
            bad.append("%s: %s has no intent-filter for %s" % (label, suffix, action)); return
        ok = False
        for acts, cats, sch, mimes in hits:
            if schemes is not None and not schemes <= sch: continue
            if mime is not None and mime not in mimes: continue
            if category is not None and category not in cats: continue
            ok = True
        if not ok:
            bad.append("%s: the %s filter of %s lacks its schemes / mime type / category" % (label, action.rsplit(".", 1)[-1], suffix))
        # the declared class exists
        name = el.get(A + "name")
        rel = name.lstrip(".").replace(".", "/") if name.startswith(".") else name.replace("org.fossify.phone.", "").replace(".", "/")
        path = os.path.join(root, SRC, rel + ".kt")
        found = os.path.isfile(path)
        if not found:
            # several receivers share a file: look for the class declaration anywhere in the package
            cls = name.rsplit(".", 1)[-1]
            for d, _, fs in os.walk(os.path.join(root, SRC)):
                for f in fs:
                    if f.endswith(".kt") and re.search(r"\bclass\s+%s\b" % cls, open(os.path.join(d, f), encoding="utf-8").read()):
                        found = True
        if not found:
            bad.append("%s: class %s is declared in the manifest but does not exist" % (label, name))

    need("receiver", ".SmsDeliverReceiver", "SMS_DELIVER", "BROADCAST_SMS", "android.provider.Telephony.SMS_DELIVER")
    need("receiver", ".MmsWapPushReceiver", "WAP_PUSH_DELIVER", "BROADCAST_WAP_PUSH", "android.provider.Telephony.WAP_PUSH_DELIVER", mime="application/vnd.wap.mms-message")
    need("service", ".RespondViaMessageService", "RESPOND_VIA_MESSAGE", "SEND_RESPOND_VIA_MESSAGE", "android.intent.action.RESPOND_VIA_MESSAGE", schemes=SCHEMES, category="android.intent.category.DEFAULT")
    need("activity", ".SmsComposeActivity", "SENDTO", None, "android.intent.action.SENDTO", schemes=SCHEMES, category="android.intent.category.DEFAULT")
    need("activity", ".SmsComposeActivity", "SEND", None, "android.intent.action.SEND", schemes=SCHEMES, category="android.intent.category.DEFAULT")
    # the role is requested through RoleManager with a prompt, and falls back to ACTION_CHANGE_DEFAULT
    role = open(os.path.join(root, SRC, "sms/SmsRole.kt"), encoding="utf-8").read() if os.path.isfile(os.path.join(root, SRC, "sms/SmsRole.kt")) else ""
    if "ROLE_SMS" not in role or "createRequestRoleIntent" not in role or "ACTION_CHANGE_DEFAULT" not in role:
        bad.append("SmsRole.kt does not request ROLE_SMS through RoleManager (with the pre-Q ACTION_CHANGE_DEFAULT fallback)")
    return bad

PASS = FAIL = 0
def ok(m):
    global PASS; PASS += 1; print("  PASS: " + m)
def no(m):
    global FAIL; FAIL += 1; print("  FAIL: " + m)

real = check(APP)
if real:
    for b in real: no(b)
else:
    ok("every ROLE_SMS component is declared, protected and backed by a class")

def clone():
    tmp = tempfile.mkdtemp(prefix="dialer-smsrole-")
    for k in ("app/src/main",):
        shutil.copytree(os.path.join(APP, k), os.path.join(tmp, k))
    return tmp

def mutate(tmp, fn):
    p = os.path.join(tmp, MANIFEST)
    old = open(p, encoding="utf-8").read()
    new = fn(old)
    open(p, "w", encoding="utf-8").write(new)
    return old != new

def drop_block(tag, name):
    return lambda s: re.sub(r"\s*<%s\s+android:name=\"%s\".*?</%s>" % (tag, re.escape(name), tag), "", s, count=1, flags=re.S)

MUT = [
    ("the SMS_DELIVER receiver is removed", drop_block("receiver", ".sms.SmsDeliverReceiver")),
    ("the SMS_DELIVER receiver loses BROADCAST_SMS", lambda s: s.replace("android.permission.BROADCAST_SMS", "android.permission.SOMETHING_ELSE")),
    ("the WAP_PUSH_DELIVER receiver is removed", drop_block("receiver", ".sms.mms.MmsWapPushReceiver")),
    ("the WAP push loses its MMS mime type", lambda s: s.replace("application/vnd.wap.mms-message", "application/other")),
    ("the WAP push loses BROADCAST_WAP_PUSH", lambda s: s.replace("android.permission.BROADCAST_WAP_PUSH", "android.permission.SOMETHING_ELSE")),
    ("the RESPOND_VIA_MESSAGE service is removed", drop_block("service", ".sms.RespondViaMessageService")),
    ("the service loses SEND_RESPOND_VIA_MESSAGE", lambda s: s.replace("android.permission.SEND_RESPOND_VIA_MESSAGE", "android.permission.SOMETHING_ELSE")),
    ("the compose activity is removed", drop_block("activity", ".sms.SmsComposeActivity")),
    ("SENDTO is no longer handled", lambda s: s.replace('<action android:name="android.intent.action.SENDTO" />', "")),
    ("the mmsto scheme is dropped", lambda s: s.replace('<data android:scheme="mmsto" />', "")),
    ("the compose activity stops being exported", lambda s: re.sub(r'(android:name="\.sms\.SmsComposeActivity"\s+android:exported=")true', r"\1false", s)),
    ("RECEIVE_WAP_PUSH is no longer requested", lambda s: s.replace("android.permission.RECEIVE_WAP_PUSH", "android.permission.NOT_REQUESTED")),
    ("SEND_SMS is no longer requested", lambda s: s.replace('<uses-permission android:name="android.permission.SEND_SMS" />', "")),
]
for label, fn in MUT:
    tmp = clone()
    try:
        if not mutate(tmp, fn):
            no("mutation `%s` changed nothing (the tester's anchor is stale)" % label)
        elif check(tmp):
            ok("mutation caught: " + label)
        else:
            no("mutation NOT caught: " + label)
    finally:
        shutil.rmtree(tmp, ignore_errors=True)

tmp = clone()
try:
    # a declared class that does not exist
    os.remove(os.path.join(tmp, SRC, "sms/SmsReceivers.kt"))
    if check(tmp):
        ok("mutation caught: the receiver / service classes are deleted")
    else:
        no("mutation NOT caught: the receiver / service classes are deleted")
finally:
    shutil.rmtree(tmp, ignore_errors=True)

print("== RESULT: %d passed, %d failed ==" % (PASS, FAIL))
sys.exit(1 if FAIL else 0)
PY
