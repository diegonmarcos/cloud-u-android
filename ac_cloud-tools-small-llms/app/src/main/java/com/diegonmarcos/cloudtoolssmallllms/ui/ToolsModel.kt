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
import com.diegonmarcos.cloudtoolssmallllms.data.AccountStatus
import com.diegonmarcos.cloudtoolssmallllms.data.Catalogue
import com.diegonmarcos.cloudtoolssmallllms.data.LocalCall
import com.diegonmarcos.cloudtoolssmallllms.data.LocalChatBackend
import com.diegonmarcos.cloudtoolssmallllms.data.ModelRow
import com.diegonmarcos.cloudtoolssmallllms.data.ModelRuntime
import com.diegonmarcos.cloudtoolssmallllms.data.ModelsData
import com.diegonmarcos.cloudtoolssmallllms.data.OllamaRuntime
import com.diegonmarcos.cloudtoolssmallllms.data.PhoneRuntime
import com.diegonmarcos.cloudtoolssmallllms.data.RuntimeState
import com.diegonmarcos.cloudtoolssmallllms.data.SecretPrefs
import com.diegonmarcos.cloudtoolssmallllms.data.Stores
import com.diegonmarcos.cloudtoolssmallllms.BuildConfig
import com.diegonmarcos.cloudtoolssmallllms.core.Costs
import com.diegonmarcos.superapp.ai.AiBackend
import com.diegonmarcos.superapp.ai.AiChatState
import com.diegonmarcos.superapp.ai.AiConfig
import com.diegonmarcos.superapp.ai.AiFileSessions
import com.diegonmarcos.superapp.ai.AiGatewayBackend
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
 * The app's state, held once per Activity: the chat, the model data, the ledger, the router and worker choices,
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
    /** The open page of the section (a second-row tab); null = the section's first. */
    var page by mutableStateOf<String?>(null)
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


    // ── Chat (libs:ai's page over the fleet agent gateway) ───────────────────────────────────

    /** The model data (assets/tools-models.json): the Models and Apps sections and the AI chat's model picker. */
    val models: ModelsData = ModelsData.load(app)

    /** The Models view the switcher picked (kept across the Small / Medium / Retrieval pages). */
    var view by mutableStateOf(ModelsView.TABLE)

    /** The app open in the Apps section; null = the icon grid. */
    var appId by mutableStateOf<String?>(null)

    /** The AI chat's backend: "openrouter" (cloud, needs openrouter_id) or "local" (a Store runtime, the model must be Loaded). */
    var backend by mutableStateOf(stores.backend.takeIf { it == LOCAL } ?: OPENROUTER)
        private set

    /** The runtimes of the Store, one per declared location. The state they report is [runtimeStates]; nothing else is kept. */
    val runtimes: List<ModelRuntime> = models.locations.mapNotNull { l ->
        when (l.kind) {
            "device" -> PhoneRuntime(app, l.id)
            "ollama" -> l.url?.let { OllamaRuntime(l.id, it, models.quantTags) }
            else -> null
        }
    }
    var runtimeStates by mutableStateOf<Map<String, RuntimeState>>(emptyMap())
        private set
    /** What the Store is doing right now ("install llama-3.2-1b-instruct on Fleet Ollama"). */
    var storeBusy by mutableStateOf<String?>(null)
        private set
    /** The last Store action's failure, if any. */
    var storeError by mutableStateOf<String?>(null)
        private set
    var localCalls by mutableStateOf(stores.localUsage())
        private set

    /** The first ollama-kind runtime's address: where the Local chat is sent. */
    private val localUrl: String? = models.locations.firstOrNull { it.kind == "ollama" }?.url

    /** The names of the models currently Loaded on any runtime. */
    fun loadedNames(): Set<String> = runtimeStates.values.flatMap { st -> st.loaded.map { it.name } }.toSet()

    /** The chat rows the picker offers for [backend]: OpenRouter = those with an openrouter_id; Local = those Loaded. */
    fun pickerRows(): List<ModelRow> = models.chatRows().filter { if (backend == LOCAL) it.model in loadedNames() else it.openrouterId != null }

    private fun pickerModels(): List<AiModel> =
        pickerRows().map { AiModel(it.model, "${models.macro(it.macro)?.label.orEmpty()} · ${it.model} (${it.params})") }

    fun chooseBackend(b: String) {
        backend = if (b == LOCAL) LOCAL else OPENROUTER
        stores.backend = backend
        syncPicker()
    }

    /** Re-reads every runtime (off the main thread) and refreshes the chat picker from it. */
    fun refreshStore() {
        scope.launch {
            val st = withContext(Dispatchers.IO) { runtimes.associate { it.id to it.state() } }
            runtimeStates = st
            syncPicker()
        }
    }

    private fun syncPicker() {
        chat.models = pickerModels()
        if (chat.models.none { it.id == chat.model }) chat.models.firstOrNull()?.let { chat.pick(it.id) }
    }

    /** One Store action on [runtime]; [what] names it for the busy line. The runtime's own state is re-read after. */
    fun storeAction(runtime: ModelRuntime, what: String, act: (ModelRuntime) -> String?) {
        if (storeBusy != null) return
        storeBusy = what; storeError = null
        scope.launch {
            val err = withContext(Dispatchers.IO) { act(runtime) }
            storeBusy = null
            storeError = err?.let { "$what: $it" }
            refreshStore()
        }
    }

    fun clearLocalUsage() {
        stores.clearLocalUsage()
        localCalls = emptyList()
    }

    /**
     * The one backend of the chat: routes each call by [backend]. OpenRouter goes through the fleet agent gateway
     * (the picked model out as its OpenRouter id) and is recorded in Costs in money (an estimate: tokens ~ characters / 4,
     * priced by the shared catalogue; nothing for a model the catalogue does not price). Local goes to the Store's
     * runtime on its OpenAI-compatible endpoint and is recorded as tokens and time, at no cost.
     */
    private val routedBackend = object : AiBackend {
        private val gateway = AiGatewayBackend(AiConfig.load(app), BuildConfig.AI_GATEWAY_URL)
        private val local = localUrl?.let { LocalChatBackend(AiConfig.load(app), it) }
        override val id: String get() = backend
        override fun models(): List<AiModel> = emptyList()
        override fun chat(req: AiRequest, onDelta: (String) -> Unit): AiReply {
            val row = models.row(req.model.orEmpty())
            val chars = req.messages.sumOf { it.content.length }
            if (backend == LOCAL) {
                val l = local ?: return AiReply(null, error = "no local runtime is declared")
                val t0 = System.currentTimeMillis()
                val reply = l.chat(req, onDelta)
                val text = reply.text
                if (row != null && text != null) recordLocal(LocalCall(t0, row.model, (chars + text.length) / 4, System.currentTimeMillis() - t0))
                return reply
            }
            val wire = AiRequest(row?.wireId ?: req.model, req.messages, req.web, req.nativeWeb, req.agent, req.tools, req.wire, req.stream)
            val reply = gateway.chat(wire, onDelta)
            val text = reply.text
            if (row != null && text != null) {
                val price = catalogue.book.price(row.wireId)
                if (price != null) charge(row.model, Costs.estimate(price, chars / 4, text.length / 4))
            }
            return reply
        }
    }

    private fun recordLocal(c: LocalCall) {
        localCalls = stores.addLocalUsage(c)
    }

    /**
     * The AI page's state: the open conversation, kept in a file. The model picker lists ONLY chat models, grouped by
     * macro task, filtered by the backend ([pickerRows]); the gateway holds the fleet keys, so no token is held for it.
     */
    val chat = AiChatState(AiStandardHost(
        AiConfig.load(app), AiFileSessions(File(app.filesDir, "ai-sessions.json")),
        AiMemoryPrefs((models.chatRows().firstOrNull { it.openrouterId != null } ?: models.chatRows().first()).model),
        direct = routedBackend,
        modelSource = { pickerModels() },
    ))

    /** Apps' Try in AI: open the AI section with [model] picked. */
    fun tryInAi(model: String) {
        syncPicker()
        chat.pick(model)
        section = Decl.SECTION_AI
        page = null
    }

    private fun charge(model: String, usd: Double) {
        if (usd <= 0.0) return
        val l = Ledger(ledger.entries)
        l.add(Ledger.Entry(now(), usd, CHAT_TASK, model))
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
        /** The ledger's task id of a chat call. */
        const val CHAT_TASK = "chat"
        const val OPENROUTER = "openrouter"
        const val LOCAL = "local"
    }
}
