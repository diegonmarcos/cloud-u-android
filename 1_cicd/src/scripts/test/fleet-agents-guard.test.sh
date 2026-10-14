#!/usr/bin/env bash
# fleet-agents-guard.test — prove the fleet agent registry's check FAILS when
# aa_cloud-superapp/data/fleet-agents.json disagrees with the build.json files, or when
# a declaration is malformed, and passes when it agrees. A check only ever watched
# passing is indistinguishable from `exit 0`.
#
# Also pins the agents the owner named: each must be in the generated file.
#
# python3 and coreutils only; no network, no build.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
GUARD="$ROOT/1_cicd/src/scripts/cloud-android-fleet-agents.py"
DATA="$ROOT/aa_cloud-superapp/data/fleet-agents.json"
FAILURES=0
ok()   { printf 'ok     %s\n' "$1"; }
fail() { printf 'FAIL   %s\n' "$1"; FAILURES=$((FAILURES + 1)); }

python3 "$GUARD" check "$ROOT" >/dev/null 2>&1 && ok "the committed tree passes" \
  || fail "the committed tree does not pass: $(python3 "$GUARD" check "$ROOT" | head -3)"

# The owner's list: every one must be present, by app and id.
for want in \
  "cloud-drive/screenshots" \
  "cloud-tasks/screenshot-events" \
  "cloud-code/claude" "cloud-code/hermes" "cloud-code/goose" "cloud-code/deepseek" "cloud-code/openclaw" \
  "cloud-search/rs_house_purchase" "cloud-search/job_placement" \
  "cloud-browser/agent-door" "cloud-mail/agent-door" \
  "cloud-superapp/auto-update" "cloud-superapp/companion-install" "cloud-superapp/privileged-plane"; do
  python3 - "$DATA" "$want" <<'PY' && ok "declared: $want" || fail "missing from fleet-agents.json: $want"
import json, sys
sys.exit(0 if any(a["id"] == sys.argv[2] for a in json.load(open(sys.argv[1]))["agents"]) else 1)
PY
done

# Every row carries what the page draws, and no status source is HTTP.
python3 - "$DATA" <<'PY' && ok "every row has name, app, kind, trigger, open and a local status source" || fail "a row is incomplete"
import json, sys
d = json.load(open(sys.argv[1]))
bad = [a["id"] for a in d["agents"]
       if not all(a.get(k) for k in ("name", "app", "kind", "trigger", "open", "status"))
       or a["status"].get("source") not in ("workmanager", "workmanager_tag", "service", "provider",
                                           "provider_presence", "installed", "none")
       or not (a["open"].startswith("page:") if a["app"] == "cloud-superapp"
               else a["open"].startswith("extapp:%s#page:" % a["app"]))]
print(bad) if bad else None
sys.exit(1 if bad else 0)
PY

WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
( cd "$ROOT" && for f in */build.json */*/build.json \
      aa_cloud-superapp/data/constellation-fleet.json aa_cloud-superapp/data/fleet-agents.json \
      ab_cloud-libs-shared/libs/model-catalogue/src/main/assets/agents/agents.json \
      1_cicd/src/scripts/cloud-android-fleet-agents.py; do
    [ -f "$f" ] && printf '%s\n' "$f"; done | grep -v '^z_archive/' | tar -cf - -T - 2>/dev/null ) | tar -xf - -C "$WORK"
chk() { python3 "$WORK/1_cicd/src/scripts/cloud-android-fleet-agents.py" check "$WORK" 2>&1; }
has() { local o; o="$(chk)"; grep -q "$1" <<<"$o"; }
chk >/dev/null && ok "the copy passes" || fail "the copy does not pass: $(chk | head -3)"

# 1. an app declares a new agent: the data file is stale
python3 - "$WORK" <<'PY'
import json, sys
p = sys.argv[1] + "/ac_cloud-drive/build.json"
d = json.load(open(p))
d["fleet_agents"].append({"id": "x", "name": "X", "kind": "on-device worker", "trigger": "t",
                          "open": "page:agents", "status": {"source": "none", "reason": "r"}})
json.dump(d, open(p, "w"))
PY
has "fleet-agents.json is stale" && ok "a new agent in a build.json makes the data file stale" || fail "a new agent went unnoticed"

# 2. regenerate in the copy: green again
python3 "$WORK/1_cicd/src/scripts/cloud-android-fleet-agents.py" write "$WORK" >/dev/null
chk >/dev/null && ok "regenerating makes it pass" || fail "regeneration does not converge: $(chk | head -3)"

# 3. a malformed row: unknown kind, unknown status source, an open that is not page:
python3 - "$WORK" <<'PY'
import json, sys
p = sys.argv[1] + "/ac_cloud-drive/build.json"
d = json.load(open(p)); r = d["fleet_agents"][-1]
r["kind"] = "robot"; r["status"] = {"source": "http"}; r["open"] = "https://x"
json.dump(d, open(p, "w"))
PY
has "kind 'robot'" && ok "an unknown kind fails" || fail "an unknown kind passed"
has "status source 'http'" && ok "an HTTP status source fails" || fail "an HTTP status source passed"
has "is not a page: target" && ok "an open that is not a page: target fails" || fail "a non-page open passed"

# 4. a `from` that points at nothing
python3 - "$WORK" <<'PY'
import json, sys
p = sys.argv[1] + "/ac_cloud-drive/build.json"
d = json.load(open(p)); d["fleet_agents"][-1] = {"from": "nope.json", "path": "agents", "kind": "in-app agent",
    "trigger": "t", "open": "page:x", "status": {"source": "none", "reason": "r"}}
json.dump(d, open(p, "w"))
PY
has "from nope.json" && ok "an expansion over a missing file fails" || fail "a missing expansion source passed"

# 5. an app nothing can open
python3 - "$WORK" <<'PY'
import json, sys
p = sys.argv[1] + "/ac_cloud-drive/build.json"
d = json.load(open(p)); d["fleet_agents"].pop(); d["name"] = "cloud-ghost"; json.dump(d, open(p, "w"))
PY
has "cloud-ghost is not in" && ok "an app missing from ui.external_apps fails" || fail "an unopenable app passed"

echo
[ "$FAILURES" = 0 ] && echo "PASS fleet-agents-guard.test" || { echo "FAIL fleet-agents-guard.test ($FAILURES)"; exit 1; }
