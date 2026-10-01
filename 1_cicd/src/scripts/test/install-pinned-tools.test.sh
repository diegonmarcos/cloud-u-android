#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════╗
# ║ install-pinned-tools.test — a failed tool download fails the     ║
# ║ step that downloaded it, not a step forty minutes later          ║
# ╚══════════════════════════════════════════════════════════════════╝
#
# WHY THIS EXISTS (#754). Every ship workflow installed sops with
# `curl -fsSL URL | sudo tee /usr/local/bin/sops`, in a `run:` step GitHub
# executes as `bash -e` WITHOUT pipefail. GitHub answered HTTP 500, curl -f
# failed, tee succeeded, the step went green with an EMPTY executable sops
# (an empty file runs as an empty script: `sops --version` exits 0), and the
# build died later on "missing SOPS_AGE_KEY" — a misleading verdict about the
# wrong thing.
#
# TWO HALVES.
#   1. The shared installer (1_cicd/src/actions/install-pinned-tools/install.sh)
#      is driven, unmodified, against a local server that answers 500, against
#      a wrong pinned sha256, and against a correct one. The first two must exit
#      non-zero AND leave nothing installed; the third must install and run it.
#   2. Every workflow source (1_cicd/src/cicd, templates, actions) is read: no
#      `run:` step may pipe curl into anything unless that step runs with
#      pipefail, and no workflow may fetch an oras/sops release itself — the
#      pin lives in tools.json and nowhere else. The scan is proven against
#      planted fixtures in both directions before it reads the tree.

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
INSTALL="$ROOT/1_cicd/src/actions/install-pinned-tools/install.sh"
FAILURES=0
ok()   { printf 'ok     %s\n' "$1"; }
fail() { printf 'FAIL   %s\n' "$1"; FAILURES=$((FAILURES + 1)); }

WORK="$(mktemp -d)"
SERVER_PID=""
trap '[ -n "$SERVER_PID" ] && kill "$SERVER_PID" 2>/dev/null; rm -rf "$WORK"' EXIT

# ── half 1: the installer, against a real HTTP server ──────────────────────
# /good serves a fake tool; anything else answers 500, the failure that started this.
mkdir -p "$WORK/srv"
printf '#!/bin/sh\necho fake-tool "$@"\n' > "$WORK/srv/good"
GOOD_SHA="$(sha256sum "$WORK/srv/good" | cut -d' ' -f1)"
python3 - "$WORK/srv" "$WORK/port" <<'PY' &
import http.server, os, sys
root, portfile = sys.argv[1], sys.argv[2]
class H(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        p = os.path.join(root, self.path.lstrip("/"))
        if self.path == "/good":
            body = open(p, "rb").read(); self.send_response(200)
        else:
            body = b"Internal Server Error"; self.send_response(500)
        self.send_header("Content-Length", str(len(body))); self.end_headers(); self.wfile.write(body)
    def log_message(self, *a): pass
s = http.server.HTTPServer(("127.0.0.1", 0), H)
open(portfile + ".tmp", "w").write(str(s.server_port)); os.rename(portfile + ".tmp", portfile)
s.serve_forever()
PY
SERVER_PID=$!
for _ in $(seq 50); do [ -s "$WORK/port" ] && break; sleep 0.1; done
PORT="$(cat "$WORK/port" 2>/dev/null || true)"
[ -n "$PORT" ] || { echo "FAIL   the local test server never started"; exit 1; }

# run_case NAME URL SHA EXPECT(ok|fail)
run_case() {
    local name="$1" url="$2" sha="$3" expect="$4" dest="$WORK/dest-$1"
    mkdir -p "$dest"
    printf '{"tool":{"url":"%s","sha256":"%s","check":"--probe"}}\n' "$url" "$sha" > "$WORK/pins-$name.json"
    INSTALL_TOOLS_PINS="$WORK/pins-$name.json" INSTALL_TOOLS_DEST="$dest" \
        bash "$INSTALL" tool > "$WORK/out-$name" 2>&1
    local rc=$?
    if [ "$expect" = fail ]; then
        if [ "$rc" -ne 0 ] && [ ! -e "$dest/tool" ]; then ok "$name: exit $rc, nothing installed"
        else fail "$name: exit $rc, installed=$([ -e "$dest/tool" ] && echo yes || echo no) — a broken download went green"; fi
    else
        if [ "$rc" -eq 0 ] && grep -q 'fake-tool --probe' "$WORK/out-$name"; then ok "$name: installed and executed"
        else fail "$name: exit $rc — a good download was refused"; sed 's/^/       /' "$WORK/out-$name"; fi
    fi
}
run_case http500   "http://127.0.0.1:$PORT/missing" "$GOOD_SHA" fail
run_case badsha    "http://127.0.0.1:$PORT/good"    "$(printf '0%.0s' $(seq 64))" fail
run_case good      "http://127.0.0.1:$PORT/good"    "$GOOD_SHA" ok
# A name tools.json does not declare is an error, not an install of "null".
INSTALL_TOOLS_PINS="$WORK/pins-good.json" INSTALL_TOOLS_DEST="$WORK/dest-good" \
    bash "$INSTALL" nosuchtool >/dev/null 2>&1 \
    && fail "unknown tool name exited 0" || ok "unknown tool name refused"

# The premise, re-proved on this machine: without pipefail the old spelling
# really is green on a 500. If this stops holding, half 2 is guarding nothing.
if bash -e -c "curl -fsSL http://127.0.0.1:$PORT/missing | cat > /dev/null" 2>/dev/null; then
    ok "premise: curl -f | cat under bash -e (no pipefail) exits 0 on HTTP 500"
else
    fail "premise: the no-pipefail pipe did NOT go green — rethink this guard"
fi

# ── half 2: the workflow sources ───────────────────────────────────────────
SCAN="$WORK/scan.py"
cat > "$SCAN" <<'PY'
import glob, os, re, sys
root = sys.argv[1]
files = sys.argv[2:] or sorted(
    glob.glob(os.path.join(root, "1_cicd/src/cicd/*.yml"))
    + glob.glob(os.path.join(root, "1_cicd/src/templates/*.in"))
    + glob.glob(os.path.join(root, "1_cicd/src/actions/*/action.yml")))
PINNED = re.compile(r"github\.com/(oras-project/oras|getsops/sops)/releases/download")
PIPEFAIL = re.compile(r"^\s*set\s+-[a-z]*o\s+pipefail|^\s*set\s+-o\s+pipefail")
bad = []
for f in files:
    lines = open(f).read().split("\n")
    rel = os.path.relpath(f, root)
    for n, l in enumerate(lines, 1):
        if PINNED.search(l) and not l.lstrip().startswith("#"):
            bad.append("%s:%d fetches an oras/sops release itself — use ./.github/actions/install-pinned-tools (the pin lives in its tools.json)" % (rel, n))
    # A step: `- ` at some indent, up to the next line at that indent or less.
    i = 0
    while i < len(lines):
        m = re.match(r"^(\s*)- ", lines[i])
        if not m:
            i += 1; continue
        ind = len(m.group(1)); s = i; i += 1
        while i < len(lines) and (not lines[i].strip() or len(lines[i]) - len(lines[i].lstrip()) > ind):
            i += 1
        step = lines[s:i]
        text = "\n".join(step)
        if not re.search(r"^\s*-?\s*run:", text, re.M):
            continue
        # `shell: bash` (named, not a custom template) runs with -eo pipefail.
        if re.search(r"^\s*shell:\s*bash\s*$", text, re.M) or any(PIPEFAIL.search(x) for x in step):
            continue
        code = [x for x in step if not x.lstrip().startswith("#")]
        for k, x in enumerate(code):
            if "curl" not in x:
                continue
            nxt = code[k + 1].lstrip() if k + 1 < len(code) else ""
            if re.search(r"curl\b[^#]*\|(?!\|)", x) or (x.rstrip().endswith("\\") and nxt.startswith("|")):
                bad.append("%s:%d pipes curl with no pipefail — a failed download exits 0 here" % (rel, s + 1))
                break
for b in bad:
    print(b)
sys.exit(1 if bad else 0)
PY

# Prove the scan in both directions before believing its verdict on the tree.
cat > "$WORK/planted-bad.yml" <<'EOF'
jobs:
  j:
    steps:
      - name: old spelling
        run: |
          curl -fsSL https://example.invalid/tool \
            | sudo tee /usr/local/bin/tool >/dev/null
EOF
cat > "$WORK/planted-pin.yml" <<'EOF'
jobs:
  j:
    steps:
      - name: own pin
        run: |
          set -euo pipefail
          curl -fsSL -o x https://github.com/getsops/sops/releases/download/v3.9.4/sops-v3.9.4.linux.amd64
EOF
cat > "$WORK/planted-good.yml" <<'EOF'
jobs:
  j:
    steps:
      - name: pipefail on
        run: |
          set -euo pipefail
          curl -fsSL https://example.invalid/install.sh | sh
      - name: no pipe
        run: curl -fsSL -o x https://example.invalid/x
      - name: composite
        shell: bash
        run: curl -fsSL https://example.invalid/x | sudo bash -
EOF
python3 "$SCAN" "$WORK" "$WORK/planted-bad.yml"  >/dev/null && fail "scan missed curl | tee with no pipefail"  || ok "scan catches curl | tee with no pipefail"
python3 "$SCAN" "$WORK" "$WORK/planted-pin.yml"  >/dev/null && fail "scan missed a workflow pinning sops itself" || ok "scan catches a workflow pinning sops itself"
python3 "$SCAN" "$WORK" "$WORK/planted-good.yml" >/dev/null && ok "scan passes pipefail / no-pipe / shell: bash" || fail "scan flagged a safe spelling"

if out="$(python3 "$SCAN" "$ROOT")"; then
    ok "every workflow source fails at the download"
else
    fail "workflow sources that can go green on a failed download:"
    printf '%s\n' "$out" | sed 's/^/       /'
fi

[ "$FAILURES" -eq 0 ] || { echo "$FAILURES failure(s)"; exit 1; }
echo "all passed"
