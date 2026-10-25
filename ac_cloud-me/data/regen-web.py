#!/usr/bin/env python3
"""front-media/b_Media/mySocials/dist → data/web/mysocials/

Profile does not reimplement the LinkedIn and Instagram layouts — it ships the
real pages and renders them. Anything else is an approximation that drifts the
moment the web design changes.

This copies exactly what those two pages reference: the HTML, the stylesheets
they link, and every data/script file in their <script src> tags. Nothing is
guessed and nothing unreferenced is carried.

  ./data/regen-web.py           refresh the bundle
  ./data/regen-web.py --check   exit 1 if it would change (drift)
"""
import filecmp, os, re, shutil, sys

HERE = os.path.dirname(os.path.abspath(__file__))
DIST = os.environ.get("MYSOCIALS_DIST") or os.path.normpath(os.path.join(
    HERE, "..", "..", "..", "front-media", "b_Media", "mySocials", "dist"))
OUT = os.path.join(HERE, "web", "mysocials")

# The pages Profile shows: Professional (linkedin), Personal (instagram), Social, Health and DD. Their
# dependencies are read out of the HTML, so adding another page here is a one-line change. Health and DD
# carry the public SCHEMA only; their values are injected by WebPage after sign-in (never in this bundle).
PAGES = ["linkedin.html", "instagram-diegocnmarcos_.html", "social.html", "health.html", "dd.html"]
# Files a page names in its data rather than in a src/href attribute.
EXTRA = [r"social-photo-\d+\.jpg"]

LOCAL_REF = re.compile(r'(?:src|href)="(?!https?://|#|mailto:)([^"]+)"')


def wanted():
    """Every page plus every local file those pages reference."""
    files = set(PAGES)
    for page in PAGES:
        html = open(os.path.join(DIST, page), encoding="utf-8").read()
        for ref in LOCAL_REF.findall(html):
            # index.html is the socials hub — a link out, not a dependency.
            if ref == "index.html":
                continue
            if os.path.isfile(os.path.join(DIST, ref)):
                files.add(ref)
    for f in os.listdir(DIST):
        if any(re.fullmatch(x, f) for x in EXTRA):
            files.add(f)
    return sorted(files)


def main():
    if not os.path.isdir(DIST):
        print(f"mySocials dist not present at {DIST} — skipped")
        return
    files = wanted()
    check = "--check" in sys.argv
    if check:
        stale = [f for f in files
                 if not os.path.exists(os.path.join(OUT, f))
                 or not filecmp.cmp(os.path.join(DIST, f), os.path.join(OUT, f), shallow=False)]
        extra = [f for f in os.listdir(OUT) if f not in files] if os.path.isdir(OUT) else []
        if stale or extra:
            sys.exit(f"DRIFT: {len(stale)} stale, {len(extra)} unreferenced in {OUT} — run {sys.argv[0]}")
        print(f"web bundle matches mySocials dist ({len(files)} files)")
        return
    os.makedirs(OUT, exist_ok=True)
    for f in os.listdir(OUT):
        if f not in files:
            os.remove(os.path.join(OUT, f))
    for f in files:
        shutil.copy2(os.path.join(DIST, f), os.path.join(OUT, f))
    total = sum(os.path.getsize(os.path.join(OUT, f)) for f in files)
    print(f"wrote {len(files)} files, {total // 1024}KB to {OUT}")


main()
