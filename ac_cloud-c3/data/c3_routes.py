"""The Containers > Table rows (pure functions, no I/O) - imported by gen_infra_slice.py and unit-tested by
test/test_c3_routes.py. Nothing here names a host, a VM or an address: every value is READ from its inputs.

Inputs
  services_public.json / services_private.json   the C3 service estate (identity, category, private DNS, port)
  consolidated.services[<service>]               per-service facts (api.has_web_ui, mcp.has_mcp, proxy wg_only/auth)
  consolidated.vms[<vm>]                         the VM's wg_ip (mesh address) or ip
  caddy build.json caddy_config.edge_auth        the edge auth tiers (a) private (b) public-protected (c) public-open

Auth type of a row (the owner's three, plus the tier the policy has that they did not name):
  mesh    no public route, or a route flagged wg_only: tier (a), answered 403 to anything off the mesh
  open    the WHOLE host is listed in edge_auth.public_open (scope "host"): tier (c), anonymous
  bearer  tier (b) on a machine surface: an MCP, a service without a web UI, the API gateway host, or a route that
          declares auth "bearer". Authorization: Bearer <fleet token>.
  woauth  tier (b) on a web UI: the Authelia web session cookie ("WOAuth").
Tier (b) accepts either credential; the split says which one the route is MEANT for.
"""

AUTH_MESH, AUTH_OPEN, AUTH_BEARER, AUTH_WOAUTH = "mesh", "open", "bearer", "woauth"
API_HOST_LABEL = "api"  # the fleet's API gateway host (api.<domain>/<service>); mcp.* is read from mcp.has_mcp


def open_hosts(edge_auth):
    """Hosts that are anonymous as a whole (public_open entries of scope 'host')."""
    return {e["host"] for e in (edge_auth or {}).get("public_open", []) if e.get("scope") == "host" and e.get("host")}


def split_url(public_url):
    host, _, path = (public_url or "").partition("/")
    return host, ("/" + path) if path else ""


def auth_type(public_url, service, route_auth, wg_only, edge_auth):
    """service = the consolidated services[<name>] dict (or {}); route_auth = the route's declared auth string."""
    if not public_url or wg_only:
        return AUTH_MESH
    host, _ = split_url(public_url)
    if host in open_hosts(edge_auth):
        return AUTH_OPEN
    service = service or {}
    machine = (
        bool((service.get("mcp") or {}).get("has_mcp"))
        or (service.get("api") is not None and (service.get("api") or {}).get("has_web_ui") is False)
        or host.split(".")[0] == API_HOST_LABEL
        or route_auth == "bearer"
    )
    return AUTH_BEARER if machine else AUTH_WOAUTH


def vm_address(vms, vm, port):
    """'<wg_ip or ip>:<port>' for a VM that has an address; the bare address with no port; '' when the VM has none
    (e.g. 'local', 'all'). A VM without a mesh address falls back to its public ip only when that is a real address."""
    v = (vms or {}).get(vm) or {}
    ip = v.get("wg_ip") or v.get("ip") or ""
    if not ip or not ip[0].isdigit():
        return ""
    return "%s:%s" % (ip, port) if port else ip


def build_routes(public, private, services, vms, edge_auth):
    """One row per container: public rows (both URLs) then private rows (private URL only)."""
    rows = []
    for p in public:
        sv = services.get(p.get("service")) or {}
        route = next((c.get("proxy") or {} for c in (sv.get("containers") or {}).values()
                      if (c.get("proxy") or {}).get("domain") and p.get("public_url", "").startswith(c["proxy"]["domain"])), {})
        rows.append({
            "category": p.get("category") or "",
            "container": p.get("name") or "",
            "public_url": p.get("public_url") or "",
            "auth_type": auth_type(p.get("public_url"), sv, route.get("auth") or p.get("auth"), bool(route.get("wg_only")), edge_auth),
            "private_url": p.get("private_dns") or "",
            "vm_name": p.get("vm") or "",
            "vm_addr": vm_address(vms, p.get("vm"), p.get("port")),
        })
    for p in private:
        rows.append({
            "category": p.get("category") or "",
            "container": p.get("name") or "",
            "public_url": "",
            "auth_type": AUTH_MESH,
            "private_url": p.get("private_dns") or "",
            "vm_name": p.get("vm") or "",
            "vm_addr": vm_address(vms, p.get("vm"), p.get("port")),
        })
    return rows
