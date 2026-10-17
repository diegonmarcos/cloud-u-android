#!/usr/bin/env python3
"""Regenerate web/data/*.json from diegonmarcos.github.io at the pinned commit.

build.json::data names the source repo, the commit and one input path per page.
Every input is fetched from raw.githubusercontent.com at THAT commit (never a
branch), reduced to what the mobile pages draw, and written to web/data/. The
pages never fetch at runtime; this script is the only way the data moves.

  python3 data/regen.py              fetch, reduce, write web/data/*.json
  python3 data/regen.py --from DIR   read the inputs from DIR/<input path> instead
  python3 data/regen.py --check      regenerate in memory, fail if web/data differs
"""
import json, os, re, subprocess, sys, tempfile, time, urllib.request

APP = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CFG = json.load(open(os.path.join(APP, "build.json")))["data"]
REPO, COMMIT = CFG["source"]["repo"], CFG["source"]["commit"]
RAW = "https://raw.githubusercontent.com/%s/%s/" % (REPO, COMMIT)
OUT = os.path.join(APP, "web", "data")
YT_ID = re.compile(r"/vi(?:_webp)?/([A-Za-z0-9_-]{11})/")


def fetch(url):
    for attempt in range(5):
        try:
            with urllib.request.urlopen(url, timeout=60) as r:
                return r.read()
        except Exception as e:  # transient network errors: retry, then fail loud
            last = e
            time.sleep(2 * (attempt + 1))
    raise SystemExit("regen: could not fetch %s: %s" % (url, last))


def read_input(key, src_dir):
    rel = CFG["inputs"][key]
    if src_dir:
        return open(os.path.join(src_dir, rel), "rb").read()
    return fetch(RAW + rel)


def poster_paths(src_dir):
    """Which movie posters exist at the pinned commit (the site's dist/posters)."""
    if src_dir:
        base = os.path.join(src_dir, "c-Circus/movies/dist/posters")
        return {"c-Circus/movies/dist/posters/" + n for n in os.listdir(base)} if os.path.isdir(base) else set()
    tree = json.loads(fetch("https://api.github.com/repos/%s/git/trees/%s?recursive=1" % (REPO, COMMIT)))
    return {t["path"] for t in tree["tree"] if t["path"].startswith("c-Circus/movies/dist/posters/")}


def tidal_search(title, artist):
    q = ("%s %s" % (artist or "", title or "")).strip()
    return "https://tidal.com/search?q=" + urllib.request.quote(q)


def music(raw):
    d = json.loads(raw)
    sections = []
    for s in d["sections"]:
        folders = []
        for f in s["folders"]:
            pls = []
            for p in f["playlists"]:
                tl = p.get("trackList") or []
                cover = next((t["cover"] for t in tl if t.get("cover")), None)
                pls.append({"name": p["name"], "tracks": p.get("tracks", len(tl)), "cover": cover,
                            "items": [[t.get("title", ""), t.get("artist", "")] for t in tl]})
            folders.append({"name": f["name"], "playlists": pls})
        sections.append({"name": s["name"], "folders": folders})
    return {"_doc": "From tidal.json. A track opens a TIDAL search for 'artist title' (TIDAL ids are not in the source).",
            "search": "https://tidal.com/search?q=",
            "profile": d.get("profile", {}), "sections": sections}


def yt_video(v):
    m = YT_ID.search(v.get("thumbnail", ""))
    if not m:
        return None
    return {"id": m.group(1), "title": v.get("title", ""), "duration": v.get("duration", "")}


def videos(raw):
    d = json.loads(raw)
    pls = []
    for p in d["playlists"]:
        vids = [x for x in (yt_video(v) for v in p.get("videos", [])) if x]
        pls.append({"name": p["name"], "videos": vids})
    return {"_doc": "From youtube.json. Video ids are parsed from the thumbnail URLs (i.ytimg.com/vi/<id>/...); thumbnails are rebuilt from the id.",
            "playlists": pls}


def gallery(raw):
    d = json.loads(raw)
    user = (d.get("profile") or {}).get("username", "")
    boards = []
    for b in d["boards"]:
        # The slug rule of mySocials main.ts: lower-case, every run of
        # non [a-z0-9] characters becomes one '-'.
        slug = re.sub(r"[^a-z0-9]+", "-", b["name"].lower())
        boards.append({"name": b["name"], "pins": b.get("pins", 0), "cover": b.get("cover"),
                       "url": "https://www.pinterest.com/%s/%s/" % (user, slug)})
    return {"_doc": "From pinterest.json; board URLs use main.ts's slug rule.",
            "profile_url": "https://www.pinterest.com/%s/" % user, "boards": boards}


def books(raw):
    d = json.loads(raw)
    return {"_doc": "From shelf.json. Covers and pages come from Open Library by ISBN.",
            "books": [{"title": b["title"], "author": b.get("author", ""), "isbn": b.get("isbn", ""),
                       "spine": b.get("spine", "#444")} for b in d.get("books", [])],
            "vinyls": d.get("vinyls", [])}


def strip_html(s):
    return re.sub(r"\s+", " ", re.sub(r"<[^>]+>", " ", s or "")).strip()


def feed(raw):
    src = raw.decode("utf-8")
    src = re.sub(r"^import .*$", "", src, flags=re.M)
    src = re.sub(r"export const sampleFeed\s*:\s*FeedItem\[\]\s*=", "module.exports =", src)
    with tempfile.TemporaryDirectory() as tmp:
        js = os.path.join(tmp, "feed.cjs")
        open(js, "w").write(src)
        items = json.loads(subprocess.check_output(
            ["node", "-e", "process.stdout.write(JSON.stringify(require(process.argv[1])))", js]))
    out = []
    for it in items:
        t = it.get("type")
        text = it.get("excerpt") or it.get("summary") or ""
        if t == "tweet":
            text = it.get("content", "")
        elif not text:
            text = strip_html(it.get("content", ""))[:400]
        out.append({
            "id": it.get("id"), "type": t, "title": it.get("title", ""),
            "text": text,
            "body": it.get("content", "") if t == "markdown" else "",
            "url": it.get("url") or "",
            "source": (it.get("source") or {}).get("name") or (it.get("feed") or {}).get("name") or "",
            "author": (it.get("author") or {}).get("name", ""),
            "date": it.get("publishDate") or it.get("timestamp") or it.get("_createdAt") or "",
            "tags": it.get("categories") or it.get("tags") or [],
            "read_min": it.get("readTime"),
        })
    out.sort(key=lambda x: x["date"], reverse=True)
    return {"_doc": "From myfeed's sampleFeed.ts (the feed's sample content), newest first.", "items": out}


def movie_row(k, v, posters):
    p = "c-Circus/movies/dist/" + v.get("Poster", "")
    return {"id": k, "title": v.get("Title", ""), "year": v.get("Year", ""), "type": v.get("Type", "movie"),
            "rating": v.get("imdbRating", ""), "genre": v.get("Genre", ""), "plot": v.get("Plot", ""),
            "director": v.get("Director", ""), "runtime": v.get("Runtime", ""),
            "poster": RAW + p if p in posters else None}


CAT_NAMES = {"home": "Now showing", "series2025": "Series 2025", "ghibli": "Studio Ghibli", "european": "European cinema",
             "newnoirs": "New noirs", "scifi": "Science fiction", "staffpicks": "Staff picks", "epics": "Epics",
             "thrillers": "Thrillers", "epicseries": "Epic series", "ridleyscott": "Ridley Scott",
             "prestige20m": "Prestige", "prestigepre22": "Prestige before 2022", "prestigetier2": "Prestige, tier 2",
             "alpacino": "Al Pacino", "deniro": "De Niro", "dicaprio": "DiCaprio"}


def cat_name(k):
    if k in CAT_NAMES:
        return CAT_NAMES[k]
    m = re.match(r"oscars(\d{4})$", k)
    if m:
        return "Oscars " + m.group(1)
    return k[:1].upper() + k[1:]


def movies(raw_movies, raw_cats, posters):
    m = json.loads(raw_movies)
    cats = json.loads(raw_cats)
    rows = {k: movie_row(k, v, posters) for k, v in m.items()}
    shelves = [{"id": k, "name": cat_name(k), "ids": [i for i in ids if i in rows]} for k, ids in cats.items()]
    return {"_doc": "From c-Circus/movies src/data (movies.json + categories.json). Posters are the site's own, at the pinned commit; a title opens its IMDb page.",
            "imdb": "https://www.imdb.com/title/", "movies": rows, "shelves": shelves}


def documentaries(v, mv):
    docs = next((p for p in v["playlists"] if p["name"] == "Docs"), {"videos": []})
    films = [r for r in mv["movies"].values() if "Documentary" in r["genre"]]
    return {"_doc": "The YouTube 'Docs' playlist plus every movie whose genre includes Documentary.",
            "videos": docs["videos"], "films": films}


def build(src_dir):
    v = videos(read_input("videos", src_dir))
    mv = movies(read_input("movies", src_dir), read_input("movie_categories", src_dir), poster_paths(src_dir))
    return {
        "music": music(read_input("music", src_dir)),
        "videos": v,
        "gallery": gallery(read_input("gallery", src_dir)),
        "books": books(read_input("books", src_dir)),
        "feed": feed(read_input("feed", src_dir)),
        "movies": mv,
        "documentaries": documentaries(v, mv),
    }


SHARED = CFG.get("shared")


def wrap_core(src):
    """sources-core.mjs (ESM) -> a plain script: window.LegalSources / module.exports."""
    names = re.findall(r"^export (?:async )?(?:function|const) (\w+)", src, re.M)
    body = re.sub(r"^export ", "", src, flags=re.M)
    return ("/* GENERATED by data/regen.py from %s@%s:%s - do not edit. */\n(function (root) {\n'use strict';\n%s\nvar api = { %s };\n"
            "if (typeof module !== 'undefined' && module.exports) module.exports = api; else root.LegalSources = api;\n})(typeof window !== 'undefined' ? window : this);\n"
            % (SHARED["repo"], SHARED["commit"], SHARED["files"]["core"][0], body, ", ".join("%s: %s" % (n, n) for n in names)))


def shared_files(src_dir):
    """{out path: text} for build.json::data.shared, fetched at its own pin."""
    if not SHARED:
        return {}
    raw = "https://raw.githubusercontent.com/%s/%s/" % (SHARED["repo"], SHARED["commit"])
    out = {}
    for key, (rel, dest) in SHARED["files"].items():
        if not isinstance(rel, str) or key.startswith("_"):
            continue
        text = (open(os.path.join(src_dir, rel), "rb").read() if src_dir else fetch(raw + rel)).decode("utf-8")
        out[dest] = wrap_core(text) if key == "core" else text
    return out


def dump(obj):
    return json.dumps(dict(obj, source="%s@%s" % (REPO, COMMIT)), ensure_ascii=False, separators=(",", ":")) + "\n"


def main(argv):
    src_dir = argv[argv.index("--from") + 1] if "--from" in argv else None
    data = build(src_dir)
    assert sorted(data) == sorted(CFG["pages"]), "build.json::data.pages and regen.py disagree"
    if "--check" in argv:
        bad = [k for k, v in data.items()
               if not os.path.exists(os.path.join(OUT, k + ".json")) or open(os.path.join(OUT, k + ".json")).read() != dump(v)]
        bad += [d for d, t in shared_files(src_dir).items()
                if not os.path.exists(os.path.join(APP, d)) or open(os.path.join(APP, d), encoding="utf-8").read() != t]
        if bad:
            raise SystemExit("regen --check: web/data differs from the pinned source for: " + ", ".join(bad))
        print("regen --check: web/data matches %s@%s" % (REPO, COMMIT))
        return
    os.makedirs(OUT, exist_ok=True)
    for k, v in data.items():
        open(os.path.join(OUT, k + ".json"), "w").write(dump(v))
        print("wrote web/data/%s.json (%d bytes)" % (k, len(dump(v))))
    for d, t in shared_files(src_dir).items():
        open(os.path.join(APP, d), "w", encoding="utf-8").write(t)
        print("wrote %s (%d bytes)" % (d, len(t)))


if __name__ == "__main__":
    main(sys.argv[1:])
