#!/usr/bin/env bash
# cloud-c3-webserver — Pages links and Configs (About, Engines) are DERIVED from the server's own data.
#   G1 Pages draws a block per served endpoint of /__api__/serving with a name link and a port link, each
#      carrying data-open (a tap hands it to cloud-browser); a planted endpoint appears, a removed one goes.
#   G2 Configs has exactly the two pages About and Engines; Engines draws a row per registry entry, a switch
#      only where `toggle` is true, the reason where an engine is not portable; About draws ports, DNS and versions.
#   G3 the shell types no engine id, no served name and no port: all of it comes from the API.
#   M  each defect is planted in a copy and shown red.
# python3, node.
set -uo pipefail
APP="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FAILURES=0
pass() { echo "  PASS  $*"; }; fail() { echo "  FAIL  $*"; FAILURES=$((FAILURES + 1)); }
for t in python3 node; do command -v $t >/dev/null || { echo "ERROR $t absent"; exit 1; }; done

check() {  # check <shell.html> <build.json> <engines.rs>
python3 - "$1" "$2" "$3" <<'PY'
import json, os, re, subprocess, sys, tempfile
shell, bj, eng = (open(a).read() for a in sys.argv[1:4])
bad = []
pure = re.search(r'<script id="pure">(.*?)</script>', shell, re.S).group(1)
glue = shell.split('<script id="pure">')[1].split('</script>', 1)[1]
for ident in re.findall(r'"name":\s*"([a-z0-9-]+\.app)"', bj) + re.findall(r'"id":\s*"([a-z]+)",\s*"name":\s*"[a-z-]+\.app"', bj):
    if re.search(r"""['">]%s[<'"]""" % re.escape(ident), shell): bad.append("G3 shell.html spells %r" % ident)
for e in [x['id'] for x in json.loads(bj)['engines']['list']]:
    if re.search(r"""(?<!role )(?<![=!])(===?|!==?|case)\s*['"]%s['"]""" % re.escape(e), shell): bad.append("G3 shell.html spells the engine id %r" % e)
if re.search(r"\b(8000|8001|8002|8003|5353)\b", shell): bad.append("G3 shell.html spells a served port")
with tempfile.TemporaryDirectory() as d:
    open(d + "/p.js", "w").write(pure)
    open(d + "/h.js", "w").write(r"""
const p = require(process.argv[2]);
function must(c, m) { if (!c) { console.log('    ' + m); process.exitCode = 1; } }
const ep = (id, name, port, role, up) => ({id, name, port, role, owner:'o', title:id.toUpperCase(), summary:'s <b>', url_name:'http://'+name+'/', url_ip:'http://127.0.0.1:'+port+'/', up});
const sv = {dns:{host:'127.0.0.1',port:5353,answer:'127.0.0.1',ttl_s:30,scope:'only ours'}, opener:'pkg.browser', app:'app', version_name:'1.0', version_code:3, crate_version:'0.2', bridge:{authority:'a.b'}, bundled:[{name:'marked',version:'15'}],
  endpoints:[ep('a','a.app',9001,'external',false), ep('b','b.app',9002,'drive',true)]};
let h = p.renderServed(sv);
must((h.match(/data-open="a"/g)||[]).length === 2 && (h.match(/data-open="b"/g)||[]).length === 2, 'Pages needs two [data-open] links per endpoint');
must(h.includes('data-via="name"') && h.includes('data-via="ip"') && h.includes('href="http://a.app/"') && h.includes('127.0.0.1:9002'), 'Pages lacks the name or the port link');
must(!h.includes('<b>'), 'Pages does not escape a summary');
const more = JSON.parse(JSON.stringify(sv)); more.endpoints.push(ep('c','c.app',9003,'diag',null));
must(p.renderServed(more).includes('data-open="c"'), 'a planted endpoint is not drawn');
const less = JSON.parse(JSON.stringify(sv)); less.endpoints.pop();
must(!p.renderServed(less).includes('data-open="b"'), 'a removed endpoint is still drawn');
const t = p.renderCfgTabs('engines');
must((t.match(/data-cfg="/g)||[]).length === 2 && t.includes('data-cfg="about"') && t.includes('data-cfg="engines"'), 'Configs must have exactly About and Engines');
const list = [{id:'x',name:'X',group:'render',toggle:true,on:true,covers:'.x',summary:'sx',reason:null},
              {id:'y',name:'Y',group:'core',toggle:false,on:true,covers:'.y',summary:'sy',reason:null},
              {id:'z',name:'Z',group:'not-portable',toggle:false,on:false,covers:'.z',summary:'sz',reason:'needs git'}];
const e = p.renderEngines(list);
must((e.match(/class="eng"/g)||[]).length === 3, 'Engines must draw a row per registry entry');
must((e.match(/role="switch"/g)||[]).length === 1 && e.includes('data-engine="x"') && e.includes('aria-checked="true"'), 'a switch only where toggle is true, reflecting on');
must(e.includes('Not portable: needs git') && e.indexOf('Render engines') < e.indexOf('Core') && e.indexOf('Core') < e.indexOf('Not portable to'), 'reason or group order lost');
const a = p.renderServing(sv, {root:'/r', methods:['GET','HEAD'], os:'android', arch:'arm64'});
must(['a.app','b.app','127.0.0.1:9002','udp 127.0.0.1:5353','Server crate','0.2','marked','android / arm64','/r'].every(x => a.includes(x)), 'About omits a port, name, DNS line or version');
""")
    r = subprocess.run(["node", d + "/h.js", d + "/p.js"], capture_output=True, text=True)
    if r.returncode or r.stdout.strip(): bad.append("node: " + r.stdout + r.stderr)
for b in bad: print("    " + b)
sys.exit(1 if bad else 0)
PY
}
S="$APP/server/ui/shell.html"; B="$APP/build.json"; E="$APP/server/src/engines.rs"
echo "── cloud-c3-webserver pages / configs ──"
check "$S" "$B" "$E" && pass "G1-G3 Pages links, Configs About + Engines, derived from the API under node" || fail "G1-G3 the shell hand-lists or misrenders"
W="$(mktemp -d)"; trap 'rm -rf "$W"' EXIT
mut() { # mut <title> <python replace old> <new>
  cp "$S" "$W/s.html"; python3 - "$W/s.html" "$2" "$3" <<'PY' || { fail "M: '$1' did not apply"; return; }
import sys; p, a, b = sys.argv[1:4]; s = open(p).read(); n = s.replace(a, b, 1); assert n != s; open(p, "w").write(n)
PY
  if check "$W/s.html" "$B" "$E" >/dev/null 2>&1; then fail "M: '$1' stayed GREEN"; else pass "M: '$1' goes red"; fi
}
mut "Pages drops the port link" "'<a data-open=\"' + esc(e.id) + '\" data-via=\"ip\"" "'<a data-x=\"' + esc(e.id) + '\" data-via=\"ip\""
mut "a third Configs page" "[['about', 'About'], ['engines', 'Engines']]" "[['about', 'About'], ['engines', 'Engines'], ['x', 'X']]"
mut "a switch on every engine" "var sw = e.toggle" "var sw = true"
mut "an engine id typed in the shell" "var SERVING = null;" "var SERVING = null; var k = 1; if (k === 'markdown') k = 2;"
mut "a port typed in the shell" "var SERVING = null;" "var SERVING = null; var p = 8002;"
echo; [ "$FAILURES" -eq 0 ] && echo "── test-c3-webserver-pages: all green ──" || { echo "── test-c3-webserver-pages: $FAILURES FAILED ──"; exit 1; }
