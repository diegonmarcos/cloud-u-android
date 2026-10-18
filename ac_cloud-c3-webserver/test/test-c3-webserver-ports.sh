#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════════════╗
# ║ cloud-c3-webserver — the name -> port map is ONE declaration, the ports ║
# ║ stay unique and consistent, and the private DNS stays a private DNS.    ║
# ╚══════════════════════════════════════════════════════════════════════════╝
#
# THE FAILURE THIS EXISTS FOR. The phone's four local servers now have names
# (termux.app :8000, nix.app :8001, emulated.app :8002, cloud.app :8003) that
# three apps must agree on: the web server that answers them, the browser that
# resolves them and opens them, and the owners of :8000 / :8001. A map typed in
# each of them drifts the first time one is edited, and the symptom is a pinned
# tab that loads somebody else's site (.app is a real TLD and termux.app is a
# real website) or nothing at all. test-localhost-ports.sh keeps the PORTS
# unique across apps; this keeps the MAP one declaration and consistent.
#
#   Q1  THE DECLARATION. build.json::serving has exactly four endpoints; every
#       name is one lowercase label under .app, names / ports / ids are unique,
#       ports are 1024..65535, one endpoint is the drive and one the diagnostics
#       page, and the DNS server is a loopback address on a port that is not 53
#       and not any endpoint's.
#   Q2  THE OWNERS AGREE. Each endpoint's port is the one its owner declares:
#       termux and nix-on-droid runtime.webserver.port, this app runtime.port
#       (the drive) and runtime.diag.port (the diagnostics page).
#   Q3  THE READERS READ IT. The browser's pins are exactly the names with their
#       titles, its build.json does NOT carry the map (so there is one copy),
#       its gradle bakes ac_cloud-c3-webserver/build.json::serving; the web
#       server compiles the same file in; the SuperApp provider's authority is
#       the declared bridge authority.
#   Q4  NOTHING ELSE TYPES IT. Outside tests and comments, no Rust source of
#       the app, no shell page and no Tauri glue spells a name or a port.
#   Q5  A PRIVATE DNS, NOT A RESOLVER. The browser client asks only a loopback
#       server, accepts only a loopback answer and never falls back to the
#       system (the pins the DNS resolver guard also holds); the server crate
#       refuses names it does not own.
#   Q6  THE ENGINE REGISTRY IS ONE DECLARATION. build.json::engines.list holds every
#       engine's name, description and switch; no source of the app (Rust, the
#       shell, the pages, the Tauri glue) repeats a description or a name.
#   M   each defect above is planted in a copy and shown red.
#
# python3, jq. Reads the sibling apps' build.json (termux, nix-on-droid, browser)
# and the SuperApp / libs:browser sources; run from a full checkout (CI) or a
# sparse one that has them.
set -uo pipefail

APP="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO="$(cd "$APP/.." && pwd)"
FAILURES=0
pass() { echo "  PASS  $*"; }
fail() { echo "  FAIL  $*"; FAILURES=$((FAILURES + 1)); }
for tool in python3 jq; do
  command -v "$tool" >/dev/null 2>&1 || { echo "ERROR $tool absent — a verdict from a missing tool is not a verdict"; exit 1; }
done
for f in "$APP/build.json" "$REPO/ac_cloud-termux/build.json" "$REPO/ac_cloud-nix-on-droid/build.json" "$REPO/ac_cloud-browser/build.json" \
         "$REPO/ac_cloud-browser/app/build.gradle" "$APP/server/src/dns.rs" "$APP/server/src/serving.rs"; do
  [ -f "$f" ] || { echo "ERROR missing source: $f — this tester is unrun, not passing"; exit 1; }
done

# check <app> <repo> <qN...> : prints one line per problem, exit 1 if any
check() {
  python3 - "$1" "$2" "$3" <<'PY'
import glob, json, os, re, sys
app, repo, which = sys.argv[1], sys.argv[2], sys.argv[3].split(",")
bad = []
def read(p):
    try: return open(p, encoding="utf-8").read()
    except OSError: return ""
def js(p):
    try: return json.loads(read(p))
    except ValueError: return {}
bj = js(os.path.join(app, "build.json"))
sv = bj.get("serving") or {}
eps = sv.get("endpoints") or []
dns = sv.get("dns") or {}
rt = bj.get("runtime") or {}

if "q1" in which:
    if len(eps) != 4: bad.append("Q1 serving.endpoints has %d entries, want the four private names" % len(eps))
    for key in ("name", "port", "id"):
        vals = [e.get(key) for e in eps]
        if len(vals) != len(set(vals)): bad.append("Q1 serving.endpoints %s repeats: %r" % (key, vals))
    for e in eps:
        n = e.get("name", "")
        if not re.fullmatch(r"[a-z0-9-]{2,}\.app", n): bad.append("Q1 name %r is not one lowercase label under .app (exact names only, never a wildcard)" % n)
        p = e.get("port")
        if not isinstance(p, int) or isinstance(p, bool) or not 1024 <= p <= 65535: bad.append("Q1 %s port %r is not an integer in 1024..65535" % (n, p))
    roles = sorted(e.get("role") for e in eps)
    if roles.count("drive") != 1 or roles.count("diag") != 1 or any(r not in ("external", "drive", "diag") for r in roles):
        bad.append("Q1 roles %r: want exactly one drive, one diag, the rest external" % roles)
    ports = {e.get("role"): e.get("port") for e in eps}
    if ports.get("drive") != rt.get("port"): bad.append("Q1 the drive endpoint is %r but runtime.port is %r" % (ports.get("drive"), rt.get("port")))
    if ports.get("diag") != (rt.get("diag") or {}).get("port"): bad.append("Q1 the diag endpoint is %r but runtime.diag.port is %r" % (ports.get("diag"), (rt.get("diag") or {}).get("port")))
    import ipaddress
    for k in ("host", "answer"):
        try:
            if not ipaddress.ip_address(dns.get(k, "")).is_loopback: raise ValueError
        except ValueError:
            bad.append("Q1 serving.dns.%s = %r is not a loopback address" % (k, dns.get(k)))
    dp = dns.get("port")
    if not isinstance(dp, int) or dp == 53 or not 1024 <= dp <= 65535: bad.append("Q1 serving.dns.port %r must be 1024..65535 and never 53 (Android cannot bind it without root)" % (dp,))
    if dp in [e.get("port") for e in eps]: bad.append("Q1 the DNS port %r is also an endpoint's port" % (dp,))
    if not (sv.get("bridge") or {}).get("authority", "").endswith(".fleetdiag"): bad.append("Q1 serving.bridge.authority is not <package>.fleetdiag")
    if not (sv.get("opener") or {}).get("package"): bad.append("Q1 serving.opener.package is missing: the Pages tab would not know which browser to open")

if "q2" in which:
    def port_of(e):
        o = js(os.path.join(repo, e.get("owner", "?"), "build.json")).get("runtime") or {}
        return {"external": (o.get("webserver") or {}).get("port"), "drive": o.get("port"), "diag": (o.get("diag") or {}).get("port")}.get(e.get("role"))
    for e in eps:
        if port_of(e) != e.get("port"):
            bad.append("Q2 serving says %s is :%r but %s declares :%r" % (e.get("name"), e.get("port"), e.get("owner"), port_of(e)))

if "q3" in which:
    b = js(os.path.join(repo, "ac_cloud-browser", "build.json"))
    br = (b.get("ui") or {}).get("browser") or {}
    pins = br.get("default_pinned_tabs") or []
    urls = [p.get("url") if isinstance(p, dict) else p for p in pins]
    titles = {p.get("url"): p.get("title") for p in pins if isinstance(p, dict)}
    for e in eps:
        want = "http://" + e.get("name", "")
        if want not in urls: bad.append("Q3 the browser does not pin %s" % want)
        if titles.get(want) != e.get("title"): bad.append("Q3 the browser's %s pin is titled %r, serving says %r" % (want, titles.get(want), e.get("title")))
        if (br.get("local_pinned_tabs") or {}).get(e.get("title")) != want: bad.append("Q3 ui.browser.local_pinned_tabs.%s is not %s" % (e.get("title"), want))
    if "private_names" in br: bad.append("Q3 the browser's build.json carries its own private_names: the map must have ONE declaration (gradle bakes it)")
    if any(re.search(r"localhost|127\.0\.0\.1", u or "") for u in urls): bad.append("Q3 a browser pin still spells a loopback address instead of the name")
    gradle = read(os.path.join(repo, "ac_cloud-browser/app/build.gradle"))
    if "ac_cloud-c3-webserver/build.json" not in gradle or not re.search(r"buildJson\.ui\.browser\.private_names\s*=", gradle) or ".serving" not in gradle:
        bad.append("Q3 ac_cloud-browser/app/build.gradle does not bake ac_cloud-c3-webserver/build.json::serving as ui.browser.private_names")
    if "build.json" not in read(os.path.join(app, "server/src/serving.rs")):
        bad.append("Q3 server/src/serving.rs does not compile build.json in")
    prov = read(os.path.join(repo, "aa_cloud-superapp/app/src/main/AndroidManifest.xml"))
    m = re.search(r'FleetDiagnosticsProvider"\s+android:authorities="\$\{applicationId\}\.([a-z]+)"', prov)
    if not m or (sv.get("bridge") or {}).get("authority", "").rsplit(".", 1)[-1] != m.group(1):
        bad.append("Q3 the SuperApp provider's authority suffix %r is not the declared bridge authority %r" % (m and m.group(1), (sv.get("bridge") or {}).get("authority")))

if "q4" in which:
    names = [e.get("name") for e in eps] + ["termux.app", "nix.app", "emulated.app", "cloud.app"]
    nums = [str(e.get("port")) for e in eps] + [str(dns.get("port"))]
    files = sorted(glob.glob(os.path.join(app, "server/src/*.rs")) + glob.glob(os.path.join(app, "src-tauri/src/*.rs")))
    files += [os.path.join(app, "server/ui/shell.html"), os.path.join(app, "server/ui/diag.html")]
    for f in files:
        text = read(f)
        if f.endswith(".rs"): text = re.split(r"#\[cfg\(test\)\]", text)[0]
        if not f.endswith(".rs"): text = re.sub(r"<!--.*?-->", "", text, flags=re.S)
        # a // comment, but never the // inside a string literal ("http://..."); Rust char literals ('"') are skipped first
        lit = r"""('(?:\\.|[^'\\])'|"(?:\\.|[^"\\])*")|//[^\n]*""" if f.endswith(".rs") else r"""("(?:\\.|[^"\\\n])*"|'(?:\\.|[^'\\\n])*')|//[^\n]*"""
        text = re.sub(lit, lambda m: m.group(1) or "", text)
        for n in set(names):
            if n and n in text: bad.append("Q4 %s spells the name %s: names come from build.json::serving" % (os.path.relpath(f, app), n))
        for n in set(nums):
            if re.search(r"(?<![\w.])%s(?![\w.])" % re.escape(n), text): bad.append("Q4 %s spells the port %s: ports come from build.json" % (os.path.relpath(f, app), n))

if "q5" in which:
    pn = read(os.path.join(repo, "ab_cloud-libs-shared/libs/browser/src/main/java/com/diegonmarcos/superapp/browser/PrivateNames.kt"))
    for want, why in (("if (!isLoopbackLiteral(host)) return null", "ask only a loopback DNS server"),
                      ("if (!isLoopbackAddress(ip))", "accept only a loopback answer"),
                      ("InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))", "reach the server without a name lookup of its own"),
                      ("Result.Fail(name,", "turn a failed resolution into a failure page")):
        if want not in pn: bad.append("Q5 PrivateNames.kt no longer %s (%r gone)" % (why, want))
    if re.search(r"InetAddress\.getBy(Name|AllByName)\(", pn): bad.append("Q5 PrivateNames.kt resolves a name through the system (InetAddress.getByName)")
    host = read(os.path.join(repo, "ab_cloud-libs-shared/libs/browser/src/main/java/com/diegonmarcos/superapp/browser/BrowserHostFragment.kt"))
    if "config.privateNames.matches(target)" not in host or "private fun WebView.loadResolved" not in host:
        bad.append("Q5 BrowserHostFragment no longer routes private names through loadResolved (links and loads)")
    dnsrs = read(os.path.join(app, "server/src/dns.rs"))
    if "RCODE_REFUSED" not in dnsrs or "is_loopback()" not in dnsrs: bad.append("Q5 dns.rs no longer refuses names it does not own or non-loopback peers")
    if re.search(r"\.send_to\([^)]*\)\s*;[^}]*forward|UdpSocket::bind\(\(\"0\.0\.0\.0\"", dnsrs): bad.append("Q5 dns.rs forwards or binds a wildcard address")
if "q6" in which:
    engines = (bj.get("engines") or {}).get("list") or []
    if len(engines) < 15: bad.append("Q6 build.json::engines.list has %d entries, want the whole registry" % len(engines))
    srcs = glob.glob(os.path.join(app, "server/src/*.rs")) + glob.glob(os.path.join(app, "src-tauri/src/*.rs")) + glob.glob(os.path.join(app, "server/ui/*.html"))
    for f in srcs:
        text = re.split(r"#\[cfg\(test\)\]", read(f))[0] if f.endswith(".rs") else read(f)
        for e in engines:
            for key in ("summary",):
                v = e.get(key, "")
                if len(v) > 12 and v in text: bad.append("Q6 %s repeats engine %s's %s: it is declared only in build.json::engines" % (os.path.relpath(f, app), e.get("id"), key))
for b in bad: print("    " + b)
sys.exit(1 if bad else 0)
PY
}

echo "── cloud-c3-webserver ports / DNS map ──"
check "$APP" "$REPO" q1 && pass "Q1 the declaration: four unique lowercase .app names, unique ports, one drive, one diag, DNS on loopback and not :53" || fail "Q1 the declaration is malformed"
check "$APP" "$REPO" q2 && pass "Q2 each endpoint's port is the one its owner declares (termux, nix-on-droid, this app's drive and diagnostics)" || fail "Q2 the map and the owners disagree"
check "$APP" "$REPO" q3 && pass "Q3 the browser pins the names with their titles and bakes the one declaration; the server compiles it in; the provider authority matches" || fail "Q3 a reader of the map drifted"
check "$APP" "$REPO" q4 && pass "Q4 no Rust source, shell page or Tauri glue of the app spells a name or a port" || fail "Q4 a name or a port is typed outside build.json"
check "$APP" "$REPO" q6 && pass "Q6 the engine registry is declared once, in build.json::engines; no source repeats a description" || fail "Q6 a second copy of the engine registry"
check "$APP" "$REPO" q5 && pass "Q5 the browser client is loopback-only and never falls back to the system; the server refuses names it does not own" || fail "Q5 the private DNS grew into a resolver"

# ── M: mutation-proof ──────────────────────────────────────────────────────
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
mutate() {
  local title="$1" qs="$2" mut="$3" copy="$WORK/m$RANDOM$RANDOM"
  mkdir -p "$copy"
  # a copy of exactly what the checks read
  for d in ac_cloud-c3-webserver ac_cloud-termux ac_cloud-nix-on-droid ac_cloud-browser aa_cloud-superapp/app/src/main/AndroidManifest.xml \
           ab_cloud-libs-shared/libs/browser/src/main/java/com/diegonmarcos/superapp/browser; do
    mkdir -p "$copy/$(dirname "$d")"
    case "$d" in
      ac_cloud-termux|ac_cloud-nix-on-droid) mkdir -p "$copy/$d"; cp "$REPO/$d/build.json" "$copy/$d/" ;;
      ac_cloud-c3-webserver) mkdir -p "$copy/$d"; cp -R "$REPO/$d/build.json" "$REPO/$d/server" "$REPO/$d/src-tauri" "$copy/$d/"; rm -rf "$copy/$d/server/target" "$copy/$d/src-tauri/target" "$copy/$d/src-tauri/gen" ;;
      ac_cloud-browser) mkdir -p "$copy/$d/app"; cp "$REPO/$d/build.json" "$copy/$d/"; cp "$REPO/$d/app/build.gradle" "$copy/$d/app/" ;;
      *) cp -R "$REPO/$d" "$copy/$(dirname "$d")/" ;;
    esac
  done
  "$mut" "$copy" || { fail "M: mutation '$title' did not apply — a mutation that does not mutate proves nothing"; return; }
  if check "$copy/ac_cloud-c3-webserver" "$copy" "$qs" >/dev/null 2>&1; then fail "M: '$title' stayed GREEN — the check is vacuous"; else pass "M: '$title' goes red"; fi
}
bjset() {  # bjset <copy> <app> <python statement on d>
  python3 - "$1/$2/build.json" "$3" <<'PY'
import json, sys
p, stmt = sys.argv[1], sys.argv[2]
d = json.load(open(p)); exec(stmt); json.dump(d, open(p, "w"), indent=2)
PY
}
m_dup_port()      { bjset "$1" ac_cloud-c3-webserver 'd["serving"]["endpoints"][1]["port"] = 8000'; }
m_dup_name()      { bjset "$1" ac_cloud-c3-webserver 'd["serving"]["endpoints"][1]["name"] = "termux.app"'; }
m_com_name()      { bjset "$1" ac_cloud-c3-webserver 'd["serving"]["endpoints"][1]["name"] = "nix.com"'; }
m_wild_name()     { bjset "$1" ac_cloud-c3-webserver 'd["serving"]["endpoints"][1]["name"] = "*.app"'; }
m_dns53()         { bjset "$1" ac_cloud-c3-webserver 'd["serving"]["dns"]["port"] = 53'; }
m_dns_public()    { bjset "$1" ac_cloud-c3-webserver 'd["serving"]["dns"]["host"] = "0.0.0.0"'; }
m_dns_answer()    { bjset "$1" ac_cloud-c3-webserver 'd["serving"]["dns"]["answer"] = "10.0.0.7"'; }
m_dns_on_ep()     { bjset "$1" ac_cloud-c3-webserver 'd["serving"]["dns"]["port"] = 8001'; }
m_runtime_drift() { bjset "$1" ac_cloud-c3-webserver 'd["runtime"]["port"] = 8100'; }
m_diag_drift()    { bjset "$1" ac_cloud-c3-webserver 'd["runtime"]["diag"]["port"] = 8100'; }
m_owner_drift()   { bjset "$1" ac_cloud-termux 'd["runtime"]["webserver"]["port"] = 8100'; }
m_nix_drift()     { bjset "$1" ac_cloud-nix-on-droid 'd["runtime"]["webserver"]["port"] = 8101'; }
m_two_drives()    { bjset "$1" ac_cloud-c3-webserver 'd["serving"]["endpoints"][0]["role"] = "drive"'; }
m_pin_drift()     { bjset "$1" ac_cloud-browser 'br = d["ui"]["browser"]; br["default_pinned_tabs"][4]["url"] = "http://localhost:8001"'; }
m_pin_title()     { bjset "$1" ac_cloud-browser 'br = d["ui"]["browser"]; br["default_pinned_tabs"][5]["title"] = "Drive"'; }
m_second_copy()   { bjset "$1" ac_cloud-browser 'd["ui"]["browser"]["private_names"] = {"names": {"emulated.app": 8002}}'; }
m_gradle_nobake() { python3 - "$1/ac_cloud-browser/app/build.gradle" <<'PY'
import sys; p = sys.argv[1]; s = open(p).read()
n = s.replace("buildJson.ui.browser.private_names = [", "buildJson.ui.browser.not_the_map = [")
assert n != s; open(p, "w").write(n)
PY
}
m_typed_name()    { python3 - "$1/ac_cloud-c3-webserver/src-tauri/src/lib.rs" <<'PY'
import sys; p = sys.argv[1]; s = open(p).read()
n = s.replace("let state_dir", 'let _opens = "http://emulated.app/";\n            let state_dir', 1)
assert n != s; open(p, "w").write(n)
PY
}
m_typed_port()    { python3 - "$1/ac_cloud-c3-webserver/server/src/files.rs" <<'PY'
import sys; p = sys.argv[1]; s = open(p).read()
n = s.replace("fn not_found() -> Resp {\n    Resp::text(404", "fn not_found() -> Resp {\n    let _p = 8003;\n    Resp::text(404", 1)
assert n != s; open(p, "w").write(n)
PY
}
m_provider_authority() { python3 - "$1/aa_cloud-superapp/app/src/main/AndroidManifest.xml" <<'PY'
import sys; p = sys.argv[1]; s = open(p).read()
n = s.replace('${applicationId}.fleetdiag"', '${applicationId}.fleetdiagnostics"')
assert n != s; open(p, "w").write(n)
PY
}
m_client_not_loopback() { python3 - "$1/ab_cloud-libs-shared/libs/browser/src/main/java/com/diegonmarcos/superapp/browser/PrivateNames.kt" <<'PY'
import sys; p = sys.argv[1]; s = open(p).read()
n = s.replace("if (!isLoopbackLiteral(host)) return null", "")
assert n != s; open(p, "w").write(n)
PY
}
m_client_any_answer() { python3 - "$1/ab_cloud-libs-shared/libs/browser/src/main/java/com/diegonmarcos/superapp/browser/PrivateNames.kt" <<'PY'
import sys; p = sys.argv[1]; s = open(p).read()
n = s.replace("if (!isLoopbackAddress(ip))", "if (false)")
assert n != s; open(p, "w").write(n)
PY
}
m_client_system() { python3 - "$1/ab_cloud-libs-shared/libs/browser/src/main/java/com/diegonmarcos/superapp/browser/PrivateNames.kt" <<'PY'
import sys; p = sys.argv[1]; s = open(p).read()
n = s.replace("return runCatching {", "return runCatching {\n                InetAddress.getByName(name)", 1)
assert n != s; open(p, "w").write(n)
PY
}
m_host_unrouted() { python3 - "$1/ab_cloud-libs-shared/libs/browser/src/main/java/com/diegonmarcos/superapp/browser/BrowserHostFragment.kt" <<'PY'
import sys; p = sys.argv[1]; s = open(p).read()
n = s.replace("config.privateNames.matches(target)", "false")
assert n != s; open(p, "w").write(n)
PY
}
m_engine_copy()  { python3 - "$1/ac_cloud-c3-webserver/server/ui/shell.html" "$1/ac_cloud-c3-webserver/build.json" <<'PY2'
import json, sys
p, b = sys.argv[1:3]; e = json.load(open(b))["engines"]["list"][0]["summary"]
s = open(p).read(); n = s.replace("<main", "<!-- " + e + " --><main", 1); assert n != s; open(p, "w").write(n)
PY2
}
m_dns_answers_all() { python3 - "$1/ac_cloud-c3-webserver/server/src/dns.rs" <<'PY'
import sys; p = sys.argv[1]; s = open(p).read()
n = s.replace("RCODE_REFUSED", "RCODE_OK")
assert n != s; open(p, "w").write(n)
PY
}
mutate "two endpoints on one port"                         q1 m_dup_port
mutate "two endpoints with one name"                       q1 m_dup_name
mutate "a name under .com"                                 q1 m_com_name
mutate "a wildcard name"                                   q1 m_wild_name
mutate "the DNS server on :53"                             q1 m_dns53
mutate "the DNS server on a wildcard address"              q1 m_dns_public
mutate "the DNS answering a non-loopback address"          q1 m_dns_answer
mutate "the DNS port is also an endpoint's"                q1 m_dns_on_ep
mutate "runtime.port drifts from the drive endpoint"       q1 m_runtime_drift
mutate "runtime.diag.port drifts from the diag endpoint"   q1 m_diag_drift
mutate "two drives"                                        q1 m_two_drives
mutate "termux moves its port, the map does not"           q2 m_owner_drift
mutate "nix-on-droid moves its port, the map does not"     q2 m_nix_drift
mutate "a browser pin back on localhost"                   q3 m_pin_drift
mutate "a browser pin loses its title"                     q3 m_pin_title
mutate "a second copy of the map in the browser"           q3 m_second_copy
mutate "the browser gradle stops baking the map"           q3 m_gradle_nobake
mutate "the provider authority drifts from the declaration" q3 m_provider_authority
mutate "a name typed in the Tauri glue"                    q4 m_typed_name
mutate "a port typed in the server"                        q4 m_typed_port
mutate "the browser client accepts a non-loopback server"  q5 m_client_not_loopback
mutate "the browser client accepts any answer"             q5 m_client_any_answer
mutate "the browser client resolves through the system"    q5 m_client_system
mutate "the browser stops routing private names"           q5 m_host_unrouted
mutate "an engine description copied into the shell"        q6 m_engine_copy
mutate "the DNS server answers every name"                 q5 m_dns_answers_all

echo
if [ "$FAILURES" -eq 0 ]; then echo "── test-c3-webserver-ports: all green ──"; else echo "── test-c3-webserver-ports: $FAILURES FAILED ──"; exit 1; fi
