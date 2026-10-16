#!/usr/bin/env bash
# Tester: Configs ▸ About loads each service block lazily, off the main thread,
# and says so when a block fails. (#286)
#
# WHY THIS EXISTS. The About page declares 30 service blocks. Every one of them
# used to run its body inline inside onCreateView — a ~1,235-line stretch that
# did two recursive directory walks, a 7-day usage-stats binder query, sysfs
# and /proc reads, a logcat exec and socket connects, all on the main thread
# before the first frame could draw. The owner asked for "a lazy load per
# service ... each will only run as we go in that block".
#
# Four ways that regresses, none of which a compiler sees:
#
#   1. SOMEONE UN-DEFERS section(). The helper is the whole mechanism: it
#      registers the body instead of calling it. One edit calling body(grp)
#      directly restores the original bug for all 30 blocks at once, silently.
#   2. A NEW BLOCKING CALL LANDS IN A BODY. A section body still looks like
#      ordinary code, so the next person to add File().readText() to one has no
#      signal that it now runs on the main thread when scrolled to. T5 resolves
#      each known-heavy call to the block that encloses it.
#   3. THE PROBE OUTLIVES THE VIEW. #194 is a live crash class in this
#      repository: AggregatorStackFragment painted after detach and
#      requireContext() threw on the main thread. This page can now have ~30
#      probes in flight, so the same defect would be thirty times as likely.
#   4. A FAILING BLOCK GOES QUIET. #281 exists because a status surface said
#      READY when nothing worked. A diagnostics page that renders an empty
#      section, or spins forever, is worse than a slow one.
#
# SHAPE (#773): the page is Compose. A block is an AboutSection (AboutModel.kt) whose body runs through AboutSection.load();
# the viewport trigger is the LazyColumn composing the block (a LaunchedEffect per block in DevControlFragment.Content) and
# an idle loop warms the rest one block per frame; probes live on the page's composition scope instead of the view-lifecycle
# scope + scroll listener the View page needed. Every check below keeps the intent it had; the mechanisms it names changed.
#
# Static tester: no device, no build. The Kotlin is parsed for structure —
# brace-matched regions, not substring presence, because a comment mentioning
# Dispatchers.IO must not satisfy an assertion about running on it.
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"          # → aa_cloud-superapp
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }

DIR="$APP/app/src/main/java/com/diegonmarcos/superapp/devcontrol"
FRAG="$DIR/DevControlFragment.kt"
MODEL="$DIR/AboutModel.kt"
UI="$DIR/AboutUi.kt"

# FAIL CLOSED — a tester whose tool is missing answers from the tool's absence.
if ! command -v python3 >/dev/null 2>&1; then
  echo "  FAIL: python3 is not on PATH — this tester proves nothing without it"
  echo "== RESULT: 0 passed, 1 failed =="
  exit 1
fi
for f in "$FRAG" "$MODEL" "$UI"; do
  if [ ! -f "$f" ]; then
    echo "  FAIL: missing $f — the tree is not what this tester was written against"
    echo "== RESULT: 0 passed, 1 failed =="
    exit 1
  fi
done

# One python program answers every structural question and prints KEY=VALUE lines. Comments and string literals are
# stripped FIRST, so prose can never satisfy a check — the mistake that made an earlier guard in this repository match its
# own explanatory comment.
EVAL="$(python3 - "$FRAG" "$MODEL" "$UI" <<'PY'
import sys, re

def strip(raw):
    # Kotlin block comments NEST, so this counts depth rather than searching for the first */ — a KDoc containing a /*
    # otherwise swallows the file. Offsets are preserved.
    out = list(raw)
    i, n, depth = 0, len(raw), 0
    def blank(a, b):
        for k in range(a, b):
            if out[k] != '\n':
                out[k] = ' '
    while i < n:
        if depth:
            if raw.startswith('/*', i): depth += 1; blank(i, i+2); i += 2; continue
            if raw.startswith('*/', i): depth -= 1; blank(i, i+2); i += 2; continue
            blank(i, i+1); i += 1; continue
        if raw.startswith('/*', i): depth += 1; blank(i, i+2); i += 2; continue
        if raw.startswith('//', i):
            j = raw.find('\n', i); j = n if j < 0 else j
            blank(i, j); i = j; continue
        c = raw[i]
        if c == '"':
            if raw.startswith('"""', i):
                j = raw.find('"""', i+3); j = n if j < 0 else j+3
                blank(i+3, j-3 if j >= 6 else i+3); i = j; continue
            j = i+1
            while j < n and raw[j] != '"':
                if raw[j] == '\\': j += 1
                j += 1
            blank(i+1, min(j, n)); i = min(j+1, n); continue
        # Char literals MUST be blanked too: '(' and '\n' appear in these files and would unbalance every brace match.
        if c == "'" and i + 2 < n and (raw[i+2] == "'" or raw[i+1] == '\\'):
            j = i+1
            while j < n and raw[j] != "'":
                if raw[j] == '\\': j += 1
                j += 1
            blank(i+1, min(j, n)); i = min(j+1, n); continue
        i += 1
    return ''.join(out), depth

frag, d1 = strip(open(sys.argv[1], encoding='utf-8').read())
model, d2 = strip(open(sys.argv[2], encoding='utf-8').read())
ui, d3 = strip(open(sys.argv[3], encoding='utf-8').read())
print("UNTERMINATED_BLOCK_COMMENT=%d" % (1 if (d1 or d2 or d3) else 0))

def body_of(code, start_idx):
    b = code.find('{', start_idx)
    if b < 0: return ''
    d, k = 0, b
    while k < len(code):
        if code[k] == '{': d += 1
        elif code[k] == '}':
            d -= 1
            if d == 0: return code[b:k+1]
        k += 1
    return ''

def fn_body(code, sig):
    j = code.find(sig)
    return body_of(code, j) if j >= 0 else ''

# ── T1: a section DEFERS its body instead of invoking it ──────────────
reg = fn_body(model, 'fun section(head: String')          # AboutPage.section: only registers
print("SECTION_FOUND=%d" % (1 if reg else 0))
print("SECTION_REGISTERS=%d" % (1 if 'blocks +=' in reg and 'AboutSection(' in reg else 0))
print("SECTION_DEFERS_BODY=%d" % (1 if reg and 'body(' not in reg and 'body.invoke' not in reg else 0))
ld = fn_body(model, 'fun load(scope: CoroutineScope)')
# The ONLY invocation of the caller's lambda is inside load(), after the once-only guard.
print("LOAD_FOUND=%d" % (1 if ld else 0))
print("LOAD_ONCE_ONLY=%d" % (1 if ('if (started) return' in ld and ld.find('if (started) return') < ld.find('body(')) else 0))
print("LOAD_RENDERS_FAILURE=%d" % (1 if ('catch (t: Throwable)' in ld and 'items +=' in ld and 'FAILED' in ld) else 0))
print("SECTION_HAS_PLACEHOLDER=%d" % (1 if ('!section.started' in ui and 'loads when you reach it' in open(sys.argv[3], encoding='utf-8').read()) else 0))

# ── T2: the trigger is the viewport (a composed block), plus the idle warm-up ──
content = fn_body(frag, 'override fun Content()')
print("CONTENT_FOUND=%d" % (1 if content else 0))
print("PUMP_USES_VIEWPORT=%d" % (1 if re.search(r'is AboutBlock\.Section\s*->\s*\{[^}]*LaunchedEffect\(block\.section\)\s*\{\s*loadSection\(block\.section\)', content, re.S) else 0))
print("PUMP_WARMS_REST=%d" % (1 if re.search(r'LaunchedEffect\(page\)\s*\{[^}]*for \(s in page\.sections\)\s*\{\s*loadSection\(s\);\s*withFrameNanos', content, re.S) else 0))
print("PUMP_LAZY_LIST=%d" % (1 if 'LazyColumn(' in content else 0))
print("PUMP_ONCE_ONLY=%d" % (1 if 'if (started) return' in ld else 0))

# ── T3: teardown (the #194 class) ────────────────────────────────────
ls = fn_body(content, 'fun loadSection(s: AboutSection)') if 'fun loadSection' in content else ''
print("LOADSECTION_FOUND=%d" % (1 if ls else 0))
print("LOADSECTION_ATTACHED_GUARD=%d" % (1 if 'isAdded' in ls and 'view != null' in ls else 0))
print("SCOPE_IS_COMPOSITION=%d" % (1 if 'val scope = rememberCoroutineScope()' in content else 0))
print("NO_VIEW_LISTENER=%d" % (0 if re.search(r'addOnScrollChangedListener|OnScrollChangedListener|viewLifecycleOwner|lifecycleScope', frag + model + ui) else 1))

# ── T4: asyncSection's contract ──────────────────────────────────────
asy = fn_body(model, 'fun <T> asyncSection(')
print("ASYNC_FOUND=%d" % (1 if asy else 0))
print("ASYNC_OFF_MAIN=%d" % (1 if 'Dispatchers.IO' in asy else 0))
print("ASYNC_TIMEOUT=%d" % (1 if 'withTimeout' in asy else 0))
print("ASYNC_ISADDED_GUARD=%d" % (1 if 'attached()' in asy else 0))
asy_at = model.find('fun <T> asyncSection(')
onfail_at = model.find('.onFailure', asy_at) if asy_at >= 0 else -1
onfail = body_of(model, onfail_at) if 0 <= onfail_at < asy_at + len(asy) else ''
print("ASYNC_RENDERS_FAILURE=%d" % (1 if ('small(' in onfail and 'could not be read' in open(sys.argv[2], encoding='utf-8').read()) else 0))
print("ASYNC_REMOVES_SPINNER=%d" % (1 if 'remove(loading)' in asy else 0))
print("ASYNC_ON_PAGE_SCOPE=%d" % (1 if re.search(r'section\(head\)\s*\{[^}]*launch\s*\{', asy, re.S) else 0))

# ── T5: every known-heavy call runs off the main thread ──────────────
regions = []
for pat in (r'withContext\(Dispatchers\.IO\)', r'probe = \{'):
    for m in re.finditer(pat, frag):
        start = m.start() if pat.endswith(r'\{') else m.end()
        blk = body_of(frag, start)
        if blk:
            s = frag.find(blk, start)
            regions.append((s, s + len(blk)))
def off_main(pos):
    return any(a <= pos < b for a, b in regions)

HEAVY = {
    'dirSize(':              'recursive walk of the private data tree',
    'collectAppMesh(':       'package-manager resolves per declared peer',
    'collectSelfIpcContract(': 'full manifest unmarshal over binder',
    'readUsageStats(':       '7-day usage-stats query',
    '/proc/cpuinfo':         'procfs scan',
    '/sys/class/thermal':    'sysfs enumeration',
    'scaling_cur_freq':      'sysfs read',
    'portOpen(':             'a TCP connect',
    'pingHost(':             'an ICMP/TCP probe',
}
# The raw text still has the string literals the strip blanked; the HEAVY needles that are paths live in them.
raw_frag = open(sys.argv[1], encoding='utf-8').read()
dec = frag.find('private fun declare(')
dec_end = dec + len(body_of(frag, dec))
leaks = []
for needle, why in HEAVY.items():
    hay = raw_frag if needle.startswith('/') else frag
    for m in re.finditer(re.escape(needle), hay):
        p = m.start()
        # Only call sites inside the declaration are main-thread candidates — the helper that DEFINES the call is only
        # reached through one of these sites.
        if not (dec <= p < dec_end):
            continue
        if not off_main(p):
            leaks.append("%s (%s) at offset %d" % (needle, why, p))
print("HEAVY_ON_MAIN_COUNT=%d" % len(leaks))
print("HEAVY_ON_MAIN_LIST=%s" % ("; ".join(leaks) if leaks else "-"))
print("IO_REGIONS=%d" % len(regions))

# ── T6: nothing bypasses section() ───────────────────────────────────
print("SECTION_CALLS=%d" % len(re.findall(r'(?<![A-Za-z])section\("', raw_frag)))
print("ASYNC_CALLS=%d" % len(re.findall(r'asyncSection\("', raw_frag)))
rq = [m.start() for m in re.finditer(r'requireContext\(\)', frag) if off_main(m.start())]
print("REQUIRECONTEXT_OFF_MAIN=%d" % len(rq))
PY
)"

if [ -z "$EVAL" ]; then
  echo "  FAIL: the structural parse produced nothing — tester is broken, not the code"
  echo "== RESULT: 0 passed, 1 failed =="
  exit 1
fi
# shellcheck disable=SC2046
eval "$(echo "$EVAL" | grep -E '^[A-Z_]+=[0-9]+$')"
HEAVY_LIST="$(echo "$EVAL" | sed -n 's/^HEAVY_ON_MAIN_LIST=//p')"

echo "== T0: the files parse (Kotlin block comments NEST — one stray /* hides everything) =="
if [ "${UNTERMINATED_BLOCK_COMMENT:-1}" = "0" ]; then
  ok "no unterminated block comment"
else
  bad "unterminated block comment — a /* inside a KDoc has swallowed the rest of a file"
fi

echo "== T1: a section DEFERS its body instead of running it (#286, the whole mechanism) =="
[ "${SECTION_FOUND:-0}" = "1" ]        && ok "AboutPage.section() found" \
                                       || bad "AboutPage.section() not found — this tester is aimed at nothing"
[ "${SECTION_REGISTERS:-0}" = "1" ]    && ok "section() registers an AboutSection into the page" \
                                       || bad "section() no longer registers a lazy unit — every block is eager again"
[ "${SECTION_DEFERS_BODY:-0}" = "1" ]  && ok "section() never invokes the caller's body" \
                                       || bad "section() invokes body() itself — all 30 services are eager again"
[ "${LOAD_FOUND:-0}" = "1" ]           && ok "AboutSection.load() found" \
                                       || bad "AboutSection.load() not found — nothing runs a deferred body"
[ "${SECTION_HAS_PLACEHOLDER:-0}" = "1" ] && ok "an unloaded block shows a placeholder, not nothing" \
                                       || bad "no placeholder — an unloaded section is indistinguishable from an empty one"

echo "== T2: the trigger is the viewport, which is what the Index drives =="
[ "${CONTENT_FOUND:-0}" = "1" ]        && ok "the page's Content() exists" \
                                       || bad "no Content() — nothing ever loads a deferred section"
[ "${PUMP_LAZY_LIST:-0}" = "1" ]       && ok "the page is a LazyColumn: only blocks near the viewport are composed" \
                                       || bad "the page is not lazy — every block would be composed (and loaded) at once"
[ "${PUMP_USES_VIEWPORT:-0}" = "1" ]   && ok "a composed block is a reached block: it loads itself from a LaunchedEffect" \
                                       || bad "no per-block load trigger — sections below the fold would never load on reaching them"
[ "${PUMP_WARMS_REST:-0}" = "1" ]      && ok "the rest of the page is warmed one block per frame (#333)" \
                                       || bad "no idle warm-up — scrolling would meet a placeholder everywhere"
[ "${PUMP_ONCE_ONLY:-0}" = "1" ] && [ "${LOAD_ONCE_ONLY:-0}" = "1" ] \
                                       && ok "a loaded section is skipped on later triggers (no re-probing on scroll)" \
                                       || bad "load() has no once-only guard before the body — probes would re-run on every trigger"

echo "== T3: in-flight work dies with the view (#194 crash class) =="
[ "${SCOPE_IS_COMPOSITION:-0}" = "1" ] && ok "work hangs off rememberCoroutineScope(), cancelled when the view's composition goes" \
                                       || bad "the page's scope is not the composition's — probes outlive the view (#194)"
[ "${LOADSECTION_FOUND:-0}" = "1" ] && [ "${LOADSECTION_ATTACHED_GUARD:-0}" = "1" ] \
                                       && ok "a body only starts while the fragment is attached" \
                                       || bad "loadSection has no isAdded/view guard — a detached host would render into nothing (#194)"
[ "${NO_VIEW_LISTENER:-0}" = "1" ]     && ok "no scroll listener or fragment/view lifecycle scope to leak" \
                                       || bad "a View-era listener or lifecycleScope is back — it holds a dead view tree"

echo "== T4: a slow or failing service reports, and cannot hang the page =="
[ "${ASYNC_FOUND:-0}" = "1" ]            && ok "asyncSection() exists" \
                                         || bad "asyncSection() is gone — network/disk blocks have nowhere off-main to run"
[ "${ASYNC_OFF_MAIN:-0}" = "1" ]         && ok "its probe runs on Dispatchers.IO" \
                                         || bad "asyncSection does not dispatch to IO — it is lazy but still blocking"
[ "${ASYNC_TIMEOUT:-0}" = "1" ]          && ok "the probe is bounded by a timeout" \
                                         || bad "no timeout — one hanging service spins forever"
[ "${ASYNC_ON_PAGE_SCOPE:-0}" = "1" ]    && ok "launched from the block's own scope, so teardown cancels it" \
                                         || bad "not launched from the block's scope — probes outlive the view (#194)"
[ "${ASYNC_ISADDED_GUARD:-0}" = "1" ]    && ok "renders only while still attached" \
                                         || bad "no attached() guard before painting the result"
[ "${ASYNC_RENDERS_FAILURE:-0}" = "1" ]  && ok "a failure/timeout is RENDERED in its own block" \
                                         || bad "a failing probe adds no item — the block would stay blank, which is the #281 lie"
[ "${LOAD_RENDERS_FAILURE:-0}" = "1" ]   && ok "a throwing body is RENDERED in its own block too (#331)" \
                                         || bad "a throwing body escapes load() — it would take the process down from a composition effect"
[ "${ASYNC_REMOVES_SPINNER:-0}" = "1" ]  && ok "the loading placeholder is removed when the probe settles" \
                                         || bad "the spinner is never removed — a settled block still looks like it is loading"

echo "== T5: no known-heavy read happens on the main thread =="
if [ "${IO_REGIONS:-0}" -lt 1 ]; then
  bad "found zero withContext(Dispatchers.IO) regions — T5 would pass vacuously, so it proves nothing"
elif [ "${HEAVY_ON_MAIN_COUNT:-1}" = "0" ]; then
  ok "every heavy read inside the page declaration sits in an off-main block (${IO_REGIONS} IO regions)"
else
  bad "heavy reads still on the main thread: $HEAVY_LIST"
fi

echo "== T6: the page still declares its services, and probes hold no Fragment context =="
TOTAL=$(( ${SECTION_CALLS:-0} + ${ASYNC_CALLS:-0} ))
# The owner asked for lazy loading, NOT for a redesign: the services must all
# still be here. Declared as a floor, so adding a service is never a failure.
if [ "$TOTAL" -ge 29 ]; then
  ok "$TOTAL service blocks still declared (${SECTION_CALLS:-0} plain + ${ASYNC_CALLS:-0} async)"
else
  bad "only $TOTAL service blocks declared — sections were removed; #286 was lazy loading, not a redesign"
fi
if [ "${REQUIRECONTEXT_OFF_MAIN:-1}" = "0" ]; then
  ok "no requireContext() inside an off-main block"
else
  bad "requireContext() called from a background block (${REQUIRECONTEXT_OFF_MAIN} site(s)) — that is the #194 throw"
fi

echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
