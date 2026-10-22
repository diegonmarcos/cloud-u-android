# Mail endpoints: Cloud Account publishes, Cloud Mail routes (#1090)

> #1093: the router, the bearer interceptors, the declaration parser and the network-change signal described below now
> live once, in `libs:fleet-endpoints` and `libs:fleetconfig-model`, shared with Cloud Account; the declaration is
> `ac_cloud-account/build.json::fleet_endpoints` (the `kind: mail` services). See [fleet-endpoints.md](fleet-endpoints.md).

## What exists

- Cloud Account is the source of truth for the fleet mail accounts. It already syncs the vault's `mail` section
  (accounts, passwords, JMAP host, IMAP/SMTP SNI names) to Cloud Mail through the fleet setup contract
  (`sterna_account.vault_mail`, consumed once by `VaultMailImport`).
- That section names ONE host per service (the public one). It carries neither the mesh address nor the bearer, and
  Cloud Mail sent the fleet token only when the owner typed it under Account > Authentication, per domain.
- Cloud Mail had one endpoint per account. A fleet account on a phone with the VPN on resolved the public host to the
  mesh or not, depending on DNS, and answered 401 from the edge gate when no token was set (#1071).

## What this adds

1. **Declaration, once.** `ac_cloud-account/build.json::fleet_endpoints.services[]` (`kind: mail`; was `mail_endpoints`): `mesh_dns` / `mesh_port` /
   `mesh_scheme` (the mail service's `containers.app.dns` / `.port` / `.protocol` in cloud-u-containers) and
   `public_domain` (`proxy.domain`). `MailEndpointsContract.parseDecl` drops a service whose mesh name is not a `.app`
   name, whose public URL is not https, or whose policy is unknown.
2. **Publication.** `AccountData.Provider` method `mail_endpoints`
   (`libs:account` `MailEndpointsPublisher`). Per declared mail account whose JMAP host is a declared service's public
   domain: `{email, service, mesh, public, policy}`. The bearer (the vault's `fleet.bearer`) rides along only for a
   package granted both `mail.endpoints` and `fleet.bearer` (Secrets). Cloud Mail's package is seeded with both
   (`AccountVault` seed version 2: only the new default is added on upgrade, a revoked grant stays revoked).
3. **Storage in Mail.** `FleetEndpointStore`: one sealed blob per account (`ep_<id>` in the existing `gateway_auth`
   store, declared in fleet-config.json): mesh, public, policy (+ whether the owner pinned it), bearer.
4. **Transport.** `EndpointRouteInterceptor` (application interceptor) rewrites a request for a registered fleet
   host onto the planned route: `mesh_first` probes the mesh with a TCP connect (1.2 s), tries it, and falls back to
   the public URL; `mesh_only` / `public_only` have one route. The verdict is cached per endpoint and dropped on a
   network change (`EndpointNetworkWatch`) or after 10 minutes. Only a failure that proves the request never arrived
   (refused, unresolvable, TLS handshake, connect timeout) moves to the other route, so a mail is never sent twice.
5. **Bearer.** `FleetBearerInterceptor` (network interceptor, per hop) adds `X-Fleet-Authorization` on the EXACT public
   https origin of a registered endpoint and strips it on the mesh host. A host that is not registered (every plain
   IMAP/JMAP provider, every sibling subdomain) never receives it. A redirect off the public host does not carry it.
6. **UI.** Configs > Accounts > account > Endpoints: mesh, public, bearer masked, endpoint policy, Sync, and the route
   chip (Mesh / Public / Checking / Offline), repeated on the account row. Accounts on one endpoint share one policy.

## Limits

- IMAP/SMTP stay on their single endpoint; only the JMAP HTTP transport is routed.
- The router keys on the host: two accounts on the same endpoint cannot be on different routes.
- The mesh URL must be an origin (no path). Its TLS certificate must be trusted by the phone, or the mesh path fails
  its handshake and the account runs on public.
