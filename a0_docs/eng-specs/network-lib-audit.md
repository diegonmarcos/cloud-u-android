# Network lib audit — one `libs:network`, or separate engines plus a thin `libs:network-ui`

Audit only. Nothing moved. Measured on origin/main `96e057a72` (2026-10-09); sizes are
Kotlin/Java lines under `src/` and, for APK weight, the table in `engine-apk-split.md`.

## What each piece is

| piece | what it is | size (src lines / APK) | who compiles it |
|---|---|---|---|
| `libs:firewall` | no-root per-app firewall: local `VpnService` + firestack (Go, `libgojni.so`) engine, rules/prefs, `FirewallDialog` (the controls), `FirewallInfo` (read-only status) | 1.5k lines, 15 files; 22.1 MB lib APK, 19.45 MB of it `libgojni.so` stored | SuperApp only |
| `libs:net` | contract + client half of the WireGuard engine (`AidlBackend`, profiles, tunnel model) | 2.9k lines, 19 files; 2.6 MB | SuperApp, `libs:account`, `libs:net-wg` |
| `libs:net-wg` | the WireGuard runtime engine: `GoBackend$VpnService` + exported `NetBackendService` bound by signature permission | 0.7k lines, 4 files; 19.8 MB (8.5 MB native) | nobody links it; it is its own `Cloud-Lib-Net-Wg.apk` (done in the engine split) |
| `libs:sysdns` | `FleetDnsBridge` + `ResolverProxy`: the one resolver of a process, bridging names to the preset's servers | 1.1k lines, 4 files | SuperApp, Cloud Store, `libs:gh`, `libs:gix`, `libs:rclone` |
| mesh / Apps Mesh | Cloud Mesh = SuperApp `network/mesh/*` + `WireGuardFragment` over `libs:net`; Apps Mesh = `libs:appstore` `AppsMesh.page`, hosted by SuperApp `AppsMeshFragment` and Cloud Store Access | mesh UI in the app module; Apps Mesh inside appstore (9.9k lines, mostly Store GUI) | SuperApp, Cloud Store (appstore) |
| Peer Control | the `kde` page = `libs:kde-connect` (`KdeConnectFragment`, peers, device-admin receiver) | 3.6k lines, 43 files; 9.9 MB | SuperApp only |
| `libs:shizuku-adb-debug-tools` | privileged shell channel (Shizuku/ADB) used to write Private DNS, grant permissions, etc. Not network code; relevant only as the DNS "apply to Android" path | 2.9k lines, 27 files; 11 MB | SuperApp, Cloud Store, Cloud Account, `account`, `updater`, `battery`, `appstore` |

The Configs ▸ Network pages (now Firewall, DNS, Cloud Mesh, Apps Mesh, Peer Control) are
all drawn by the app module or by appstore/kde-connect fragments; none of the engines draw.

## Process and uid constraints (from `engine-apk-split.md`)

- **VPN consent is per package.** Android grants `VpnService.prepare` to one package; the
  user's consent and always-on belong to whichever APK runs the `VpnService`. `net-wg`
  already moved this once (the DNS page asks for consent through the engine's consent
  activity). `libs:firewall` is classified "later, not safe": moving it changes the VPN
  identity the user approved, and there is a single VPN slot, so the firewall and the
  mesh tunnel are mutually exclusive by design (`DnsFragment` already special-cases
  "the firewall holds the one VPN slot").
- **Per-package permissions block the other pieces.** `kde-connect`: device-admin
  enrolment is per package. `shizuku-adb-debug-tools`: the Shizuku grant is per package.
- **The firewall must never capture the fleet's engine APKs** (`FLEET_ENGINES`, derived
  from every `kind=lib` row of `constellation-fleet.json` at build time). A new network
  engine APK is automatically exempt, but only if it keeps a fleet row.
- **Resolver is one per process** (`FleetDnsBridge`), hence `sysdns` is linked into the
  engines (`gh`, `gix`, `rclone`) as well as apps; it must stay tiny and dependency-free.

## Rebuild fan-out (#870) versus cohesion

#870 isolation: a lib APK republishes when the hash of its own directory changes, and every
app that links the module rebuilds. Today the network engines have narrow, different
fan-outs: `firewall` and `kde-connect` rebuild only SuperApp; `net-wg` rebuilds nothing but
itself; `sysdns` touches five consumers but is small and rarely changes; `net` touches
SuperApp and `account`.

A single `libs:network` would join 1.5k + 2.9k + 0.7k + 1.1k + 3.6k lines and a 19 MB native
engine into one hash and one module: a one-line DNS fix would rebuild SuperApp, Cloud Store,
`gh`, `gix`, `rclone`, `account` and republish the 22 MB firewall APK; a firestack bump
would rebuild everything that links `sysdns`. It also drags `libgojni.so` (firewall) into
Cloud Store and the engines that only need the resolver, or forces the module to be split
by flavour, which is the separation we already have. It also breaks the engine split's
direction of travel (engines leave apps; contracts stay thin). Cohesion gain is real but is
a UI/navigation gain, not a code gain: the engines share almost no code (different Go
cores, different uid/consent rules), they share a page group.

## Recommendation (plain words)

**Do not consolidate into one `libs:network`.** Keep `firewall`, `net`, `net-wg`, `sysdns`
and `kde-connect` as separate modules, each with its own fan-out and publish hash. Add a
thin **`libs:network-ui`** (pure Android Views, no engines, depends only on contracts such
as `FirewallInfo`'s data class and `libs:net` client interfaces) that holds the Configs ▸
Network pages: `FirewallFragment`, `DnsFragment`, the Cloud Mesh pages and the shared
section/page chrome. Today those live in the SuperApp app module, so the page group is
cohesive in one place already; `network-ui` is worth creating only when a second host
needs the pages (e.g. Cloud Store Access already hosts Apps Mesh). Until then the current
layout (pages in the SuperApp `network/` package) is the cheapest correct shape.

## Migration plan (only if the owner approves; not done here)

1. Create `libs:network-ui` with no engine dependency; move `FirewallFragment` first
   (it only wraps `FirewallDialog`), keep `FirewallDialog` and `FirewallInfo` in
   `libs:firewall`.
2. Move `DnsFragment` (needs `libs:net` client + `sysdns`), then the mesh pages. Each step
   is a pure file move plus a Gradle line; rebuild-isolation data
   (`1_cicd/src/data/rebuild-isolation.json`) must list the new module and the fan-out
   guard must show SuperApp as the only consumer until a second host adopts it.
3. Keep `libs:core` untouched (it is over its mesh-membership budget); `network-ui` must
   not depend on anything that adds to core.
4. Separately, and not as part of consolidation: the firewall's route to its own engine APK
   is blocked by VPN identity; revisit only with an explicit owner decision on re-consent.
5. Peer Control stays in `libs:kde-connect`; the ADB Shell page (another agent, libs:
   shizuku-adb-debug-tools) is separate from Peer Control and, when it lands, sits after it.
