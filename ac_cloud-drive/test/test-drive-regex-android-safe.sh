#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════════════╗
# ║ Android's ICU regex rejects a literal "}" that is not escaped (desktop   ║
# ║ JVM accepts it, so JVM tests pass and the phone crashes at startup).     ║
# ╚══════════════════════════════════════════════════════════════════════════╝
#   R1 no Regex/Pattern literal in shipped Kotlin/Java (src/main, any app or lib) has a bare "}" outside a character
#      class that does not close a {n,m} quantifier.
#   R2 mutation: the exact crash pattern from UnlistedRepos (\{([a-z_]+)}) is caught by the same scan.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
CHECK=$(cat <<'PY'
import re, sys, pathlib
CALL = re.compile(r'(?:Regex|toRegex|Pattern\.compile)\(\s*(?:"""(.*?)"""|"((?:[^"\\]|\\.)*)")', re.S)
def strip_templates(p):
    out, i = [], 0
    while i < len(p):
        if p.startswith('${', i):
            d, i = 1, i + 2
            while i < len(p) and d:
                d += {'{': 1, '}': -1}.get(p[i], 0); i += 1
            out.append('X'); continue
        out.append(p[i]); i += 1
    return ''.join(out)
def bad(p):
    p = strip_templates(p)
    i, cls = 0, False
    while i < len(p):
        c = p[i]
        if c == '\\':
            if p[i+1:i+3] in ('p{', 'P{'):
                j = p.find('}', i); i = j + 1 if j > 0 else len(p); continue
            i += 2; continue
        if cls:
            if c == ']': cls = False
        elif c == '[': cls = True; i += 1; i += 1 if i < len(p) and p[i] == '^' else 0; i += 1 if i < len(p) and p[i] == ']' else 0; continue
        elif c == '{':
            m = re.match(r'\{(\d+|X)(,(\d*|X))?\}', p[i:])
            if m: i += len(m.group(0)); continue
        elif c == '}': return True
        i += 1
    return False
hits = 0
for root in sys.argv[1:]:
    for f in pathlib.Path(root).rglob('*'):
        if f.suffix not in ('.kt', '.java') or '/build/' in str(f): continue
        s = f.read_text(errors='ignore')
        for m in CALL.finditer(s):
            raw, esc = m.group(1), m.group(2)
            pat = raw if raw is not None else bytes(esc, 'utf-8').decode('unicode_escape', errors='ignore') if '\\' in esc else esc
            if bad(pat):
                hits += 1; print(f"{f}:{s.count(chr(10), 0, m.start()) + 1}: {pat}")
sys.exit(1 if hits else 0)
PY
)
fail=0
mapfile -t mains < <(find "$ROOT" -path '*/src/main' -type d -not -path '*/build/*')
if out=$(python3 -c "$CHECK" "${mains[@]}"); then echo "R1 PASS"; else echo "R1 FAIL bare } in a regex (Android ICU rejects it):"; echo "$out"; fail=1; fi
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
printf '%s\n' 'val ph = Regex("""\{([a-z_]+)}""")' > "$tmp/M.kt"
if python3 -c "$CHECK" "$tmp" >/dev/null; then echo "R2 FAIL mutation not caught"; fail=1; else echo "R2 PASS (mutation caught)"; fi
exit $fail
