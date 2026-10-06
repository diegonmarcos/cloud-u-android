# ─── GENERATED: do not edit — edit 1_cicd/src/scripts/cloud-android-ai-registry-refresh.py ───
#!/usr/bin/env python3
"""Refresh every AI model registry from the live OpenRouter catalogue, in place.

The registries (aa_cloud-superapp/test/ai-registries.json lists them) bake
name, size, quantisation, categories and prices into each app's BuildConfig;
nothing on a phone refreshes quant and trained_for, and the scheduled
"Test -> AI model registry" run goes red the day OpenRouter changes either.
test-keyboard-ai-routing.sh T7/T8 live print every drift; this script applies
exactly what those blocks compare, so the refresh is one command instead of a
hand edit per row per registry:

  * trained_for  <- the categories OpenRouter ranks the model in today
                    (the same twelve category queries T8 issues)
  * quant        <- the quantisations its live endpoints report, "unknown" dropped
  * prompt/completion <- the catalogue price in dollars per million tokens, but
                    ONLY when it drifted by more than the 1 % T7 tolerates, so an
                    owner-authored figure inside the band stays as written
  * pricing_as_of <- today, on every registry that changed

Edits are textual, row by row, so the hand formatting of each build.json is
kept (one "prompt"/"completion" line, one "quant" line, one "trained_for"
line per model). A row this script cannot find in that shape is reported and
left alone. Exit 0 = refreshed (or nothing to do); 1 = a row could not be
rewritten or the catalogue was unreachable.

Usage: python3 1_cicd/src/scripts/cloud-android-ai-registry-refresh.py [--dry-run] [--date YYYY-MM-DD]
"""
import collections, datetime, json, os, re, sys, urllib.request

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
MANIFEST = os.path.join(ROOT, "aa_cloud-superapp/test/ai-registries.json")
CATEGORIES = ("programming", "roleplay", "marketing", "marketing/seo", "technology", "science",
              "translation", "legal", "finance", "health", "trivia", "academia")
TOLERANCE = 0.01


def get(url):
    with urllib.request.urlopen(url, timeout=40) as r:
        return json.load(r)


def fmt_price(usd_per_million):
    s = ("%.4f" % usd_per_million).rstrip("0").rstrip(".")
    return s if s else "0"


def jlist(xs, like):
    """Render xs in the style of the list text it replaces: one line, or one item per line."""
    m = re.match(r"\[\n(\s*)", like)
    if not m or not xs:
        return "[" + ", ".join('"%s"' % x for x in xs) + "]"
    inner, close = m.group(1), re.search(r"\n(\s*)\]$", like).group(1)
    return "[\n" + ",\n".join(inner + '"%s"' % x for x in xs) + "\n" + close + "]"


def main(argv):
    dry = "--dry-run" in argv
    today = datetime.date.today().isoformat()
    if "--date" in argv:
        today = argv[argv.index("--date") + 1]
    regs = json.load(open(MANIFEST))["registries"]
    first = json.load(open(os.path.join(ROOT, regs[0]["path"])))[regs[0]["key"]]["providers"]["openrouter"]
    try:
        catalogue = {m["id"]: m for m in get(first["catalog_url"])["data"]}
        ranked = collections.defaultdict(set)
        for c in CATEGORIES:
            for m in get("https://openrouter.ai/api/v1/models?category=" + c.replace("/", "%2F"))["data"]:
                ranked[m["id"]].add(c)
    except Exception as e:  # noqa: BLE001
        print("catalogue unreachable: %s" % e)
        return 1
    endpoints = {}
    problems, changed_files = [], []
    for r in regs:
        path = os.path.join(ROOT, r["path"])
        text = open(path, encoding="utf-8").read()
        models = json.loads(text)[r["key"]]["providers"]["openrouter"]["models"]
        new = text
        touched = []
        for m in models:
            mid = m["id"]
            if mid not in endpoints:
                try:
                    endpoints[mid] = get("https://openrouter.ai/api/v1/models/%s/endpoints" % mid)["data"]["endpoints"]
                except Exception as e:  # noqa: BLE001
                    problems.append("%s %s: endpoints unreachable (%s)" % (r["label"], mid, e))
                    endpoints[mid] = None
            live_cats = sorted(ranked.get(mid, set()))
            live_q = None if endpoints[mid] is None else sorted(
                {e.get("quantization") for e in endpoints[mid] if e.get("quantization")} - {"unknown"})
            # The row: from its "id" line to the closing brace of that object.
            row = re.search(r'(\{\s*\n\s*"id": "%s",.*?\n\s*\})' % re.escape(mid), new, re.S)
            if not row:
                problems.append("%s %s: row not found in the expected shape" % (r["label"], mid)); continue
            block = row.group(1)
            nb = block
            if sorted(m.get("trained_for", [])) != live_cats:
                nb2, n = re.subn(r'("trained_for": )(\[[^\]]*\])', lambda g: g.group(1) + jlist(live_cats, g.group(2)), nb, count=1)
                if n != 1:
                    problems.append("%s %s: no trained_for line to rewrite" % (r["label"], mid)); continue
                nb = nb2; touched.append("%s trained_for -> %s" % (mid, live_cats))
            if live_q is not None and sorted(m.get("quant", [])) != live_q:
                nb2, n = re.subn(r'("quant": )(\[[^\]]*\])', lambda g: g.group(1) + jlist(live_q, g.group(2)), nb, count=1)
                if n != 1:
                    problems.append("%s %s: no quant line to rewrite" % (r["label"], mid)); continue
                nb = nb2; touched.append("%s quant -> %s" % (mid, live_q))
            c = catalogue.get(mid)
            if c:
                for k in ("prompt", "completion"):
                    live = float(c["pricing"][k]) * 1e6
                    if abs(live - m[k]) > TOLERANCE * max(live, m[k]):
                        nb2, n = re.subn(r'("%s": )(-?\d+(?:\.\d+)?)' % k, lambda g: g.group(1) + fmt_price(live), nb, count=1)
                        if n != 1:
                            problems.append("%s %s: no %s price to rewrite" % (r["label"], mid, k)); break
                        nb = nb2; touched.append("%s %s %s -> %s" % (mid, k, m[k], fmt_price(live)))
            if nb != block:
                new = new.replace(block, nb, 1)
        if new != text:
            new, n = re.subn(r'("pricing_as_of": ")[^"]*(")', lambda g: g.group(1) + today + g.group(2), new, count=1)
            if n != 1:
                problems.append("%s: no pricing_as_of to bump" % r["label"])
            json.loads(new)  # still a document
            changed_files.append(r["path"])
            print("== %s (%s)" % (r["label"], r["path"]))
            for t in touched:
                print("   " + t)
            if not dry:
                open(path, "w", encoding="utf-8").write(new)
    if problems:
        print("\n".join("PROBLEM " + p for p in problems))
        return 1
    print("refreshed %d registr%s%s" % (len(changed_files), "y" if len(changed_files) == 1 else "ies",
                                        " (dry run)" if dry else ""))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
