package app.sterna.ui.text

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
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
 * curriculum vitae, and not resuming anything that was paused. It shares ENHANCE's provider, key,
 * model and error wording on purpose (one binder method, `summarise`, over the same engine); what
 * it does NOT share is the prompt, which comes from the keyboard's summary registry.
 */
enum class TextTool { ENHANCE, TRANSLATE, RESUME }

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
 */
class TextToolRunner internal constructor(private val client: TextToolsClient) {

    /** Non-null while a call is in flight; the screen shows progress for exactly this tool. */
    var busy by mutableStateOf<TextTool?>(null)
        private set

    /** The last finished run, until the screen dismisses it. */
    var outcome by mutableStateOf<TextToolOutcome?>(null)
        private set

    /** Named in the progress line. Null when nothing is bound, which the run itself reports. */
    fun providerLabel(): String? = client.enhanceProviderLabel()

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
        if (busy != null) return
        if (text.isBlank()) {
            outcome = TextToolOutcome(tool, null, "Nothing to send — the message has no text outside its quoted history")
            return
        }
        busy = tool
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                // THE routing decision, and the only one. Enhance goes to the OpenRouter
                // provider; Translate goes to the translation library. They are different
                // engines with different costs, and a reply does not say which one answered.
                when (tool) {
                    TextTool.ENHANCE -> client.enhance(text)
                    TextTool.TRANSLATE -> client.translate(text)
                    TextTool.RESUME -> client.summarise(text)
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
 */
@Composable
fun rememberTextToolRunner(): TextToolRunner {
    val app = LocalContext.current.applicationContext
    return remember(app) { TextToolRunner(sharedClient(app)) }
}

private var shared: TextToolsClient? = null

@Synchronized
private fun sharedClient(app: android.content.Context): TextToolsClient =
    shared ?: TextToolsClient(app).also { shared = it }

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
