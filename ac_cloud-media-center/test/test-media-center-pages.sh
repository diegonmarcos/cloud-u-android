#!/bin/sh
# test-media-center-pages.sh — the seven pages draw what web/data says, and the
# app is wired to show them.
#
# Static, no build, no network: node runs web/assets/pages.js's pure RENDER
# half against the committed web/data/*.json, and python reads the Tauri and
# build.json declarations. Each check is then turned red on a planted defect in
# a scratch copy, so a check that cannot fail does not count.
set -eu
HERE="$(cd "$(dirname "$0")" && pwd)"
APP="$(cd "$HERE/.." && pwd)"
command -v node >/dev/null || { echo "ERROR: node is required"; exit 1; }
command -v python3 >/dev/null || { echo "ERROR: python3 is required"; exit 1; }

check() {
  node - "$1" <<'JS' || return 1
const path = require('path'), fs = require('fs');
const app = process.argv[2];
const P = require(path.join(app, 'web/assets/pages.js'));
const bj = JSON.parse(fs.readFileSync(path.join(app, 'build.json'), 'utf8'));
const fails = [];
const ok = (c, m) => { if (c) console.log('  ok   ' + m); else { console.log('  FAIL ' + m); fails.push(m); } };
const load = (p) => JSON.parse(fs.readFileSync(path.join(app, 'web/data', p + '.json'), 'utf8'));
const ids = P.PAGES.map((p) => p.id);
ok(JSON.stringify(ids) === JSON.stringify(bj.data.pages), 'the page table is build.json::data.pages, in order (' + ids.join(', ') + ')');
for (const id of ids) {
  const html = fs.existsSync(path.join(app, 'web', id, 'index.html')) ? fs.readFileSync(path.join(app, 'web', id, 'index.html'), 'utf8') : '';
  ok(html.includes('data-page="' + id + '"'), 'web/' + id + '/index.html exists and names its page');
  const d = load(id);
  ok(d.source === bj.data.source.repo + '@' + bj.data.source.commit, id + ': data is stamped with the pinned source');
  const out = P.RENDER[id].render(d, {});
  ok(out.length > 200 && !out.includes('class="empty"'), id + ': renders content, not the empty state');
}
const mu = load('music');
const nTracks = mu.sections.reduce((a, s) => a + s.folders.reduce((b, f) => b + f.playlists.reduce((c, p) => c + p.items.length, 0), 0), 0);
ok(nTracks === mu.profile.tracks, 'music: every TIDAL track is in the data (' + nTracks + ')');
const first = mu.sections[0].folders[0].playlists[0];
const pl = P.RENDER.music.render(mu, { playlist: mu.sections[0].folders[0].name + '/' + first.name });
ok(pl.includes('href="https://tidal.com/search?q=' + encodeURIComponent((first.items[0][1] + ' ' + first.items[0][0]).trim()).replace(/'/g, '&#39;') + '"'), 'music: a track links to a TIDAL search for "artist title"');
const vi = load('videos');
const allV = vi.playlists.flatMap((p) => p.videos);
ok(allV.length > 0 && allV.every((v) => /^[A-Za-z0-9_-]{11}$/.test(v.id)), 'videos: every id is an 11-character YouTube id');
ok(P.RENDER.videos.render(vi, {}).includes('https://www.youtube.com/watch?v=' + allV[0].id), 'videos: a card opens youtube.com/watch?v=<id>');
const ga = load('gallery');
ok(ga.boards.every((b) => b.url.endsWith('/' + b.name.toLowerCase().replace(/[^a-z0-9]+/g, '-') + '/')), "gallery: board URLs follow main.ts's slug rule");
const bo = load('books');
ok(P.RENDER.books.render(bo, {}).includes('https://openlibrary.org/isbn/' + bo.books[0].isbn), 'books: a book opens its Open Library page');
const fe = load('feed');
ok(fe.items.length > 0 && fe.items.every((i, n) => n === 0 || fe.items[n - 1].date >= i.date), 'feed: items are newest first');
const mo = load('movies');
ok(mo.shelves.every((s) => s.ids.every((i) => mo.movies[i])), 'movies: every shelf names only titles that exist');
ok(P.renderMovieDetail(mo, mo.movies[mo.shelves[0].ids[0]]).includes('https://www.imdb.com/title/' + mo.shelves[0].ids[0] + '/'), 'movies: the detail sheet links IMDb');
const dc = load('documentaries');
const docsPl = vi.playlists.find((p) => p.name === 'Docs');
ok(docsPl && JSON.stringify(dc.videos) === JSON.stringify(docsPl.videos), "documentaries: the YouTube 'Docs' playlist, whole");
const docFilms = Object.values(mo.movies).filter((m) => m.genre.includes('Documentary')).map((m) => m.id).sort();
ok(docFilms.length > 0 && JSON.stringify(dc.films.map((m) => m.id).sort()) === JSON.stringify(docFilms), 'documentaries: every movie whose genre is Documentary');
const pages = fs.readFileSync(path.join(app, 'web/assets/pages.js'), 'utf8');
ok(/opener\.openUrl/.test(pages) && /a\[data-ext\]/.test(pages), 'links out go through tauri-plugin-opener');
process.exit(fails.length ? 1 : 0);
JS
  python3 - "$1" <<'PY' || return 1
import json, os, re, sys
app = sys.argv[1]
bj = json.load(open(os.path.join(app, "build.json")))
conf = json.load(open(os.path.join(app, "src-tauri/tauri.conf.json")))
cargo = open(os.path.join(app, "src-tauri/Cargo.toml")).read()
lib = open(os.path.join(app, "src-tauri/src/lib.rs")).read()
cap = json.load(open(os.path.join(app, "src-tauri/capabilities/default.json")))
fails = []
def ok(c, m):
    print(("  ok   " if c else "  FAIL ") + m)
    if not c: fails.append(m)
ok(conf["identifier"] == bj["android"]["application_id"], "tauri.conf.json identifier is build.json::android.application_id")
ok(conf["productName"] == bj["name"], "tauri.conf.json productName is build.json::name")
ok(conf["build"].get("frontendDist") == "../" + bj["tauri"]["web_dir"], "tauri.conf.json bundles build.json::tauri.web_dir")
ok(re.search(r'^tauri-plugin-opener\s*=', cargo, re.M) is not None and "tauri_plugin_opener::init()" in lib, "tauri-plugin-opener is a dependency and registered")
ok("opener:default" in cap.get("permissions", []), "the window holds opener:default")
ok(bj["android"]["application_id"] != "com.diegonmarcos.mediacenter", "not the gallery's applicationId (com.diegonmarcos.mediacenter is cloud-gallery's)")
sys.exit(1 if fails else 0)
PY
}

echo "== the pages and their wiring =="
check "$APP"
[ "${1:-}" = "check" ] && exit 0

echo "== mutations: each planted defect must turn the check red =="
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
fails=0
mutate() {
  rm -rf "$WORK/app"; mkdir -p "$WORK/app"
  cp -R "$APP/build.json" "$APP/web" "$WORK/app/"; mkdir -p "$WORK/app/src-tauri"
  cp -R "$APP/src-tauri/tauri.conf.json" "$APP/src-tauri/Cargo.toml" "$APP/src-tauri/src" "$APP/src-tauri/capabilities" "$WORK/app/src-tauri/"
  (cd "$WORK/app" && sh -c "$2")
  if check "$WORK/app" >/dev/null 2>&1; then echo "  FAIL mutation $1: still green"; fails=$((fails + 1)); else echo "  ok   mutation $1: red"; fi
}
mutate page-missing "rm -r web/books"
mutate data-unpinned "python3 -c \"import json;p='web/data/feed.json';d=json.load(open(p));d['source']='x@main';json.dump(d,open(p,'w'))\""
mutate video-id-garbled "python3 -c \"import json;p='web/data/videos.json';d=json.load(open(p));d['playlists'][0]['videos'][0]['id']='bad';json.dump(d,open(p,'w'))\""
mutate slug-rule-broken "python3 -c \"import json;p='web/data/gallery.json';d=json.load(open(p));d['boards'][0]['url']+='x/';json.dump(d,open(p,'w'))\""
mutate docs-dropped "python3 -c \"import json;p='web/data/documentaries.json';d=json.load(open(p));d['films']=[];json.dump(d,open(p,'w'))\""
mutate opener-unregistered "sed -i 's/\.plugin(tauri_plugin_opener::init())//' src-tauri/src/lib.rs"
mutate gallery-identity "sed -i 's/com.diegonmarcos.cloudmediacenter/com.diegonmarcos.mediacenter/' build.json src-tauri/tauri.conf.json"
[ "$fails" -eq 0 ] || { echo "$fails mutation(s) stayed green"; exit 1; }
echo "all checks green, all mutations red"
