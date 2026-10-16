package com.diegonmarcos.cloudagenda

import com.diegonmarcos.cloudagenda.agent.AgentBackend
import com.diegonmarcos.cloudagenda.agent.AgentReply
import com.diegonmarcos.cloudagenda.agent.AgentRunner
import com.diegonmarcos.cloudagenda.agent.AgentScheduler
import com.diegonmarcos.cloudagenda.agent.AgentStatus
import com.diegonmarcos.cloudagenda.agent.AgentStore
import com.diegonmarcos.cloudagenda.agent.BackendStatus
import com.diegonmarcos.cloudagenda.agent.ExtractionProtocol
import com.diegonmarcos.cloudagenda.agent.GatewayAgentBackend
import com.diegonmarcos.cloudagenda.agent.MailItem
import com.diegonmarcos.cloudagenda.agent.MailSource
import com.diegonmarcos.cloudagenda.agent.TaskSearch
import com.diegonmarcos.cloudagenda.data.AgentConfig
import com.diegonmarcos.cloudagenda.data.Attachment
import com.diegonmarcos.cloudagenda.data.AttachmentKind
import com.diegonmarcos.cloudagenda.data.MailSourceKind
import com.diegonmarcos.cloudagenda.data.ProposalDraft
import com.diegonmarcos.cloudagenda.data.ProposalKind
import com.diegonmarcos.cloudagenda.data.Snapshot
import com.diegonmarcos.cloudagenda.data.Tag
import com.diegonmarcos.cloudagenda.data.Task
import com.diegonmarcos.cloudagenda.data.TaskStatus
import com.diegonmarcos.superapp.ai.AiAgent
import com.diegonmarcos.superapp.ai.AiConfig
import com.diegonmarcos.superapp.ai.AiHttp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The mail agent: the wire it speaks to the model, one pass of the runner, the backend probe, and the scheduler's arithmetic. */
class AgentTest {

    // ── ExtractionProtocol ──────────────────────────────────────────────────────────────────────────────

    private val all = ProposalKind.entries.toSet()

    @Test fun `a JSON array inside prose and a code fence is read`() {
        val reply = "Sure! ```json\n[{\"kind\":\"invoice\",\"title\":\"Pay rent\",\"details\":\"Due soon\",\"date_target\":null,\"date_deadline\":\"2026-12-01\",\"location\":\"\",\"confidence\":0.9}]\n```"
        val out = ExtractionProtocol.parse(reply, all, "mail:a/1", "Rent")
        assertEquals(1, out.size)
        val d = out.single()
        assertEquals(ProposalKind.INVOICE, d.kind)
        assertEquals("Pay rent", d.title)
        assertNull(d.dateTarget)
        assertEquals("2026-12-01", d.dateDeadline)
        assertEquals("mail:a/1", d.sourceRef)
        assertEquals("Rent", d.sourceSubject)
        assertEquals(0.9, d.confidence, 1e-9)
    }

    @Test fun `a kind the user switched off, an unknown kind and a blank title are dropped`() {
        val reply = """[{"kind":"event","title":"Concert"},{"kind":"invoice","title":"Bill"},{"kind":"party","title":"Nope"},{"kind":"event","title":"  "}]"""
        val out = ExtractionProtocol.parse(reply, setOf(ProposalKind.EVENT), "r", "s")
        assertEquals(listOf("Concert"), out.map { it.title })
    }

    @Test fun `dates must be real dates, otherwise they are null and never guessed`() {
        val reply = """[{"kind":"deadline","title":"A","date_target":"2026-02-30","date_deadline":"next friday"},{"kind":"deadline","title":"B","date_target":"null","date_deadline":"2026-03-04"}]"""
        val out = ExtractionProtocol.parse(reply, all, "r", "s")
        assertNull(out[0].dateTarget); assertNull(out[0].dateDeadline)
        assertNull(out[1].dateTarget); assertEquals("2026-03-04", out[1].dateDeadline)
    }

    @Test fun `confidence is clamped and the number per mail is capped`() {
        val items = (1..9).joinToString(",") { """{"kind":"event","title":"E$it","confidence":${if (it == 1) 7 else -2}}""" }
        val out = ExtractionProtocol.parse("[$items]", all, "r", "s")
        assertEquals(ExtractionProtocol.MAX_PER_MAIL, out.size)
        assertEquals(1.0, out[0].confidence, 1e-9)
        assertEquals(0.0, out[1].confidence, 1e-9)
    }

    @Test fun `anything that is not a JSON array yields nothing`() {
        for (r in listOf("", "no items", "{\"kind\":\"event\"}", "[not json", "] [")) assertTrue(r, ExtractionProtocol.parse(r, all, "r", "s").isEmpty())
        assertTrue(ExtractionProtocol.parse("[]", all, "r", "s").isEmpty())
    }

    @Test fun `the prompt names the wanted kinds, carries the mail and cuts a long body`() {
        val p = ExtractionProtocol.prompt(listOf(ProposalKind.EVENT, ProposalKind.DEADLINE), "Hello", "a@b.c", "2026-10-01", "x".repeat(20_000))
        assertTrue("event, deadline" in p)
        assertTrue("Subject: Hello" in p && "From: a@b.c" in p)
        assertTrue(p.length < 20_000)
        assertFalse("invoice, " in p)
    }

    // ── AgentRunner ─────────────────────────────────────────────────────────────────────────────────────

    private class FakeStore(var cfg: AgentConfig) : AgentStore {
        val runs = ArrayList<Triple<Long, String, String>>()
        val stored = ArrayList<ProposalDraft>()
        override fun agentConfig(): AgentConfig = cfg
        override fun recordAgentRun(at: Long, status: String, detail: String) { runs.add(Triple(at, status, detail)); cfg = cfg.copy(lastRunAt = at, lastStatus = status, lastDetail = detail) }
        override fun addProposals(drafts: List<ProposalDraft>): Int { stored.addAll(drafts); return drafts.size }
    }

    private class FakeMail(private val items: List<MailItem>, private val reason: String? = null) : MailSource {
        var reads = 0
        override fun unavailableReason(): String? = reason
        override fun recent(since: Long, limit: Int, subject: String): List<MailItem> { reads++; return items }
        override fun bodyText(accountId: String, id: String): String = "body of $id"
    }

    private class FakeBackend(var status: BackendStatus = BackendStatus.Ready, var reply: (String) -> AgentReply = { AgentReply("[]", null) }) : AgentBackend {
        var asked = 0
        override fun status(agent: String): BackendStatus = status
        override fun extract(prompt: String, agent: String, model: String): AgentReply { asked++; return reply(prompt) }
    }

    private fun mail(id: String, account: String = "acc", box: String = "inbox") = MailItem(id, account, box, "Subject $id", "Sender", "s@x.y", 1_700_000_000_000, false)

    private val on = AgentConfig(enabled = true)

    @Test fun `a disabled agent does nothing and records nothing`() {
        val store = FakeStore(AgentConfig(enabled = false)); val m = FakeMail(listOf(mail("1"))); val b = FakeBackend()
        val r = AgentRunner(store, m, b).run()
        assertEquals(AgentStatus.DISABLED, r.status)
        assertTrue(store.runs.isEmpty()); assertEquals(0, m.reads); assertEquals(0, b.asked)
    }

    @Test fun `no backend means the status says so and no mail is read`() {
        val store = FakeStore(on); val m = FakeMail(listOf(mail("1"))); val b = FakeBackend(BackendStatus.NotDeployed("gateway down"))
        val r = AgentRunner(store, m, b).run()
        assertEquals(AgentStatus.BACKEND_NOT_DEPLOYED, r.status)
        assertEquals("gateway down", store.cfg.lastDetail)
        assertEquals(0, m.reads); assertEquals(0, b.asked); assertTrue(store.stored.isEmpty())
    }

    @Test fun `unavailable mail and the unbuilt JMAP reader are reported, not faked`() {
        val s1 = FakeStore(on)
        assertEquals(AgentStatus.MAIL_UNAVAILABLE, AgentRunner(s1, FakeMail(emptyList(), "Cloud Mail is not installed"), FakeBackend()).run().status)
        val s2 = FakeStore(on.copy(source = MailSourceKind.JMAP, jmapUrl = "https://jmap.example/"))
        val m = FakeMail(listOf(mail("1")))
        assertEquals(AgentStatus.JMAP_NOT_BUILT, AgentRunner(s2, m, FakeBackend()).run().status)
        assertEquals(0, m.reads)
    }

    @Test fun `nothing switched on is a configuration problem`() {
        val s = FakeStore(on.copy(extractEvents = false, extractBookings = false, extractInvoices = false, extractDeadlines = false))
        assertEquals(AgentStatus.NOT_CONFIGURED, AgentRunner(s, FakeMail(listOf(mail("1"))), FakeBackend()).run().status)
    }

    @Test fun `a pass stores proposals with the mail link, honours account and mailbox, and moves the window`() {
        val store = FakeStore(on.copy(account = "acc", mailboxes = "inbox"))
        val mails = listOf(mail("1"), mail("2", box = "spam"), mail("3", account = "other"), mail("4"))
        val b = FakeBackend(reply = { p -> AgentReply("""[{"kind":"event","title":"Event from ${if ("Subject 1" in p) "one" else "four"}","date_target":"2026-11-14"}]""", null) })
        val r = AgentRunner(store, FakeMail(mails), b, now = { 5_000L }).run()
        assertEquals(AgentStatus.OK, r.status)
        assertEquals(2, b.asked)
        assertEquals(listOf("Event from one", "Event from four"), store.stored.map { it.title })
        assertEquals(listOf("mail:acc/1", "mail:acc/4"), store.stored.map { it.sourceRef })
        assertEquals(5_000L, store.cfg.lastRunAt)
        assertEquals(2, r.newProposals)
    }

    @Test fun `a model failure is an error that keeps the old window so the mail is retried`() {
        val store = FakeStore(on.copy(lastRunAt = 1_234))
        val b = FakeBackend(reply = { AgentReply(null, "HTTP 500") })
        val r = AgentRunner(store, FakeMail(listOf(mail("1"))), b, now = { 9_999L }).run()
        assertEquals(AgentStatus.ERROR, r.status)
        assertEquals(1_234L, store.cfg.lastRunAt)
        assertTrue(store.stored.isEmpty())
    }

    // ── the gateway backend ─────────────────────────────────────────────────────────────────────────────

    private val aiJson = """{"gateway":{"service":"s","chat_path":"/v1/chat/completions","health_path":"/health"},
        "openrouter":{"id":"openrouter","label":"OpenRouter","chat_url":"u","models_url":"m","account_provider":"openrouter","default_model":"x"},"protocol":{}}"""

    private class FakeHttp(val code: Int = 200, val body: String = "{}", val fail: Exception? = null) : AiHttp {
        override fun get(url: String, headers: Map<String, String>, timeoutMs: Int): AiHttp.Response {
            fail?.let { throw it }
            return AiHttp.Response(code, body)
        }
        override fun post(url: String, headers: Map<String, String>, body: String, timeoutMs: Int, onChunk: ((String) -> Unit)?) = AiHttp.Response(code, this.body)
    }

    private val hermes = AiAgent("hermes", "Hermes", "tools", "hermes")

    private fun backend(http: AiHttp) = GatewayAgentBackend(AiConfig.parse(aiJson), "http://gw", listOf(hermes), http)

    @Test fun `the backend is ready when the gateway answers and lists the agent`() {
        assertEquals(BackendStatus.Ready, backend(FakeHttp(200, """{"modes":["goose","hermes"]}""")).status("hermes"))
        assertEquals(BackendStatus.Ready, backend(FakeHttp(200, """{"ok":true}""")).status("hermes"))
        assertEquals(BackendStatus.Ready, backend(FakeHttp(200, """{"modes":{"hermes":{}}}""")).status(""))
    }

    @Test fun `the backend is not deployed when unreachable, failing, or without the chosen agent`() {
        assertTrue(backend(FakeHttp(fail = java.io.IOException("no route"))).status("") is BackendStatus.NotDeployed)
        assertTrue(backend(FakeHttp(code = 502)).status("") is BackendStatus.NotDeployed)
        val missing = backend(FakeHttp(200, """{"modes":[{"id":"goose"}]}""")).status("hermes")
        assertTrue(missing is BackendStatus.NotDeployed && "Hermes" in missing.reason)
    }

    @Test fun `health bodies of every usual shape give their mode ids, an unreadable one gives null`() {
        assertEquals(setOf("a", "b"), GatewayAgentBackend.liveModes("""{"modes":["a","b"]}"""))
        assertEquals(setOf("a"), GatewayAgentBackend.liveModes("""{"modes":[{"id":"a"}]}"""))
        assertEquals(setOf("a", "b"), GatewayAgentBackend.liveModes("""{"modes":{"a":1,"b":2}}"""))
        assertNull(GatewayAgentBackend.liveModes("""{"modes":3}"""))
        assertNull(GatewayAgentBackend.liveModes("not json"))
    }

    // ── scheduler arithmetic ────────────────────────────────────────────────────────────────────────────

    @Test fun `the worker is scheduled only for an enabled agent with a frequency, never below the 15 minute floor`() {
        assertNull(AgentScheduler.periodMinutes(AgentConfig(enabled = false, frequencyMinutes = 60)))
        assertNull(AgentScheduler.periodMinutes(AgentConfig(enabled = true, frequencyMinutes = 0)))
        assertEquals(60L, AgentScheduler.periodMinutes(AgentConfig(enabled = true, frequencyMinutes = 60)))
        assertEquals(15L, AgentScheduler.periodMinutes(AgentConfig(enabled = true, frequencyMinutes = 5)))
    }

    // ── searching the tasks (the AI box's tool) ─────────────────────────────────────────────────────────

    @Test fun `task search matches every word across name, details, tags, links and dates`() {
        val snap = Snapshot(
            tasks = listOf(
                Task("1", "L", name = "Call dentist", details = "crown", dateDeadline = "2026-11-30", tagIds = setOf("t"),
                    attachments = listOf(Attachment("a", AttachmentKind.MAIL, "mail:x/1", "Appointment mail"))),
                Task("2", "L", name = "Buy milk", status = TaskStatus.COMPLETED),
            ),
            tags = listOf(Tag("t", "health", 1)),
        )
        assertEquals(listOf("1"), TaskSearch.search(snap, "dentist health").map { it.id })
        assertEquals(listOf("1"), TaskSearch.search(snap, "appointment 2026-11").map { it.id })
        assertEquals(listOf("2"), TaskSearch.search(snap, "completed").map { it.id })
        assertTrue(TaskSearch.search(snap, "dentist milk").isEmpty())
        assertTrue(TaskSearch.search(snap, "  ").isEmpty())
    }
}
