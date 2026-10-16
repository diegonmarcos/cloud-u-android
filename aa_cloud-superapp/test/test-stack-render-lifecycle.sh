#!/usr/bin/env bash
# Tester: deferred work in the aggregator stack cannot outlive the view it
# paints.
#
# THE CRASH THIS PINS (Samsung SM-G996B, Android 15, app 0.1.1-dev, f030922f):
#
#   java.lang.IllegalStateException: Fragment AggregatorStackFragment{456af3b}
#     not attached to a context.
#     at androidx.fragment.app.Fragment.requireContext(Fragment.java:977)
#     at androidx.fragment.app.Fragment.getResources(Fragment.java:1041)
#     at ...AggregatorStackFragment.dp(AggregatorStackFragment.kt:2704)
#     at ...AggregatorStackFragment.notifRowView(AggregatorStackFragment.kt:1847)
#     at ...AggregatorStackFragment.paintNtfyGroup(AggregatorStackFragment.kt:1342)
#     at ...AggregatorStackFragment.renderNtfyGroups$lambda$3$0$0(:1288)
#     at android.os.Handler.handleCallback(Handler.java:959)
#
# `dp()` is where it landed, not what was wrong. A channel poll ran on an
# unscoped executor and hopped its result back with `body.post`; the user left
# the page before it answered; the runnable ran anyway and repainted through a
# fragment that no longer had a Context.
#
# THE FALSE BELIEF, written down in the code that crashed: "View.post silently
# drops its runnable on a detached view". It does not. A view that was ATTACHED
# when post() was called has already handed the runnable to the ViewRootImpl's
# main-thread Handler — which is precisely the `Handler.handleCallback` frame
# above — and that Handler runs it whatever has become of the fragment. Only a
# view that was NOT yet attached queues its runnable for attach.
#
# So a null-guard at `dp()` is not the fix and is not what is asserted here.
# What is asserted is that deferred work which repaints is owned by the view's
# lifecycle, and that work which is NOT so owned cannot reach a Context.
#
# SHAPE (#773): the page is Compose. The structural cure the Views needed a rule
# for is now the framework's: a coroutine launched from a LaunchedEffect /
# produceState / rememberCoroutineScope() is cancelled when its composition is
# disposed, and the composition is disposed with the fragment's VIEW lifecycle
# (KitComposeFragment). So the rule becomes: every coroutine the page owns comes
# from one of those three, and NOTHING outside them (a Handler, a lifecycleScope
# - the FRAGMENT's, which outlives the view - GlobalScope, an executor) exists.
#
# Invariants:
#   T1  no bare Handler and no postDelayed in the aggregator files - both outlive them
#   T2  every coroutine is owned by the composition (LaunchedEffect / produceState /
#       rememberCoroutineScope), never by the FRAGMENT's lifecycleScope (it survives
#       onDestroyView, so it would crash identically) and never an unscoped one
#   T3  no ad-hoc executor in the notification render path
#   T4  THE GENERAL RULE: no deferred callback reaches a Context after the view is
#       gone - a fragment Context getter (requireContext / requireActivity /
#       resources) is read only while composing, and the one `.post` left is
#       guarded by isAdded and touches no Context
#   T5  the ntfy poll and the refresh watchdog - the two paths that did outlive
#       the view - are on the composition scope by name, and the poll is off-main
set -uo pipefail
APP="$(cd "$(dirname "$0")/.." && pwd)"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  ok: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }

DIR="$APP/app/src/main/java/com/diegonmarcos/superapp/launcher"
FRAG="$DIR/AggregatorStackFragment.kt"
[ -f "$FRAG" ] || { echo "  FAIL: $FRAG not found"; exit 1; }

# CODE-ONLY view. The KDoc above the fixed sites quotes the bug verbatim -
# `body.post`, `host.postDelayed`, `View.post` - so every assertion below must
# read the Kotlin and never the prose explaining it. One stripped copy per file,
# plus ALL.kt holding the page's whole code.
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
python3 - "$DIR" "$TMP" <<'STRIP'
import glob, io, os, re, sys
d, out = sys.argv[1], sys.argv[2]
allc = []
for f in sorted(glob.glob(os.path.join(d, "Aggregator*.kt"))):
    s = io.open(f, encoding="utf-8").read()
    s = re.sub(r"/\*.*?\*/", "", s, flags=re.S)
    s = re.sub(r"^[ \t]*//.*$", "", s, flags=re.M)
    s = re.sub(r"[ \t]//.*$", "", s, flags=re.M)
    io.open(os.path.join(out, os.path.basename(f)), "w", encoding="utf-8").write(s)
    allc.append(s)
io.open(os.path.join(out, "ALL.kt"), "w", encoding="utf-8").write("\n".join(allc))
STRIP
ALL="$TMP/ALL.kt"
CODE="$TMP/AggregatorStackFragment.kt"
STATE="$TMP/AggregatorState.kt"
PANELS="$TMP/AggregatorPanels.kt"
NOTIFY="$TMP/AggregatorNotify.kt"

echo "== nothing schedules work the view cannot cancel =="
# A bare Handler and postDelayed are both owned by the Looper, not by the view,
# so neither is cancelled by onDestroyView. The watchdog was the second armed
# copy of this crash: it fired finishRefresh, which builds its outcome line
# and so reached a Context.
if grep -qE '(android\.os\.)?Handler\(' "$ALL"; then
  bad "T1: a bare Handler is back - the Looper owns it, the view cannot cancel it"
else
  ok "T1: no bare Handler in the aggregator page"
fi
if grep -q 'postDelayed' "$ALL"; then
  bad "T1: postDelayed is back - it fires after onDestroyView with no way to stop it"
else
  ok "T1: no postDelayed in the aggregator page"
fi

echo "== every coroutine belongs to the composition, not to the fragment =="
# `lifecycleScope` on a Fragment lives until onDestroy, so it OUTLIVES the views it
# paints. A composition-owned scope is cancelled when the view's composition is
# disposed. Getting this wrong reproduces the crash exactly.
if grep -qE 'lifecycleScope' "$ALL"; then
  bad "T2: a lifecycleScope is back - the fragment's outlives its views (and viewLifecycleOwner's no longer is the owner here)"
else
  ok "T2: no lifecycleScope anywhere in the aggregator page"
fi
if grep -qP 'GlobalScope|(?<![A-Za-z])CoroutineScope\(|MainScope\(' "$ALL"; then
  bad "T2: an unscoped CoroutineScope is back - nothing cancels it"
else
  ok "T2: no unscoped CoroutineScope"
fi
# Counted, not thresholded: every launch site must be reachable only from a
# composition-owned scope. Each `.launch {` receiver is a name that is, in the
# same file, either a rememberCoroutineScope() or a CoroutineScope PARAMETER
# handed in by a composable (the state never creates one).
python3 - "$TMP" <<'OWN' && ok "T2: every .launch is on a composition-owned scope" || bad "T2: see above"
import glob, os, re, sys
bad = []
n = 0
for f in sorted(glob.glob(os.path.join(sys.argv[1], "Aggregator*.kt"))):
    if f.endswith("ALL.kt"): continue
    t = open(f).read()
    for m in re.finditer(r'(\w+)\.launch\s*\{', t):
        n += 1
        recv = m.group(1)
        owned = re.search(r'val\s+%s\s*=\s*rememberCoroutineScope\(\)' % recv, t) or \
                re.search(r'\b%s\s*:\s*CoroutineScope\b' % recv, t)
        if not owned:
            bad.append("%s: %s.launch has no rememberCoroutineScope()/CoroutineScope parameter behind it" % (os.path.basename(f), recv))
    # bare `launch {` (inside a LaunchedEffect / produceState / coroutineScope) is owned by that block.
for b in bad: print("    " + b)
if n == 0:
    print("    no launch sites found - either they are gone or this parser stopped matching them")
    sys.exit(1)
sys.exit(1 if bad else 0)
OWN
if grep -q 'LaunchedEffect' "$NOTIFY" && grep -q 'produceState' "$TMP/AggregatorFeeds.kt" && grep -q 'rememberCoroutineScope' "$CODE"; then
  ok "T2: the page's work hangs off LaunchedEffect / produceState / rememberCoroutineScope"
else
  bad "T2: the page lost one of LaunchedEffect / produceState / rememberCoroutineScope - something else owns its work"
fi

echo "== the notification render path owns no threads of its own =="
# The cloud dashboard's status-light pings used to run on an 8-thread executor;
# they are a Semaphore-gated LaunchedEffect now. What must not come back is an
# executor anywhere on the page: it would outlive the composition.
if grep -q 'Executors\.' "$ALL"; then
  bad "T3: an executor is back on the aggregator page - nothing cancels it on teardown"
else
  ok "T3: the aggregator page schedules nothing the view does not own"
fi
if grep -q 'Semaphore(8)' "$PANELS" && grep -q 'LaunchedEffect(host, port)' "$PANELS"; then
  ok "T3: the status-light pings are bounded (8) and composition-scoped"
else
  bad "T3: the status-light pings lost their bound or their scope"
fi

echo "== THE GENERAL RULE: no deferred callback can reach a Context =="
python3 - "$TMP" <<'CLOSURE' && ok "T4: no deferred callback reaches a Context" || bad "T4: see above"
import glob, io, os, re, sys

def block_end(s, open_idx):
    depth = 0
    for i in range(open_idx, len(s)):
        if s[i] == '{': depth += 1
        elif s[i] == '}':
            depth -= 1
            if depth == 0: return i + 1
    return len(s)

problems = []

# (a) the FRAGMENT's own Context getters are read only while COMPOSING: inside
# Content()/palette()/onCreate - never in a lambda a Handler/animator/coroutine
# runs later. `resources` with no receiver is Fragment.getResources().
frag = io.open(os.path.join(sys.argv[1], "AggregatorStackFragment.kt"), encoding="utf-8").read()
spans = []
for m in re.finditer(r'\bfun\s+(\w+)\s*\(', frag):
    brace = frag.find('{', m.end())
    spans.append((m.start(), block_end(frag, brace), m.group(1)))
CTX = re.compile(r'(?<![\w.])resources\b|requireContext\(|requireActivity\(')
for m in CTX.finditer(frag):
    host = [sp for sp in spans if sp[0] <= m.start() < sp[1]]
    name = host[-1][2] if host else "<class body>"
    if name not in ("Content", "palette"):
        problems.append("AggregatorStackFragment.kt: a fragment Context getter (%s) in %s() - it can run after onDestroyView" % (m.group(0), name))

# (b) nothing but the fragment may reach the FRAGMENT's context at all: the state,
# the model and the bodies hold the Context they were handed, which a destroyed
# fragment cannot invalidate.
for f in sorted(glob.glob(os.path.join(sys.argv[1], "Aggregator*.kt"))):
    b = os.path.basename(f)
    if b in ("AggregatorStackFragment.kt", "ALL.kt"): continue
    t = io.open(f, encoding="utf-8").read()
    if re.search(r'requireContext\(|requireActivity\(', t):
        problems.append("%s reaches a fragment Context getter" % b)

# (c) every `.post {` / `.postDelayed {` / `.withEndAction {` left on the page is
# guarded by the fragment's isAdded and calls nothing that touches a Context.
found = 0
for f in sorted(glob.glob(os.path.join(sys.argv[1], "Aggregator*.kt"))):
    b = os.path.basename(f)
    if b == "ALL.kt": continue
    t = io.open(f, encoding="utf-8").read()
    for m in re.finditer(r'\.(post|postDelayed|withEndAction)\s*\{', t):
        found += 1
        bopen = t.index('{', m.end() - 1)
        body = t[bopen:block_end(t, bopen)]
        if 'isAdded' not in body:
            problems.append("%s: a .%s callback with no isAdded guard" % (b, m.group(1)))
        if re.search(r'\bdp\(|\bresources\b|requireContext\(|LocalContext', body):
            problems.append("%s: a .%s callback reaches a Context" % (b, m.group(1)))
if found == 0:
    problems.append("found no .post callback at all - the embedded-fragment commit moved or this parser stopped matching it")

for p in problems: print("    " + p)
sys.exit(1 if problems else 0)
CLOSURE

echo "== the two paths that actually crashed are scoped, by name =="
# T4 is the rule; these two are the evidence. Both were the same defect: work
# posted to a Handler that survived the fragment.
if grep -q 'LaunchedEffect(i, state.pollEpoch' "$NOTIFY" && grep -q 'state.pollPanel(i, panel)' "$NOTIFY"; then
  ok "T5: the ntfy poll is driven by a LaunchedEffect - cancelled with the composition"
else
  bad "T5: the ntfy poll no longer runs from a LaunchedEffect - the crash is back"
fi
if grep -q 'fun startRefresh(scope: CoroutineScope)' "$STATE" && grep -q 'scope.launch' \
     <<<"$(awk '/fun startRefresh/,/^    }$/' "$STATE")" \
   && grep -q 'st.startRefresh(scope)' "$CODE" && grep -q 'val scope = rememberCoroutineScope()' "$CODE"; then
  ok "T5: the refresh watchdog is launched on the composition's scope"
else
  bad "T5: the watchdog is no longer composition-scoped - it fires finishRefresh into a dead Context"
fi
# The poll must still run OFF the main thread - a lifecycle fix that moved a
# blocking socket read onto the UI thread would trade a crash for an ANR.
if grep -q 'withContext(Dispatchers.IO) { AggregatorModel.ntfyPoller(slots) }' \
     <<<"$(awk '/suspend fun pollPanel/,/^    }$/' "$STATE")"; then
  ok "T5: the poll itself still runs off the main thread"
else
  bad "T5: the network poll is on the main thread now - an ANR instead of a crash"
fi

echo
echo "PASS=$PASS FAIL=$FAIL"
[ "$FAIL" -eq 0 ]
