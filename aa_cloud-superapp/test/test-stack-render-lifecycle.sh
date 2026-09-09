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
# Invariants:
#   T1  no bare Handler and no postDelayed in the fragment — both outlive it
#   T2  every coroutine is on viewLifecycleOwner's scope, never the FRAGMENT's
#       (the fragment's own scope survives onDestroyView, so it would crash
#       identically) and never an unscoped one
#   T3  no ad-hoc executor in the notification render path
#   T4  THE GENERAL RULE: no `.post`/animation callback anywhere in the file
#       reaches a Context, computed as the transitive closure of this
#       fragment's own functions over `resources` / requireContext /
#       requireActivity — this is the assertion that would have caught the
#       crash before it shipped, and it is checked against the CODE, not
#       against the list of paths that happen to exist today
#   T5  the ntfy poll and the refresh watchdog — the two paths that did outlive
#       the view — are on the view scope by name
set -uo pipefail
APP="$(cd "$(dirname "$0")/.." && pwd)"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  ok: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }

AGG="$APP/app/src/main/java/com/diegonmarcos/superapp/launcher/AggregatorStackFragment.kt"
[ -f "$AGG" ] || { echo "  FAIL: $AGG not found"; exit 1; }

# CODE-ONLY view. The KDoc above the fixed sites quotes the bug verbatim —
# `body.post`, `host.postDelayed`, `View.post` — so every assertion below must
# read the Kotlin and never the prose explaining it.
CODE="$(mktemp)"; trap 'rm -f "$CODE"' EXIT
python3 - "$AGG" "$CODE" <<'STRIP'
import io, re, sys
s = io.open(sys.argv[1], encoding="utf-8").read()
s = re.sub(r"/\*.*?\*/", "", s, flags=re.S)
s = re.sub(r"^[ \t]*//.*$", "", s, flags=re.M)
s = re.sub(r"[ \t]//.*$", "", s, flags=re.M)
io.open(sys.argv[2], "w", encoding="utf-8").write(s)
STRIP

echo "== nothing schedules work the view cannot cancel =="
# A bare Handler and postDelayed are both owned by the Looper, not by the view,
# so neither is cancelled by onDestroyView. The watchdog was the second armed
# copy of this crash: it fired finishRefresh, which builds its outcome line
# with stateLine, which calls dp().
if grep -qE '(android\.os\.)?Handler\(' "$CODE"; then
  bad "T1: a bare Handler is back — the Looper owns it, the view cannot cancel it"
else
  ok "T1: no bare Handler in the fragment"
fi
if grep -q 'postDelayed' "$CODE"; then
  bad "T1: postDelayed is back — it fires after onDestroyView with no way to stop it"
else
  ok "T1: no postDelayed in the fragment"
fi

echo "== every coroutine belongs to the VIEW, not to the fragment =="
# The distinction is the whole point and it is one identifier wide:
# `lifecycleScope` on a Fragment lives until onDestroy, so it OUTLIVES the
# views it paints; `viewLifecycleOwner.lifecycleScope` is cancelled in
# onDestroyView. Getting this wrong reproduces the crash exactly.
total_launch=$(grep -c '\.launch {' "$CODE")
view_launch=$(grep -c 'viewLifecycleOwner\.lifecycleScope\.launch {' "$CODE")
# Counted, not thresholded: a fixed minimum would make this fail whenever a
# coroutine is legitimately removed, which is a failure for the wrong reason.
# The invariant is the RATIO — every launch site, whatever the number.
if [ "$total_launch" -eq "$view_launch" ] && [ "$view_launch" -gt 0 ]; then
  ok "T2: all $view_launch coroutines are on viewLifecycleOwner's scope"
else
  bad "T2: $total_launch launch sites but only $view_launch on viewLifecycleOwner — the rest outlive the views they paint"
fi
if grep -qE 'GlobalScope|CoroutineScope\(' "$CODE"; then
  bad "T2: an unscoped CoroutineScope is back — nothing cancels it"
else
  ok "T2: no unscoped CoroutineScope"
fi

echo "== the notification render path owns no threads of its own =="
# The cloud dashboard's status-light pool is a different case and stays (T4
# proves its callback touches no Context). What must not come back is an
# executor in the path that paints notification ROWS, because that path calls
# dp() on every row it draws.
if awk '/private fun renderNtfyGroups/,/^    private fun paintNtfyGroup/' "$CODE" \
     | grep -q 'Executors\.'; then
  bad "T3: renderNtfyGroups owns an executor again — nothing cancels it on teardown"
else
  ok "T3: renderNtfyGroups schedules nothing the view does not own"
fi

echo "== THE GENERAL RULE: no unscoped callback can reach a Context =="
python3 - "$CODE" <<'CLOSURE' && ok "T4: no .post/animation callback reaches a Context" || bad "T4: see above"
import io, re, sys

src = io.open(sys.argv[1], encoding="utf-8").read()

def block_end(s, open_idx):
    """Index just past the {...} block whose opening brace is at open_idx."""
    depth = 0
    for i in range(open_idx, len(s)):
        if s[i] == '{': depth += 1
        elif s[i] == '}':
            depth -= 1
            if depth == 0: return i + 1
    return len(s)

def paren_close(s, open_idx):
    depth = 0
    for i in range(open_idx, len(s)):
        if s[i] == '(': depth += 1
        elif s[i] == ')':
            depth -= 1
            if depth == 0: return i
    return len(s)

# ── every function this fragment declares: body, span and parameter names ──
# The parameters matter. `flipRead(v, r, paint: (Boolean) -> Unit, toRight)`
# takes a repaint LAMBDA called `paint`, and this file separately declares a
# local `fun paint()` inside filterRow that does reach a Context. Treating the
# two as one name is how a text-level closure invents a path that is not there.
funcs, spans = {}, []
for m in re.finditer(r'\bfun\s+(\w+)\s*\(', src):
    name = m.group(1)
    pclose = paren_close(src, m.end() - 1)
    params = set(re.findall(r'(\w+)\s*:', src[m.end():pclose]))
    brace = src.find('{', pclose)
    eq = src.find('=', pclose)
    stop = src.find('\n\n', pclose)
    if eq != -1 and (brace == -1 or eq < brace) and (stop == -1 or eq < stop):
        end = stop if stop != -1 else len(src)          # expression body
    elif brace != -1:
        end = block_end(src, brace)
    else:
        continue
    funcs[name] = funcs.get(name, "") + src[pclose:end]
    spans.append((m.start(), end, name, params))

# ── seed: functions that reach a Context DIRECTLY ────────────────────────
# `resources` with no receiver is Fragment.getResources() → requireContext().
CTX = re.compile(r'(?<![\w.])resources\b|requireContext\(|requireActivity\(')
reaching = {n for n, b in funcs.items() if CTX.search(b)}

# ── close over calls until nothing new reaches a Context ─────────────────
changed = True
while changed:
    changed = False
    for n, b in funcs.items():
        if n in reaching: continue
        for callee in set(re.findall(r'\b(\w+)\s*\(', b)):
            if callee in reaching and callee != n:
                reaching.add(n); changed = True; break

if not reaching:
    print("    read no Context-reaching functions out of the fragment — the")
    print("    parser is asserting against nothing")
    sys.exit(1)

def enclosing(off):
    """Innermost declared function containing `off`, for parameter shadowing."""
    best = None
    for start, end, name, params in spans:
        if start <= off < end and (best is None or start > best[0]):
            best = (start, end, name, params)
    return best

# ── every deferred callback that is NOT lifecycle-scoped ─────────────────
# `.post {`, `.postDelayed {`, `.withEndAction {` — a Handler or an animator
# owns these, so onDestroyView cannot stop them.
bad_paths, found = [], 0
for m in re.finditer(r'\.(post|postDelayed|withEndAction)\s*\{', src):
    found += 1
    bopen = src.index('{', m.end() - 1)
    body = src[bopen:block_end(src, bopen)]
    host = enclosing(m.start())
    shadowed = host[3] if host else set()
    hit = sorted({c for c in re.findall(r'\b(\w+)\s*\(', body)
                  if c in reaching and c not in shadowed})
    if hit:
        line = src[:m.start()].count('\n') + 1
        recv = src[max(0, m.start() - 40):m.start()].split('\n')[-1].strip()
        bad_paths.append((line, recv + m.group(0), hit))

if found == 0:
    print("    found no .post/.withEndAction callbacks at all — either they are")
    print("    gone (fine) or this parser stopped matching them (not fine)")
    sys.exit(1)

for line, site, hit in bad_paths:
    print("    AggregatorStackFragment.kt:%d  %s" % (line, site))
    print("      reaches a Context via: %s" % ", ".join(hit))
    print("      — a Handler owns this callback, so it runs after onDestroyView")

if bad_paths:
    sys.exit(1)
print("    checked %d unscoped callbacks against %d Context-reaching functions"
      % (found, len(reaching)))
CLOSURE

# THE BLIND SPOT T4 HAS TO SKIP, PINNED BY HAND. Shadowing a parameter means
# the closure above stops at `paint(...)` inside the swipe animation's end
# action without following it. The lambda it actually receives is built in
# notifRowView; it sets colours and a typeface on views it already holds, and
# that is what makes the skip safe. If it ever starts measuring, T4 would go
# blind rather than loud — so the claim is made here instead.
if awk '/val paint: \(Boolean\) -> Unit = \{/,/^        \}$/' "$CODE" \
     | grep -qE '(^|[^.[:alnum:]_])(dp\(|resources)'; then
  bad "T4: the swipe repaint closure now measures — it runs from an animator callback the view lifecycle does not cancel, and T4 cannot see inside it"
else
  ok "T4: the swipe repaint closure touches views only, never a Context"
fi

echo "== the two paths that actually crashed are scoped, by name =="
# T4 is the rule; these two are the evidence. Both were the same defect: work
# posted to a Handler that survived the fragment.
if awk '/private fun renderNtfyGroups/,/^    private fun paintNtfyGroup/' "$CODE" \
     | grep -q 'viewLifecycleOwner\.lifecycleScope\.launch {'; then
  ok "T5: the ntfy poll's repaint is cancelled with the view"
else
  bad "T5: the ntfy poll no longer paints from the view's scope — the crash is back"
fi
if awk '/private fun startRefresh/,/^    }$/' "$CODE" \
     | grep -q 'viewLifecycleOwner\.lifecycleScope\.launch {'; then
  ok "T5: the refresh watchdog is cancelled with the view"
else
  bad "T5: the watchdog is no longer view-scoped — it fires finishRefresh into a dead Context"
fi
# The poll must still run OFF the main thread — a lifecycle fix that moved a
# blocking socket read onto the UI thread would trade a crash for an ANR.
if awk '/private fun renderNtfyGroups/,/^    private fun paintNtfyGroup/' "$CODE" \
     | grep -q 'withContext(kotlinx.coroutines.Dispatchers.IO)'; then
  ok "T5: the poll itself still runs off the main thread"
else
  bad "T5: the network poll is on the main thread now — an ANR instead of a crash"
fi

echo
echo "PASS=$PASS FAIL=$FAIL"
[ "$FAIL" -eq 0 ]
