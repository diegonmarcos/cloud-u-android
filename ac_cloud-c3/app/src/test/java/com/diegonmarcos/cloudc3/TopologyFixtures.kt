package com.diegonmarcos.cloudc3

import com.diegonmarcos.cloudc3.pages.ContainersModel
import com.diegonmarcos.cloudc3.pages.FleetModel
import com.diegonmarcos.cloudc3.pages.TopologyModel
import com.diegonmarcos.cloudc3.pages.TopologyModel.Deployed
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * The topology tests' FIXTURE fleet: obviously fake names, in tests only. The shape mirrors the real documents
 * (containers-declared.json, fleet_slice.json, infra_slice.json, the `docker ps` answer of GET /health/deployed/{vm}).
 */
object TopologyFixtures {

    private fun entry(name: String, service: String, role: String, host: String, folder: String, pub: String = "", auth: String = "mesh", image: String = "repo/$name:1.2", engine: String = "", port: Int = 8080) =
        """{"name":"$name","kind":"container","service":"$service","role":"$role","dir":"$folder","group":"G","category":"C","description":"d","image":"$image","host":"$host","dns":"$name.mesh.test","port":$port,"public":${pub.isNotBlank()},"protocol":"","db_engine":"$engine","legacy_category":"x","public_url":"$pub","auth":"","auth_type":"$auth"}"""

    val catalog = ContainersModel.parse(
        """{"groups":[{"id":"infra","label":"G","categories":[{"label":"C","containers":["web","web-db","api","orphan"]}]}],"containers":[""" +
            listOf(
                entry("web", "web", "app", "vm-a", "proj1", pub = "w.test", auth = "two_factor"),
                entry("web-db", "web", "db", "vm-a", "proj1", engine = "postgres", port = 5432),
                entry("api", "api", "app", "vm-b", "proj2", port = 5050),
                entry("orphan", "orphan", "app", "local", "proj3"),
            ).joinToString(",") + "]}",
    )!!

    val slice = FleetModel.parse(
        """{"providers":[
            {"id":"pv","label":"Provider One","vms":["id-a","id-b"]},
            {"id":"cloud","label":"Cloud","self_hosted":true,"vms":["id-a","id-b"]},
            {"id":"empty","label":"Empty Vendor","vms":[]}],
          "vms":[
            {"id":"id-a","alias":"vm-a","provider":"pv","region":"r1","wg_ip":"10.0.0.2"},
            {"id":"id-b","alias":"vm-b","provider":"pv","region":"r2","wg_ip":"10.0.0.3"}]}""",
    )

    val infra = TopologyModel.parseInfra(Json.parseToJsonElement(
        """{"hosts":[{"host":"w.test","service":"web","vm":"id-a","auth":"two_factor","wg_only":false}],
          "wireguard":{"wg0":{"subnet":"10.0.0.0/24","hub":"id-a","peers":[
              {"name":"vm-a","wg_ip":"10.0.0.2","role":"hub"},{"name":"vm-b","wg_ip":"10.0.0.3","role":"spoke"},{"name":"phone","wg_ip":"10.0.0.9","role":"client"}]}},
          "firewalls":{"vms":{"vm-a":[{"port":443,"proto":"tcp","source":"0.0.0.0/0","desc":"HTTPS ingress"}]}},
          "dns":{"primary":"10.0.0.1","entries":[{"name":"web.mesh.test","type":"A","value":"10.0.0.2","service":"web"}]}}""",
    ) as JsonObject)

    val ps = """[{"vm":"id-a","containers":[
        {"name":"web","status":"Up 3 hours (healthy)","image":"repo/web:1.3"},
        {"name":"stray","status":"Up 1 hour","image":"x"}]}]"""

    val live = TopologyModel.Live(
        deployed = mapOf("vm-a" to TopologyModel.parseDeployed("id-a", ps), "vm-b" to Deployed.Blind("ssh timeout")),
        disks = mapOf("id-a" to FleetModel.DiskUsage("45G", "34G", "77%")),
        runs = listOf(
            TopologyModel.Run("Ship ok", "completed", "success", "u", 1_700_000_000_000),
            TopologyModel.Run("Ship bad", "completed", "failure", "u", 1_700_000_100_000),
        ),
    )
    val decl = TopologyModel.Decl(emptyList(), TopologyModel.ReleaseSource("o", "r", "Ship"))
    val inputs = TopologyModel.Inputs(catalog, slice, infra, live, decl)
}
