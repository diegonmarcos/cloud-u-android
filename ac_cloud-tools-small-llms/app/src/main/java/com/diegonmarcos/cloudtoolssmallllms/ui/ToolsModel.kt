package com.diegonmarcos.cloudtoolssmallllms.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.diegonmarcos.cloudtoolssmallllms.Decl
import com.diegonmarcos.cloudtoolssmallllms.core.Ledger
import com.diegonmarcos.cloudtoolssmallllms.core.OpenRouterChat
import com.diegonmarcos.cloudtoolssmallllms.core.TaskRegistry
import com.diegonmarcos.cloudtoolssmallllms.core.TokenVault
import com.diegonmarcos.cloudtoolssmallllms.core.ToolsConfig
import com.diegonmarcos.cloudtoolssmallllms.core.ToolsFlow
import com.diegonmarcos.cloudtoolssmallllms.data.AccountStatus
import com.diegonmarcos.cloudtoolssmallllms.data.Catalogue
import com.diegonmarcos.cloudtoolssmallllms.data.SecretPrefs
import com.diegonmarcos.cloudtoolssmallllms.data.Stores
import com.diegonmarcos.cloudtoolssmallllms.R
import com.diegonmarcos.cloudtoolssmallllms.core.Costs
import com.diegonmarcos.superapp.ai.AiBackend
import com.diegonmarcos.superapp.ai.AiChatState
import com.diegonmarcos.superapp.ai.AiConfig
import com.diegonmarcos.superapp.ai.AiFileSessions
import com.diegonmarcos.superapp.ai.AiMemoryPrefs
import com.diegonmarcos.superapp.ai.AiModel
import com.diegonmarcos.superapp.ai.AiReply
import com.diegonmarcos.superapp.ai.AiRequest
import com.diegonmarcos.superapp.ai.AiStandardHost
import com.diegonmarcos.superapp.decisions.UrlHttp
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The app's state, held once per Activity: the chat, the ledger, the router and worker choices,
 * the token's mask, the Account's status and the catalogue's prices. Every network call runs on
 * [Dispatchers.IO] through :tools-core ([ToolsFlow], [OpenRouterChat]); this class only moves
 * their results into Compose state and the stores.
 */
class ToolsModel(context: Context, private val scope: CoroutineScope) {
    private val app = context.applicationContext
    val cfg: ToolsConfig = Decl.tools
    private val stores = Stores(context)
    private val vault = TokenVault(SecretPrefs(context))
    private val account = AccountStatus(context)
    val catalogue = Catalogue(context, UrlHttp, cfg.openRouter.timeoutMs)

    var section by mutableStateOf(Decl.nav.default()?.id ?: Decl.nav.bottomNav.firstOrNull().orEmpty())
    var ledger by mutableStateOf(stores.ledger())
        private set
    /** The task the next send is pinned to; null = the router decides. */
    var pinned by mutableStateOf<String?>(null)
    var router by mutableStateOf(stores.router.takeIf { it in cfg.router.choices } ?: cfg.router.default)
        private set
    var workers by mutableStateOf(stores.workers())
        private set
    var masked by mutableStateOf("")
        private set
    var tokenSet by mutableStateOf(false)
        private set
    var keyStatus by mutableStateOf<String?>(null)
        private set
    var accountState by mutableStateOf<AccountStatus.State?>(null)
        private set
    var jevListed by mutableStateOf(stores.jev().first)
        private set
    var jevPrice by mutableStateOf(stores.jev().second ?: cfg.router.jevPriceIn)
        private set
    var asOf by mutableStateOf("")
        private set
    /** Bumped whenever prices change, so priced composables recompose. */
    var pricesVersion by mutableStateOf(0)
        private set

    fun registry(): TaskRegistry = TaskRegistry(cfg, catalogue.book, workers)

    private val flow = ToolsFlow(cfg, UrlHttp, { vault.token() }, catalogue.book, { registry() }, { router }, { jevListed }, { jevPrice })

    init {
        refreshToken()
        scope.launch {
            withContext(Dispatchers.IO) { catalogue.stored() }
            asOf = catalogue.asOf(); pricesVersion++
            withContext(Dispatchers.IO) { catalogue.load() }
            asOf = catalogue.asOf(); pricesVersion++
        }
        refreshJev()
        refreshAccount()
    }

    // ── AI (libs:ai's page over this app's router backend) ───────────────────────────────────

    /**
     * The AI tab's backend: one turn of the router → worker flow as an AiReply. The request is the user's last
     * message (the router sees one request at a time); the answer carries its task, model and cost, and the cost
     * lands in the ledger. A question back (the router is unsure) names the candidate tasks; pinning one in the
     * task row sends the next message straight to it. Blocking: libs:ai calls it off the main thread.
     */
    private val routerBackend = object : AiBackend {
        override val id: String get() = "router"
        override fun models(): List<AiModel> = emptyList()
        override fun chat(req: AiRequest, onDelta: (String) -> Unit): AiReply =
            turn(req.messages.lastOrNull { it.role == "user" }?.content.orEmpty())
    }

    /** The AI page's state: the open conversation, kept in a file (class content, never the token). */
    val chat = AiChatState(AiStandardHost(
        AiConfig.load(app), AiFileSessions(File(app.filesDir, "ai-sessions.json")), AiMemoryPrefs(""),
        direct = routerBackend, modelSource = { emptyList() },
    ))

    private fun turn(request: String): AiReply {
        val forced = pinned
        return when (val t = flow.send(request, forced)) {
            is ToolsFlow.Turn.Answered -> {
                val a = t.answer
                charge(a.task, a.worker, a.total)
                val label = cfg.task(a.task)?.label ?: a.task
                val chip = app.getString(R.string.chip_cost, label, short(a.worker), Costs.usd(a.total)) +
                    (if (a.routerModel.isNotEmpty()) " · " + app.getString(R.string.chip_router, short(a.routerModel), Costs.usd(a.routerCost)) +
                        " · " + app.getString(R.string.chip_confidence, Math.round(a.confidence * 100).toInt()) else "")
                AiReply(a.text + "\n\n— " + chip)
            }
            is ToolsFlow.Turn.Clarify -> {
                charge(ROUTER_TASK, t.decision.routerModel, t.decision.routerCost)
                val names = t.decision.candidates.mapNotNull { cfg.task(it.first)?.label }
                AiReply(t.decision.question + (if (names.isEmpty()) "" else "\n" + app.getString(R.string.chat_router_unsure) + " " + names.joinToString(" · ")))
            }
            is ToolsFlow.Turn.Failed -> {
                charge(ROUTER_TASK, t.routerModel, t.routerCost)
                AiReply(null, error = t.reason)
            }
        }
    }

    private fun charge(task: String, model: String, usd: Double?) {
        if (usd == null || usd <= 0.0) return
        val l = Ledger(ledger.entries)
        l.add(Ledger.Entry(now(), usd, task, model))
        ledger = l
        stores.saveLedger(l)
    }

    fun clearLedger() {
        ledger = Ledger()
        stores.saveLedger(ledger)
    }

    // ── settings ─────────────────────────────────────────────────────────────────────────────

    fun saveToken(raw: String): Boolean = vault.set(raw).also { refreshToken() }

    fun removeToken() {
        vault.clear()
        keyStatus = null
        refreshToken()
    }

    private fun refreshToken() {
        masked = vault.masked()
        tokenSet = vault.isSet
    }

    /** Settings' Test: GET the key endpoint with the stored token; spends nothing. */
    fun testToken(ok: (OpenRouterChat.KeyInfo) -> Unit) {
        scope.launch {
            val info = withContext(Dispatchers.IO) { OpenRouterChat.keyInfo(UrlHttp, cfg.openRouter.keyUrl, vault.token(), cfg.openRouter.timeoutMs) }
            ok(info)
        }
    }

    fun chooseRouter(id: String) {
        if (id !in cfg.router.choices) return
        router = id
        stores.router = id
    }

    fun chooseWorker(task: String, model: String?) {
        val m = workers.toMutableMap()
        if (model == null || model == cfg.task(task)?.worker) m.remove(task) else m[task] = model
        workers = m
        stores.saveWorkers(m)
    }

    /** Re-read the decision catalogue: Jev is used automatically while it lists the pinned model. */
    fun refreshJev() {
        scope.launch {
            val body = withContext(Dispatchers.IO) {
                runCatching { UrlHttp.send(cfg.openRouter.decisionModelsUrl, null, null, cfg.openRouter.timeoutMs) }.getOrNull()?.takeIf { it.code in 200..299 }?.body
            }
            val listed = OpenRouterChat.listsModel(body, cfg.router.jevModel) ?: return@launch
            val price = OpenRouterChat.decisionPrice(body, cfg.router.jevModel)
            jevListed = listed
            if (price != null) jevPrice = price
            stores.saveJev(listed, price, now())
        }
    }

    fun refreshAccount() {
        scope.launch { accountState = withContext(Dispatchers.IO) { account.read() } }
    }

    private fun now(): Long = System.currentTimeMillis()

    companion object {
        /** The ledger's task id for a routing call that ran no worker (a question back, a failure). */
        const val ROUTER_TASK = "router"
    }
}
