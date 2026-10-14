# ─── GENERATED: do not edit — edit 1_cicd/src/scripts/cloud-android-ipc-mesh.py ───
#!/usr/bin/env python3
"""
cloud-android-ipc-mesh -- #ipc THE fleet IPC registry, and the guard that keeps app-to-app
traffic on one phone off HTTP (a0_docs/eng-specs/fleet-ipc.md).

The rule: two fleet apps on the same phone talk over Android IPC (a content provider, a bound
service / AIDL, an intent). The loopback HTTP debug APIs are for callers OFF the phone (a PC,
a server, the MCP, an agent, CI), through the SuperApp gateway.

  gen   [ROOT]   writes REGISTRY from the declarations, nothing else:
                   - the fleet roster (aa_cloud-superapp/data/constellation-fleet.json),
                   - every AndroidManifest.xml that merges into a member (its own tree, plus
                     the manifest of each shared lib it compiles: cloud_android_lib_closure),
                     taking each exported component a fleet app can reach: guarded by a
                     signature permission, or answering a fleet action, or a deep link,
                   - the fleet IPC layer's ops: libs:devtools AppDebugServer.dispatch (every
                     member that links devtools serves them on content://<pkg>.fleet) and each
                     member's own AppDebugServer.route(group, Op(...)) registrations,
                   - AIDL interfaces and their methods, for a service whose class implements
                     one (I<Name>.Stub),
                   - callers: a member whose compiled sources name the endpoint (an authority,
                     an action, a deep link, or an op in a file that talks FleetIpc).
  check [ROOT]   I1 REGISTRY is what `gen` writes now (stale = run gen and commit)
                 I2 the IPC door: devtools' FleetTokenProvider is exported behind
                    CONSTELLATION_DATA, overrides call() with FleetIpc.serve, and FleetIpc.serve
                    re-checks the caller (call() is not permission-checked by the framework);
                    devtools queries the MESH_MEMBER action so every member is visible
                 I3 http: no app-to-app loopback HTTP client outside HTTP_ALLOW (a file may
                    hold at most its allowed count, each entry with a reason)
  http  [ROOT]   I3 alone.
  door  [ROOT]   I2 alone.
  check/gen accept --registry FILE (default REGISTRY under ROOT): the tester's stale copy.

EXIT 0 ok . 1 violation . 2 usage
"""
import glob, json, os, re, sys
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.dont_write_bytecode = True
from cloud_android_lib_closure import SHARED_LIBS, inputs, lib_edges, shared_libs  # noqa: E402

REGISTRY = "aa_cloud-superapp/data/ipc-mesh.json"
FLEET = "aa_cloud-superapp/data/constellation-fleet.json"
HTTP_ALLOW = "1_cicd/src/data/ipc-http-allow.json"
DEVTOOLS = SHARED_LIBS + "/devtools"
DISPATCH = DEVTOOLS + "/src/main/java/com/diegonmarcos/superapp/devtools/AppDebugServer.kt"
PERM = "com.diegonmarcos.cloud.permission.CONSTELLATION_DATA"
METHOD = "fleet.ipc.v1"
A = "{http://schemas.android.com/apk/res/android}"
SKIP = {".git", "build", ".gradle", "node_modules", ".cxx", "test", "tests", "androidTest", "z_archive",
        "intermediates", "generated", "platforms", "www", "dist"}
SYSTEM_PERM = re.compile(r"^android\.permission\.BIND_")


def label(path):
    """A source path as the registry writes it: libs:<lib>/... for a shared lib. Never the bare
    repo path: a JSON value that IS a shared-lib path counts as a build input of the app whose
    tree holds the file (cloud_android_lib_closure JSON_REF), and the registry sits in the
    SuperApp's data/."""
    return "libs:" + path[len(SHARED_LIBS) + 1:] if path.startswith(SHARED_LIBS + "/") else path


def read(p):
    with open(p, encoding="utf-8", errors="replace") as h:
        return h.read()


def walk(top, exts):
    for dp, dn, fn in os.walk(top):
        dn[:] = sorted(d for d in dn if d not in SKIP)
        for f in sorted(fn):
            if f.endswith(exts):
                yield os.path.join(dp, f)


def strip_comments(s):
    s = re.sub(r"/\*.*?\*/", lambda m: re.sub(r"[^\n]", " ", m.group(0)), s, flags=re.S)
    return re.sub(r'(?m)(^|[^:"\\])//[^\n]*', r"\1", s)


# ── the roster ─────────────────────────────────────────────────────────────

def roster(root):
    apps = json.load(open(os.path.join(root, FLEET)))["apps"]
    out = []
    for a in apps:
        d = a.get("repo_url", "").split("/tree/main/")[-1]
        if a.get("package") and os.path.isdir(os.path.join(root, d)):
            out.append({"id": a["id"], "pkg": a["package"], "label": a.get("label", a["id"]), "kind": a.get("kind", "app"), "dir": d})
    return out


def libs_of(root, m, edges, libs):
    """Shared libs merged into member m: an app's closure; a lib APK's own lib, its edges, and
    core + devtools (lib-apks/app/build.gradle links every flavour to libs:core)."""
    if m["dir"].startswith(SHARED_LIBS + "/"):
        seed = {m["dir"].split("/")[2], "core", "devtools"}
        out, todo = set(), list(seed)
        while todo:
            x = todo.pop()
            if x in out or x not in libs:
                continue
            out.add(x)
            todo.extend(edges.get(x, ()))
        return out
    if os.path.exists(os.path.join(root, m["dir"], "build.json")):
        return {l for l, why in inputs(root, m["dir"], edges, libs).items() if why == "closure"}
    return {"core", "devtools"}


def own_tree(m):
    """The member's own sources: its dir, minus a lib APK's shared lib (counted as a lib)."""
    return None if m["dir"].startswith(SHARED_LIBS + "/") else m["dir"]


# ── manifests ──────────────────────────────────────────────────────────────

def manifests(root, top):
    if top is None:
        return []
    return [p for p in walk(os.path.join(root, top), ("AndroidManifest.xml",)) if "/src/main/" in p or p.endswith("/app/AndroidManifest.xml")]


def lib_manifest(root, lib):
    p = os.path.join(root, SHARED_LIBS, lib, "src", "main", "AndroidManifest.xml")
    return [p] if os.path.exists(p) else []


def permissions_declared(root, files):
    out = {}
    for p in files:
        try:
            t = ET.parse(p).getroot()
        except ET.ParseError:
            continue
        for e in t.iter("permission"):
            out[e.get(A + "name", "")] = e.get(A + "protectionLevel", "normal")
    return out


def components(root, path, pkg):
    try:
        t = ET.parse(path).getroot()
    except ET.ParseError:
        return []
    app = t.find("application")
    if app is None:
        return []
    sub = lambda s: (s or "").replace("${applicationId}", pkg)
    out = []
    for tag in ("provider", "service", "receiver", "activity", "activity-alias"):
        for e in app.findall(tag):
            name = sub(e.get(A + "name")) or sub(e.get(A + "targetActivity"))
            filters = e.findall("intent-filter")
            exported = e.get(A + "exported")
            exported = (exported == "true") if exported is not None else bool(filters) and tag != "provider"
            if not exported:
                continue
            actions = sorted({sub(a.get(A + "name")) for f in filters for a in f.findall("action")})
            uris = sorted({f"{sub(d.get(A + 'scheme'))}://{sub(d.get(A + 'host') or '')}{sub(d.get(A + 'path') or d.get(A + 'pathPrefix') or '')}"
                           for f in filters for d in f.findall("data") if d.get(A + "scheme")})
            perm = sub(e.get(A + "permission") or e.get(A + "readPermission") or "")
            out.append({"tag": tag, "name": name, "authority": sub(e.get(A + "authorities")) if tag == "provider" else None,
                        "actions": actions, "uris": uris, "permission": perm, "source": label(os.path.relpath(path, root))})
    return out


FLEET_ACTION = re.compile(r"^(com\.diegonmarcos\.|cld\.)")
CUSTOM_SCHEME = re.compile(r"^(?!https?://|file://|content://|tel:|mailto:|geo:|sms)[a-z][a-z0-9+.-]*://")


def fleet_facing(c, levels):
    """A component a FLEET app reaches: guarded by a signature permission, or a fleet action /
    deep link. System-bound components (BIND_* permissions) and plain launchers are not."""
    if c["permission"] and SYSTEM_PERM.match(c["permission"]):
        return False
    if c["permission"] and "signature" in levels.get(c["permission"], ""):
        return True
    if any(FLEET_ACTION.match(a) for a in c["actions"]):
        return True
    if c["tag"].startswith("activity") and any(CUSTOM_SCHEME.match(u) for u in c["uris"]):
        return True
    return False


# ── the IPC layer's ops ────────────────────────────────────────────────────

def universal_ops(root):
    s = read(os.path.join(root, DISPATCH))
    body = s[s.index("fun dispatch("):]
    body = body[:body.index("\n    }\n")]
    return sorted(set(re.findall(r'^\s+"([a-z/]+)" ->', body, re.M)))


ROUTE = re.compile(r"AppDebugServer(?:\.INSTANCE)?\.route\(\s*([^,()]+?)\s*,\s*(?:listOf|Arrays\.asList)\(", re.S)
OP = re.compile(r"""AppDebugServer\.Op\(\s*("(?:[^"\\]|\\.)*"|[\w.]+)""")


def resolve(expr, app_dir, root, consts):
    expr = expr.strip()
    if expr.startswith('"'):
        return expr.strip('"')
    m = re.match(r"BuildConfig\.DEBUG_API_(\w*?)_?GROUP$", expr)
    if m and app_dir:
        key = (m.group(1).lower() + "_group") if m.group(1) else "group"
        for j in walk(os.path.join(root, app_dir), (".json",)):
            if "debug_api" not in read(j):
                continue
            try:
                stack = [json.load(open(j))]
            except ValueError:
                continue
            while stack:
                o = stack.pop()
                if isinstance(o, dict):
                    if isinstance(o.get("debug_api"), dict) and isinstance(o["debug_api"].get(key), str):
                        return o["debug_api"][key]
                    stack.extend(o.values())
                elif isinstance(o, list):
                    stack.extend(o)
    last = expr.split(".")[-1]
    return consts.get(last, "<" + expr + ">")


def consts_in(files):
    consts = {}
    for p in files:
        s = strip_comments(read(p))
        if "AppDebugServer" not in s and "GROUP" not in s and "OP_" not in s:
            continue
        for k, v in re.findall(r'const\s+val\s+(\w+)\s*=\s*"([^"]*)"', s):
            consts.setdefault(k, v)
        for k, v in re.findall(r'static\s+final\s+String\s+(\w+)\s*=\s*"([^"]*)"', s):
            consts.setdefault(k, v)
    return consts


def routes_in(root, files, app_dir, const_files=()):
    consts = consts_in(list(files) + list(const_files))
    texts = [(p, strip_comments(read(p))) for p in files if "AppDebugServer" in read(p)]
    out = []
    for p, s in texts:
        for m in ROUTE.finditer(s):
            group = resolve(m.group(1), app_dir, root, consts)
            depth, i = 1, m.end()
            while i < len(s) and depth:
                depth += {"(": 1, ")": -1}.get(s[i], 0)
                i += 1
            for o in OP.findall(s[m.end():i]):
                op = resolve(o, app_dir, root, consts) if not o.startswith('"') else o.strip('"')
                out.append(((group + "/" + op).strip("/"), label(os.path.relpath(p, root))))
    return out


# ── AIDL ───────────────────────────────────────────────────────────────────

def aidl_index(root):
    out = {}
    for p in glob.glob(os.path.join(root, "a[abc]_*", "**", "*.aidl"), recursive=True):
        if any(x in p.split(os.sep) for x in SKIP):
            continue
        s = strip_comments(read(p))
        m = re.search(r"interface\s+(\w+)\s*\{(.*)\}", s, re.S)
        if not m:
            continue
        methods = [n for n in re.findall(r"(?:oneway\s+)?[\w<>\[\], .]+?\s+(\w+)\s*\([^;]*\)\s*;", m.group(2))]
        out.setdefault(m.group(1), sorted(set(methods)))
    return out


# ── sources, for callers ───────────────────────────────────────────────────

class Corpus:
    def __init__(self, root):
        self.root, self.cache = root, {}

    def text(self, top):
        if top not in self.cache:
            d = os.path.join(self.root, top)
            self.cache[top] = "\n".join(strip_comments(read(p)) for p in walk(d, (".kt", ".java"))) if os.path.isdir(d) else ""
        return self.cache[top]

    def files(self, top):
        d = os.path.join(self.root, top)
        return list(walk(d, (".kt", ".java"))) if os.path.isdir(d) else []


IPC_TALK = re.compile(r"FleetIpc\.|StoreMesh\.ask\(|StoreMesh\.docs\(")
HELPER_OP = {"ping": "system/ping", "info": "system/info", "docs": "docs", "peers": "fleet/peers",
             "dns": "net/dns", "crashes": "diagnostics/crashes"}


def ops_called(corpus, top, known):
    """Ops named in the files under top that talk the fleet IPC layer."""
    out = set()
    for p in corpus.files(top):
        s = strip_comments(read(p))
        if not IPC_TALK.search(s):
            continue
        out.update(HELPER_OP[h] for h in re.findall(r"FleetIpc\.(ping|info|docs|peers|dns|crashes)\(", s))
        if "StoreMesh.docs(" in s:
            out.add("docs")
        out.update(x for x in re.findall(r'"([a-z][\w-]*(?:/[\w./-]*)?)"', s) if x in known)
    return out


# ── gen ────────────────────────────────────────────────────────────────────

PKG = "<pkg>"


def endpoint(c, pkg, levels, corpus, tops, aidl):
    """One manifest component as a registry endpoint, and the needles a caller's code names it by."""
    short = c["name"].split(".")[-1]
    tmpl = lambda x: x.replace(pkg, PKG) if pkg != PKG else x
    level = levels.get(c["permission"], "none") if c["permission"] else "none"
    e = {"permission": c["permission"] or None, "level": level, "source": c["source"], "component": tmpl(c["name"])}
    sfx = lambda xs: [x.replace(pkg, "") + '"' for x in xs if x.startswith(pkg + ".")]
    if c["tag"] == "provider":
        auths = c["authority"].split(";")
        needles = auths + sfx(auths)
        e.update(id="provider:" + tmpl(c["authority"]), kind="provider", name=short, authority=tmpl(c["authority"]))
    elif c["tag"] == "service":
        iface = _stub_of(corpus, tops, short, aidl)
        needles = c["actions"] + sfx(c["actions"])
        e.update(id="service:" + tmpl(c["name"]), kind="service", name=short, actions=[tmpl(x) for x in c["actions"]],
                 aidl=[{"interface": i, "methods": aidl[i]} for i in iface])
    elif c["tag"] == "receiver":
        needles = [x for x in c["actions"] if pkg not in x] + sfx(c["actions"])
        e.update(id="receiver:" + tmpl(c["name"]), kind="receiver", name=short, actions=[tmpl(x) for x in c["actions"]])
    else:
        deep = [u for u in c["uris"] if CUSTOM_SCHEME.match(u)]
        acts = [a for a in c["actions"] if FLEET_ACTION.match(a)]
        needles = acts + [u.rstrip("/") for u in deep]
        e.update(id=("deep_link:" if deep else "intent:") + tmpl(c["name"]), kind="deep_link" if deep else "intent",
                 name=short, actions=[tmpl(x) for x in acts], uris=deep)
    return e, [n for n in needles if n and n != '"']


ORDER = ["op", "provider", "service", "receiver", "intent", "deep_link"]


def generate(root):
    edges, libs = lib_edges(root), shared_libs(root)
    members = roster(root)
    corpus = Corpus(root)
    aidl = aidl_index(root)
    every_manifest = [p for m in members for p in manifests(root, own_tree(m))] + \
        [p for lib in sorted(libs) for p in lib_manifest(root, lib)]
    levels = permissions_declared(root, every_manifest)
    levels.setdefault(PERM, "signature")
    fleet_rows = {a["id"]: a for a in json.load(open(os.path.join(root, FLEET)))["apps"]}

    plan = []
    for m in members:
        ls = libs_of(root, m, edges, libs)
        tops = ([m["dir"]] if own_tree(m) else []) + [f"{SHARED_LIBS}/{l}" for l in sorted(ls)]
        plan.append((m, ls, tops))
    used = sorted({l for _, ls, _ in plan for l in ls})

    # ops: the universal ones (libs:devtools dispatch), a shared lib's own routes, each member's own
    lib_ops = {l: routes_in(root, corpus.files(f"{SHARED_LIBS}/{l}"), None) for l in used}
    lib_ops["devtools"] = [(o, label(DISPATCH)) for o in universal_ops(root)] + lib_ops.get("devtools", [])
    own_ops = {m["id"]: routes_in(root, corpus.files(own_tree(m)), own_tree(m), [p for t in tops for p in corpus.files(t)])
               if own_tree(m) else [] for m, _, tops in plan}
    known = {o for v in list(lib_ops.values()) + list(own_ops.values()) for o, _ in v}
    calls = {m["id"]: set().union(*(ops_called(corpus, t, known) for t in tops)) for m, ls, tops in plan}
    texts = {m["id"]: "\n".join(corpus.text(t) for t in tops) for m, ls, tops in plan}

    def callers_by(needles, carriers):
        return sorted(i for i, s in texts.items() if i not in carriers and any(n in s for n in needles))

    def op_eps(ops, carriers):
        return [{"id": "op:" + o, "kind": "op", "name": o, "method": METHOD, "permission": PERM, "level": "signature",
                 "source": src, "callers": sorted(i for i, c in calls.items() if i not in carriers and o in c)}
                for o, src in sorted(set(ops))]

    # shared-lib templates: what a lib merges into EVERY member that links it, <pkg> for the member
    shared = {}
    for l in used:
        eps = op_eps(lib_ops.get(l, []), set())
        for p in lib_manifest(root, l):
            for c in components(root, p, PKG):
                if fleet_facing(c, levels):
                    e, needles = endpoint(c, PKG, levels, corpus, [f"{SHARED_LIBS}/{l}"], aidl)
                    e["callers"] = callers_by(needles, set())
                    eps.append(e)
        if eps:
            shared[l] = sorted(eps, key=lambda x: (ORDER.index(x["kind"]), x["id"]))

    out = []
    for m, ls, tops in plan:
        eps = op_eps(own_ops[m["id"]], {m["id"]})
        seen = set()
        for p in manifests(root, own_tree(m)):
            for c in components(root, p, m["pkg"]):
                if not fleet_facing(c, levels) or (c["tag"], c["name"], c["authority"]) in seen:
                    continue
                seen.add((c["tag"], c["name"], c["authority"]))
                e, needles = endpoint(c, m["pkg"], levels, corpus, tops, aidl)
                e["callers"] = callers_by(needles, {m["id"]})
                eps.append(e)
        # a lib APK member: its engine's declared binders are its callers (build.json::engines)
        if m["dir"].startswith(SHARED_LIBS + "/"):
            binders = sorted(i for i, r in fleet_rows.items() if any(x.get("fleet") == m["id"] for x in r.get("engines") or []))
            for e in shared.get(m["dir"].split("/")[2], []):
                if e["kind"] == "service":
                    e["callers"] = sorted(set(e["callers"]) | set(binders))
        eps.sort(key=lambda x: (ORDER.index(x["kind"]), x["id"]))
        out.append({"id": m["id"], "pkg": m["pkg"], "label": m["label"], "kind": m["kind"], "dir": label(m["dir"]),
                    "door": "devtools" in ls, "libs": [l for l in sorted(ls) if l in shared], "endpoints": eps})
    return {
        "_doc": "GENERATED by 1_cicd/src/scripts/cloud-android-ipc-mesh.py gen -- do not edit. THE fleet IPC registry "
                "(a0_docs/eng-specs/fleet-ipc.md): every IPC endpoint each fleet member exposes to the others -- the fleet "
                "IPC layer's ops on content://<pkg>.fleet (method fleet.ipc.v1), signature-guarded providers, bound "
                "services and their AIDL, fleet intent actions and deep links -- with the permission protecting it and the "
                "members whose code names it (callers, a static reading). A member's endpoints are its own `endpoints` plus "
                "the `shared` endpoints of every lib in its `libs`, with <pkg> = its package. Read by libs:appstore IpcMesh "
                "(Cloud Store > Access > IPC, SuperApp > Configs > Network > IPC); cloud-android-ipc-mesh.py check fails "
                "when it is stale.",
        "permission": {"name": PERM, "level": "signature"},
        "door": {"authority": PKG + ".fleet", "method": METHOD, "provider": "com.diegonmarcos.superapp.devtools.FleetTokenProvider",
                 "probe": "system/ping"},
        "gateway": {"pkg": "com.diegonmarcos.superapp", "route": "/app/<id|pkg>/api/<op>"},
        "shared": shared,
        "members": out,
    }


def _stub_of(corpus, tops, short, aidl):
    found = set()
    for t in tops:
        for p in corpus.files(t):
            if os.path.basename(p).split(".")[0] != short:
                continue
            found.update(i for i in re.findall(r"\b(I[A-Z]\w+)\.Stub\b", strip_comments(read(p))) if i in aidl)
    return sorted(found)


def dump(d):
    return json.dumps(d, indent=1, ensure_ascii=False, sort_keys=False) + "\n"


# ── check ──────────────────────────────────────────────────────────────────

LOOPBACK_CLIENT = re.compile(
    r'InetSocketAddress\(\s*(?:"127\.0\.0\.1"|"localhost"|LOOPBACK)'
    r'|"(?:https?|wss?)://(?:127\.0\.0\.1|localhost)[:/"$]'
    r'|FleetIpcWire\.httpGet\('
    r'|\bURL\(\s*RishBridge\.bridgeUrl\(\)')


def http_hits(root):
    hits = {}
    for top in sorted(glob.glob(os.path.join(root, "a[abc]_*"))):
        for p in walk(top, (".kt", ".java")):
            n = len(LOOPBACK_CLIENT.findall(strip_comments(read(p))))
            if n:
                hits[os.path.relpath(p, root)] = n
    return hits


def check_http(root):
    allow = {k: v for k, v in json.load(open(os.path.join(root, HTTP_ALLOW)))["files"].items() if not k.startswith("_")}
    bad = []
    hits = http_hits(root)
    for f, n in sorted(hits.items()):
        a = allow.get(f)
        if a is None:
            bad.append(f"I3 {f}: {n} loopback HTTP client(s) and no entry in {HTTP_ALLOW} -- app-to-app on the phone is IPC "
                       "(libs:devtools FleetIpc); a loopback client that is NOT a sibling app needs an entry with its reason")
        elif n > a.get("max", 0):
            bad.append(f"I3 {f}: {n} loopback HTTP client(s), {HTTP_ALLOW} allows {a.get('max', 0)} ({a.get('why', '')})")
        elif not a.get("why"):
            bad.append(f"I3 {f}: allowed in {HTTP_ALLOW} with no reason")
    for f in sorted(set(allow) - set(hits)):
        bad.append(f"I3 {f}: allowed in {HTTP_ALLOW} but holds no loopback client any more -- delete the entry (the ratchet only shrinks)")
    for f, n in sorted(hits.items()):
        if f in allow and n < allow[f].get("max", 0):
            bad.append(f"I3 {f}: holds {n}, {HTTP_ALLOW} allows {allow[f]['max']} -- lower it to {n} (the ratchet only shrinks)")
    return bad


def check_door(root):
    bad = []
    man = read(os.path.join(root, DEVTOOLS, "src/main/AndroidManifest.xml"))
    m = re.search(r'<provider\s[^>]*FleetTokenProvider[^>]*>', man, re.S)
    if not m or 'android:exported="true"' not in m.group(0) or PERM not in m.group(0):
        bad.append("I2 libs:devtools FleetTokenProvider is not exported behind CONSTELLATION_DATA")
    if "com.diegonmarcos.cloud.action.MESH_MEMBER" not in man.split("<application")[0]:
        bad.append("I2 libs:devtools no longer queries the MESH_MEMBER action: members cannot see each other's door")
    src = os.path.join(root, DEVTOOLS, "src/main/java/com/diegonmarcos/superapp/devtools")
    prov = strip_comments(read(os.path.join(src, "FleetTokenProvider.kt")))
    if not re.search(r"override fun call\([^)]*\)[^=]*=\s*\n?\s*context\?\.let \{ FleetIpc\.serve\(", prov):
        bad.append("I2 FleetTokenProvider.call() does not hand calls to FleetIpc.serve")
    ipc = strip_comments(read(os.path.join(src, "FleetIpc.kt")))
    serve = ipc[ipc.find("fun serve("):ipc.find("fun call(")]
    if "checkCallingPermission(FleetToken.PERMISSION)" not in serve or "FleetIpcWire.gate(" not in serve:
        bad.append("I2 FleetIpc.serve does not re-check the caller's CONSTELLATION_DATA through FleetIpcWire.gate")
    return bad


def main(argv):
    if not argv or argv[0] not in ("gen", "check", "http", "door"):
        print(__doc__)
        return 2
    reg = None
    if "--registry" in argv:
        i = argv.index("--registry")
        reg = argv[i + 1]
        argv = argv[:i] + argv[i + 2:]
    root = os.path.abspath(argv[1] if len(argv) > 1 else ".")
    reg = os.path.abspath(reg) if reg else os.path.join(root, REGISTRY)
    if argv[0] == "gen":
        d = generate(root)
        open(reg, "w").write(dump(d))
        n = sum(len(m["endpoints"]) + sum(len(d["shared"][l]) for l in m["libs"]) for m in d["members"])
        print(f"wrote {REGISTRY}: {len(d['members'])} members, {n} endpoints ({len(d['shared'])} shared libs)")
        return 0
    bad = check_door(root) if argv[0] == "door" else check_http(root)
    if argv[0] == "check":
        bad = check_door(root) + bad
        cur = read(reg) if os.path.exists(reg) else ""
        if cur != dump(generate(root)):
            bad.insert(0, f"I1 {REGISTRY} is stale: run python3 1_cicd/src/scripts/cloud-android-ipc-mesh.py gen . and commit it")
    for b in bad:
        print("FAIL " + b)
    print(f"-- ipc mesh ({argv[0]}): {len(bad)} violation(s) --")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
