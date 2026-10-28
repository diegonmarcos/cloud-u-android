#!/usr/bin/env bash
# #1073 cloud-drive reads ONE repo list: git-repos-master/repos.json.
#
#   R1  data/repos.sot.json is the pinned copy (build.json storage.repos_source.sha256), valid cloud-repos/v1,
#       and identical to the libs:repos copy
#   R2  data/drive-git-repos.json is exactly what tools/gen-drive-git-repos.js makes from
#       drive-git-family.json + the copy (no hand edit, no second list)
#   R3  the reorganisations reached the list: cloud-webapps + cloud-me-containers are registered
#       (created, so NOT pending_creation) and absent from the family, no cloud-mobile repo, the cloud-superapp rename declared
#   R4  app/build.gradle bakes the copy (REPOS_SOT_B64) and fails the build on drift; Declarations.kt filters
#       gitFamily through the registry
#   R5  the generator refuses a repo the registry marks pending_creation, an unlisted repo and a private seed (it bites)
#   R6  the copy equals the declared url when the network answers (live drift guard; offline = reported, the
#       pin in R1 still holds)
set -uo pipefail
ROOT="${CLOUD_ANDROID_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && while [ "$PWD" != "/" ] && [ ! -e "$PWD/.git" ]; do cd ..; done; printf '%s' "$PWD")}"
APP="$ROOT/ac_cloud-drive"; DATA="$APP/data"
fails=0
ok()  { echo "  PASS  $1"; }
bad() { echo "  FAIL  $1"; fails=$((fails + 1)); }
# raw.githubusercontent.com is CDN-cached for minutes after a push, so try the contents API (always fresh) first.
fetch_registry() {  # $1 = raw url, $2 = out file
  local api; api="$(printf '%s' "$1" | sed -E 's#^https://raw.githubusercontent.com/([^/]+)/([^/]+)/(.+)/([^/]+)$#https://api.github.com/repos/\1/\2/contents/\4?ref=\3#')"
  curl -fsSL --max-time 20 -H 'Accept: application/vnd.github.raw' ${GITHUB_TOKEN:+-H "Authorization: Bearer $GITHUB_TOKEN"} "$api" -o "$2" 2>/dev/null \
    || curl -fsSL --max-time 20 "$1" -o "$2" 2>/dev/null
}
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT

echo "── R1 the copy is the pinned one ──"
want="$(node -e 'console.log(require(process.argv[1]).storage.repos_source.sha256)' "$APP/build.json")"
have="$(sha256sum "$DATA/repos.sot.json" | cut -d' ' -f1)"
[ "$want" = "$have" ] && ok "repos.sot.json sha256 = build.json pin" || bad "repos.sot.json $have != pin $want (run ac_cloud-drive/tools/sync-repos-sot.sh)"
node -e 'const r=require(process.argv[1]);process.exit(r._schema==="cloud-repos/v1"&&r.repos.length>5?0:1)' "$DATA/repos.sot.json" && ok "valid cloud-repos/v1" || bad "repos.sot.json is not cloud-repos/v1"
cmp -s "$DATA/repos.sot.json" "$ROOT/ab_cloud-libs-shared/libs/repos/repos.sot.json" && ok "libs:repos copy identical" || bad "libs:repos/repos.sot.json differs from cloud-drive's copy"

LIBS="$ROOT/ab_cloud-libs-shared/libs/repos"
python3 - "$LIBS" <<'PY' && ok "libs:repos repos.json is derived from repos.sot.json (no pending, none missing)" || bad "libs:repos repos.json is stale vs repos.sot.json (run libs/repos/sync.sh --refresh-list)"
import json,sys
d=sys.argv[1]; reg=json.load(open(d+'/repos.sot.json')); lst=json.load(open(d+'/repos.json'))
ex=set(lst['exclude']); want=sorted(r['name'] for r in reg['repos'] if r.get('group') in ('a_cloud','b_front','b_projects') and r.get('status')!='pending_creation' and r['name'] not in ex)
have=sorted(r['name'] for r in lst['repos'])
if want!=have: print('want-have',sorted(set(want)-set(have)),'have-want',sorted(set(have)-set(want))); sys.exit(1)
PY

echo "── R2 drive-git-repos.json is generated ──"
node "$APP/tools/gen-drive-git-repos.js" --check >/dev/null 2>&1 && ok "drive-git-repos.json = generator output" || { bad "drive-git-repos.json is stale"; node "$APP/tools/gen-drive-git-repos.js" --check; }

echo "── R3 the reorganisations ──"
node -e '
const r=require(process.argv[1]), f=require(process.argv[2]); const e=[];
for (const n of ["cloud-webapps","cloud-me-containers"]) { const x=r.repos.find(y=>y.name===n); if(!x) e.push(n+" not registered"); else if(x.status==="pending_creation") e.push(n+" still pending_creation (repo exists; registry must not say so)"); if (f.family.some(y=>y.name===n)) e.push(n+" is in the drive family"); }
if (r.repos.some(x=>x.name==="cloud-mobile")) e.push("cloud-mobile is a repo");
if (!(r.renames||[]).some(x=>x.from==="cloud-mobile"&&x.to==="cloud-superapp")) e.push("cloud-mobile->cloud-superapp not declared");
if (e.length){console.error(e.join("\n"));process.exit(1)}' "$DATA/repos.sot.json" "$DATA/drive-git-family.json" && ok "webapps/me-containers registered (not pending) + not offered; cloud-superapp rename declared" || bad "reorganisations missing"
old_refs="$(grep -rIn 'cloud-mobile' "$DATA" "$APP/build.json" 2>/dev/null | grep -v 'repos.sot.json' || true)"
[ -n "$old_refs" ] && bad "cloud-mobile still referenced in cloud-drive data/build.json" || ok "no cloud-mobile path in cloud-drive data"

echo "── R4 the build bakes and guards the copy ──"
g="$APP/app/build.gradle"; k="$APP/app/src/main/java/com/diegonmarcos/clouddrive/Declarations.kt"
grep -q 'REPOS_SOT_B64' "$g" && grep -q 'storageDecl.repos_source' "$g" && grep -q 'reposSotSha != ' "$g" && grep -q 'marks pending_creation' "$g" && ok "build.gradle bakes REPOS_SOT_B64 and fails on pin / unlisted / pending" || bad "build.gradle does not bake + guard the registry"
grep -q 'restrictToRegistry(parseGitFamily(decode(BuildConfig.GIT_REPOS_B64)), repoSot)' "$k" && ok "Declarations.gitFamily is filtered through the registry" || bad "gitFamily is not filtered through the registry"

echo "── R5 the generator bites ──"
cp -r "$APP" "$T/app"
node -e 'const fs=require("fs"),p=process.argv[1]+"/data/repos.sot.json",r=JSON.parse(fs.readFileSync(p));r.repos.find(x=>x.name==="cloud-me-my-ai").status="pending_creation";fs.writeFileSync(p,JSON.stringify(r))' "$T/app"
node "$T/app/tools/gen-drive-git-repos.js" >/dev/null 2>&1 && bad "generator accepted a pending repo" || ok "generator refuses a pending_creation repo"
cp "$APP/data/repos.sot.json" "$T/app/data/repos.sot.json"
node -e 'const fs=require("fs"),p=process.argv[1]+"/data/drive-git-family.json",f=JSON.parse(fs.readFileSync(p));f.family.push({name:"nowhere-repo",label:"x"});fs.writeFileSync(p,JSON.stringify(f))' "$T/app"
node "$T/app/tools/gen-drive-git-repos.js" >/dev/null 2>&1 && bad "generator accepted an unlisted repo" || ok "generator refuses a repo the registry does not list"
cp "$APP/data/drive-git-family.json" "$T/app/data/drive-git-family.json"
node -e 'const fs=require("fs"),p=process.argv[1]+"/data/drive-git-family.json",f=JSON.parse(fs.readFileSync(p));f.family.find(x=>x.name==="cloud-me-my-ai").seed=true;fs.writeFileSync(p,JSON.stringify(f))' "$T/app"
node "$T/app/tools/gen-drive-git-repos.js" >/dev/null 2>&1 && bad "generator accepted a private seed" || ok "generator refuses a private repo marked seed"
cp "$APP/data/drive-git-family.json" "$T/app/data/drive-git-family.json"
echo " " >> "$T/app/data/drive-git-repos.json"
node "$T/app/tools/gen-drive-git-repos.js" --check >/dev/null 2>&1 && bad "--check accepted a hand-edited generated file" || ok "--check fails on a hand-edited drive-git-repos.json"

echo "── R6 the copy equals the declared url ──"
url="$(node -e 'console.log(require(process.argv[1]).storage.repos_source.url)' "$APP/build.json")"
if fetch_registry "$url" "$T/live.json" && node -e 'JSON.parse(require("fs").readFileSync(process.argv[1],"utf8"))' "$T/live.json" 2>/dev/null; then
  cmp -s "$T/live.json" "$DATA/repos.sot.json" && ok "repos.sot.json = $url" || bad "repos.sot.json DRIFTED from $url (run ac_cloud-drive/tools/sync-repos-sot.sh)"
else
  echo "  INFO  $url not reachable from here; live comparison not made (R1 pin still enforced)"
fi

echo; [ "$fails" -eq 0 ] && echo "ALL PASS" || echo "$fails FAILED"; exit $((fails > 0))
