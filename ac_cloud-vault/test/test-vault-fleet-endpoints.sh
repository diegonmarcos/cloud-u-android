#!/bin/sh
# #1093 Cloud Vault reaches the self-hosted vault over the shared fleet endpoint design, statically:
#   F1 the network module compiles the ONE shared source tree (libs/fleet-endpoints) by reference, no copy of it here.
#   F2 the route + bearer interceptors are added AFTER the base-URL interceptor in the authenticated, unauthenticated and
#      static clients (so they see the real server URL) and NOT in the external client (non-Bitwarden hosts).
#   F3 the application pulls Cloud Account's published endpoints at start; the bearer is held in memory only
#      (no prefs, no file, no log), and the manifest queries Cloud Account.
#   F4 the vault's mesh endpoint is in the GENERATED cleartext block (from the fleet declaration), never the whole .app TLD.
# Each check is then run against planted mutations of the real sources; a mutation that leaves every check green fails.
set -eu
APP_DIR="$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)"
export APP_DIR
python3 - <<'PY'
import os, re, sys
APP = os.environ["APP_DIR"]
NET = "network/src/main/kotlin/com/bitwarden/network/retrofit/RetrofitsImpl.kt"
GRADLE = "network/build.gradle.kts"
FLEET = "../ab_cloud-libs-shared/libs/fleet-endpoints-android/src/main/java/com/diegonmarcos/superapp/fleetendpoints/android/FleetBoot.kt"
VAULTFLEET = "app/src/main/kotlin/com/x8bit/bitwarden/fleet/VaultFleetEndpoints.kt"
APPCLS = "app/src/main/kotlin/com/x8bit/bitwarden/BitwardenApplication.kt"
MANIFEST = "app/src/main/AndroidManifest.xml"
NSC = "app/src/main/res/xml/network_security_config.xml"
APPGRADLE = "app/build.gradle.kts"
FILES = [NET, GRADLE, FLEET, VAULTFLEET, APPCLS, MANIFEST, NSC, APPGRADLE]

def rd(rel): return open(os.path.join(APP, rel), encoding="utf-8").read()
def fn(src, name):
    i = src.index("private fun " + name)
    j = src.find("\n    private fun ", i + 10)
    return src[i: j if j > 0 else len(src)]

def checks(f):
    bad = []
    def need(c, m):
        if not c: bad.append(m)
    g = f[GRADLE]
    need("ab_cloud-libs-shared/libs/fleet-endpoints/src/main/kotlin" in g and "kotlin.directories.add" in g, "F1 network does not compile the shared fleet-endpoints tree by reference")
    need(not os.path.isdir(os.path.join(APP, "network/src/main/kotlin/com/diegonmarcos")), "F1 a copy of the shared tree sits in the network module")
    need("ab_cloud-libs-shared/libs/fleet-endpoints-android/src/main/java" in f[APPGRADLE] and "kotlin.directories.add" in f[APPGRADLE], "F1 the app does not compile the shared Android halves by reference")
    n = f[NET]
    for name in ("createAuthenticatedRetrofit", "createUnauthenticatedRetrofit"):
        b = fn(n, name)
        need(".withFleetEndpoints()" in b and b.index("addInterceptor(baseUrlInterceptor)") < b.index(".withFleetEndpoints()"), "F2 " + name + " does not add the fleet interceptors after the base-URL interceptor")
    need(".withFleetEndpoints()" in n[n.index("override fun createStaticRetrofit"):n.index("//endregion Static Retrofit")], "F2 the static client lacks the fleet interceptors")
    need(".withFleetEndpoints()" not in fn(n, "createExternalRetrofit"), "F2 the external (non-Bitwarden) client carries the fleet interceptors")
    need("EndpointRouteInterceptor(FleetTransport.router)" in n and "addNetworkInterceptor(FleetBearerInterceptor(" in n, "F2 the route interceptor is not application-level or the bearer interceptor is not a network interceptor")
    v = f[FLEET]
    need("FleetEndpointsClient.pull(" in v and "NetworkChangeWatch.start(" in v, "F3 no pull at start / on a network change")
    need("VaultFleetEndpoints.start(this)" in f[APPCLS], "F3 the application does not start the endpoint pull")
    need("FleetBoot.start(context)" in f[VAULTFLEET], "F3 the vault hook does not run the shared FleetBoot")
    need(not re.search(r"SharedPreferences|getSharedPreferences|writeText|openFileOutput|Timber|Log\.", v), "F3 the bearer holder persists or logs")
    need("@Volatile" in v and "private var bearer" in v, "F3 the bearer is not an in-memory field")
    need('<package android:name="com.diegonmarcos.cloudaccount"' in f[MANIFEST], "F3 the manifest does not query Cloud Account")
    x = f[NSC]
    need("BEGIN fleet-mesh-cleartext" in x and re.search(r'BEGIN fleet-mesh-cleartext[^>]*-->\s*<domain includeSubdomains="false">vaultwarden\.mesh\.diegonmarcos\.com</domain>', x) is not None, "F4 vaultwarden.mesh.diegonmarcos.com is not in the GENERATED cleartext block")
    need(not re.search(r'<domain[^>]*>\.?app</domain>|<domain[^>]*>\*\.app</domain>', x), "F4 the whole .app TLD is opened")
    return bad

real = {r: rd(r) for r in FILES}
bad = checks(real)
for b in bad: print("FAIL     " + b)
if bad: sys.exit(1)
print("PASS     F1-F4 hold on the real sources")

def mut(rel, old, new, count=1):
    def go(f):
        assert old in f[rel], "mutation anchor missing: " + old
        g = dict(f); g[rel] = f[rel].replace(old, new, count); return g
    return go

mutations = {
    "drop the by-reference source dir": mut(GRADLE, "kotlin.directories.add", "kotlin.directories.remove"),
    "no fleet interceptors in the static client": mut(NET, "                    .addInterceptor(permissionInterceptor)\n                    .withFleetEndpoints()\n                    .build(),\n            )\n            .build()\n    }\n\n    //endregion Static", "                    .addInterceptor(permissionInterceptor)\n                    .build(),\n            )\n            .build()\n    }\n\n    //endregion Static"),
    "fleet interceptors in the external client": lambda f: dict(f, **{NET: f[NET].replace("externalOkHttpClient\n                    .newBuilder()", "externalOkHttpClient\n                    .newBuilder().withFleetEndpoints()")}),
    "bearer interceptor as an application interceptor": mut(NET, "addNetworkInterceptor(FleetBearerInterceptor(", "addInterceptor(FleetBearerInterceptor("),
    "no pull": mut(FLEET, "FleetEndpointsClient.pull(", "FleetEndpointsClientX.pull("),
    "the bearer is logged": mut(FLEET, "private var bearer: String? = null", "private var bearer: String? = null.also { Timber.d(\"x\") }"),
    "application does not start it": mut(APPCLS, "VaultFleetEndpoints.start(this)", "// removed"),
    "the vault hook runs nothing": mut(VAULTFLEET, "FleetBoot.start(context)", "Unit"),
    "no query for Cloud Account": mut(MANIFEST, '<package android:name="com.diegonmarcos.cloudaccount" />', ""),
    "no cleartext entry": mut(NSC, '<domain includeSubdomains="false">vaultwarden.mesh.diegonmarcos.com</domain>', ""),
    "the whole .app TLD opened": mut(NSC, '<domain includeSubdomains="false">vaultwarden.mesh.diegonmarcos.com</domain>', '<domain includeSubdomains="true">app</domain>'),
    "the Android halves are not compiled": mut(APPGRADLE, "libs/fleet-endpoints-android", "libs/fleet-endpoints-androidX"),
}
survivors = []
for name, m in mutations.items():
    if checks(m(real)): print("PASS     mutation caught: " + name)
    else:
        survivors.append(name); print("FAIL     mutation survived: " + name)
if survivors: sys.exit(1)
PY
