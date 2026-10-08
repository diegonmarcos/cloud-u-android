#!/usr/bin/env bash
# Tester (#802): `POST /api/account/import` — the engine's way to land the declared bundle.
#
# linux-account (cloud-u-linux/da_linux-account) is the engine that pushes the decrypted
# vault export into the phone's Account apps over the loopback fleet API. Until this route
# the ONLY way in was the UI file picker, so a phone with the bundle in its checkout still
# ran with an empty Account. What is pinned here, because each is one refactor from lost:
#   T1  the route EXISTS, is documented (Op) and takes the bundle as the POST body (_body),
#       with its own larger body ceiling — the default 256 KiB holds for every other op
#   T2  it runs the SAME gates as the Import File line: AccountHost.classify (VaultFile:
#       sops/ENC refused, schema_version known) and refuses with the host's sentence
#   T3  it writes S through the ONE landing the UI uses (AccountModel.landBundle) — the
#       fragment calls it, nothing else writes S from a bundle
#   T4  nothing of the body is echoed: the answer is the verdict, counts, no rows, no _body
#   T5  the server honours an op's maxBody only for a fleet-authenticated caller
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
has()   { grep -qF -- "$2" "$1" 2>/dev/null && ok "$3" || bad "$3 ($1)"; }
hasnt() { grep -qF -- "$2" "$1" 2>/dev/null && bad "$3 ($1)" || ok "$3"; }
code()  { sed -E 's://.*$::; /^\s*\*/d; /^\s*\/\*/d' "$1"; }   # comment lines dropped
codehasnt() { code "$1" | grep -qF -- "$2" && bad "$3 ($1)" || ok "$3"; }

LIB="$ROOT/../ab_cloud-libs-shared/libs"
DBG="$LIB/account/src/main/java/com/diegonmarcos/superapp/profile/AccountDebugApi.kt"
MD="$LIB/account/src/main/java/com/diegonmarcos/superapp/profile/AccountModel.kt"
PF="$LIB/account/src/main/java/com/diegonmarcos/superapp/profile/ProfileFragment.kt"
HOST="$LIB/account/src/main/java/com/diegonmarcos/superapp/profile/AccountHost.kt"
SRV="$LIB/devtools/src/main/java/com/diegonmarcos/superapp/devtools/AppDebugServer.kt"

echo "T1 the route exists, documented, body-fed, with its own ceiling"
has "$DBG" 'Op("import", "POST body = the decrypted vault export"' "import is a documented Op of /api/account"
has "$DBG" 'maxBody = IMPORT_MAX_BODY' "...declaring its own body ceiling"
has "$DBG" 'const val IMPORT_MAX_BODY = 4 * 1024 * 1024' "...4 MiB (the bundle is ~2 MB)"
has "$DBG" '"import" -> importBundle(ctx, q["_body"].orEmpty(), m)' "the op reads the body (_body), never a query value"
has "$SRV" 'val maxBody: Int = MAX_BODY_BYTES' "Op carries maxBody, default = the server ceiling"
has "$SRV" 'internal const val MAX_BODY_BYTES = 256 * 1024' "...which stays 256 KiB for every other op"
has "$SRV" 'if (o.maxBody != MAX_BODY_BYTES) append(""""max_body":${o.maxBody},""")' "/api/docs shows a raised ceiling"

echo "T2 the same gates as the Import File line"
has "$PF" 'else -> when (val v = AccountHost.classify(text)) {' "the UI import classifies through AccountHost.classify"
has "$DBG" 'val v = AccountHost.classify(text)' "...and so does the route"
has "$DBG" 'if (v !is com.diegonmarcos.cloudlib.auth.VaultFile.Verdict.Bundle)' "anything but the decrypted export is refused"
has "$DBG" 'AccountHost.refusal(ctx, v).orEmpty())' "...with the host's own refusal sentence"
has "$MD" 'VaultConnect.unknownSchemaVersion(body, com.diegonmarcos.cloudlib.auth.VaultConnect.knownSchemaVersions)?.let { return it }' "the landing refuses an unknown schema_version"
has "$DBG" 'put("verdict", "unknownschema").put("schema_version", bad)' "...and the route reports it as a verdict"
codehasnt "$DBG" 'VaultFile.classify(' "the route never calls the classifier directly (the host's hook is the one)"

echo "T3 one landing for the UI and the route"
has "$MD" 'fun landBundle(ctx: Context?, body: JSONObject, via: String): Int?' "AccountModel.landBundle is the one landing"
has "$MD" 'get(ctx).landServer(bundle, via)' "...writing S through landServer"
has "$PF" 'AccountModel.landBundle(context, body, via)?.let { v ->' "the fragment's landVault calls it"
has "$DBG" 'AccountModel.landBundle(ctx, v.bundle, "api:import")' "the route calls it, source api:import"
codehasnt "$PF" 'landServer(' "the fragment no longer writes S itself"
codehasnt "$DBG" 'landServer(' "the route never writes S itself"
codehasnt "$PF" 'VaultConnect.Imported.bundle = ' "the fragment no longer sets Imported itself"

echo "T4 nothing of the body is echoed"
has "$DBG" 't.optJSONObject(i)?.remove("rows")' "the per-topic rows (texts) are dropped from the answer"
codehasnt "$DBG" 'put("body"' "no field named body in any answer"
codehasnt "$DBG" 'Log.' "AccountDebugApi logs nothing"
n=$(code "$DBG" | grep -c '"_body"'); [ "$n" = "1" ] && ok "_body is read in exactly one place (the dispatch)" || bad "_body read sites: $n"
codehasnt "$DBG" 'put("text", text' "the request text is never put into an answer"

echo "T5 the raised ceiling is for a fleet caller only"
has "$SRV" 'val limit = if (fleet) bodyLimit(op) else MAX_BODY_BYTES' "an unauthenticated request keeps the default ceiling"
has "$SRV" 'internal fun bodyLimit(op: String): Int =' "bodyLimit reads the op's declared maxBody"
has "$SRV" 'if (op !in OPEN_OPS && !fleet) {' "the token check is the same decision"

# The terminals' TerminalDebugApi.java constructs Op(op, params, description): a Kotlin default
# parameter (maxBody) needs @JvmOverloads or the terminal does not compile (CI 2026-10-08).
has "$ROOT/../ab_cloud-libs-shared/libs/devtools/src/main/java/com/diegonmarcos/superapp/devtools/AppDebugServer.kt" 'data class Op @JvmOverloads constructor(' "Op keeps its Java-callable constructors (@JvmOverloads) beside the maxBody default"

# The device pick travels with the bundle (linux-account phone import ?device=): a phone left to
# the live tunnel's address inherits whoever's profile is up — the A37 ran as the S21+.
has "$DBG" 'val device = q["device"]?.trim().orEmpty()' "import reads the optional device pick"
has "$DBG" 'VaultCockpit.devices(v.bundle).none { it.id == device } -> "✗ device' "a device the bundle does not declare is refused by name"
has "$DBG" 'VaultCockpit.selectDevice(ctx, device); "✓ $device"' "a declared device is selected through VaultCockpit.selectDevice, the Connect tab setter"

echo; echo "$PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
