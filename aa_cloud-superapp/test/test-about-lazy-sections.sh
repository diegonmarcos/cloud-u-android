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
# Static tester: no device, no build. The Kotlin is parsed for structure —
# brace-matched regions, not substring presence, because a comment mentioning
# Dispatchers.IO must not satisfy an assertion about running on it.
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"          # → aa_cloud-superapp
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }

FRAG="$APP/app/src/main/java/com/diegonmarcos/superapp/devcontrol/DevControlFragment.kt"

# FAIL CLOSED — a tester whose tool is missing answers from the tool's absence.
if ! command -v python3 >/dev/null 2>&1; then
  echo "  FAIL: python3 is not on PATH — this tester proves nothing without it"
  echo "== RESULT: 0 passed, 1 failed =="
  exit 1
fi
if [ ! -f "$FRAG" ]; then
  echo "  FAIL: missing $FRAG — the tree is not what this tester was written against"
  echo "== RESULT: 0 passed, 1 failed =="
  exit 1
fi

# One python program answers every structural question and prints KEY=VALUE
# lines. Comments and string literals are stripped FIRST, so prose can never
# satisfy a check — the mistake that made an earlier guard in this repository
# match its own explanatory comment.
EVAL="$(python3 - "$FRAG" <<'PY'
import sys, re

raw = open(sys.argv[1], encoding='utf-8').read()

# ── strip comments and string bodies, preserving offsets ──────────────
# Kotlin block comments NEST, so this counts depth rather than searching
# for the first */ — a KDoc containing a /* otherwise swallows the file.
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
    # Char literals MUST be blanked too. '(' and '\n' appear in this file, and
    # leaving them in place unbalances every brace/paren match downstream —
    # which silently emptied the onCreateView range and made T6 read 0 sections.
    if c == "'":
        j = i+1
        while j < n and raw[j] != "'":
            if raw[j] == '\\': j += 1
            j += 1
        blank(i+1, min(j, n)); i = min(j+1, n); continue
    i += 1
code = ''.join(out)
print("UNTERMINATED_BLOCK_COMMENT=%d" % (1 if depth else 0))

def body_of(start_idx):
    """Text of the brace-delimited block that starts at/after start_idx."""
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

def fn_body(sig):
    j = code.find(sig)
    return body_of(j) if j >= 0 else ''

# ── T1: section() defers instead of invoking ─────────────────────────
sec = fn_body('private fun section(ctx: Context, host: LinearLayout')
print("SECTION_FOUND=%d" % (1 if sec else 0))
print("SECTION_REGISTERS=%d" % (1 if 'lazySections +=' in sec else 0))
# The ONLY invocation of the caller's lambda must sit after the registration,
# i.e. inside the deferred block.
reg = sec.find('lazySections +=')
inv = sec.find('body(')
print("SECTION_DEFERS_BODY=%d" % (1 if (reg >= 0 and inv > reg) else 0))
print("SECTION_HAS_PLACEHOLDER=%d" % (1 if 'placeholder' in sec else 0))

# ── T2: the pump, and its once-only guard ────────────────────────────
pump = fn_body('private fun pumpLazySections()')
print("PUMP_FOUND=%d" % (1 if pump else 0))
print("PUMP_ONCE_ONLY=%d" % (1 if ('started' in pump and 'continue' in pump) else 0))
print("PUMP_USES_VIEWPORT=%d" % (1 if 'scrollY' in pump else 0))
print("PUMP_WIRED_TO_SCROLL=%d" % (1 if 'addOnScrollChangedListener' in code else 0))

# ── T3: teardown (the #194 class) ────────────────────────────────────
odv = fn_body('override fun onDestroyView()')
print("ONDESTROYVIEW_FOUND=%d" % (1 if odv else 0))
print("ONDESTROYVIEW_REMOVES_LISTENER=%d" % (1 if 'removeOnScrollChangedListener' in odv else 0))
print("ONDESTROYVIEW_CLEARS=%d" % (1 if 'lazySections.clear()' in odv else 0))

# ── T4: asyncSection's contract ──────────────────────────────────────
asy = fn_body('private fun <T> asyncSection(')
print("ASYNC_FOUND=%d" % (1 if asy else 0))
print("ASYNC_OFF_MAIN=%d" % (1 if 'Dispatchers.IO' in asy else 0))
print("ASYNC_TIMEOUT=%d" % (1 if 'withTimeout' in asy else 0))
print("ASYNC_LIFECYCLE_SCOPE=%d" % (1 if 'viewLifecycleOwner.lifecycleScope' in asy else 0))
print("ASYNC_ISADDED_GUARD=%d" % (1 if 'isAdded' in asy else 0))
# A failure must REACH THE SCREEN, not just a log: an addView on the failure
# path. body_of() indexes into `code`, so the search has to be absolute too —
# an offset taken inside `asy` would point at an unrelated block.
asy_at = code.find('private fun <T> asyncSection(')
onfail_at = code.find('.onFailure', asy_at) if asy_at >= 0 else -1
onfail = body_of(onfail_at) if 0 <= onfail_at < asy_at + len(asy) else ''
print("ASYNC_RENDERS_FAILURE=%d" % (1 if ('addView' in onfail) else 0))
print("ASYNC_REMOVES_SPINNER=%d" % (1 if 'removeView(loading)' in asy else 0))

# ── T5: every known-heavy call runs off the main thread ──────────────
# Build the set of off-main regions by brace-matching each withContext(
# Dispatchers.IO) block, then ask which heavy call sites fall inside one.
regions = []
for pat in (r'withContext\(Dispatchers\.IO\)', r'probe = \{'):
    for m in re.finditer(pat, code):
        # `probe = { ... }` counts because asyncSection runs it inside
        # withContext(Dispatchers.IO) — which T4::ASYNC_OFF_MAIN asserts
        # separately, so this is not circular: if asyncSection ever stops
        # dispatching to IO, T4 goes red and this region stops being honest.
        start = m.start() if pat.endswith(r'\{') else m.end()
        blk = body_of(start)
        if blk:
            s = code.find(blk, start)
            regions.append((s, s + len(blk)))
def off_main(pos):
    return any(a <= pos < b for a, b in regions)

HEAVY = {
    'dirSize(':              'recursive walk of the private data tree',
    'collectIpcContract(':   'full manifest unmarshal over binder',
    'readUsageStats(':       '7-day usage-stats query',
    '/proc/cpuinfo':         'procfs scan',
    '/sys/class/thermal':    'sysfs enumeration',
    'scaling_cur_freq':      'sysfs read',
}
onc = code.find('override fun onCreateView(')
onc_end = onc + len(body_of(onc))
leaks = []
for needle, why in HEAVY.items():
    for m in re.finditer(re.escape(needle), code):
        p = m.start()
        # Only care about call sites inside onCreateView's lexical range —
        # the private helper that DEFINES the call is not itself a main-thread
        # execution, it is only reached through one of these sites.
        if not (onc <= p < onc_end):
            continue
        if not off_main(p):
            leaks.append("%s (%s) at offset %d" % (needle, why, p))
print("HEAVY_ON_MAIN_COUNT=%d" % len(leaks))
print("HEAVY_ON_MAIN_LIST=%s" % ("; ".join(leaks) if leaks else "-"))
print("IO_REGIONS=%d" % len(regions))

# ── T6: nothing bypasses section() ───────────────────────────────────
print("SECTION_CALLS=%d" % len(re.findall(r'(?<![A-Za-z])section\(ctx, ', code)))
print("ASYNC_CALLS=%d" % len(re.findall(r'asyncSection\(ctx, ', code)))
# requireContext() must never be reached from inside an IO region.
rq = [m.start() for m in re.finditer(r'requireContext\(\)', code) if off_main(m.start())]
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

echo "== T0: the file parses (Kotlin block comments NEST — one stray /* hides everything) =="
if [ "${UNTERMINATED_BLOCK_COMMENT:-1}" = "0" ]; then
  ok "no unterminated block comment"
else
  bad "unterminated block comment — a /* inside a KDoc has swallowed the rest of the file"
fi

echo "== T1: section() DEFERS its body instead of running it (#286, the whole mechanism) =="
[ "${SECTION_FOUND:-0}" = "1" ]        && ok "section() helper found" \
                                       || bad "section() helper not found — this tester is aimed at nothing"
[ "${SECTION_REGISTERS:-0}" = "1" ]    && ok "section() registers into lazySections" \
                                       || bad "section() no longer registers a lazy unit — every block is eager again"
[ "${SECTION_DEFERS_BODY:-0}" = "1" ]  && ok "the caller's body is invoked only inside the deferred block" \
                                       || bad "section() invokes body() BEFORE/OUTSIDE the deferred block — all 30 services are eager again"
[ "${SECTION_HAS_PLACEHOLDER:-0}" = "1" ] && ok "an unloaded block shows a placeholder, not nothing" \
                                       || bad "no placeholder — an unloaded section is indistinguishable from an empty one"

echo "== T2: the trigger is the viewport, which is what the Index drives =="
[ "${PUMP_FOUND:-0}" = "1" ]           && ok "pumpLazySections() exists" \
                                       || bad "no pump — nothing ever loads a deferred section"
[ "${PUMP_USES_VIEWPORT:-0}" = "1" ]   && ok "the pump measures against the scroll position" \
                                       || bad "the pump does not read scrollY — it cannot be viewport-driven"
[ "${PUMP_WIRED_TO_SCROLL:-0}" = "1" ] && ok "wired to the ScrollView via OnScrollChangedListener" \
                                       || bad "no scroll listener — sections below the fold would never load"
[ "${PUMP_ONCE_ONLY:-0}" = "1" ]       && ok "a loaded section is skipped on later pumps (no re-probing on scroll)" \
                                       || bad "the pump has no once-only guard — probes would re-run on every scroll event"

echo "== T3: in-flight work dies with the view (#194 crash class) =="
[ "${ONDESTROYVIEW_FOUND:-0}" = "1" ]            && ok "onDestroyView() is overridden" \
                                                 || bad "no onDestroyView — nothing cancels or unregisters on teardown"
[ "${ONDESTROYVIEW_REMOVES_LISTENER:-0}" = "1" ] && ok "the scroll listener is removed on teardown" \
                                                 || bad "the scroll listener is never removed — it holds a destroyed ScrollView and keeps pumping into a dead view tree"
[ "${ONDESTROYVIEW_CLEARS:-0}" = "1" ]           && ok "the lazy registry is cleared per view lifecycle" \
                                                 || bad "lazySections is never cleared — stale views leak across reattach"

echo "== T4: a slow or failing service reports, and cannot hang the page =="
[ "${ASYNC_FOUND:-0}" = "1" ]            && ok "asyncSection() exists" \
                                         || bad "asyncSection() is gone — network/disk blocks have nowhere off-main to run"
[ "${ASYNC_OFF_MAIN:-0}" = "1" ]         && ok "its probe runs on Dispatchers.IO" \
                                         || bad "asyncSection does not dispatch to IO — it is lazy but still blocking"
[ "${ASYNC_TIMEOUT:-0}" = "1" ]          && ok "the probe is bounded by a timeout" \
                                         || bad "no timeout — one hanging service spins forever"
[ "${ASYNC_LIFECYCLE_SCOPE:-0}" = "1" ]  && ok "scoped to viewLifecycleOwner, so teardown cancels it" \
                                         || bad "not scoped to viewLifecycleOwner — probes outlive the view (#194)"
[ "${ASYNC_ISADDED_GUARD:-0}" = "1" ]    && ok "renders only while still attached" \
                                         || bad "no isAdded guard before painting the result"
[ "${ASYNC_RENDERS_FAILURE:-0}" = "1" ]  && ok "a failure/timeout is RENDERED in its own block" \
                                         || bad "a failing probe adds no view — the block would stay blank, which is the #281 lie"
[ "${ASYNC_REMOVES_SPINNER:-0}" = "1" ]  && ok "the loading placeholder is removed when the probe settles" \
                                         || bad "the spinner is never removed — a settled block still looks like it is loading"

echo "== T5: no known-heavy read happens on the main thread =="
if [ "${IO_REGIONS:-0}" -lt 1 ]; then
  bad "found zero withContext(Dispatchers.IO) regions — T5 would pass vacuously, so it proves nothing"
elif [ "${HEAVY_ON_MAIN_COUNT:-1}" = "0" ]; then
  ok "every heavy read inside onCreateView sits in an off-main block (${IO_REGIONS} IO regions)"
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
