package com.diegonmarcos.cloudagenda.agent

import com.diegonmarcos.cloudagenda.data.AgentConfig
import com.diegonmarcos.cloudagenda.data.MailSourceKind
import com.diegonmarcos.cloudagenda.data.ProposalDraft
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** The last-run status words the Agent page maps to its banner. */
object AgentStatus {
    const val DISABLED = "disabled"
    const val NOT_CONFIGURED = "not_configured"
    const val BACKEND_NOT_DEPLOYED = "backend_not_deployed"
    const val MAIL_UNAVAILABLE = "mail_unavailable"
    const val JMAP_NOT_BUILT = "jmap_reader_not_built"
    const val OK = "ok"
    const val ERROR = "error"
}

/** What the runner reads and writes: the declaration, the run record, the proposals. TasksRepository is the real one. */
interface AgentStore {
    fun agentConfig(): AgentConfig
    fun recordAgentRun(at: Long, status: String, detail: String)
    fun addProposals(drafts: List<ProposalDraft>): Int
}

class RunResult(val status: String, val detail: String, val newProposals: Int = 0)

/**
 * One pass of the mail agent: check the extraction backend, read the new mail, ask the model per mail, store what it
 * finds as PROPOSALS. It never creates a task: a proposal waits in Settings > Inbox for the user's approval. If the
 * backend is not there it says so and stops before touching any mail; it never fabricates a result.
 */
class AgentRunner(
    private val store: AgentStore,
    private val mail: MailSource,
    private val backend: AgentBackend,
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun run(): RunResult {
        val cfg = store.agentConfig()
        if (!cfg.enabled) return RunResult(AgentStatus.DISABLED, "the agent is off")
        return finish(pass(cfg))
    }

    private fun finish(r: RunResult): RunResult {
        // Only a successful pass moves the window forward: a failed one is retried over the same mail.
        val at = now()
        store.recordAgentRun(if (r.status == AgentStatus.OK) at else store.agentConfig().lastRunAt, r.status, r.detail)
        return r
    }

    private fun pass(cfg: AgentConfig): RunResult {
        if (cfg.kinds.isEmpty()) return RunResult(AgentStatus.NOT_CONFIGURED, "no kind of item is switched on")
        when (val b = backend.status(cfg.agent)) {
            is BackendStatus.NotDeployed -> return RunResult(AgentStatus.BACKEND_NOT_DEPLOYED, b.reason)
            BackendStatus.Ready -> {}
        }
        if (cfg.source == MailSourceKind.JMAP) {
            return RunResult(AgentStatus.JMAP_NOT_BUILT, "reading a JMAP server directly is not built yet; the declaration is saved. Use Cloud Mail as the source.")
        }
        mail.unavailableReason()?.let { return RunResult(AgentStatus.MAIL_UNAVAILABLE, it) }

        val since = if (cfg.lastRunAt > 0) cfg.lastRunAt - OVERLAP_MS else now() - FIRST_WINDOW_MS
        val boxes = cfg.mailboxSet
        val mails = try {
            mail.recent(since, FETCH_LIMIT).filter { (cfg.account.isBlank() || it.accountId == cfg.account) && (boxes.isEmpty() || it.mailboxId in boxes) }
        } catch (e: Exception) {
            return RunResult(AgentStatus.MAIL_UNAVAILABLE, "Cloud Mail refused the request (${e.javaClass.simpleName})")
        }

        var added = 0
        val kinds = cfg.kinds
        for (m in mails.take(PER_RUN)) {
            val prompt = ExtractionProtocol.prompt(kinds, m.subject, m.sender, day(m.receivedAt), runCatching { mail.bodyText(m.accountId, m.id) }.getOrDefault(""))
            val reply = backend.extract(prompt, cfg.agent, cfg.model)
            if (reply.error != null || reply.text == null) {
                return RunResult(AgentStatus.ERROR, "the model did not answer: ${reply.error ?: "empty reply"}", added)
            }
            val drafts = ExtractionProtocol.parse(reply.text, kinds.toSet(), m.ref, m.subject)
            added += store.addProposals(drafts)
        }
        return RunResult(AgentStatus.OK, "$added new proposal${if (added == 1) "" else "s"} from ${minOf(mails.size, PER_RUN)} mail${if (mails.size == 1) "" else "s"}", added)
    }

    private fun day(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))

    companion object {
        const val FETCH_LIMIT = 100
        const val PER_RUN = 20
        const val OVERLAP_MS = 60_000L
        const val FIRST_WINDOW_MS = 7L * 24 * 3600 * 1000
    }
}
