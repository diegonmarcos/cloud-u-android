#!/usr/bin/env bash
# test-roadmap-tree.sh — #1350 RoadMap drift guard.
#   T1 data/repos/roadmap-tree.json is exactly the render of the committed snapshot data/repos/roadmap-tree.dirs.txt
#      (data/regen-roadmap-tree.py --check).
#   T2 the rules hold on the shipped tree: separators are section titles (never folders), each section owns its
#      "<n>." folders, tooling and dot folders are hidden at every depth, the snapshot holds no hidden folder.
#   T3 the snapshot is current with the private vault when this runner can read it (--check-live); CI's token cannot
#      read a private repo, so there it SKIPS and T1 is the guard. Refresh where gh can read the vault:
#        ./data/regen-roadmap-tree.py --refresh      (then commit roadmap-tree.dirs.txt and roadmap-tree.json)
#   Mutations: each check must go red on its own break, on a scratch copy.
set -uo pipefail
APP="$(cd "$(dirname "$0")/.." && pwd)"
PASS=0; FAIL=0
ok() { echo "  PASS: $1"; PASS=$((PASS+1)); }
bad() { echo "  FAIL: $1"; FAIL=$((FAIL+1)); }

rules() { # <app dir>: prints one line per violation
  python3 - "$1" <<'PY'
import json, os, re, sys
app = sys.argv[1]
t = json.load(open(os.path.join(app, "data/repos/roadmap-tree.json"), encoding="utf-8"))
snap = [l.strip() for l in open(os.path.join(app, "data/repos/roadmap-tree.dirs.txt"), encoding="utf-8") if l.strip()]
HIDDEN = {"0_docs", "0_hub", "1_workflows", "_dotfiles"}
SEP = re.compile(r"^\d+_+$")
PARENT = re.compile(r"^\d+_{2,}[^_]")  # #1367 a parent folder (0___Inbox): a section owning its children
def walk(f):
    yield f
    for c in f.get("children", []): yield from walk(c)
if t.get("_schema") != "cloud-me-roadmap-tree/v1": print("schema is not cloud-me-roadmap-tree/v1")
secs = t.get("sections", [])
if not secs or any(not (SEP.match(s.get("separator", "")) or PARENT.match(s.get("separator", ""))) for s in secs if s.get("title") != "Other"): print("a section title is not a separator or parent folder")
for s in secs:
    sp = s.get("separator", ""); n = sp.split("_")[0]
    mine = (lambda f: f["path"].startswith(sp + "/")) if PARENT.match(sp) else (lambda f: f["name"].startswith(n + "."))
    if s.get("title") != "Other" and not all(mine(f) for f in s.get("folders", [])): print(f"section {sp} holds a folder that is not its own")
fs = [f for s in secs for top in s.get("folders", []) for f in walk(top)]
if any(SEP.match(f["name"]) or (PARENT.match(f["name"]) and "/" not in f["path"]) for f in fs): print("a separator or parent folder is drawn as a folder")
if any(f["path"].split("/")[0] in HIDDEN or any(p.startswith(".") for p in f["path"].split("/")) for f in fs): print("a hidden tooling or dot folder is in the tree")
if any(p.split("/")[0] in HIDDEN or any(q.startswith(".") for q in p.split("/")) for p in snap): print("the snapshot holds a hidden folder")
if t.get("folder_count") != len(fs) or len(fs) < 50: print(f"folder_count {t.get('folder_count')} vs {len(fs)} drawn")
PY
}

echo "== T1 roadmap-tree.json is the render of its snapshot =="
if (cd "$APP" && python3 data/regen-roadmap-tree.py --check >/dev/null); then ok "roadmap-tree.json is current with roadmap-tree.dirs.txt"; else bad "roadmap-tree.json drifted from its snapshot — run ./data/regen-roadmap-tree.py"; fi
echo "== T2 the tree follows the rules =="
r="$(rules "$APP")"; if [ -z "$r" ]; then ok "separators are titles, tooling folders hidden, folders only"; else bad "rules"; echo "$r" | sed 's/^/        /'; fi
echo "== T3 the snapshot is current with the vault (when readable) =="
(cd "$APP" && python3 data/regen-roadmap-tree.py --check-live); rc=$?
case $rc in 0) ok "the snapshot matches the vault";; 3) ok "vault not readable here: skipped, T1 guards the committed snapshot";; *) bad "the vault moved past the snapshot — ./data/regen-roadmap-tree.py --refresh";; esac

echo "== mutations =="
W="$(mktemp -d)"; trap 'rm -rf "$W"' EXIT
mk() { rm -rf "$W/a"; mkdir -p "$W/a/data/repos"; cp "$APP/data/regen-roadmap-tree.py" "$W/a/data/"; cp "$APP"/data/repos/roadmap-tree.* "$W/a/data/repos/"; }
red_check() { mk; (cd "$W/a" && eval "$2") >/dev/null 2>&1; if (cd "$W/a" && python3 data/regen-roadmap-tree.py --check >/dev/null 2>&1); then bad "NOT red: $1"; else ok "red: $1"; fi; }
red_rules() { mk; (cd "$W/a" && eval "$2") >/dev/null 2>&1; if [ -z "$(rules "$W/a")" ]; then bad "NOT red: $1"; else ok "red: $1"; fi; }
red_check "a folder added to the snapshot without a re-render" "echo '2.9___New' >> data/repos/roadmap-tree.dirs.txt"
red_check "the generated tree edited by hand" "sed -i 's/1.1_Backlogs/1.1_Renamed/' data/repos/roadmap-tree.json"
red_check "the generator stops hiding _dotfiles" "sed -i 's/\"_dotfiles\")/\"_none\")/' data/regen-roadmap-tree.py; echo '_dotfiles' >> data/repos/roadmap-tree.dirs.txt"
red_rules "a dot folder leaks into the tree" "python3 -c \"import json;p='data/repos/roadmap-tree.json';d=json.load(open(p));d['sections'][0]['folders'][0]['children'].append({'name':'.obsidian','path':'0.0_Inbox/.obsidian','children':[]});d['folder_count']+=1;json.dump(d,open(p,'w'))\""
red_rules "a separator drawn as a folder" "python3 -c \"import json;p='data/repos/roadmap-tree.json';d=json.load(open(p));d['sections'][1]['folders'].insert(0,{'name':'1___________________','path':'1___________________','children':[]});d['folder_count']+=1;json.dump(d,open(p,'w'))\""
red_rules "a folder in the wrong section" "python3 -c \"import json;p='data/repos/roadmap-tree.json';d=json.load(open(p));d['sections'][0]['folders'].append(d['sections'][1]['folders'].pop());json.dump(d,open(p,'w'))\""
red_rules "a parent folder drawn as a folder" "python3 -c \"import json;p='data/repos/roadmap-tree.json';d=json.load(open(p));d['sections'][0]['folders'].insert(0,{'name':'1___Workflows','path':'1___Workflows','children':[]});d['folder_count']+=1;json.dump(d,open(p,'w'))\""
red_check "the generator stops treating 0___Inbox as a parent" "sed -i 's/_{2,}(\[^_\]/_{9,}([^_]/' data/regen-roadmap-tree.py"
red_rules "the snapshot keeps a tooling folder" "echo '0_hub' >> data/repos/roadmap-tree.dirs.txt"

echo; echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
