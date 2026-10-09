#!/usr/bin/env bash
# Cloud Account redesign task 7 (spec 4.10 + section 8 item 7): the Secrets island.
#
# Static (no build, no device, no network). Holds that:
#  1. a secret literal in a device file is REFUSED (DeviceProfile.build / parse gate on cls.secret)
#  2. Secrets pages: connections draws a value only for a manifest `config` key (fail closed),
#     secrets lists only `secret` keys by fingerprint (never the value), the Cloud Vault package
#     comes from the fleet manifest row `vault` (never a literal), grants keeps revoke / revoke-all
#  3. MainActivity mounts the three pages; DataTab / ConfigsTab are gone
#  4. every op AccountDebugApi routes is declared, and every declared op is routed (no dead op)
# Then each mutation is planted in a scratch copy and the check must go RED.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
LIB="$ROOT/ab_cloud-libs-shared/libs/account/src/main/java/com/diegonmarcos/superapp/profile"
MAIN="$ROOT/ac_cloud-account/app/src/main/java/com/diegonmarcos/cloudaccount/MainActivity.kt"
FAIL=0
ok()  { echo "  ok   $1"; }
bad() { echo "  FAIL $1"; FAIL=1; }

# checks <dir>: prints one line per failed check; empty = all hold
checks() {
  local d="$1" tabs="$1/AccountVaultTabs.kt" dp="$1/DeviceProfile.kt" api="$1/AccountDebugApi.kt" main="$2"
  # 1. secret literal refused
  grep -q 'require(bad.isEmpty())' "$dp" && grep -q 'refused: a secret value is in settings' "$dp" || echo "device file does not refuse a secret literal (build)"
  grep -q 'Parsed.Refused("refused: a secret value' "$dp" || echo "device file does not refuse a secret literal (parse)"
  [ "$(grep -c 'cls.secret(app, store, key) && isLiteral(v)' "$dp")" -ge 2 ] || echo "secretViolations lost the secret-class gate"
  # 2. pages
  grep -q '"config"' "$tabs" && grep -q 'keyClass(m.stores.getValue(r.store), r.key, app.id) == "config"' "$tabs" || echo "connections value is not gated on class == config"
  grep -q 'keyClass(m.stores.getValue(r.store), r.key, app.id) != "secret") return@mapNotNull null' "$tabs" || echo "secrets page is not limited to secret-class keys"
  grep -q 'SHA-256' "$tabs" || echo "secrets page has no sha256 fingerprint"
  grep -q 'it.id == "vault"' "$tabs" || echo "Cloud Vault is not looked up in the fleet manifest"
  ! grep -q 'com\.diegonmarcos\.cloudvault' "$tabs" || echo "Cloud Vault package hardcoded in the Secrets pages"
  ! grep -Eq 'Dense\([^)]*vault\.connection\(' "$tabs" || echo "secrets page renders a raw value"
  grep -q 'VaultTags.revokeAll(who)' "$tabs" && grep -q 'VaultTags.revoke(who, k)' "$tabs" || echo "grants lost revoke / revoke-all"
  # 3. mounting
  for p in 'ConnectionsTab(' 'SecretsTab(openVault' 'GrantsTab()'; do grep -qF "$p" "$main" || echo "MainActivity does not mount $p"; done
  ! grep -rq 'fun DataTab\|fun ConfigsTab' "$d" || echo "DataTab / ConfigsTab came back"
  # 4. ops: declared == routed (for the ops whose name is a plain string case)
  python3 - "$api" <<'PY' || echo "debug op declared but not routed, or routed but not declared"
import re,sys
s=open(sys.argv[1]).read()
decl=set(re.findall(r'^\s*Op\("([a-z]+)"',s,re.M))
a=s.index('fun handle('); body=s[a:]
routed=set()
for l in re.findall(r'^ {12}((?:"[a-z]*"(?:, )?)+) ->',body,re.M): routed|=set(re.findall(r'"([a-z]*)"',l))
sys.exit(1 if (decl-routed) or (routed-decl-{"","get","put"}) else 0)
PY
}

run() { # label dir main
  local out; out="$(checks "$2" "$3")"
  if [ -z "$out" ]; then ok "$1"; else echo "$out" | sed 's/^/  FAIL /'; FAIL=1; fi
}

echo "== real tree"
run "all Secrets checks hold" "$LIB" "$MAIN"

echo "== mutations (each must go red)"
S="$(mktemp -d)"; trap 'rm -rf "$S"' EXIT
mutate() { # name file sedexpr
  rm -rf "$S/lib"; mkdir -p "$S/lib"; cp "$LIB"/*.kt "$S/lib/"; cp "$MAIN" "$S/Main.kt"
  local f="$S/lib/$2"; [ "$2" = Main.kt ] && f="$S/Main.kt"
  python3 - "$f" "$3" "$4" <<'PY'
import sys
p,old,new=sys.argv[1:4]
s=open(p).read()
if old not in s: print("MUTATION TARGET MISSING: "+old); sys.exit(3)
open(p,"w").write(s.replace(old,new,1))
PY
  local rc=$?
  if [ $rc -ne 0 ]; then bad "$1 (mutation did not apply)"; return; fi
  if [ -n "$(checks "$S/lib" "$S/Main.kt")" ]; then ok "$1 -> red"; else bad "$1 stayed green"; fi
}
mutate "secret literal accepted by build"      DeviceProfile.kt 'require(bad.isEmpty())' 'require(true)'
mutate "secret literal accepted by parse"      DeviceProfile.kt 'Parsed.Refused("refused: a secret value' 'Parsed.Refused("ignored'
mutate "secret gate dropped from violations"   DeviceProfile.kt 'cls.secret(app, store, key) && isLiteral(v)' 'isLiteral(v)'
mutate "connections shows non-config values"   AccountVaultTabs.kt 'r.key, app.id) == "config"' 'r.key, app.id) != "x"'
mutate "secrets page lists every class"        AccountVaultTabs.kt '!= "secret") return@mapNotNull null' '!= "") return@mapNotNull null'
mutate "secrets page renders the value"        AccountVaultTabs.kt 'Dense(row.path)' 'Dense(row.path); Dense(vault.connection(row.path))'
mutate "Cloud Vault package hardcoded"         AccountVaultTabs.kt 'it.id == "vault"' 'it.pkg == "com.diegonmarcos.cloudvault"'
mutate "grants lose revoke-all"                AccountVaultTabs.kt 'VaultTags.revokeAll(who)' 'VaultTags.GRANTS'
mutate "grants page unmounted"                 Main.kt 'GrantsTab()' 'AccountPlaceholderPage(section, page, "x")'
mutate "a removed op still routed"             AccountDebugApi.kt '"vault" -> vault(ctx)' '"vault" -> vault(ctx)
            "ghostop" -> JSONObject()'

[ "$FAIL" -eq 0 ] && echo "PASS" || { echo "FAILED"; exit 1; }
