#!/usr/bin/env python3
"""cloud-android-fleet-agents -- every agent the fleet runs, consolidated for Cloud SuperApp.

Cloud SuperApp > Configs > Apps > Agents lists the agents of the whole fleet. No list of
them is typed anywhere: every app declares its own in its build.json, top level,

  fleet_agents: [ {id, name, kind, trigger, summary, open, status}, ... ]

(the shape is documented once, in aa_cloud-superapp/build.json::_doc_fleet_agents), and this
script consolidates every declaration into aa_cloud-superapp/data/fleet-agents.json, which
app/build.gradle bakes as BuildConfig.FLEET_AGENTS_B64.

A row may instead EXPAND a list declared elsewhere, so the list is never restated:

  {from: <repo-relative JSON file>, path: <dotted path to a list>, summary_key, ...defaults}

gives one row per item of that list (id = item.id, name = item.label, summary =
item[summary_key]), every other key of the row copied onto each. Cloud Search's agents
catalogue and the shared agents.json (Claude, Hermes, Goose, OpenClaw, DeepSeek) are read
that way.

Resolved here, so the app does no lookup of its own:
  app        the declaring build.json's `name` (cloud-drive ...), which is also the
             ui.external_apps id the SuperApp opens it by
  app_label  that name as words (Cloud Drive)
  package    the app's package, from data/constellation-fleet.json (the row whose label is
             the app); for a status of source `installed`, the package of its fleet row
  open       a page: target of the declaring app; for every app but cloud-superapp it is
             emitted as extapp:<app>#page:..., the launcher's deep-link grammar

  gen ROOT     print the consolidated JSON
  write ROOT   write aa_cloud-superapp/data/fleet-agents.json (data/regen.sh calls this)
  check ROOT   exit 1 when a declaration is invalid or the committed file differs from gen
"""
import glob
import json
import os
import sys

OUT = "aa_cloud-superapp/data/fleet-agents.json"
FLEET = "aa_cloud-superapp/data/constellation-fleet.json"
SELF = "aa_cloud-superapp/build.json"
SELF_APP = "cloud-superapp"

KINDS = ["on-device worker", "on-device service", "agent door", "in-app agent",
         "remote gateway agent", "bound engine"]
# Each status source and the keys it needs. Every one is a LOCAL channel: this app's own
# WorkManager and services, PackageManager, or a fleet app's own provider. None is HTTP.
SOURCES = {
    "workmanager": ["unique"],
    "workmanager_tag": ["tag"],
    "service": ["class"],
    "provider": ["authority_suffix", "path"],
    "provider_presence": ["authority_suffix"],
    "installed": ["fleet"],
    "none": ["reason"],
}
WORDS = {"superapp": "SuperApp", "c3": "C3", "ai": "AI", "llms": "LLMs"}


def label_of(name):
    return " ".join(WORDS.get(w, w[:1].upper() + w[1:]) for w in name.split("-"))


def dig(obj, path):
    for k in path.split("."):
        if not isinstance(obj, dict) or k not in obj:
            return None
        obj = obj[k]
    return obj


def build_jsons(root):
    out = []
    for pat in ("*/build.json", "*/*/build.json"):
        for p in sorted(glob.glob(os.path.join(root, pat))):
            rel = os.path.relpath(p, root)
            if rel.startswith(("z_archive/", ".")):
                continue
            out.append(rel)
    return out


def consolidate(root):
    """(document, problems)."""
    problems = []
    fleet = json.load(open(os.path.join(root, FLEET)))
    rows = fleet.get("apps", [])
    pkg_by_label = {r.get("label"): r.get("package") for r in rows}
    pkg_by_id = {r.get("id"): r.get("package") for r in rows}
    selfbj = json.load(open(os.path.join(root, SELF)))
    ext_ids = {e.get("id") for e in dig(selfbj, "ui.external_apps") or []}

    declared = []
    for rel in build_jsons(root):
        try:
            bj = json.load(open(os.path.join(root, rel)))
        except (OSError, ValueError) as e:
            problems.append("%s: unreadable (%s)" % (rel, e))
            continue
        if "fleet_agents" not in bj:
            continue
        if not isinstance(bj["fleet_agents"], list):
            problems.append("%s: fleet_agents is not a list" % rel)
            continue
        declared.append((rel, bj))
    # cloud-superapp first, then every other app in directory order.
    declared.sort(key=lambda t: (t[0] != SELF, t[0]))

    agents = []
    for rel, bj in declared:
        app = bj.get("name") or ""
        if not app:
            problems.append("%s: declares fleet_agents but has no name" % rel)
            continue
        expanded = []
        for i, row in enumerate(bj["fleet_agents"]):
            if not isinstance(row, dict):
                problems.append("%s: fleet_agents[%d] is not an object" % (rel, i))
                continue
            if "from" in row:
                src = row["from"]
                try:
                    items = dig(json.load(open(os.path.join(root, src))), row.get("path", ""))
                except (OSError, ValueError) as e:
                    problems.append("%s: fleet_agents[%d] from %s: unreadable (%s)" % (rel, i, src, e))
                    continue
                if not isinstance(items, list) or not items:
                    problems.append("%s: fleet_agents[%d] from %s path %s is not a non-empty list"
                                    % (rel, i, src, row.get("path")))
                    continue
                base = {k: v for k, v in row.items() if k not in ("from", "path", "summary_key")}
                for it in items:
                    e = dict(base)
                    e["id"] = it.get("id", "")
                    e["name"] = it.get("label") or it.get("id", "")
                    if row.get("summary_key"):
                        e["summary"] = it.get(row["summary_key"], "")
                    if it.get("fleet"):
                        e["service"] = it["fleet"]
                    e["declared_in"] = "%s (%s::%s)" % (rel, src, row.get("path"))
                    expanded.append(e)
            else:
                e = dict(row)
                e["declared_in"] = rel
                expanded.append(e)
        for e in expanded:
            where = "%s: agent %r" % (rel, e.get("id"))
            for k in ("id", "name", "kind", "trigger", "open", "status"):
                if not e.get(k):
                    problems.append("%s has no %s" % (where, k))
            if e.get("kind") and e["kind"] not in KINDS:
                problems.append("%s: kind %r is not one of %s" % (where, e["kind"], KINDS))
            op = e.get("open", "")
            if op and not op.startswith("page:"):
                problems.append("%s: open %r is not a page: target of its app" % (where, op))
            st = e.get("status") or {}
            src = st.get("source")
            if src not in SOURCES:
                problems.append("%s: status source %r is not one of %s" % (where, src, sorted(SOURCES)))
            else:
                for k in SOURCES[src]:
                    if not st.get(k):
                        problems.append("%s: status source %s needs %s" % (where, src, k))
            if src == "installed":
                p = pkg_by_id.get(st.get("fleet"))
                if not p:
                    problems.append("%s: installed fleet row %r is not in %s" % (where, st.get("fleet"), FLEET))
                else:
                    st = dict(st, package=p)
            if app != SELF_APP and app not in ext_ids:
                problems.append("%s: app %s is not in %s ui.external_apps, so nothing could open it"
                                % (where, app, SELF))
            out = {
                "id": "%s/%s" % (app, e.get("id", "")),
                "name": e.get("name", ""),
                "app": app,
                "app_label": label_of(app),
                "package": pkg_by_label.get(app) or (dig(bj, "android.application_id") or ""),
                "kind": e.get("kind", ""),
                "trigger": e.get("trigger", ""),
                "summary": e.get("summary", ""),
                "open": op if app == SELF_APP else "extapp:%s#%s" % (app, op),
                "status": st,
                "declared_in": e["declared_in"],
            }
            for k in ("service", "used_by", "contract"):
                if e.get(k):
                    out[k] = e[k]
            agents.append(out)

    seen = set()
    for a in agents:
        if a["id"] in seen:
            problems.append("agent id %s is declared twice" % a["id"])
        seen.add(a["id"])
    if not agents:
        problems.append("no app declares fleet_agents: the Agents page would be empty by accident")

    doc = {
        "_doc": "GENERATED by 1_cicd/src/scripts/cloud-android-fleet-agents.py from every app's "
                "build.json::fleet_agents (do not edit; run data/regen.sh or the script's write). "
                "Read by Cloud SuperApp > Configs > Apps > Agents through BuildConfig.FLEET_AGENTS_B64.",
        "kinds": KINDS,
        "agents": agents,
    }
    return doc, problems


def dump(doc):
    return json.dumps(doc, indent=2, ensure_ascii=False) + "\n"


def main(argv):
    if len(argv) != 3 or argv[1] not in ("gen", "write", "check"):
        print(__doc__)
        return 2
    root = argv[2]
    doc, problems = consolidate(root)
    if argv[1] == "gen":
        sys.stdout.write(dump(doc))
        return 1 if problems else 0
    if argv[1] == "write":
        for p in problems:
            print("ERROR: " + p, file=sys.stderr)
        if problems:
            return 1
        with open(os.path.join(root, OUT), "w") as h:
            h.write(dump(doc))
        print("wrote %s (%d agents)" % (OUT, len(doc["agents"])))
        return 0
    path = os.path.join(root, OUT)
    committed = open(path).read() if os.path.exists(path) else ""
    if committed != dump(doc):
        problems.append("%s is stale: run python3 1_cicd/src/scripts/cloud-android-fleet-agents.py "
                        "write . (or aa_cloud-superapp/data/regen.sh)" % OUT)
    for p in problems:
        print("FAIL  " + p)
    print("── %d agent(s) declared by %d app(s); %d problem(s) ──"
          % (len(doc["agents"]), len({a["app"] for a in doc["agents"]}), len(problems)))
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
