#!/usr/bin/env bash
# EVERY declared ntfy channel must resolve to a URL on a route that is
# anonymous — checked against the catalog, not against one lucky topic.
#
# This screen has now shipped broken twice. Both times the shape was the same:
# the app polled `https://rss.diegonmarcos.com/<topic>/json`, Caddy handed that
# path to Authelia's forward_auth, and all twenty-six cards read the same
# refusal at once. The server-side contract test that already exists
# (infra-obs_ntfy/src/code/test_read_path.py) passed throughout, because it
# probes the ORIGIN for ONE topic and never looks at what the client asks for.
# Nothing on either side compared the two. That gap is what this closes.
#
# The public hostname is NOT simply "the gated version of the origin". Its only
# anonymous prefix is /feed*, and /feed* is a DIFFERENT SERVICE — the
# rss-gateway sidecar, which serves profiles.json, channels.json and
# <profile>.atom / c/<topic>.atom and has no /<topic>/json route at all. So
# "just add the /feed/ prefix" does not work either; measured, it answers 404.
# T3 exists so that discovery does not have to be made a third time.
#
# What this asserts:
#   T1  ui.ntfy declares base_url, and it is not the gated public host
#   T2  ui.ntfy.poll_window uses a unit ntfy parses — never `d`, which is
#       HTTP 400 code 40008 even on the origin that would otherwise answer
#   T3a no Kotlin file composes a programmatic ntfy read URL itself; the poll
#       address comes from NtfyCatalog.pollUrl and nowhere else
#   T3b the one exempt copy — the AdvisoryFeed lifeline — has not drifted off
#       the declared origin
#   T4  every declared channel is addressable anonymously at the declared
#       origin, asked exactly the way the app asks — one comma-separated poll
#       (live; skipped with a notice when the runner is off the mesh)
#   T5  the gated public host is still gated — if it ever starts serving polls
#       anonymously, someone opened a door and should hear about it (live)
#
# T4 is the one that would have caught this both times: it fails on the URL the
# app will actually build, for all twenty-six addresses, not for a sample.
set -uo pipefail
APP="$(cd "$(dirname "$0")/.." && pwd)"
PASS=0; FAIL=0; SKIP=0
ok()   { PASS=$((PASS+1)); echo "  ok: $1"; }
bad()  { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
skip() { SKIP=$((SKIP+1)); echo "  skip: $1"; }

echo "== ntfy reads go to a route that is actually anonymous =="

# curl on this fleet may inject an Authorization header, which turns every
# "anonymous" probe into a silently authenticated one and is how a gated route
# can look open. Stripping it is not optional here.
CURL=(curl -sS -o /dev/null -H "Authorization:" --max-time 20 -w "%{http_code}")

read -r BASE WEB WINDOW < <(python3 - "$APP" <<'PY'
import json, sys, os
n = json.load(open(os.path.join(sys.argv[1], "build.json")))["ui"]["ntfy"]
print(n.get("base_url", ""), n.get("web_base_url", ""), n.get("poll_window", ""))
PY
)
mapfile -t CHANNELS < <(python3 - "$APP" <<'PY'
import json, sys, os
n = json.load(open(os.path.join(sys.argv[1], "build.json")))["ui"]["ntfy"]
print("\n".join(n.get("channels", [])))
PY
)

# ── T1 ────────────────────────────────────────────────────────────────────────
if [ -z "$BASE" ]; then
  bad "T1 build.json::ui.ntfy.base_url is not declared"
elif [ -n "$WEB" ] && [ "$BASE" = "$WEB" ]; then
  bad "T1 base_url equals web_base_url ($BASE) — the poll would go through the gate"
else
  ok "T1 base_url declared and distinct from the human host: $BASE"
fi

# ── T2 ────────────────────────────────────────────────────────────────────────
case "$WINDOW" in
  all|*[0-9]s|*[0-9]m|*[0-9]h) ok "T2 poll_window '$WINDOW' is a unit ntfy parses" ;;
  *[0-9]d) bad "T2 poll_window '$WINDOW' is in DAYS — ntfy answers HTTP 400 code 40008" ;;
  "")      bad "T2 poll_window is not declared" ;;
  *)       bad "T2 poll_window '$WINDOW' is not s/m/h, a timestamp or 'all'" ;;
esac

# ── T3 ────────────────────────────────────────────────────────────────────────
# A read URL built by hand is the bug itself, so the literal is what is banned:
# any Kotlin composing `/json?poll=` outside the one declared builder. Comments
# quote these URLs on purpose (that is where the diagnosis is written down), so
# only code lines count.
#
# AdvisoryFeed is the ONE exemption and it is deliberate. It is the out-of-band
# channel a stranded device polls when the update chain itself is what broke,
# and it keeps its own copy of the origin precisely so it does not depend on
# this catalog — a lifeline with one dependency is a lifeline with one fewer
# way to fail. T3b is the price of that exemption: the copy must still agree.
OFFENDERS="$(grep -rn --include=*.kt '/json?poll=' "$APP/app/src/main/java" \
  | grep -vE 'NtfyCatalog\.kt|recovery/AdvisoryFeed\.kt' \
  | grep -vE ':[0-9]+: *\*' | grep -vE ':[0-9]+: *//' || true)"
if [ -n "$OFFENDERS" ]; then
  bad "T3a a poll URL is composed outside NtfyCatalog.pollUrl:"
  echo "$OFFENDERS" | sed 's/^/        /'
else
  ok "T3a every programmatic read goes through NtfyCatalog.pollUrl"
fi

FEED_BASE="$(sed -n 's/.*val BASE = "\([^"]*\)".*/\1/p' \
  "$APP/app/src/main/java/com/diegonmarcos/superapp/recovery/AdvisoryFeed.kt" | head -1)"
if [ "$FEED_BASE" = "$BASE" ]; then
  ok "T3b the AdvisoryFeed lifeline still points at the declared origin"
else
  bad "T3b AdvisoryFeed BASE ($FEED_BASE) has drifted from ui.ntfy.base_url ($BASE)"
fi

# ── T4 ────────────────────────────────────────────────────────────────────────
if [ "$(${CURL[@]} "$BASE/v1/health")" != "200" ]; then
  skip "T4 read origin $BASE did not answer — run this from the WireGuard mesh"
else
  # ONE request naming every channel, which is the request the app itself
  # builds — probing the 26 addresses separately would both test a URL the app
  # no longer sends and burn 26 of ntfy's 60-token burst, which is how this
  # very check first answered 429 on its last three channels.
  ALL_TOPICS="$(IFS=,; echo "${CHANNELS[*]}")"
  T4="$(curl -sS -H "Authorization:" --max-time 30 \
        -w '\nHTTP %{http_code}' "$BASE/$ALL_TOPICS/json?poll=1&since=$WINDOW" \
      | python3 -c '
import json, sys
raw = sys.stdin.read().rsplit("\nHTTP ", 1)
code = raw[1].strip() if len(raw) > 1 else "?"
seen = set()
for line in raw[0].splitlines():
    if line.strip():
        try:
            m = json.loads(line)
        except ValueError:
            continue
        if m.get("event") == "message":
            seen.add(m.get("topic"))
print(code, " ".join(sorted(seen)))')"
  T4_CODE="${T4%% *}"
  if [ "$T4_CODE" = "429" ]; then
    skip "T4 rate limited (429) — ntfy replenishes one token per 10s, try again shortly"
  elif [ "$T4_CODE" != "200" ]; then
    bad "T4 the ${#CHANNELS[@]}-channel poll answered $T4_CODE at $BASE"
  else
    # Every envelope must belong to a channel we asked for. A topic we did not
    # name coming back means the address list is not what we think it is.
    STRAY=""
    for t in ${T4#* }; do
      case " ${CHANNELS[*]} " in *" $t "*) ;; *) STRAY="$STRAY $t" ;; esac
    done
    if [ -n "$STRAY" ]; then
      bad "T4 poll returned messages for undeclared topics:$STRAY"
    else
      ok "T4 all ${#CHANNELS[@]} declared channels poll 200 anonymously in one request at $BASE"
    fi
  fi

  # ── T5 ──────────────────────────────────────────────────────────────────────
  # 401 and 302 are the same verdict: refused for want of a credential. Which
  # one comes back depends on the Accept header, so both are a pass.
  gate="$(${CURL[@]} "${WEB:-https://rss.diegonmarcos.com}/${CHANNELS[0]}/json?poll=1&since=$WINDOW")"
  case "$gate" in
    401|403|302) ok "T5 the public host still refuses an anonymous poll ($gate)" ;;
    # curl's 000 is "nothing came back", which is a statement about this
    # runner's link and not about the gate. Failing on it would cry wolf every
    # time the edge is slow, and a check that cries wolf stops being read.
    000)         skip "T5 ${WEB} did not answer — no verdict on the gate from here" ;;
    *) bad "T5 ${WEB} answered $gate to an anonymous poll — the gate may have been opened" ;;
  esac
fi

echo
echo "$PASS passed, $FAIL failed, $SKIP skipped"
[ "$FAIL" = "0" ]
