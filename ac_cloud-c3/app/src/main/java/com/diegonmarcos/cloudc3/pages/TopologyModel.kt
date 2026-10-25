package com.diegonmarcos.cloudc3.pages

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * The Ops section's five topology views as pure functions: declared fleet data and live answers in, a tree of [Node]s out.
 * The pages draw that tree (and a flat list of it); the JVM suite runs the same code against fixture data.
 *
 * WHAT IS REAL. Declared: the containers document ([ContainersModel.Catalog]), the fleet slice (providers, VMs, mesh IPs) and the
 * infra slice (Caddy hosts, WireGuard subnets, firewall rules, DNS). Live: c3-infra-api GET /health/deployed/{vm} (a `docker ps`:
 * name, status, image), GET /health/tier3/{vm} (disk) and the GitHub Actions runs of the repository ui.topology.release names.
 * WHAT IS NOT. Anything no endpoint provides (per-edge p99 and 5xx, CPU/RAM/IOPS/OOM, flow counters, traffic split, the commit a
 * deploy came from) is NEVER invented: ui.topology.views[].missing lists it and the page prints that list under the view.
 *
 * The five views share one grouping idea (a tree keyed differently) and one entity vocabulary, [Keys]: a node that stands for a
 * service, a VM or a container carries that key, which is what lets a view be opened on the same entity from another view.
 */
object TopologyModel {

    const val SERVICE = "service"
    const val INFRASTRUCTURE = "infrastructure"
    const val ORCHESTRATION = "orchestration"
    const val NETWORK = "network"
    const val RELEASE = "release"
    val ALL = listOf(SERVICE, INFRASTRUCTURE, ORCHESTRATION, NETWORK, RELEASE)

    /** The entity keys a node carries, so another view can be opened on the same service, VM or container. */
    object Keys {
        fun service(name: String) = "service:$name"
        fun vm(alias: String) = "vm:$alias"
        fun container(name: String) = "container:$name"
    }

    // ── the declaration (build.json::ui.topology) ───────────────────────────

    data class ViewDecl(val id: String, val grouping: String, val metric: String, val question: String, val missing: List<String>)
    data class ReleaseSource(val owner: String, val repo: String, val workflow: String)
    data class Decl(val views: List<ViewDecl>, val release: ReleaseSource?) {
        fun view(id: String): ViewDecl? = views.firstOrNull { it.id == id }
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    private fun JsonObject.obj(k: String): JsonObject? = this[k] as? JsonObject
    private fun JsonObject.arr(k: String): List<JsonObject> = (this[k] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

    fun parseDecl(text: String): Decl {
        val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return Decl(emptyList(), null)
        return Decl(
            views = root.arr("views").map { o ->
                ViewDecl(
                    o.str("id").orEmpty(), o.str("grouping").orEmpty(), o.str("metric").orEmpty(), o.str("question").orEmpty(),
                    (o["missing"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
                )
            },
            release = root.obj("release")?.let { r ->
                ReleaseSource(r.str("owner").orEmpty(), r.str("repo").orEmpty(), r.str("workflow").orEmpty())
            },
        )
    }

    // ── the tree ────────────────────────────────────────────────────────────

    /** Worst first: the order a group sorts its children and a parent takes its colour from. */
    enum class Health { BAD, WARN, UNKNOWN, OK, NONE }

    /**
     * One node. [metric] is the dominant metric of its view, as text; [keys] are the entities the node (or what it stands for)
     * is; [pivot] is the one entity worth opening in another view from here (null for a group with no entity of its own).
     */
    data class Node(
        val id: String,
        val label: String,
        val kind: String,
        val metric: String = "",
        val health: Health = Health.NONE,
        val keys: Set<String> = emptySet(),
        val pivot: String? = null,
        val children: List<Node> = emptyList(),
    )

    /** A live `docker ps` row. */
    data class Box(val name: String, val status: String, val image: String)

    sealed class Deployed {
        data class Seen(val boxes: List<Box>) : Deployed()
        data class Blind(val why: String) : Deployed()
    }

    data class Run(val title: String, val status: String, val conclusion: String, val url: String, val tsMillis: Long)

    /** Everything live a view may show. Empty maps mean "not read", which the views say instead of drawing zeroes. */
    data class Live(
        /** By VM alias (what the containers document names as host). */
        val deployed: Map<String, Deployed> = emptyMap(),
        /** By VM id and alias. */
        val disks: Map<String, FleetModel.DiskUsage> = emptyMap(),
        val runs: List<Run> = emptyList(),
        /** Why the runs are absent, or null. */
        val runsNote: String? = null,
    )

    // ── the infra slice (data/infra_slice.json) ─────────────────────────────

    data class Route(val host: String, val service: String, val vm: String, val auth: String, val wgOnly: Boolean)
    data class Peer(val name: String, val ip: String, val role: String)
    data class Subnet(val name: String, val cidr: String, val hub: String, val peers: List<Peer>)
    data class Rule(val port: Int?, val proto: String, val source: String, val desc: String)
    data class DnsEntry(val name: String, val value: String, val service: String)
    data class Infra(
        val routes: List<Route>, val subnets: List<Subnet>, val firewall: Map<String, List<Rule>>,
        val dnsPrimary: String, val dns: List<DnsEntry>,
    )

    val NO_INFRA = Infra(emptyList(), emptyList(), emptyMap(), "", emptyList())

    fun parseInfra(root: JsonObject?): Infra {
        root ?: return NO_INFRA
        val wg = root.obj("wireguard")
        val subnets = wg?.entries.orEmpty().mapNotNull { (name, v) ->
            val o = v as? JsonObject ?: return@mapNotNull null
            Subnet(name, o.str("subnet").orEmpty(), o.str("hub").orEmpty(),
                o.arr("peers").map { p -> Peer(p.str("name").orEmpty(), p.str("wg_ip").orEmpty(), p.str("role").orEmpty()) })
        }
        val fw = root.obj("firewalls")?.obj("vms")?.entries.orEmpty().associate { (vm, v) ->
            vm to (v as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.map { r ->
                Rule((r["port"] as? JsonPrimitive)?.intOrNull, r.str("proto").orEmpty(), r.str("source").orEmpty(), r.str("desc").orEmpty())
            }
        }
        val dns = root.obj("dns")
        return Infra(
            routes = root.arr("hosts").map { h ->
                Route(h.str("host").orEmpty(), h.str("service").orEmpty(), h.str("vm").orEmpty(), h.str("auth").orEmpty(),
                    (h["wg_only"] as? JsonPrimitive)?.contentOrNull == "true")
            },
            subnets = subnets, firewall = fw, dnsPrimary = dns?.str("primary").orEmpty(),
            dns = dns?.arr("entries").orEmpty().map { DnsEntry(it.str("name").orEmpty(), it.str("value").orEmpty(), it.str("service").orEmpty()) },
        )
    }

    /** Everything a view is built from. */
    data class Inputs(
        val catalog: ContainersModel.Catalog,
        val slice: FleetModel.Slice,
        val infra: Infra,
        val live: Live = Live(),
        val decl: Decl = Decl(emptyList(), null),
    )

    fun build(view: String, i: Inputs): List<Node> = when (view) {
        SERVICE -> service(i)
        INFRASTRUCTURE -> infrastructure(i)
        ORCHESTRATION -> orchestration(i)
        NETWORK -> network(i)
        RELEASE -> release(i)
        else -> emptyList()
    }

    // ── live parsing ────────────────────────────────────────────────────────

    /** One GET /health/deployed/{vm} body: `[{vm, containers:[{name,status,image}], error?}]`. */
    fun parseDeployed(vmId: String, body: String): Deployed {
        val arr = runCatching { json.parseToJsonElement(body) as JsonArray }.getOrElse { return Deployed.Blind("unparseable answer: ${body.take(80)}") }
        val entry = arr.mapNotNull { it as? JsonObject }.firstOrNull { it.str("vm") == vmId }
            ?: return Deployed.Blind("the API returned no entry for $vmId")
        entry.str("error")?.let { return Deployed.Blind(it.trim().ifBlank { "error with no message" }) }
        return Deployed.Seen(entry.arr("containers").map { Box(it.str("name").orEmpty(), it.str("status").orEmpty(), it.str("image").orEmpty()) })
    }

    // ── shared builders ─────────────────────────────────────────────────────

    private fun vmOf(i: Inputs, alias: String): FleetModel.Vm? = i.slice.vms.firstOrNull { it.alias == alias }

    private fun boxOf(i: Inputs, e: ContainersModel.Entry): Pair<Deployed?, Box?> {
        val d = i.live.deployed[e.host]
        return d to (d as? Deployed.Seen)?.boxes?.firstOrNull { it.name == e.name }
    }

    /** Health and one-line state of a declared container against what `docker ps` shows. */
    fun containerState(i: Inputs, e: ContainersModel.Entry): Pair<Health, String> {
        val (d, box) = boxOf(i, e)
        return when {
            vmOf(i, e.host) == null -> Health.UNKNOWN to "not on a mesh VM (${e.host.ifBlank { "no host" }})"
            d == null -> Health.UNKNOWN to "not read"
            d is Deployed.Blind -> Health.UNKNOWN to "VM unreadable: ${d.why.take(60)}"
            box == null -> Health.BAD to "declared, not running"
            box.status.startsWith("Up") && box.status.contains("unhealthy") -> Health.BAD to box.status
            box.status.startsWith("Up") -> Health.OK to box.status
            box.status.startsWith("Restarting") -> Health.BAD to box.status
            else -> Health.WARN to box.status
        }
    }

    private fun worst(hs: List<Health>): Health = hs.filter { it != Health.NONE }.minOrNull() ?: Health.NONE

    private fun leaves(n: Node): List<Node> = if (n.children.isEmpty()) listOf(n) else n.children.flatMap { leaves(it) }

    private fun upText(nodes: List<Node>): String {
        val l = nodes.flatMap { leaves(it) }.filter { it.health != Health.NONE }
        return if (l.isEmpty()) "" else "${l.count { it.health == Health.OK }}/${l.size} up"
    }

    /** Children sorted worst first, then by label; the parent takes the worst health and an "n/m up" count. */
    private fun group(id: String, label: String, kind: String, children: List<Node>, keys: Set<String> = emptySet(), pivot: String? = null, extra: String = ""): Node {
        val sorted = children.sortedWith(compareBy<Node>({ it.health }, { it.label }))
        return Node(id, label, kind, listOf(extra, upText(sorted)).filter { it.isNotBlank() }.joinToString(" - "),
            worst(sorted.map { it.health }), keys, pivot, sorted)
    }

    private fun containerNode(i: Inputs, prefix: String, e: ContainersModel.Entry, label: String = e.name, state: String? = null): Node {
        val (h, text) = containerState(i, e)
        return Node("$prefix/c:${e.name}", label, "container", state ?: text, h,
            setOf(Keys.container(e.name), Keys.service(e.service), Keys.vm(e.host)), Keys.container(e.name))
    }

    private fun onVm(i: Inputs) = i.catalog.entries.filter { vmOf(i, it.host) != null }
    private fun offVm(i: Inputs) = i.catalog.entries.filter { vmOf(i, it.host) == null }

    // ── a) Service (logical flow) ───────────────────────────────────────────

    /** Gateway -> service -> its containers and the DB / queue it depends on. Edge: the auth tier the gateway applies. */
    private fun service(i: Inputs): List<Node> {
        val services = i.catalog.entries.groupBy { it.service }.map { (svc, es) ->
            val deps = es.filter { it.role != "app" }
            val apps = es - deps.toSet()
            val tier = es.firstOrNull { it.publicUrl.isNotBlank() }?.authType.orEmpty()
            val kids = apps.map { containerNode(i, "service/$svc", it) } +
                deps.map { containerNode(i, "service/$svc", it, "${it.name} (${it.dbEngine.ifBlank { it.role }})").copy(kind = "dependency") }
            val g = group("service/$svc", svc, "service", kids, setOf(Keys.service(svc)) + es.map { Keys.vm(it.host) }, Keys.service(svc),
                extra = if (tier.isBlank()) "mesh only" else "auth $tier")
            g.copy(keys = g.keys + g.children.flatMap { it.keys })
        }
        val (pub, priv) = services.partition { s -> i.catalog.entries.any { it.service == s.label && it.publicUrl.isNotBlank() } }
        return listOf(
            group("gateway/public", "Gateway: public edge", "gateway", pub),
            group("gateway/mesh", "Mesh only: behind the gateway, no public route", "gateway", priv),
        ).filter { it.children.isNotEmpty() }
    }

    // ── b) Infrastructure (compute) ─────────────────────────────────────────

    private fun diskText(i: Inputs, vm: FleetModel.Vm): String =
        (i.live.disks[vm.id] ?: i.live.disks[vm.alias])?.let { "disk ${it.percent}" } ?: "disk not read"

    /** Provider -> VM -> container. Node metric: disk usage and containers up; the missing CPU/RAM/IOPS/OOM is stated by the page. */
    private fun infrastructure(i: Inputs): List<Node> {
        val byHost = onVm(i).groupBy { it.host }
        val providers = i.slice.providers.filter { !it.selfHosted && it.vms.isNotEmpty() }.map { p ->
            val vms = p.vms.mapNotNull { id -> i.slice.vms.firstOrNull { it.id == id } }.map { vm ->
                val prefix = "infra/${p.id}/${vm.alias}"
                group(prefix, vm.alias.ifBlank { vm.id }, "vm", byHost[vm.alias].orEmpty().map { containerNode(i, prefix, it) },
                    setOf(Keys.vm(vm.alias)), Keys.vm(vm.alias), extra = listOf(vm.region, diskText(i, vm)).filterNotNull().joinToString(", "))
                    .let { n -> n.copy(keys = n.keys + n.children.flatMap { it.keys }) }
            }
            group("infra/${p.id}", p.label, "provider", vms)
        }
        val off = offVm(i)
        return providers + listOfNotNull(
            if (off.isEmpty()) null else group("infra/none", "Not on a mesh VM", "provider", off.map { containerNode(i, "infra/none", it) }),
        )
    }

    // ── c) Orchestration (GitOps / declared vs deployed) ────────────────────

    /** Fleet -> VM (as the cluster) -> compose project -> service -> container, each leaf declared vs actually running. */
    private fun orchestration(i: Inputs): List<Node> {
        val fleet = i.slice.providers.firstOrNull { it.selfHosted }
        val vms = fleet?.vms?.mapNotNull { id -> i.slice.vms.firstOrNull { it.id == id } } ?: i.slice.vms
        val declaredAnywhere = i.catalog.entries.map { it.name }.toSet()
        val vmNodes = vms.map { vm ->
            val here = i.catalog.entries.filter { it.host == vm.alias }
            val projects = here.groupBy { it.folder.ifBlank { "(no compose project)" } }.map { (proj, pes) ->
                val services = pes.groupBy { it.service }.map { (svc, ses) ->
                    val prefix = "orch/${vm.alias}/$proj/$svc"
                    group(prefix, svc, "service", ses.map { e ->
                        val (h, text) = containerState(i, e)
                        containerNode(i, prefix, e, state = when (h) {
                            Health.OK -> "in sync - $text"
                            Health.BAD -> "drift - $text"
                            else -> text
                        })
                    }, setOf(Keys.service(svc)), Keys.service(svc))
                }
                group("orch/${vm.alias}/$proj", proj, "project", services)
            }
            val extra = (i.live.deployed[vm.alias] as? Deployed.Seen)?.boxes.orEmpty().filter { it.name !in declaredAnywhere }
                .map { b -> Node("orch/${vm.alias}/undeclared/${b.name}", b.name, "container", "running, not declared - ${b.status}", Health.WARN, setOf(Keys.container(b.name), Keys.vm(vm.alias)), Keys.container(b.name)) }
            val kids = projects + listOfNotNull(if (extra.isEmpty()) null else group("orch/${vm.alias}/undeclared", "Running but not declared", "project", extra))
            val node = group("orch/${vm.alias}", vm.alias.ifBlank { vm.id }, "vm", kids, setOf(Keys.vm(vm.alias)), Keys.vm(vm.alias))
            node.copy(keys = node.keys + leaves(node).flatMap { it.keys })
        }
        val off = offVm(i)
        val root = group("orch/fleet", fleet?.label ?: "Fleet", "fleet", vmNodes + listOfNotNull(
            if (off.isEmpty()) null else group("orch/none", "Declared, not on a mesh VM", "vm", off.map { containerNode(i, "orch/none", it) }),
        ))
        return listOf(root)
    }

    // ── d) Network & Security ───────────────────────────────────────────────

    /** WireGuard subnet -> VM peer -> Caddy routes (-> container IP:port) and firewall rules; then the mesh DNS. */
    private fun network(i: Inputs): List<Node> {
        val placed = mutableSetOf<String>()
        val subnets = i.infra.subnets.map { s ->
            val peers = s.peers.map { p ->
                val vm = vmOf(i, p.name)
                val head = "${p.ip} (${p.role})"
                if (vm == null || !placed.add(vm.alias)) {
                    Node("net/${s.name}/${p.name}", p.name, "peer", head, Health.NONE, setOf(Keys.vm(p.name)), Keys.vm(p.name))
                } else {
                    val routes = i.infra.routes.filter { it.vm == vm.id }.map { r ->
                        val served = i.catalog.entries.filter { it.service == r.service && it.host == vm.alias }
                        // the route points at the container that is public; a mesh-only service at its app container
                        val boxes = served.filter { it.publicUrl.isNotBlank() }.ifEmpty { served.filter { it.role == "app" } }.ifEmpty { served }
                        Node("net/${s.name}/${p.name}/route/${r.host}", r.host, "route", listOf(r.service, r.auth, if (r.wgOnly) "wg only" else "public").filter { it.isNotBlank() }.joinToString(" - "),
                            Health.NONE, setOf(Keys.service(r.service), Keys.vm(vm.alias)), Keys.service(r.service),
                            boxes.map { e -> Node("net/${s.name}/${p.name}/route/${r.host}/${e.name}", e.name, "container", "${p.ip}:${e.port ?: "?"}", Health.NONE,
                                setOf(Keys.container(e.name), Keys.service(e.service), Keys.vm(vm.alias)), Keys.container(e.name)) })
                    }
                    val rules = i.infra.firewall[vm.alias].orEmpty().mapIndexed { n, r ->
                        Node("net/${s.name}/${p.name}/fw/$n", "${r.port ?: "?"}/${r.proto} from ${r.source}", "rule", r.desc, Health.NONE, setOf(Keys.vm(vm.alias)))
                    }
                    val kids = listOfNotNull(
                        if (routes.isEmpty()) null else Node("net/${s.name}/${p.name}/routes", "Caddy routes (${routes.size})", "group", "", Health.NONE, emptySet(), null, routes),
                        if (rules.isEmpty()) null else Node("net/${s.name}/${p.name}/fw", "Firewall (${rules.size} rules)", "group", "", Health.NONE, emptySet(), null, rules),
                    )
                    Node("net/${s.name}/${p.name}", p.name, "peer", head, Health.NONE, setOf(Keys.vm(vm.alias)) + kids.flatMap { k -> k.children.flatMap { it.keys } }, Keys.vm(vm.alias), kids)
                }
            }
            Node("net/${s.name}", "${s.name} ${s.cidr}", "subnet", "hub ${s.hub} - ${peers.size} peers", Health.NONE, emptySet(), null, peers)
        }
        val dns = if (i.infra.dns.isEmpty()) emptyList() else listOf(
            Node("net/dns", "DNS (mesh namespace, primary ${i.infra.dnsPrimary})", "subnet", "${i.infra.dns.size} records", Health.NONE, emptySet(), null,
                i.infra.dns.map { Node("net/dns/${it.name}", it.name, "route", "${it.value} - ${it.service}", Health.NONE, setOf(Keys.service(it.service)), Keys.service(it.service)) }),
        )
        return subnets + dns
    }

    // ── e) Release (CI/CD) ──────────────────────────────────────────────────

    /** The tag of an image reference; a digest-only pin reads as its first 8 digest characters, no tag as "latest". */
    fun imageTag(image: String): String {
        val noDigest = image.substringBefore('@')
        val tag = noDigest.substringAfterLast('/', noDigest).substringAfter(':', "")
        return when {
            tag.isNotBlank() -> tag
            '@' in image -> image.substringAfter('@').substringAfter(':').take(8)
            else -> "latest"
        }
    }

    /** Last runs of the declared workflow, then VM (environment) -> service -> container with declared vs running image tag. */
    private fun release(i: Inputs): List<Node> {
        val src = i.decl.release
        val runs = i.live.runs.mapIndexed { n, r ->
            val h = when (r.conclusion) {
                "success" -> Health.OK
                "failure", "timed_out" -> Health.BAD
                "" -> if (r.status == "completed") Health.UNKNOWN else Health.WARN
                else -> Health.WARN
            }
            Node("release/runs/$n", r.title.ifBlank { "run" }, "run", "${r.conclusion.ifBlank { r.status }} - ${java.time.Instant.ofEpochMilli(r.tsMillis).toString().take(16).replace('T', ' ')}", h)
        }
        val runsNode = Node("release/runs", "Last ${src?.workflow ?: ""} runs (${src?.repo ?: "no source declared"})", "group",
            i.live.runsNote ?: "", worst(runs.map { it.health }), emptySet(), null, runs)
        val envs = i.slice.vms.filter { vm -> i.catalog.entries.any { it.host == vm.alias } }.map { vm ->
            val services = i.catalog.entries.filter { it.host == vm.alias }.groupBy { it.service }.map { (svc, es) ->
                val prefix = "release/${vm.alias}/$svc"
                group(prefix, svc, "service", es.map { e ->
                    val declared = imageTag(e.image)
                    val running = ((i.live.deployed[vm.alias] as? Deployed.Seen)?.boxes?.firstOrNull { it.name == e.name })?.image?.takeIf { it.isNotBlank() }?.let { imageTag(it) }
                    val (h, text) = containerState(i, e)
                    val node = containerNode(i, prefix, e, state = when {
                        running == null -> "declared $declared - $text"
                        running == declared -> "version $declared"
                        else -> "declared $declared, running $running"
                    })
                    node.copy(kind = "version", health = if (running != null && running != declared) Health.WARN else h)
                }, setOf(Keys.service(svc)), Keys.service(svc))
            }
            val n = group("release/${vm.alias}", vm.alias.ifBlank { vm.id }, "vm", services, setOf(Keys.vm(vm.alias)), Keys.vm(vm.alias))
            n.copy(keys = n.keys + leaves(n).flatMap { it.keys })
        }
        return listOf(runsNode) + envs
    }

    // ── focus (pivot) and the list fallback ─────────────────────────────────

    /** Only the branches holding [key] (a node that carries it keeps its whole subtree); null keeps everything. */
    fun focus(nodes: List<Node>, key: String?): List<Node> =
        if (key == null) nodes else nodes.mapNotNull { n ->
            if (key in n.keys) n else focus(n.children, key).takeIf { it.isNotEmpty() }?.let { n.copy(children = it) }
        }

    /** The views a node can be opened in from [from]: every other view, for a node that stands for an entity. */
    fun pivots(node: Node, from: String): List<String> = if (node.pivot == null) emptyList() else ALL.filter { it != from }

    data class FlatRow(val node: Node, val path: String)

    /** The list fallback: every leaf with its path from the root. */
    fun flatten(nodes: List<Node>, prefix: List<String> = emptyList()): List<FlatRow> = nodes.flatMap { n ->
        if (n.children.isEmpty()) listOf(FlatRow(n, prefix.joinToString(" > "))) else flatten(n.children, prefix + n.label)
    }

    /** Every node of the tree in order, with its depth. */
    fun walk(nodes: List<Node>, depth: Int = 0): List<Pair<Node, Int>> = nodes.flatMap { listOf(it to depth) + walk(it.children, depth + 1) }
}
