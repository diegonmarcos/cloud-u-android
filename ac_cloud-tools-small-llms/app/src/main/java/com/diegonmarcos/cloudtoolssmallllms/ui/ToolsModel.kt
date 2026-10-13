package com.diegonmarcos.cloudtoolssmallllms.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import com.diegonmarcos.cloudtoolssmallllms.data.Msg
import com.diegonmarcos.cloudtoolssmallllms.data.SecretPrefs
import com.diegonmarcos.cloudtoolssmallllms.data.Stores
import com.diegonmarcos.superapp.decisions.UrlHttp
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
    val cfg: ToolsConfig = Decl.tools
    private val stores = Stores(context)
    private val vault = TokenVault(SecretPrefs(context))
    private val account = AccountStatus(context)
    val catalogue = Catalogue(context, UrlHttp, cfg.openRouter.timeoutMs)

    var section by mutableStateOf(Decl.nav.default()?.id ?: Decl.nav.bottomNav.firstOrNull().orEmpty())
    val messages = mutableStateListOf<Msg>().apply { addAll(stores.messages()) }
    var ledger by mutableStateOf(stores.ledger())
        private set
    var busy by mutableStateOf(false)
        private set
    var draft by mutableStateOf("")
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

    // ── chat ─────────────────────────────────────────────────────────────────────────────────

    /** Send [text]; [echo] false = a re-run of a request already on screen (a clarify chip). */
    fun send(text: String = draft, forcedTask: String? = pinned, echo: Boolean = true) {
        val req = text.trim()
        if (req.isEmpty() || busy) return
        if (echo) {
            add(Msg(nextId(), Msg.USER, req, now(), task = forcedTask.orEmpty()))
            draft = ""
        }
        busy = true
        scope.launch {
            val turn = withContext(Dispatchers.IO) { flow.send(req, forcedTask) }
            busy = false
            when (turn) {
                is ToolsFlow.Turn.Answered -> {
                    val a = turn.answer
                    add(Msg(nextId(), Msg.ANSWER, a.text, now(), a.task, a.worker, a.total, a.routerModel, a.routerCost, a.confidence, request = req))
                    charge(a.task, a.worker, a.total)
                }
                is ToolsFlow.Turn.Clarify -> {
                    add(Msg(nextId(), Msg.CLARIFY, turn.decision.question, now(), routerModel = turn.decision.routerModel,
                        routerCost = turn.decision.routerCost, candidates = turn.decision.candidates.map { it.first }, request = req))
                    charge(ROUTER_TASK, turn.decision.routerModel, turn.decision.routerCost)
                }
                is ToolsFlow.Turn.Failed -> {
                    add(Msg(nextId(), Msg.ERROR, turn.reason, now(), routerModel = turn.routerModel, routerCost = turn.routerCost, request = req))
                    charge(ROUTER_TASK, turn.routerModel, turn.routerCost)
                }
            }
        }
    }

    /** A clarify chip: run [request] as [task]. */
    fun pick(request: String, task: String) = send(request, task, echo = false)

    fun clearChat() {
        messages.clear()
        stores.saveMessages(emptyList())
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

    private fun add(m: Msg) {
        messages += m
        stores.saveMessages(messages.toList())
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

    private var seq = (messages.maxOfOrNull { it.id } ?: 0L)
    private fun nextId(): Long = ++seq
    private fun now(): Long = System.currentTimeMillis()

    companion object {
        /** The ledger's task id for a routing call that ran no worker (a question back, a failure). */
        const val ROUTER_TASK = "router"
    }
}
