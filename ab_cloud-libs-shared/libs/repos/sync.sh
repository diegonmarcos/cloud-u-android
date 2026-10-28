#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════╗
# ║ libs:repos — read-only, history-free snapshots of the fleet      ║
# ║                                                                  ║
# ║   ./sync.sh                sync everything due                   ║
# ║   ./sync.sh --list         what is indexed, and how current      ║
# ║   ./sync.sh --force        re-download even if the sha matches   ║
# ║   ./sync.sh --refresh-list regenerate repos.json (repos.sot.json)║
# ║   ./sync.sh --check-list   fail if repos.json is stale           ║
# ║   ./sync.sh <name>...      sync only these                       ║
# ║                                                                  ║
# ║ NOT a clone. Each repo is the extracted tip TARBALL, so there is ║
# ║ no .git anywhere: nothing can be committed from here, no branch  ║
# ║ can drift, and "read-only" is a property of the thing rather     ║
# ║ than a rule someone has to keep.                                 ║
# ╚══════════════════════════════════════════════════════════════════╝
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CFG="$HERE/repos.json"

command -v gh >/dev/null || { echo "gh required (auth carries private repos too)" >&2; exit 1; }
command -v jq >/dev/null || { echo "jq required" >&2; exit 1; }

_cfg() { jq -r "$1" "$CFG"; }

# rm -rf cannot delete inside a tree we made read-only: removing a file needs
# write on its PARENT directory, and chmod -R a-w took that away too.
_nuke() { [ -e "$1" ] || return 0; chmod -R u+w "$1" 2>/dev/null || true; rm -rf "$1"; }

_selected() {
  if [ "${#TARGETS[@]}" -gt 0 ]; then printf '%s\n' "${TARGETS[@]}"; return; fi
  local inc; inc="$(_cfg '.include_private')"
  if [ "$inc" = "true" ]; then _cfg '.repos[].name'
  else _cfg '.repos[] | select(.private | not) | .name'; fi
}

sync_one() {
  local name="$1"
  local slug dir head branch cur excludes
  slug="$(jq -r --arg n "$name" '.repos[] | select(.name==$n) | .repo' "$CFG")"
  [ -n "$slug" ] || { printf '  %-30s not in repos.json\n' "$name"; return 0; }
  dir="$HERE/$name"

  branch="$(gh api "repos/$slug" --jq .default_branch 2>/dev/null)" || {
    printf '  %-30s unreachable (private without access?)\n' "$name"; return 0; }
  head="$(gh api "repos/$slug/commits/$branch" --jq .sha 2>/dev/null)" || {
    printf '  %-30s could not read %s head\n' "$name" "$branch"; return 0; }
  cur="$(cat "$dir/.head" 2>/dev/null || true)"

  if [ "$FORCE" != "1" ] && [ "$head" = "$cur" ]; then
    printf '  %-30s current  %s\n' "$name" "${head:0:8}"; return 0
  fi

  # tar --exclude patterns come from repos.json, so trimming media is data.
  excludes=()
  while IFS= read -r g; do [ -n "$g" ] && excludes+=("--exclude=$g"); done < <(_cfg '.exclude_globs[]?')

  _nuke "$dir.new"; mkdir -p "$dir.new"
  # Extract into .new and swap: an interrupted sync leaves the old snapshot
  # intact rather than a half-written one.
  if ! gh api "repos/$slug/tarball/$branch" 2>/dev/null \
       | tar -xz -C "$dir.new" --strip-components=1 "${excludes[@]}" 2>/dev/null; then
    _nuke "$dir.new"; printf '  %-30s download FAILED\n' "$name"; return 0
  fi
  printf '%s\n' "$head" > "$dir.new/.head"
  printf '%s\n' "$slug@$branch  $(date -u +%FT%TZ)" > "$dir.new/.source"
  chmod -R a-w "$dir.new"

  _nuke "$dir"; mv "$dir.new" "$dir"
  printf '  %-30s updated  %s -> %s\n' "$name" "${cur:0:8}" "${head:0:8}"
}

# #1073 the list is DERIVED from the one registry, _git-repos-master/repos.json, vendored byte-for-byte
# next to this file as repos.sot.json (refreshed by _git-repos-master's ./clone.sh --sync). No farm is read
# any more; a pending_creation entry (repo not created yet) is never listed.
#   --refresh-list  rewrite repos.json from repos.sot.json     --check-list  exit 1 if repos.json is stale
_derive_list() {  # $1 = write | check
  python3 - "$CFG" "$HERE/repos.sot.json" "$1" <<'PY'
import json,sys,collections
cfg,sot,mode=sys.argv[1:4]
d=json.load(open(cfg), object_pairs_hook=collections.OrderedDict)
reg=json.load(open(sot))
assert reg.get('_schema')=='cloud-repos/v1', 'repos.sot.json is not cloud-repos/v1'
ex=set(d['exclude'])
out=[]
for r in sorted(reg['repos'], key=lambda r:r['name']):
    if r.get('group') not in ('a_cloud','b_front','b_projects'): continue   # the cloud + front projects, as the two farms were
    if r.get('status')=='pending_creation' or r['name'] in ex: continue
    out.append(collections.OrderedDict([
        ("name",r['name']),
        ("repo",r['url'].split(':')[-1].removesuffix('.git')),
        ("private",bool(r.get('private'))),
        ("doc",r.get('doc','')),
    ]))
if mode=='check':
    if json.loads(json.dumps(d['repos']))!=json.loads(json.dumps(out)):
        have={r['name'] for r in d['repos']}; want={r['name'] for r in out}
        print("repos.json is stale vs repos.sot.json: extra=%s missing=%s (run ./sync.sh --refresh-list)"%(sorted(have-want),sorted(want-have)))
        sys.exit(1)
    print("repos.json: up to date with repos.sot.json (%d entries)"%len(out)); sys.exit(0)
d['repos']=out
open(cfg,'w').write(json.dumps(d,indent=2,ensure_ascii=False)+'\n')
print(f"  repos.json refreshed from repos.sot.json: {len(out)} entries")
PY
}
refresh_list() { _derive_list write; }

FORCE=0; TARGETS=()
case "${1:-}" in
  --list)
    printf '  %-30s %-8s %-10s %s\n' NAME PRIVATE LOCAL REPO
    while IFS= read -r n; do
      p="$(jq -r --arg n "$n" '.repos[]|select(.name==$n)|.private' "$CFG")"
      s="$(jq -r --arg n "$n" '.repos[]|select(.name==$n)|.repo' "$CFG")"
      h="$(cat "$HERE/$n/.head" 2>/dev/null || echo '-')"
      printf '  %-30s %-8s %-10s %s\n' "$n" "$p" "${h:0:8}" "$s"
    done < <(_cfg '.repos[].name')
    exit 0 ;;
  --refresh-list) refresh_list; exit 0 ;;
  --check-list) _derive_list check; exit $? ;;
  --force) FORCE=1; shift ;;
esac
[ $# -gt 0 ] && TARGETS=("$@")

echo "libs:repos — syncing (tip only, no history)"
while IFS= read -r n; do sync_one "$n"; done < <(_selected)
echo "done. $(du -sh "$HERE" 2>/dev/null | cut -f1) on disk"
