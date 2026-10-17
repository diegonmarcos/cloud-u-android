#!/usr/bin/env bash
# Cloud ▸ tabs: App | WebApps | Linktree | Configs, and WebApps is the cloud-mobile replica in the embedded browser.
#   T1 the Cloud section lists exactly those four visible pages, in that order
#   T2 WebApps declares the published site's URL (GitHub Pages, public) with ?embed=1
#   T3 WebPageFragment appends the CloudSuperApp-Embed/1 user-agent marker, keyed off embed=1 only
#   T4 an embed page is a pane: no floating Back, pull-to-refresh, the offline message, other sites leave via the link rules
set -uo pipefail
APP="$(cd "$(dirname "$0")/.." && pwd)"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
WP="$APP/app/src/main/java/com/diegonmarcos/superapp/launcher/WebPageFragment.kt"
for f in "$APP/build.json" "$WP"; do [ -f "$f" ] || { echo "ERROR: missing $f" >&2; exit 2; }; done

out="$(python3 - "$APP/build.json" <<'PY'
import json, sys
s = [x for x in json.load(open(sys.argv[1]))["ui"]["sections"] if x["id"] == "cloud"][0]
vis = [p for p in s["pages"] if not p.get("hidden")]
print("ORDER", ",".join(p["id"] for p in vis))
print("LABELS", ",".join(p["label"] for p in vis))
w = [p for p in s["pages"] if p["id"] == "webapps"]
print("URL", w[0].get("url", "") if w else "")
PY
)"
[ "$(grep '^ORDER' <<<"$out" | cut -d' ' -f2)" = "apps,webapps,lnktree,configs" ] \
  && ok "T1 Cloud lists apps, webapps, lnktree, configs in that order" || bad "T1 Cloud's tabs are not App | WebApps | Linktree | Configs ($(grep '^ORDER' <<<"$out"))"
[ "$(grep '^LABELS' <<<"$out" | cut -d' ' -f2)" = "App,WebApps,Linktree,Configs" ] \
  && ok "T1 and they read App | WebApps | Linktree | Configs" || bad "T1 the tab labels moved ($(grep '^LABELS' <<<"$out"))"
URL="$(grep '^URL' <<<"$out" | cut -d' ' -f2-)"
[ "$URL" = "https://diegonmarcos.github.io/cloud-mobile/cloud/apps/?embed=1" ] \
  && ok "T2 WebApps loads the published cloud-mobile Cloud > App page with ?embed=1" || bad "T2 WebApps url is '$URL'"
grep -qF 'const val UA_MARKER = " CloudSuperApp-Embed/1"' "$WP" && grep -qF 'if (embed) settings.userAgentString = settings.userAgentString + UA_MARKER' "$WP" \
  && ok "T3 the UA marker is appended on embed pages only" || bad "T3 the user-agent marker is missing or unconditional"
grep -qF 'if (!embed) Icon(painterResource(R.drawable.ic_back)' "$WP" \
  && ok "T4 an embed page has no floating Back" || bad "T4 an embed page still floats a Back control over the site"
grep -qF 'PullDownToRefresh(modifier' "$WP" && grep -qF 'internal fun PullDownToRefresh(' "$WP" && ok "T4 pull-to-refresh" || bad "T4 no pull-to-refresh"
grep -qF 'You look to be offline' "$WP" && ok "T4 an offline page says so" || bad "T4 the error page still blames the mesh for a public site"
grep -qF 'sameSite(url, target.toString())' "$WP" && grep -qF '.launchUri(target.toString())' "$WP" \
  && ok "T4 other sites leave through the app's link rules" || bad "T4 a link to another site loads inside the pane"
# mutation: dropping the marker must turn T3 red
MUT="$(sed 's/settings.userAgentString + UA_MARKER/settings.userAgentString/' "$WP")"
if grep -qF 'settings.userAgentString + UA_MARKER' <<<"$MUT"; then
  bad "mutation: the UA check cannot see the marker go"; else ok "mutation: removing the marker is visible to T3"; fi
echo "PASS=$PASS FAIL=$FAIL"; [ "$FAIL" -eq 0 ]
