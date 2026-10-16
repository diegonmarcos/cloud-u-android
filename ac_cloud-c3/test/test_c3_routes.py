"""Unit tests of data/c3_routes.py: the auth-type mapping (edge auth tiers) and the VM address column."""
import json, os, sys, unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "data"))
import c3_routes as r

EDGE = {"public_open": [
    {"host": "auth.example.com", "scope": "host"},
    {"host": "mcp.example.com", "scope": "paths", "paths": ["/register"]},
]}
VMS = {"vm-a": {"wg_ip": "10.0.0.6", "ip": "1.2.3.4"}, "vm-b": {"ip": "5.6.7.8"}, "vm-c": {"ip": "TBD"}, "local": {}}


class AuthType(unittest.TestCase):
    def t(self, url, sv=None, auth=None, wg=False):
        return r.auth_type(url, sv or {}, auth, wg, EDGE)

    def test_no_public_route_is_mesh(self):
        self.assertEqual(self.t(""), "mesh")

    def test_wg_only_route_is_mesh_even_when_public_host(self):
        self.assertEqual(self.t("x.example.com", wg=True), "mesh")

    def test_whole_host_open_is_open(self):
        self.assertEqual(self.t("auth.example.com"), "open")

    def test_path_scoped_open_does_not_open_the_host(self):
        self.assertEqual(self.t("mcp.example.com/tool", {"mcp": {"has_mcp": True}}), "bearer")

    def test_mcp_service_is_bearer(self):
        self.assertEqual(self.t("m.example.com/x", {"mcp": {"has_mcp": True}}), "bearer")

    def test_service_without_web_ui_is_bearer(self):
        self.assertEqual(self.t("x.example.com", {"api": {"has_web_ui": False}}), "bearer")

    def test_api_gateway_host_is_bearer(self):
        self.assertEqual(self.t("api.example.com/svc", {"api": {"has_web_ui": True}}), "bearer")

    def test_declared_bearer_route_is_bearer(self):
        self.assertEqual(self.t("x.example.com", auth="bearer"), "bearer")

    def test_web_ui_is_woauth(self):
        self.assertEqual(self.t("git.example.com", {"api": {"has_web_ui": True}}, "two_factor"), "woauth")

    def test_unknown_service_defaults_to_woauth(self):
        self.assertEqual(self.t("git.example.com"), "woauth")

    def test_wg_only_beats_open_host(self):
        self.assertEqual(self.t("auth.example.com", wg=True), "mesh")


class VmAddress(unittest.TestCase):
    def test_prefers_mesh_address_with_port(self):
        self.assertEqual(r.vm_address(VMS, "vm-a", 8010), "10.0.0.6:8010")

    def test_falls_back_to_ip(self):
        self.assertEqual(r.vm_address(VMS, "vm-b", 80), "5.6.7.8:80")

    def test_no_port_is_bare_address(self):
        self.assertEqual(r.vm_address(VMS, "vm-a", None), "10.0.0.6")

    def test_placeholder_or_missing_vm_is_blank(self):
        self.assertEqual(r.vm_address(VMS, "vm-c", 1), "")
        self.assertEqual(r.vm_address(VMS, "local", 1), "")
        self.assertEqual(r.vm_address(VMS, "nope", 1), "")


class BuildRoutes(unittest.TestCase):
    def test_public_then_private_rows_with_all_columns(self):
        pub = [{"name": "gitea", "service": "gitea", "vm": "vm-a", "public_url": "git.example.com",
                "auth": "two_factor", "private_dns": "gitea.app:3002", "port": 3002, "category": "data"}]
        prv = [{"name": "db", "service": "db", "vm": "vm-b", "private_dns": "db.app:5432", "port": 5432, "category": "data"}]
        sv = {"gitea": {"api": {"has_web_ui": True}, "containers": {"app": {"proxy": {"domain": "git.example.com"}}}}}
        rows = r.build_routes(pub, prv, sv, VMS, EDGE)
        self.assertEqual(rows[0], {"category": "data", "container": "gitea", "public_url": "git.example.com",
                                   "auth_type": "woauth", "private_url": "gitea.app:3002", "vm_name": "vm-a",
                                   "vm_addr": "10.0.0.6:3002"})
        self.assertEqual(rows[1]["auth_type"], "mesh")
        self.assertEqual(rows[1]["public_url"], "")
        self.assertEqual(rows[1]["vm_addr"], "5.6.7.8:5432")

    def test_wg_only_proxy_flag_read_from_service(self):
        pub = [{"name": "lt", "service": "lt", "vm": "vm-a", "public_url": "lt.example.com", "port": 1}]
        sv = {"lt": {"containers": {"app": {"proxy": {"domain": "lt.example.com", "wg_only": True}}}}}
        self.assertEqual(r.build_routes(pub, [], sv, VMS, EDGE)[0]["auth_type"], "mesh")


class ShippedSlice(unittest.TestCase):
    """The committed data/infra_slice.json carries routes in the shape the Table page parses."""

    def test_routes_present_and_well_formed(self):
        d = json.load(open(os.path.join(HERE, "..", "data", "infra_slice.json")))
        rows = d["routes"]
        self.assertGreater(len(rows), 50)
        keys = {"category", "container", "public_url", "auth_type", "private_url", "vm_name", "vm_addr"}
        for row in rows:
            self.assertEqual(set(row), keys)
            self.assertIn(row["auth_type"], {"mesh", "open", "bearer", "woauth"})
            if not row["public_url"]:
                self.assertEqual(row["auth_type"], "mesh")
        self.assertTrue({"bearer", "woauth", "mesh"} <= {x["auth_type"] for x in rows})


if __name__ == "__main__":
    unittest.main()
