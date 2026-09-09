package app.sterna.ui.text

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Translate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import app.sterna.R
import com.diegonmarcos.superapp.texttools.TextToolsClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Which tool, and therefore WHICH ENGINE. The pairing is the point of the enum: [ENHANCE] and
 * [RESUME] both go to the OpenRouter model chosen in AI Routing, [TRANSLATE] to the translation
 * library, and [TextToolRunner.run] is the single place that maps one to the other so there is
 * exactly one line in this app where they could be crossed — and one line to assert on.
 *
 * [RESUME] is "AI Resume", the owner's name for SUMMARISE — condense this message. Not a
 * curriculum vitae, and not resuming anything that was paused. It shares ENHANCE's provider, key
 * and error wording on purpose (one binder, one engine); what it does NOT share is the prompt,
 * which comes from THIS APP'S own summary registry — `build.json::mail_ai`, editable on this
 * app's Text Resume page, and unrelated to the keyboard's copy of the same prompts.
 *
 * [label] and [icon] live here rather than at each call site because a tool that reads "Translate"
 * on one screen and shows a different glyph on another is two tools as far as the user is
 * concerned. [inOverflow] says whether the tool is DRAWN as an entry in a screen's overflow menu;
 * [RESUME] is not, because it is started from its own toolbar icon and reports into the box under
 * the sender rather than into [TextToolPanel]'s dialog. That placement difference was already
 * expressed as an early return inside the panel — it is stated here instead, once.
 */
enum class TextTool(
    @StringRes val label: Int,
    val icon: ImageVector,
    val inOverflow: Boolean,
) {
    ENHANCE(R.string.text_tool_enhance, Icons.Filled.AutoFixHigh, inOverflow = true),
    TRANSLATE(R.string.text_tool_translate, Icons.Filled.Translate, inOverflow = true),
    RESUME(R.string.text_tool_resume, Icons.Filled.AutoAwesome, inOverflow = false),
}

/**
 * WHICH TOOLS A SURFACE OFFERS. This is the whole rule, and it is stated exactly once.
 *
 * It used to be nowhere: the reader hand-built its menu and the composer hand-built its own, so
 * "does this screen have Enhance" was answered in two places that had no way to disagree loudly.
 * That is how an action ends up rendered on a screen whose handler was removed, or removed from a
 * screen whose handler still answers.
 *
 * The split is not cosmetic, and it is the reason this type exists rather than a boolean:
 *
 *   [READ]     a RECEIVED message. Enhance REWRITES a text into a better version of itself, and a
 *              received message is a record of what somebody else sent — there is nothing to
 *              improve and nowhere to save an improvement to. [TextTool.RESUME] is the read-side
 *              counterpart: it produces a separate, shorter text ABOUT the message, which is a
 *              thing you can honestly do to somebody else's words.
 *   [COMPOSE]  a DRAFT. The text is the user's own and they can still change it, so a rewrite has
 *              both a point and somewhere to land. Summarising your own unsent draft does not.
 *
 * [TextTool.TRANSLATE] is on both because it is meaningful in both directions: understanding a
 * message you received and writing one somebody else can read are the same need pointed two ways.
 *
 * Every renderer asks this list what to draw, and [TextToolRunner.run] REFUSES anything not on it,
 * so a menu entry and its handler cannot drift apart — removing a tool here removes the button and
 * closes the path behind it in the same edit.
 */
enum class TextToolSurface(val tools: List<TextTool>) {
    READ(listOf(TextTool.RESUME, TextTool.TRANSLATE)),
    COMPOSE(listOf(TextTool.ENHANCE, TextTool.TRANSLATE)),
}

/** How a finished run ended. Exactly one of [text] and [error] is set. */
class TextToolOutcome(val tool: TextTool, val text: String?, val error: String?)

/**
 * Runs a tool and holds enough state for the screen to say what is happening.
 *
 * These are network calls on a phone: slow, and able to fail. The keyboard's Enhance bar
 * settled this already and this follows it rather than inventing a second shape — the wait
 * is visible and NAMES THE PROVIDER (so a slow call reads as a slow provider rather than a
 * stuck app), and a failure surfaces the engine's own reason verbatim instead of a generic
 * apology. There is no silent path out of [run]: every branch ends in an outcome.
 *
 * A runner belongs to ONE [surface], and that is what makes [TextToolSurface] a guard rather than
 * a suggestion — see [run].
 */
class TextToolRunner internal constructor(
    private val client: TextToolsClient,
    val surface: TextToolSurface,
    /**
     * Application context, for [MailTextToolsPrefs] — THIS app's own text-tool settings.
     *
     * A runner used to need no context at all, because it had no settings to read: every choice
     * behind a run lived in the keyboard and the keyboard applied it. It needs one now, and that
     * is the change the owner asked for.
     */
    private val context: android.content.Context,
) {

    /** Non-null while a call is in flight; the screen shows progress for exactly this tool. */
    var busy by mutableStateOf<TextTool?>(null)
        private set

    /** The last finished run, until the screen dismisses it. */
    var outcome by mutableStateOf<TextToolOutcome?>(null)
        private set

    /**
     * Named in the progress line. Null when nothing is bound, which the run itself reports.
     *
     * Asks for the label of THIS app's chosen provider, not the serving app's. The two can differ
     * now, and naming the keyboard's provider over a run routed to cloud-mail's would be a
     * progress line that lies about where the request went.
     */
    fun providerLabel(): String? = client.providerLabelFor(MailTextToolsPrefs.providerId(context))

    fun dismiss() { outcome = null }

    /**
     * Send [text] through [tool] and land the result in [outcome].
     *
     * The binder call is BLOCKING and goes on the IO dispatcher; nothing here touches the
     * engines directly, so this app never holds the provider key and cannot pick the model.
     *
     * A second tap while one is in flight is ignored rather than queued: two rewrites racing
     * to write the same field is the bug, not a feature.
     */
    fun run(scope: CoroutineScope, tool: TextTool, text: String) {
        // THE guard, and the reason it lives in front of the engines rather than in front of a
        // menu. Hiding a button only stops the taps you thought of; every other route in — a
        // stale composable, a shortcut, a caller written next year — arrives HERE. Refusing at
        // this line is what makes "Enhance is not on the read surface" true of the app rather
        // than true of one screen's overflow menu. It reports rather than throws: an unreachable
        // branch that crashes the app in a user's hand is a worse trade than one that says why.
        if (tool !in surface.tools) {
            outcome = TextToolOutcome(tool, null, "$tool is not offered on the ${surface.name.lowercase()} surface")
            return
        }
        if (busy != null) return
        if (text.isBlank()) {
            outcome = TextToolOutcome(tool, null, "Nothing to send — the message has no text outside its quoted history")
            return
        }
        busy = tool
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                // Bring the owner's existing configuration across the first time, before the
                // first run reads it. Idempotent, and a no-op once seeded.
                MailTextToolsPrefs.seedFromKeyboard(context, client)
                val provider = MailTextToolsPrefs.providerId(context)
                val model = MailTextToolsPrefs.modelId(context, provider)
                // THE routing decision, and the only one. Enhance goes to the OpenRouter
                // provider; Translate goes to the translation library. They are different
                // engines with different costs, and a reply does not say which one answered.
                //
                // Every call carries THIS APP'S settings — its composed prompt, its provider, its
                // model, its translation target — rather than an empty argument meaning "use
                // yours". That empty argument was the coupling: it made the keyboard's store the
                // only store, and cloud-mail's Text pages a view of settings it could not change.
                when (tool) {
                    TextTool.ENHANCE -> client.enhanceWith(
                        text,
                        MailTextToolsPrefs.enhancePrompt(context),
                        provider,
                        model,
                    )
                    TextTool.TRANSLATE -> client.translate(text, MailTextToolsPrefs.translateTarget(context))
                    TextTool.RESUME -> client.summariseWith(
                        text,
                        MailTextToolsPrefs.summaryPrompt(context),
                        MailTextToolsPrefs.summaryWantsBullets(context),
                        provider,
                        model,
                    )
                }
            }
            outcome = TextToolOutcome(tool, result.text, result.error)
            busy = null
        }
    }
}

/**
 * One runner per screen, over one binding per process.
 *
 * The client binds on construction and rebinds on use, so it is deliberately kept on the
 * APPLICATION context and not rebuilt per composition — a fresh bind for every recomposition
 * would spend the whole rate limit reconnecting.
 *
 * [surface] is required, not defaulted: a screen that does not say which set of tools it offers
 * has not decided, and defaulting would decide for it silently.
 */
@Composable
fun rememberTextToolRunner(surface: TextToolSurface): TextToolRunner {
    val app = LocalContext.current.applicationContext
    return remember(app, surface) { TextToolRunner(sharedClient(app), surface, app) }
}

private var shared: TextToolsClient? = null

/**
 * THE binding, one per process — the runner's, and the settings screens'.
 *
 * The screens need it too, and for one thing only: seeding cloud-mail's own store from whatever
 * the owner had already configured, once. A second client for that would open a second binding to
 * the same service and spend its own rebind budget, so they share this one.
 */
@Synchronized
internal fun textToolsClient(app: android.content.Context): TextToolsClient =
    shared ?: TextToolsClient(app).also { shared = it }

private fun sharedClient(app: android.content.Context): TextToolsClient = textToolsClient(app)

/**
 * The reader's ONE runner, reachable from both places on the screen that need it.
 *
 * The toolbar and the message header live in different subtrees — the toolbar is fixed chrome at
 * the pager level (#62), the header is inside the swiped page — but AI Resume is started from the
 * first and shown in the second, over one call. Passing a runner down through MessagePage,
 * MessageContent, ConversationBody and MessageHeader would add a parameter to four signatures,
 * two of which are pinned line for line by tests, to carry a value none of them look at.
 *
 * Provided once per reader, so a page and the bar above it are always talking about the same call
 * in flight. Deliberately without a default: a composable that reads this outside the reader has a
 * bug, and an error here says so at the first frame rather than silently starting a second runner
 * whose progress nobody would ever see.
 */
val LocalTextToolRunner = compositionLocalOf<TextToolRunner> {
    error("No TextToolRunner provided — MessagePager provides one for the whole reader")
}
