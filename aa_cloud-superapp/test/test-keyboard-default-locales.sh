#!/usr/bin/env bash
# Every language the keyboard enables on a fresh install must be one it can
# actually spell-check. A subtype whose locale has no matching dictionary does
# not fail loudly - it just offers no suggestions, no autocorrect and no learned
# words, which reads to the user as "the keyboard is dumb" rather than as a
# missing asset. That is how build.json shipped "es_ES": no such subtype is
# declared, so the seeded Spanish subtype was deleted on the next launch and the
# system-locale default (Spanish (US) on a US-region phone) silently took over.
#
#   T1  every build.json::default_locales tag resolves to a DECLARED subtype
#       in method.xml, by the same match rule the app uses (LocaleUtils.getMatchLevel)
#   T2  every default locale resolves to a main_*.dict that actually ships
#   T3  the default Spanish is Spain Spanish ("es"), not es_US / es_419
#   T4  the default English is UK English ("en_GB"), not en_US
#   T5  the seeding path resolves to a declared subtype instead of inventing one
#   T6  the shipped en_GB dictionary really carries British spelling
set -uo pipefail
APP="$(cd "$(dirname "$0")/.." && pwd)"
ROOT="$APP/.."
KBD="$ROOT/ab_cloud-libs-shared/libs/keyboard"
METHOD="$KBD/src/main/res/xml/method.xml"
DICTS="$KBD/dicts-data"
SEED="$ROOT/ac_cloud-keyboard/app/src/main/java/com/diegonmarcos/cloudkeyboard/App.kt"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  ok: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }

echo "== keyboard default locales: every default subtype has a dictionary =="

for BJ in "$ROOT/ac_cloud-keyboard/build.json" "$ROOT/ab_cloud-libs-shared/keyboard-engines/build.json"; do
  echo "-- $(basename "$(dirname "$BJ")")/build.json"
  # T1 + T2 + T3 + T4: resolve each default locale against declared subtypes and
  # shipped dictionaries using the app's own match rule.
  python3 - "$BJ" "$METHOD" "$DICTS" <<'EOF'
import json, os, re, sys
build_json, method_xml, dicts_dir = sys.argv[1:4]

def parse(tag):
    # method.xml uses ll_CC, dict filenames use ll-CC; normalise to (lang, country)
    parts = re.split(r'[-_]', tag)
    lang = parts[0].lower()
    country = parts[1].upper() if len(parts) > 1 else ''
    return lang, country

def match_level(ref, tested):
    # mirrors LocaleUtils.getMatchLevel: FULL=30, LANGUAGE_MATCH=15,
    # LANGUAGE_MATCH_COUNTRY_DIFFER=3 (== LOCALE_GOOD_MATCH), NO_MATCH=0
    if ref == tested: return 30
    if ref[0] != tested[0]: return 0
    if ref[1] != tested[1]:
        return 15 if ref[1] == '' else 3
    return 30

def best(ref, candidates):
    best_c, best_l = None, 0
    for c in candidates:
        l = match_level(ref, parse(c))
        if l > best_l and l >= 3:
            best_c, best_l = c, l
    return best_c

defaults = json.load(open(build_json))['default_locales']
subtypes = re.findall(r'android:imeSubtypeLocale="([^"]+)"', open(method_xml).read())
maindicts = [f[len('main_'):-len('.dict')] for f in os.listdir(dicts_dir)
             if f.startswith('main_') and f.endswith('.dict')]

fails = 0
for tag in defaults:
    ref = parse(tag)
    sub = best(ref, subtypes)
    dic = best(ref, maindicts)
    exact_sub = tag in subtypes
    if sub is None:
        print(f"  FAIL: T1 default locale {tag} resolves to NO declared subtype"); fails += 1
    elif not exact_sub:
        print(f"  FAIL: T1 default locale {tag} is not a declared subtype "
              f"(nearest declared: {sub}) - name the declared tag instead"); fails += 1
    else:
        print(f"  ok: T1 default locale {tag} is a declared subtype")
    if dic is None:
        print(f"  FAIL: T2 default locale {tag} has NO main dictionary that ships"); fails += 1
    else:
        print(f"  ok: T2 default locale {tag} -> main_{dic}.dict ships")

spanish = [t for t in defaults if parse(t)[0] == 'es']
if spanish == ['es']:
    print("  ok: T3 default Spanish is Spain Spanish 'es' (main_es.dict = Espanol)")
else:
    print(f"  FAIL: T3 default Spanish is {spanish}, expected ['es'] (not es_US / es_419)"); fails += 1

english = [t for t in defaults if parse(t)[0] == 'en']
if english == ['en_GB']:
    print("  ok: T4 default English is UK English 'en_GB'")
else:
    print(f"  FAIL: T4 default English is {english}, expected ['en_GB']"); fails += 1

sys.exit(1 if fails else 0)
EOF
  if [ $? -eq 0 ]; then PASS=$((PASS+1)); else FAIL=$((FAIL+1)); fi
done

# T5 the seeding path must resolve to a DECLARED subtype, never invent one.
grep -q 'getBestMatch' "$SEED" \
  && ok "T5 seedDefaultLocales resolves via LocaleUtils.getBestMatch" \
  || bad "T5 seedDefaultLocales does not resolve to the closest declared subtype"
grep -q 'createDefaultSubtype' "$SEED" \
  && bad "T5 seedDefaultLocales still invents a subtype (createDefaultSubtype) - it is dropped on next launch" \
  || ok "T5 seedDefaultLocales never invents an undeclared subtype"
grep -q 'getAvailableSubtypeLocales' "$SEED" \
  && ok "T5 seeding matches against the declared subtype locales" \
  || bad "T5 seeding does not consult the declared subtype locales"

# T6 the en_GB dictionary must carry British spelling, not just the label.
python3 - "$DICTS" <<'EOF'
import struct, sys, os
dicts = sys.argv[1]

def words(path):
    d = open(path, 'rb').read()
    hs = struct.unpack('>I', d[8:12])[0]
    out = set()
    def rd_count(p):
        b = d[p]
        return (b, p+1) if b < 0x80 else (((b & 0x7F) << 8) | d[p+1], p+2)
    def rd_chars(p, multi):
        s = ''
        while True:
            b = d[p]
            if b == 0x1F: return s, p+1
            if b < 0x1F:
                cp = (b << 16) | (d[p+1] << 8) | d[p+2]; p += 3
            else:
                cp = b; p += 1
            s += chr(cp)
            if not multi: return s, p
    def walk(pos, prefix, depth):
        if depth > 40: return
        cnt, p = rd_count(pos)
        for _ in range(cnt):
            flags = d[p]; p += 1
            s, p = rd_chars(p, flags & 0x20)
            word = prefix + s
            if flags & 0x10: p += 1
            at = flags & 0xC0
            child = 0
            if at:
                n = {0x40: 1, 0x80: 2, 0xC0: 3}[at]
                raw = int.from_bytes(d[p:p+n], 'big'); p += n
                child = p + raw - n   # children address is relative to the field start
            if flags & 0x08: p += struct.unpack('>H', d[p:p+2])[0]
            if flags & 0x04:
                while True:
                    bf = d[p]; p += 1
                    p += {0x10: 1, 0x20: 2, 0x30: 3}[bf & 0x30]
                    if not (bf & 0x80): break
            if (flags & 0x10) and not (flags & 0x02): out.add(word)
            if child: walk(child, word, depth+1)
    sys.setrecursionlimit(10000)
    walk(hs, '', 0)
    return out

gb = words(os.path.join(dicts, 'main_en-GB.dict'))
british = ['colour', 'favourite', 'realise', 'organise', 'centre',
           'neighbour', 'behaviour', 'theatre', 'apologise', 'analyse']
missing = [w for w in british if w not in gb]
if missing:
    print(f"  FAIL: T6 main_en-GB.dict is labelled en_GB but lacks British spellings: {missing}")
    sys.exit(1)
print(f"  ok: T6 main_en-GB.dict carries British spelling ({len(british)}/{len(british)} forms, {len(gb)} words)")
EOF
if [ $? -eq 0 ]; then PASS=$((PASS+1)); else FAIL=$((FAIL+1)); fi

echo "== $PASS ok, $FAIL failed =="
[ "$FAIL" -eq 0 ]
