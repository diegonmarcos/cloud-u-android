#!/usr/bin/env bash
# test-store-data: data/store.json (the Store's catalogue, what Galaxy loads) is GENERATED from data/fwa.md by data/gen-store.py.
#   S1  no drift: store.json is byte-for-byte what the generator makes of fwa.md today
#   S2  completeness: one entry per row of fwa.md, every row's link, Type, 2D/3D and VR carried over
#   S3  the generator refuses a malformed row (an unknown Type / 2D/3D / VR, a name without a link) instead of guessing
# then plants a mutation per rule in a scratch copy and requires RED for each.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
APP="$(cd "$HERE/.." && pwd)"
command -v python3 >/dev/null || { echo "ERROR: python3 is required"; exit 1; }
FAILS=0
ok()  { echo "  PASS: $1"; }
bad() { echo "  FAIL: $1"; FAILS=$((FAILS + 1)); }

check() {   # check <data dir>: S1 + S2, prints FAIL lines
python3 - "$1" <<'PY'
import json, os, re, subprocess, sys
d = sys.argv[1]
bad = []
r = subprocess.run([sys.executable, "-I", os.path.join(d, "gen-store.py"), "--check"], capture_output=True, text=True)
if r.returncode != 0: bad.append("S1 " + (r.stdout + r.stderr).strip().splitlines()[-1] if (r.stdout + r.stderr).strip() else "S1 the generator failed")
rows = [l for l in open(os.path.join(d, "fwa.md"), encoding="utf-8").read().splitlines() if l.strip().startswith("|")][2:]
try: entries = json.load(open(os.path.join(d, "store.json"), encoding="utf-8")).get("entries", [])
except Exception as e: entries = []; bad.append("S2 store.json does not parse: %s" % e)
if len(entries) != len(rows): bad.append("S2 store.json has %d entries, fwa.md %d rows" % (len(entries), len(rows)))
for row, e in zip(rows, entries):
    c = [x.strip() for x in row.strip().strip("|").split("|")]
    m = re.fullmatch(r"\[([^\]]+)\]\(([^)]+)\)", c[0])
    want = (m.group(1), m.group(2), c[1], c[2], c[5] == "Yes", c[6]) if m else None
    got = (e.get("name"), e.get("url"), e.get("type"), e.get("dimension"), e.get("vr"), e.get("description"))
    if want != got: bad.append("S2 entry %r does not carry its row %r" % (e.get("name"), c[0]))
for b in bad: print("FAIL " + b)
sys.exit(1 if bad else 0)
PY
}

echo "[$(basename "$APP")] store data"
out="$(check "$APP/data")" && ok "S1-S2 hold on the real tree (store.json is generated from fwa.md)" || { bad "the real tree is red"; echo "$out"; }

W="$(mktemp -d)"; trap 'rm -rf "$W"' EXIT
mutant() {   # mutant <name> <edit run in the copy> <needle>
  rm -rf "${W:?}/data"; cp -r "$APP/data" "$W/data"
  ( cd "$W/data" && eval "$2" )
  if out="$(check "$W/data" 2>&1)"; then bad "mutant '$1' stayed green"; elif grep -q -- "$3" <<<"$out"; then ok "mutant '$1' is red ($3)"; else bad "mutant '$1' is red for the wrong reason: $out"; fi
}
mutant "store.json is hand-edited"     "sed -i '0,/\"vr\": false/s//\"vr\": true/' store.json"            "S1"
mutant "a row is added to fwa.md only" "tail -1 fwa.md >> fwa.md && sed -i '\$s/Inside The Head/Inside The Head 2/' fwa.md" "S1"
mutant "an entry is dropped"           "python3 -c \"import json;d=json.load(open('store.json'));d['entries'].pop();json.dump(d,open('store.json','w'),indent=2)\"" "S2"

# S3: the generator itself refuses what it cannot map
refuse() {   # refuse <name> <sed on fwa.md> <needle in the generator's message>
  rm -rf "${W:?}/data"; cp -r "$APP/data" "$W/data"
  ( cd "$W/data" && sed -i "$2" fwa.md )
  if out="$(python3 -I "$W/data/gen-store.py" --check 2>&1)"; then bad "S3 the generator took a $1"; elif grep -q -- "$3" <<<"$out"; then ok "S3 the generator refuses a $1"; else bad "S3 the generator refused a $1 for the wrong reason: $out"; fi
}
refuse "unknown Type"        '0,/| Website |/s//| Site |/'  "Type 'Site'"
refuse "unknown 2D\/3D"      '0,/| 3D | Three/s//| 4D | Three/' "2D/3D '4D'"
refuse "unknown VR value"    '0,/| No |/s//| Maybe |/'      "VR 'Maybe'"
refuse "name without a link" '0,/\[Lusion v3\](https:\/\/lusion.co\/)/s//Lusion v3/' "is not \[name\](url)"

[ "$FAILS" -eq 0 ] && echo "ALL GREEN" || { echo "$FAILS FAILED"; exit 1; }
