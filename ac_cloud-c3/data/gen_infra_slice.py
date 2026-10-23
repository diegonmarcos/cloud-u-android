#!/usr/bin/env python3
"""Derive data/infra_slice.json (the Apps tab's Services/Tools data) from cloud-infra's
_cloud-data-consolidated.json. NOTHING here is hand-listed: the tool set comes from
build.json::ui.apps_page.tools[].source, every host/zone/rule is READ from the consolidated
export. Secrets: only env-var NAMES and peer NAMES are carried - never a key or password value.

  python3 data/gen_infra_slice.py [consolidated.json [caddy-build.json]]   (default: fetch main raw)

data/routes.json + data/declarations.json (the Containers > Table rows and their More pages) are built by data/c3_routes.py from data/services_public.json +
services_private.json, the consolidated export (service facts, VM addresses) and the edge auth tiers of
cloud-u-containers infra-sec_caddy/build.json caddy_config.edge_auth.
"""
import json, os, sys, urllib.request
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import c3_routes
URL = "https://raw.githubusercontent.com/diegonmarcos/cloud-t-infra/main/1_cloud-configs/dist/_cloud-data-consolidated.json"
bj = json.load(open(os.path.join(HERE, "..", "build.json")))
decl = bj["ui"]["apps_page"]
src = sys.argv[1] if len(sys.argv) > 1 else None
CADDY_URL = "https://raw.githubusercontent.com/diegonmarcos/cloud-u-containers/main/infra-sec_caddy/build.json"
caddy_src = sys.argv[2] if len(sys.argv) > 2 else None
d = json.load(open(src)) if src else json.load(urllib.request.urlopen(URL))
S = d["services"]
edge_auth = (json.load(open(caddy_src)) if caddy_src else json.load(urllib.request.urlopen(CADDY_URL)))["caddy_config"]["edge_auth"]
short = lambda s, n=180: (s or "")[:n]

def proxy_of(sv):
    for c in (sv.get("containers") or {}).values():
        p = c.get("proxy")
        if isinstance(p, dict) and p.get("domain"):
            return p
    p = (sv.get("proxy") or {}).get("primary")
    return p if isinstance(p, dict) and p.get("domain") else {}

def tool(path):
    kind, _, key = path.partition(".")
    if kind == "native":
        n = d["native"][key]
        return {"description": short(n.get("description")), "subnet": n.get("subnet"),
                "iptables": n.get("iptables")}
    sv = S[key]
    cs = list((sv.get("containers") or {}).values())
    p = proxy_of(sv)
    ports = []
    for c in cs:
        for e in c.get("extra_ports") or []:
            if e.get("service"):
                ports.append({"port": e.get("port"), "protocol": e.get("protocol"),
                              "service": e.get("service"), "desc": short(e.get("desc"), 140)})
    return {
        "description": short(sv.get("description")), "vm": sv.get("vm"), "domain": sv.get("domain"),
        "dns": sv.get("dns"), "port": sv.get("port"),
        "images": [c["image"].split("@")[0] for c in cs if c.get("image")],
        "health": next((c.get("healthcheck") for c in cs if c.get("healthcheck")), None),
        "auth": p.get("auth"), "wg_only": p.get("wg_only"),
        "ports": ports,
        "well_known": [w.get("path") for w in (sv.get("proxy") or {}).get("well_known") or [] if w.get("path")],
        "secret_names": sorted(sv.get("secret_env_vars") or []),
    }

hosts = []
for name, sv in S.items():
    p = proxy_of(sv)
    if p.get("domain"):
        hosts.append({"host": p["domain"], "service": name, "vm": sv.get("vm"),
                      "auth": p.get("auth"), "wg_only": bool(p.get("wg_only"))})
hosts.sort(key=lambda h: h["host"])

per_vm = {}
for sv in S.values():
    if sv.get("vm"):
        per_vm[sv["vm"]] = per_vm.get(sv["vm"], 0) + len(sv.get("container_names") or [])

def mesh(m):
    peers = m.get("peers") or []
    clients = [dict(v, name=v.get("name") or k) for k, v in (m.get("clients") or {}).items()]
    return {"subnet": m.get("subnet"), "port": m.get("port"), "hub": m.get("hub"),
            "peers": [{"name": p["name"], "wg_ip": p.get("wg_ip"), "role": p.get("role")} for p in peers + clients]}

cc = S["caddy"].get("caddy_config") or {}
sn = cc.get("security_snippets") or {}
st, rl = S["stalwart"], cc.get("mta_sts") or {}
out = {
    "_meta": {"generated_by": "data/gen_infra_slice.py", "source": "cloud-infra 1_cloud-configs/dist/_cloud-data-consolidated.json",
              "note": "peer/secret NAMES only - no key or password value is ever carried"},
    "tools": {t["id"]: tool(t["source"]) for t in decl["tools"]},
    "hosts": hosts,
    "dns": {"primary": d["native"]["dns"].get("primary"),  # no fallback IP: a public resolver literal is a DNS-guard bypass (#741); the description names it
            "description": d["native"]["dns"].get("description"),
            "entries": [{k: e.get(k) for k in ("name", "type", "value", "service")} for e in d["dns"]["derived_entries"]],
            "cloudflare_records": len(d["dns"].get("cloudflare") or [])},
    "firewalls": {"global": d["firewalls"]["global"],
                  "vms": {vm: [{k: r.get(k) for k in ("port", "proto", "source", "desc", "owned_by")} for r in rules]
                          for vm, rules in d["firewalls"]["os"].items()}},
    "wireguard": {"wg0": mesh(d["native"]["wireguard"]), "wg_public": mesh(d["wireguard_public"])},
    "caddy": {"wg_cidrs": (cc.get("global") or {}).get("wg_cidrs"),
              "rate_limit": sn.get("rate_limit"), "bot_agents": len((sn.get("bot_blocker") or {}).get("user_agents") or []),
              "scanner_paths": len((sn.get("scanner_blocker") or {}).get("paths") or []),
              "hsts_max_age": (sn.get("security_headers") or {}).get("hsts_max_age")},
    "mail": {"routes": [{"name": r.get("name"), "address": r.get("address"), "port": r.get("port"),
                         "default": bool(r.get("default_outbound")), "desc": short(r.get("description"), 200)}
                        for r in st.get("mta_routes") or []],
             "mta_sts": rl,
             "dkim_secret_names": sorted({n for s in ("maddy", "stalwart") for n in S[s].get("secret_env_vars") or [] if "DKIM" in n}),
             "hosts": [{"service": s, "host": S[s].get("domain"), "vm": S[s].get("vm")}
                       for s in ("maddy", "stalwart", "cloud-webmail", "mail-puller") if s in S]},
    "containers_per_vm": per_vm,
}
# The Table's rows and per-row declarations are SEPARATE files: infra_slice.json is baked into one BuildConfig string
# (javac caps a constant at 65,535 bytes), and these two are baked as chunk arrays (app/build.gradle).
routes = c3_routes.build_routes(
    json.load(open(os.path.join(HERE, "services_public.json"))),
    json.load(open(os.path.join(HERE, "services_private.json"))),
    S, d["vms"], edge_auth)
json.dump(routes, open(os.path.join(HERE, "routes.json"), "w"), indent=1, ensure_ascii=False)
json.dump(c3_routes.build_declarations(routes, S, d["vms"]), open(os.path.join(HERE, "declarations.json"), "w"),
          indent=None, separators=(",", ":"), ensure_ascii=False)
json.dump(out, open(os.path.join(HERE, "infra_slice.json"), "w"), indent=1, ensure_ascii=False)
print("wrote infra_slice.json:", len(out["tools"]), "tools,", len(hosts), "hosts,", len(routes), "routes")
