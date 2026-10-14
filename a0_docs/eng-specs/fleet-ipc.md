# Fleet IPC: app to app on one phone is Android IPC

Status: shipped — the layer, the migrated call sites, the guard, the IPC page and (phase 4) the SuperApp mesh gateway.

## The rule

- Two fleet apps on the same phone talk over Android IPC: a content provider call, a bound service (AIDL), or an intent.
- The loopback HTTP debug APIs are for callers off the phone (a PC, a server, the MCP, agents, CI). After phase 4 they reach every app through one door, the SuperApp gateway.
- `1_cicd/src/scripts/cloud-android-ipc-mesh.py check` enforces the rule on every push (mesh-membership-guard).

## Audit (2026-10-14)

| # | Caller | Callee | Purpose | Data | Frequency | Now |
|---|---|---|---|---|---|---|
| 1 | Cloud Store, SuperApp, Cloud Account (libs:appstore `StoreMesh.probeEach`, Apps Mesh) | every member's AppDebugServer, 127.0.0.1:<debug port> | liveness (`system/ping`), each member's view of the mesh (`fleet/peers`) | `pong <pkg>`, a small JSON list | page open, Re-probe, Wake, Wake all; about 50 members x 2 calls | IPC on Wake and a row's re-probe. The page-open look still pings the assigned port of a running pre-IPC build, which is the version-skew rung |
| 2 | the same (Apps Mesh Docs, Details, App API Endpoints, `/api/fleet/endpoints`) | each member | `docs`, `system/info` | JSON catalogue, 2 to 10 KB | per tap or export | IPC (`StoreMesh.ask`), HTTP only for a member with no IPC door |
| 3 | SuperApp DNS page and `/api/net/dns/overview` (`DnsOverview.members`) | every mesh peer | `net/dns`, `sysdns/state`, `diagnostics/crashes` | small JSON, crash text | per DNS page open or overview call | IPC |
| 4 | Terminals (cld.termux, cld.termux.nix) and Cloud Drive Manage > Apps (`SuperappBridgeChannel`) | SuperApp DevControlServer 127.0.0.1:38080 `/api/adb/exec` | a uid-2000 shell through the SuperApp's privileged channel | a command in, stdout out | per privileged command, often in bursts | IPC op `adb/exec` (SuperApp `AdbBridgeApi`). The loopback route is kept for an older SuperApp |

The scan found no app code that points at the phone's own mesh address. The MCP reaches the phone over ssh, then curls loopback, so it counts as an external caller.

These loopback uses are not app to app, and they stay:

- `LocalShellChannel`: the app's own app_process shell server.
- The sysdns bridge: in-process DNS for shells.
- Gallery NetFs: an in-process media server.
- Cloud Nav's DevControlServer and MyTerminal's DevApiServer: opt-in dev servers.
- Cloud Code's Acode plugins.

Each one is listed in `1_cicd/src/data/ipc-http-allow.json` with its reason.

**Existing IPC, reused rather than replaced:**

- `CONSTELLATION_DATA` (signature, libs:core)
- every member's `<pkg>.fleet` FleetTokenProvider, which is now also the IPC door
- the `MESH_MEMBER` receiver and its `<queries>` entry
- `<pkg>.fleetconfig` and `<pkg>.fleetsetup`
- SuperApp's `fleetalerts` and `fleetbearer` providers
- Cloud Account's `accountdata` provider
- the engine AIDL services (lib-cal `CalBackendService`, news, gh, feed, …)
- Drive's `agents.screenshots` provider (its own signature permission)
- `extapp:` intents

The registry lists all of them.

## The layer (libs:devtools, in every member through libs:core)

- **Door.** `content://<pkg>.fleet` (`FleetTokenProvider`) is exported behind `CONSTELLATION_DATA`. `call("fleet.ipc.v1", <op>, extras = query)` goes to `FleetIpc.serve`.
  - `serve` re-checks the caller (uid or `checkCallingPermission`), because the framework does not permission-check `call()`.
  - It then answers from `AppDebugServer.dispatch`, the same route table the loopback API serves. A route is written once (`AppDebugServer.route(group, ops)`) and reached both ways.
- **Client.** `FleetIpc.call(ctx, pkg, op, query, timeoutMs, httpFallback)` returns a `Reply(status, body, type, via)`. Typed helpers: `ping`, `info`, `docs`, `peers`, `dns`, `crashes`.
  - Its own package is answered in-process.
  - Every call has a timeout (15 s by default, since a cold start measured about 10 s). A late call gets 504, with no retry over HTTP.
  - A refusal (403) is final.
  - Bodies over 128 KB travel through a pipe, not the shared 1 MB binder buffer.
- **Waking.** A provider call starts the callee's process. There is no activity launch, so the background-start limits do not apply.
- **Visibility.** devtools' `<queries>` entry for the `MESH_MEMBER` action makes every member visible on Android 11 and later.
- **Version skew.** A member built before this layer answers `call()` with nothing. The client then asks its old loopback port (`FleetIpcWire.httpGet`, with the fleet token). This rung is the only sanctioned loopback client, and the guard holds it to its allowed files.
- **Pure half.** `FleetIpcWire` holds the gate, the ladder, the timeout and the HTTP rung, with no `android.*`, so `FleetIpcWireTest` runs on a plain JVM.

**Budgets.** devtools grows by about 200 lines (its lib-classes ceiling goes from 1600 to 1860, with the reason in `lib-classes.json`). Phase 4 removes the per-app listener and lowers it again. No new module, so no app needs its module map changed. Rebuild isolation is unchanged: devtools is refresh-only (`lib-primary-consumers.json`), so `fleet-refresh` rolls it out to the fleet.

## Guard (`cloud-android-ipc-mesh.py`, in mesh-membership-guard)

- **I1:** the registry `aa_cloud-superapp/data/ipc-mesh.json` is what `gen` writes. It is generated from the declarations: the roster, every merged AndroidManifest (lib closure), the devtools dispatch table, each member's `AppDebugServer.route` registrations, AIDL interfaces and static callers.
- **I2:** the door is exported, signature-guarded and re-checked, and members stay visible.
- **I3:** no loopback HTTP client appears outside the allow-list. A new file, or one more client in a listed file, is red. The list only shrinks.
- The tester `1_cicd/src/scripts/test/ipc-mesh-guard.test.sh` plants each break and requires red.

## The IPC page ("IPC Mesh")

- One shared composable, `libs:appstore IpcMeshPage`, drawn by Cloud Store > Access > IPC (after Apps Mesh) and SuperApp > Configs > Network > IPC (after Apps Mesh, through `IpcMeshFragment`).
- It reads the baked registry (`IPC_MESH_B64`). Per member it shows: each endpoint (an op on the door, a provider authority, a service with its AIDL methods, intent actions, deep links), the permission and its level, the callers, and the live status.
- **Live status:**
  - installed and version come from the PackageManager;
  - "resolves" comes from a PackageManager query and starts nothing;
  - "responds" comes from Ping doors, one read-only `system/ping` per member, which does start stopped members.
- **Tests:**
  - SuperApp `IpcMeshPageTest`: every declared endpoint is baked and drawn, and the page is reachable from Configs.
  - `test-ipc-mesh-page.sh` (SuperApp) and `test-store-ipc-page.sh` (Store).

## Phase 4: one mesh gateway per phone (shipped)

- **One listener.** The SuperApp's DevControlServer is the only fleet listener: one port (38080), one fleet token, bound to loopback (the mesh reaches it over ssh and curl, as today).
- **Routing.** `/app/<id|pkg>/api/<op>` is forwarded to the target app over `FleetIpc`, which wakes the app and is signature-checked. `/api/fleet/apps` lists what is behind the door.
- **Retirement.** `AppDebugServer` no longer binds a port in any app.
- **Version skew.** The gateway falls back to an old build's own port, from the legacy table in `debug-ports.json`, while phones still run old builds.
- **Registry and guards.** `debug-ports.json` declares the gateway. The per-package ports remain as the version-skew table. The debug-ports guard holds "no per-app listener".
- **MCP.** cloud-superapp-mcp asks the gateway first and sweeps the old ports only when the gateway does not answer.
- **Trade-off.** Without the SuperApp installed, remote access to the other apps stops. On-phone IPC between apps keeps working, because it never went through the SuperApp.
