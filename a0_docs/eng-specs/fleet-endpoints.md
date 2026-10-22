# Fleet endpoints: every service mesh first, then public + bearer (#1093)

Extends [mail-endpoints.md](mail-endpoints.md) (#1090) from Cloud Mail's JMAP accounts to every fleet service Cloud
Account talks to or shows, and moves the fallback into ONE component shared by Cloud Mail and Cloud Account.

## One component, no copy

`libs:fleet-endpoints` (ab_cloud-libs-shared/libs/fleet-endpoints, plain JVM Kotlin, classified `pinned` in
1_cicd/src/data/lib-classes.json) holds everything that decides a route:

- `EndpointRouter` + `EndpointRouteInterceptor`: per endpoint pair, `mesh_first` probes the mesh with a 1.2 s TCP connect,
  uses it when it answers and falls back to the public URL; `mesh_only` / `public_only` have one route. The verdict is
  cached per endpoint and dropped on `onNetworkChanged()` or after 10 minutes. Only a failure that proves the request
  never arrived (refused, unresolvable, TLS handshake, cleartext not permitted, connect timeout) moves to the other
  route, so a request is never sent twice.
- `FleetBearers` + `FleetBearerInterceptor`: `X-Fleet-Authorization` goes on the EXACT public https origin of a registered
  endpoint and is removed on its mesh host; any other host (GitHub, a plain IMAP/JMAP provider, a sibling subdomain) never
  receives it. `FleetBearers.source` lets Cloud Account read the vault's `fleet.bearer` per request instead of keeping a copy.
- `FleetHttp` (a blocking exchange over the same interceptors, for callers without OkHttp) and `EndpointChecker` (is each of
  the two endpoints reachable, asked one at a time, for Connect > Status).

Cloud Mail links it from `core:jmap` (`api`); Cloud Account from `libs:account` (`api`). The Android halves are shared too:
`NetworkChangeWatch` (libs:fleetconfig-model) is the one "network changed" signal, and `FleetEndpointsContract`
(libs:fleetconfig-model) is the declaration parser and the wire both ends share; `MailEndpointsContract` delegates its
declaration, policies and mask to it.

## Declaration, once

`ac_cloud-account/build.json::fleet_endpoints.services[]` (was `mail_endpoints`): `id`, `kind`, `source` (the service's
fleet declaration in cloud-u-containers), `mesh_dns` / `mesh_port` / `mesh_scheme` (`containers.app.dns` / `.port` /
`.protocol`), `public_domain` (`proxy.domain`; omitted for a service with no public route or a WG-only one, which must
then say `mesh_only`), `policy`, `health`. `FleetEndpointsContract.parseDecl` drops a service whose mesh name is not a
`.app` name, whose public URL is not a bare https host, whose policy is unknown or that has no public URL and is not
mesh-only. Declared now: `jmap` (mail), `gitea` (git), `authelia` and `auth-broker` (auth), `cloud-me-api` (data) and
`vaultwarden` (vault). Every http mesh endpoint needs a NAMED cleartext entry in `network_security_config.xml`
(`FleetEndpointsDeclTest`); the whole `.app` TLD is never opened.

## What Cloud Account does with it

1. **Own transport.** `FleetEndpointsRuntime.start` registers every service that has both endpoints on `FleetTransport` at
   process start. `ForgeClient.UrlHttp` sends a request for a registered host through `FleetHttp` (Gitea: mesh first, then
   `git.diegonmarcos.com` with the bearer); any other host keeps its plain connection.
2. **Connect > Status > Endpoints** (`FleetEndpointsPage`): one row per service with the state of both endpoints (mesh up or
   down, public HTTP status or unreachable), the route a request takes (mesh / public / offline), and the bearer masked.
3. **Publication.** `AccountData.Provider` method `fleet_endpoints` (grant `fleet.endpoints`; bearer only with `fleet.bearer`
   as well, and only when some service has a public endpoint) serves the pairs of every service; `mail_endpoints` (grant
   `mail.endpoints`) serves Cloud Mail its per-account view of the `kind: mail` services of the same declaration.

## A service that is a PATH of a shared host

`api.diegonmarcos.com/pub` is `c3-public-api`; the edge strips `/pub` before the service, so the mesh side serves from its
root. A declaration says so with `public_path` (and `mesh_path` when the mesh side has a base of its own) and the router
swaps the base path on the way over and back; the bearer goes only to paths under it. `route_paths` narrows the pair to the
paths a direct mesh call may reach: `/health`, `/ready`, `/analytics` are the service's declared public paths, everything
else under `/pub` (profile connect, the config artifact) is answered only behind the edge gate and the in-app `X-Auth-User`
check, which a mesh call would skip, so it stays on the public URL and is never routed. The router keys such a pair as
`host/path` (`api.diegonmarcos.com/pub`).

## Other callers

- libs:auth reaches the transport through one seam, `AuthHttp.transport` (libs:auth stays free of the dependency, so the
  other apps that link it are unchanged): `ConfigSyncClient.request` (config artifact, vault bundle) and
  `ProviderStatus.probe` (sign-in probes, e.g. `auth.diegonmarcos.com/api/health` goes to `authelia.app`). Cloud Account installs
  it in `FleetEndpointsRuntime.start`.
- libs:account's `ProfileSyncClient` routes through `FleetHttp.exchange` the same way (no redirect followed, as before).

## Limits

- Left on their own connection: the OAuth device-grant POSTs to Google and GitHub, `GitSshVault` (api.github.com) and the
  diagnostics log sink in libs:devtools (a lib every app links, which must not take this dependency).
- `auth-broker`, `cloud-me-api` and `vaultwarden` are shown on Status but not routed: they have no public endpoint to fall
  back to, and stay mesh-only.
- The router keys on the public host and base path: two services on one path cannot take different routes.
- On-phone check pending: the mesh names resolve and answer only on a phone with the tunnel up.
