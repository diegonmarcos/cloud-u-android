package com.diegonmarcos.cloudwriter

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.StringRes
import com.diegonmarcos.superapp.texttools.TextTools
import com.diegonmarcos.superapp.texttools.TextToolsClient
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * The owner's four tools, and therefore WHICH ENGINE each one reaches.
 *
 * The pairing is the point of the enum. [ENHANCE], [GRAMMAR] and [SUMMARY] go to the
 * chat-completions provider chosen in this application's own settings; [TRANSLATE] goes to the
 * translation library. They cost different money, they answer differently, and a reply does not
 * say which one produced it — so [WriterToolRunner.run] is the single place that maps a tool to an
 * engine, which means there is exactly one line in this application where they could be crossed
 * and exactly one line to assert on.
 *
 * [SUMMARY] is "Resume" elsewhere in this fleet — the owner's name for SUMMARISE, condense this
 * text. Not a curriculum vitae, and not resuming anything that was paused. It is spelled Summary
 * on this screen and summarise on the wire so neither can be misread.
 *
 * [GRAMMAR] is not a fourth engine: it is the rewrite engine asked for the one `grammar` style,
 * which is what `ITextTools.enhance(text, "grammar")` describes. [usesModel] is false for
 * [TRANSLATE] alone, because a translation has no chat model to choose — offering the owner a
 * model picker for it would be a control that changes nothing.
 *
 * [id] is the string the per-tool model preference is keyed by, so it is written down here once
 * and is not `name.lowercase()`: renaming an enum constant must not silently re-point every
 * preference the owner has set.
 */
enum class WriterTool(
    val id: String,
    @StringRes val label: Int,
    val usesModel: Boolean,
) {
    ENHANCE("enhance", R.string.tool_enhance, usesModel = true),
    GRAMMAR("grammar", R.string.tool_grammar, usesModel = true),
    SUMMARY("summary", R.string.tool_summary, usesModel = true),
    TRANSLATE("translate", R.string.tool_translate, usesModel = false),
}

/** How a finished run ended. Exactly one of [text] and [error] is set, and never neither. */
class WriterOutcome(val tool: WriterTool, val text: String?, val error: String?)

/**
 * Runs a tool and holds enough state for the screen to say what is happening.
 *
 * THERE IS NO SILENT PATH OUT OF [run]. Every branch — a blank input, a tool this build cannot
 * honour, a second tap while one call is in flight, a peer that is not installed, a provider that
 * refused — ends in [onDone] with a sentence. A control that does nothing and says nothing is
 * indistinguishable from a crash, a missing permission and a network failure, which is the defect
 * 1_cicd/src/data/silence-guard.json exists to catch elsewhere in this repository.
 *
 * The binder calls BLOCK, by contract: `ITextTools` says so in capitals and `TextToolsClient` does
 * no threading of its own on purpose, so that a caller cannot forget it must show progress. One
 * single-thread executor, and the callback is posted back to the main looper.
 */
class WriterToolRunner(context: Context) {

    private val app = context.applicationContext

    /**
     * ONE binding for the whole process. The client binds on construction and rebinds on use, so
     * a fresh one per screen would open a second binding to the same service and spend its own
     * rebind budget.
     */
    private val client = TextToolsClient(app)

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** Non-null while a call is in flight; the screen shows progress for exactly this tool. */
    @Volatile
    var busy: WriterTool? = null
        private set

    /** Is a serving application on this phone at all — the question a status line must ask. */
    fun isServingAppInstalled(): Boolean = client.isServingAppInstalled()

    /**
     * The package name of the peer that is ANSWERING, read out of its own reply.
     *
     * `aiRoutingSnapshot()` carries `app`, which is how a console survives the tools moving house:
     * the answer says who gave it, so nothing on this side has to hold a constant that would keep
     * naming Cloud Keyboard on the day something else began serving. That is the same property
     * that makes `TextTools.SERVICE_PACKAGES` an ordered preference rather than one address.
     *
     * NULL IS TWO DIFFERENT FACTS and the caller separates them with [isServingAppInstalled]: no
     * peer installed is an install, a peer bound but answering nothing is a peer too old to know
     * the method, which is an update. Reporting them as one sends the owner to the wrong repair.
     *
     * NOTHING IN THE SNAPSHOT IS A CREDENTIAL — it carries key PRESENCE and at most a four
     * character hint by construction on the far side — and this method reads one field out of it
     * and drops the rest, so there is nothing here to leak even if that ever changed.
     *
     * BLOCKS. Callers put it on a background thread.
     */
    fun aiRoutingApp(): String? {
        val raw = client.aiRoutingSnapshot() ?: return null
        return runCatching { JSONObject(raw).optString("app") }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    /**
     * What to name the provider in progress and error text — the label of THIS application's
     * chosen provider, not the serving application's.
     *
     * The two can differ, and naming the peer's provider over a run routed to cloud-writer's would
     * be a progress line that lies about where the request went. Null when nothing is bound, which
     * the run itself reports.
     *
     * BLOCKS. Callers put it on a background thread.
     */
    fun providerLabel(): String? = client.providerLabelFor(WriterPrefs.providerId(app))

    /**
     * Send [text] through [tool] and hand the outcome to [onDone] on the main thread.
     *
     * A second tap while one is in flight is REFUSED WITH A REASON rather than queued or ignored:
     * two runs racing to write the same output box is the bug, and an ignored tap is the silence
     * this class does not allow.
     */
    fun run(tool: WriterTool, text: String, onDone: (WriterOutcome) -> Unit) {
        if (busy != null) {
            onDone(WriterOutcome(tool, null, app.getString(R.string.run_busy)))
            return
        }
        if (text.isBlank()) {
            onDone(WriterOutcome(tool, null, app.getString(R.string.run_nothing_to_send)))
            return
        }
        // THE ONE TOOL THIS BUILD MAY BE UNABLE TO HONOUR, refused before anything is spent.
        //
        // Grammar Check is the `grammar` style and nothing else, so a registry with no such style
        // leaves this application unable to do the tool. It says so instead of falling back to the
        // default style, which would send "improve this text" under a button labelled Grammar
        // Check and hand back a rewritten paragraph to someone who asked for their commas fixed.
        if (tool == WriterTool.GRAMMAR && WriterRegistry.grammarPrompt() == null) {
            onDone(WriterOutcome(tool, null, app.getString(R.string.run_grammar_unavailable)))
            return
        }
        busy = tool
        worker.execute {
            val result = runTool(tool, text)
            busy = null
            main.post { onDone(WriterOutcome(tool, result.text, result.error)) }
        }
    }

    /**
     * THE ROUTING DECISION, AND THE ONLY ONE IN THIS APPLICATION.
     *
     * Every call carries THIS APPLICATION'S settings — its composed prompt, its provider, and the
     * model chosen FOR THIS TOOL — rather than an empty argument meaning "use yours". That empty
     * argument is the coupling: it makes the serving application's store the only store, and this
     * application's pages a view of settings it cannot change. It is also what makes the owner's
     * per-tool model request real rather than decorative: `WriterPrefs.modelFor(tool)` is resolved
     * separately on each of the three branches below, so Summary can run on a cheap model while
     * Enhance runs on a strong one.
     *
     * Runs on [worker]. Blocking, by contract.
     */
    private fun runTool(tool: WriterTool, text: String): TextTools.Result {
        // Bring the owner's existing configuration across the first time, before the first run
        // reads it. Idempotent, and a no-op once seeded.
        WriterPrefs.seedFromServingApp(app, client)
        val provider = WriterPrefs.providerId(app)
        return when (tool) {
            WriterTool.ENHANCE -> client.enhanceWith(
                text,
                WriterPrefs.enhancePrompt(app),
                provider,
                WriterPrefs.modelFor(app, WriterTool.ENHANCE, provider),
            )
            WriterTool.GRAMMAR -> client.enhanceWith(
                text,
                // Non-null: refused in run() above when the registry carries no grammar style.
                WriterRegistry.grammarPrompt().orEmpty(),
                provider,
                WriterPrefs.modelFor(app, WriterTool.GRAMMAR, provider),
            )
            WriterTool.SUMMARY -> client.summariseWith(
                text,
                WriterPrefs.summaryPrompt(app),
                WriterPrefs.summaryWantsBullets(app),
                provider,
                WriterPrefs.modelFor(app, WriterTool.SUMMARY, provider),
            )
            // NOT the chat provider, and it takes no model: a translation billed to the LLM
            // account, or a rewrite sent through a translator, is a crossing the reply cannot be
            // read back to detect.
            WriterTool.TRANSLATE -> client.translate(text, WriterPrefs.translateTarget(app))
        }
    }
}
