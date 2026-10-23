#!/usr/bin/env python3
"""data/web/mysocials/data-linkedin.json.js -> data/ui/study/{cv,roadmap}.json + data/ui/work/employed.json

Study (CV, Roadmap) and Work (Employed Endeavours) are the owner's public LinkedIn
profile, already bundled for Profile > Professional. There is no LinkedIn API, so
the bundle is the one source: when the profile changes, refresh that bundle, then
run this. Nothing is typed twice.

  ./data/regen-study.py           rewrite the three page files
  ./data/regen-study.py --check   exit 1 if they would change (drift)

The roadmap rule is declared here, not guessed: "Now" is every role dated
"Present", "Next" is every course whose end year is REF_YEAR or later.
"""
import json, os, re, sys

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, "web", "mysocials", "data-linkedin.json.js")
UI = os.path.join(HERE, "ui")
REF_YEAR = 2026
BREAK = "Career break"  # not employment: kept on the CV, left out of Work


def load():
    text = open(SRC, encoding="utf-8").read()
    data, _ = json.JSONDecoder().raw_decode(text[text.index("= {") + 2:])
    return data


def end_year(dates):
    tail = dates.split(" - ")[-1]
    m = re.search(r"(\d{4})", tail)
    return int(m.group(1)) if m else None


def present(dates):
    return "Present" in dates.split(" - ")[-1]


def job_card(e, body=False):
    meta = [{"label": "Dates", "value": e["dates"].split(" · ")[0]}]
    if e.get("location", "").strip():
        meta.append({"label": "Location", "value": e["location"]})
    card = {"title": e["title"], "subtitle": e["company"], "accent": "#7E57C2", "meta": meta}
    if body and e.get("description", "").strip():
        card["body"] = e["description"]
    return card


def edu_card(e, body=False):
    card = {"title": e["school"], "subtitle": e["degree"], "accent": "#42A5F5",
            "meta": [{"label": "Dates", "value": e["dates"]}]}
    if body and e.get("description", "").strip():
        card["body"] = e["description"]
    return card


def build(d):
    p = d["profile"]
    cv = [
        {"kind": "note", "title": p["name"],
         "body": f'{p["headline"]}\n{p["location"]} · {p["url"]}'},
        {"kind": "note", "title": "About", "body": d["about"]},
        {"kind": "section_title", "title": "Experience"},
        {"kind": "cards", "items": [job_card(e) for e in d["experience"]]},
        {"kind": "section_title", "title": "Education"},
        {"kind": "cards", "items": [edu_card(e) for e in d["education"]]},
        {"kind": "section_title", "title": "Skills"},
        {"kind": "note", "title": "", "body": " · ".join(d["skills"])},
        {"kind": "stats", "title": "Languages",
         "rows": [{"label": l["name"], "value": l["proficiency"]} for l in d["languages"]]},
    ]
    now = [e for e in d["experience"] if present(e["dates"])]
    nxt = [e for e in d["education"] if (end_year(e["dates"]) or 0) >= REF_YEAR]
    roadmap = [
        {"kind": "note", "title": "Roadmap",
         "body": "Derived from the LinkedIn profile: what is under way now, and the courses that run on. "
                 "Refresh the profile bundle and run data/regen-study.py to move it."},
        {"kind": "section_title", "title": "Now"},
        {"kind": "cards", "items": [job_card(e, body=True) for e in now]},
        {"kind": "section_title", "title": "Next"},
        {"kind": "cards", "items": [edu_card(e, body=True) for e in nxt]}
        if nxt else {"kind": "note", "title": "Nothing scheduled", "body": "No course on the profile runs past this year."},
    ]
    jobs = [e for e in d["experience"] if not e["company"].startswith(BREAK)]
    work = [
        {"kind": "note", "title": "Employed Endeavours",
         "body": f"{len(jobs)} roles, newest first. Career breaks are on the CV, not here."},
        {"kind": "cards", "items": [job_card(e, body=True) for e in jobs]},
    ]
    return {"study/cv.json": cv, "study/roadmap.json": roadmap, "work/employed.json": work}


def main(argv):
    out = build(load())
    drift = []
    for rel, blocks in out.items():
        path = os.path.join(UI, rel)
        text = json.dumps(blocks, indent=2, ensure_ascii=False) + "\n"
        have = open(path, encoding="utf-8").read() if os.path.exists(path) else None
        if have != text:
            drift.append(rel)
            if "--check" not in argv:
                os.makedirs(os.path.dirname(path), exist_ok=True)
                open(path, "w", encoding="utf-8").write(text)
    if "--check" in argv:
        for r in drift:
            print(f"DRIFT: data/ui/{r} differs from data-linkedin.json.js — run data/regen-study.py")
        return 1 if drift else 0
    print("wrote" if drift else "unchanged", ", ".join(drift) or "")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
