#!/usr/bin/env bash
# cloud-mail is HTML end to end: static proof that NO send path builds a message without an html part,
# that the composer's body is the HTML editor, and that the reader renders the html part whenever there
# is one -- WITHOUT a gradle build (this runner cannot build) and without a device. Same shape as
# test-mail-html.sh, which owns the sanitiser; this one owns "there is no text-only mode".
#
# Why it exists (third complaint, 2026): the IMAP/SMTP builder had been made multipart/alternative, but
# the JMAP Email/set builder, the notification quick reply, the calendar/unsubscribe/receipt mail and a
# scheduled row stored without html all still produced text/plain-only messages, and the composer showed
# a plain text field. Each check below is one of those doors.
#
#   E1  JMAP Email/set (send AND draft) always declares an htmlBody part, with a value
#   E2  the IMAP builder, the outbox row and the local-draft builder always carry html
#   E3  every OutgoingMessage( in main code names its html
#   E4  the notification quick reply hands the outbox an html body
#   E5  the composer's body is the HTML editor page, not a text field
#   E6  the reader renders the html part before it ever looks at the text one
#   E7  an attached message is shown as html too
#   M*  MUTATIONS: each of the above, broken on purpose, in memory, must be CAUGHT
set -uo pipefail
APP="$(cd "$(dirname "$0")/.." && pwd)"
export APP
python3 - <<'PY'
import os, re, sys

APP = os.environ['APP']
FILES = {
    'jmap': 'core/jmap/src/main/kotlin/app/sterna/core/jmap/JmapClient.kt',
    'repo': 'core/data/src/main/kotlin/app/sterna/core/data/mail/MailRepository.kt',
    'drafts': 'core/data/src/main/kotlin/app/sterna/core/data/mail/LocalDraftSave.kt',
    'vm': 'app/src/main/kotlin/app/sterna/ui/compose/ComposeViewModel.kt',
    'screen': 'app/src/main/kotlin/app/sterna/ui/compose/ComposeScreen.kt',
    'receiver': 'app/src/main/kotlin/app/sterna/push/NotificationActionReceiver.kt',
    'reader': 'app/src/main/kotlin/app/sterna/ui/message/ReaderBody.kt',
    'attached': 'app/src/main/kotlin/app/sterna/ui/message/AttachedMessage.kt',
}
MAIN = {k: open(os.path.join(APP, v), encoding='utf-8').read() for k, v in FILES.items()}


def func_body(src, signature):
    """Text from `signature` to the next top-level `\n}` / `\n    }` closing at the same indent."""
    at = src.find(signature)
    if at < 0:
        return ''
    indent = len(src[src.rfind('\n', 0, at) + 1:at]) - len(src[src.rfind('\n', 0, at) + 1:at].lstrip())
    end = src.find('\n' + ' ' * indent + '}', at)
    return src[at:end if end > 0 else len(src)]


def audit(s, all_main_sources):
    p = []
    jmap = s['jmap']
    # E1: both Email/set builders declare the html part unconditionally, with a fallback value.
    send = func_body(jmap, 'suspend fun sendEmail(')
    save = func_body(jmap, 'suspend fun saveDraft(')
    for name, body in (('sendEmail', send), ('saveDraft', save)):
        if not body:
            p.append(f'E1 {name} not found'); continue
        decl = re.search(r'(if \(htmlBody != null\)[^\n]*\n?\s*)?putJsonArray\("htmlBody"\)', body)
        if not decl or decl.group(1):
            p.append(f'E1 {name} declares htmlBody only conditionally (or not at all)')
        if 'htmlBody ?: htmlOfPlainText(textBody)' not in body:
            p.append(f'E1 {name} has no html value to fall back on')
        if re.search(r'if \(htmlBody != null\)', body):
            p.append(f'E1 {name} still branches on htmlBody == null')
    # E2
    if 'html = html ?: plainTextToHtml(body),' not in s['repo']:
        p.append('E2 MailRepository.outgoing() can build a message with no html')
    if 'htmlBody = if (encrypted) null else htmlBody ?: plainTextToHtml(body),' not in s['repo']:
        p.append('E2 enqueueSend() can queue a row with no html')
    if 'html = row.htmlBody ?: plainTextToHtml(row.textBody),' not in s['drafts']:
        p.append('E2 localDraftOutgoing() can build a draft with no html')
    # E3
    for name, src in all_main_sources.items():
        for m in re.finditer(r'(?<![A-Za-z])OutgoingMessage\(', src):
            before = src[max(0, m.start() - 12):m.start()]
            if 'class ' in before:
                continue
            window = src[m.end():m.end() + 900]
            close = window.find('\n        )') if '\n        )' in window else len(window)
            call = window[:close if close > 0 else len(window)]
            if not re.search(r'\bhtml\s*=', call):
                p.append(f'E3 {name}: an OutgoingMessage( call names no html')
    # E4
    recv = func_body(s['receiver'], 'private suspend fun send(')
    if 'htmlBody = bodies.html,' not in recv:
        p.append('E4 the quick reply queues its message without an html body')
    # E5
    if 'HtmlBodyPane(' not in s['screen']:
        p.append('E5 the composer no longer composes the HTML editor')
    if re.search(r'BasicTextField\(\s*\n?\s*value = body,', s['screen']):
        p.append('E5 the composer has a plain text field for the body again')
    # E6
    rb = func_body(s['reader'], 'internal fun readerBody(')
    h, t = rb.find('htmlContent()'), rb.find('textContent()')
    if h < 0 or t < 0 or h > t:
        p.append('E6 the reader looks at the text part before (or without) the html part')
    if 'const val PLAIN_TEXT_DEFAULT = false' not in open(os.path.join(APP, 'core/data/src/main/kotlin/app/sterna/core/data/settings/SettingsRepository.kt'), encoding='utf-8').read():
        p.append('E6 the reader defaults to plain text')
    # E7
    if 'html = parsed.html' not in s['attached']:
        p.append('E7 an attached message is no longer given its html')
    return p


def all_main():
    out = {}
    for root, _, files in os.walk(APP):
        if '/src/main/' not in root + '/' or '/build/' in root:
            continue
        for f in files:
            if f.endswith('.kt'):
                path = os.path.join(root, f)
                out[os.path.relpath(path, APP)] = open(path, encoding='utf-8').read()
    return out


sources = all_main()
fail = 0
problems = audit(MAIN, sources)
if problems:
    for x in problems:
        print('  FAIL:', x)
    fail += len(problems)
else:
    print('  ok: E1-E7 no send path is text-only, the composer and the reader are HTML')

# ── mutations: every door, closed on purpose, must be reported ──────────────────────────────────────
def mutate(key, old, new, count=1):
    s = dict(MAIN)
    if s[key].count(old) < 1:
        print(f'  FAIL: mutation target missing in {key}: {old[:60]}')
        return None
    s[key] = s[key].replace(old, new, count)
    return s


MUTATIONS = [
    ('M1 JMAP send: htmlBody only when given', 'jmap',
     'putJsonArray("htmlBody") {\n                                        addJsonObject { put("partId", "htmlbody")',
     'if (htmlBody != null) putJsonArray("htmlBody") {\n                                        addJsonObject { put("partId", "htmlbody")'),
    ('M2 JMAP draft: no html fallback', 'jmap',
     'putJsonObject("html") { put("value", htmlBody ?: htmlOfPlainText(textBody)) }',
     'putJsonObject("html") { put("value", htmlBody ?: "") }'),
    ('M3 IMAP builder: html passed through as given', 'repo',
     'html = html ?: plainTextToHtml(body),', 'html = html,'),
    ('M4 outbox row: html as given', 'repo',
     'htmlBody = if (encrypted) null else htmlBody ?: plainTextToHtml(body),',
     'htmlBody = if (encrypted) null else htmlBody,'),
    ('M5 local draft: html as stored', 'drafts',
     'html = row.htmlBody ?: plainTextToHtml(row.textBody),', 'html = row.htmlBody,'),
    ('M6 quick reply: no html body', 'receiver', 'htmlBody = bodies.html,', ''),
    ('M7 PGP inner entity: no html', 'vm', 'body = textBody, html = htmlBody,', 'body = textBody,'),
    ('M8 composer: a text field for the body', 'screen', 'HtmlBodyPane(',
     'BasicTextField(\n                value = body,\n            ) ; HtmlBodyPane2('),
    ('M9 reader: text part first', 'reader',
     'email.htmlContent()?.takeIf { it.isNotBlank() }', 'email.textContent()?.takeIf { it.isNotBlank() }'),
    ('M10 attached message: text only', 'attached', 'html = parsed.html', 'html = null // parsed.html'),
]
for label, key, old, new in MUTATIONS:
    mutated = mutate(key, old, new)
    if mutated is None:
        fail += 1
        continue
    srcs = dict(sources)
    for name in list(srcs):
        if os.path.normpath(os.path.join(APP, name)) == os.path.normpath(os.path.join(APP, FILES[key])):
            srcs[name] = mutated[key]
    caught = audit(mutated, srcs)
    if caught:
        print(f'  ok: {label} -> caught ({caught[0][:70]})')
    else:
        print(f'  FAIL: {label} -> NOT caught: the tester cannot see this door')
        fail += 1

print(f'== {fail} failed ==')
sys.exit(1 if fail else 0)
PY
