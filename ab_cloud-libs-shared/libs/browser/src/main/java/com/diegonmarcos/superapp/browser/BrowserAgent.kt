package com.diegonmarcos.superapp.browser

import org.json.JSONArray
import org.json.JSONObject

/** #802 I9 one declared page tool (build.json::ui.browser.addons[ai].tools[]). */
data class AgentTool(
    val id: String,
    val label: String,
    val description: String,
    /** JSON-Schema `properties` of its arguments. */
    val params: JSONObject,
    /** It changes the page, the tabs or his data. */
    val mutating: Boolean,
    /** He is asked first (every mutating tool must be). */
    val confirm: Boolean,
    /** model | on_device */
    val route: String,
) {
    /** The JSON-Schema object the model is given. */
    fun schema(): JSONObject = JSONObject().put("type", "object").put("properties", JSONObject(params.toString()))
}

/**
 * #802 I9 what the agent may NEVER do, whatever the model asks and whatever he confirms:
 * write a password or a card field, or leave the tab's site without naming the new host.
 * Pure; JVM-tested ([BrowserAgentTest]).
 */
object AgentPolicy {
    /** A selector or field key that names a secret field. */
    private val SECRET = Regex("pass(word|wd)?|pwd|cc-?(number|num|csc|exp)|card|cvc|cvv|csc|security.?code|iban|pin\\b|otp|one-time", RegexOption.IGNORE_CASE)

    /** Why [tool] with [args] is refused outright, or null when it may proceed (to confirmation, if declared). */
    fun refuse(tool: String, args: JSONObject): String? = when (tool) {
        "fill_form" -> {
            val fields = args.optJSONObject("fields")
            when {
                fields == null || fields.length() == 0 -> "fill_form needs a fields map {selector: value}"
                fields.keys().asSequence().any { SECRET.containsMatchIn(it) } ->
                    "refused: the assistant never fills passwords, card numbers or codes (Cloud Vault does that)"
                else -> null
            }
        }
        "click" -> if (args.optString("css").isBlank()) "click needs css" else null
        "navigate", "open_tab" -> if (!args.optString("url").let { it.startsWith("https://") || it.startsWith("http://") })
            "${tool} needs an http(s) url" else null
        else -> null
    }

    /** True when [url] leaves [currentUrl]'s host: the confirmation names the new host. */
    fun leavesSite(currentUrl: String?, url: String): Boolean {
        val a = BrowserSitePolicy.hostOf(currentUrl.orEmpty())
        val b = BrowserSitePolicy.hostOf(url)
        return a.isEmpty() || a != b
    }

    /** The sentence the confirmation sheet shows. */
    fun describe(tool: AgentTool, args: JSONObject, currentUrl: String?): String {
        val host = BrowserSitePolicy.hostOf(currentUrl.orEmpty()).ifEmpty { "this page" }
        return when (tool.id) {
            "click" -> "The assistant wants to click `${args.optString("css")}` on $host."
            "fill_form" -> "The assistant wants to type into ${args.optJSONObject("fields")?.length() ?: 0} field(s) on $host: " +
                (args.optJSONObject("fields")?.keys()?.asSequence()?.joinToString(", ") ?: "")
            "navigate" -> args.optString("url").let { u ->
                if (leavesSite(currentUrl, u)) "The assistant wants to leave $host for ${BrowserSitePolicy.hostOf(u)} ($u)."
                else "The assistant wants to open $u."
            }
            else -> "The assistant wants to ${tool.label.lowercase()}: ${args}"
        }
    }
}

/**
 * #802 I9 THE AGENT, as a pure state machine: model turn → tool calls → (confirmation) →
 * tool results → model turn, until the model answers in words, a mutating call waits for
 * his decision, or the per-turn cap is hit. The model, the tool runner and his decisions
 * are passed in, so the JVM suite drives it with fakes. It never sees a vault or profile
 * value: no tool reads one.
 */
class AgentLoop(private val tools: List<AgentTool>, private val maxCallsPerTurn: Int) {

    data class Call(val id: String, val name: String, val args: JSONObject)
    data class ModelTurn(val text: String?, val calls: List<Call>, val error: String? = null)

    enum class Decision { ALLOW, DENY }

    sealed class Outcome {
        data class Answer(val text: String) : Outcome()
        /** Waiting for him: the sheet shows [sentence]; resume with the decision for [call]. */
        data class Pending(val call: Call, val sentence: String) : Outcome()
        data class Failed(val error: String) : Outcome()
    }

    /** The transcript, in wire shape (role/content, assistant tool_calls, tool results). */
    val messages = JSONArray()
    /** Calls waiting for his decision, and the ones he has decided. */
    private var waiting: List<Call> = emptyList()
    private val decisions = HashMap<String, Decision>()

    val pending: Call? get() = waiting.firstOrNull()

    fun tool(name: String) = tools.firstOrNull { it.id == name }

    fun decide(callId: String, d: Decision) { decisions[callId] = d }

    fun user(text: String) { messages.put(JSONObject().put("role", "user").put("content", text)) }

    /**
     * Run until an answer, a pending confirmation, or a failure. [model] gets the transcript;
     * [run] executes a call and answers its result text; [currentUrl] is the active tab's.
     */
    fun step(model: (JSONArray) -> ModelTurn, run: (Call) -> String, currentUrl: () -> String?): Outcome {
        var used = 0
        while (true) {
            // Calls left from a turn that stopped for a confirmation come first.
            if (waiting.isEmpty()) {
                val t = model(messages)
                if (t.error != null) return Outcome.Failed(t.error)
                if (t.calls.isEmpty()) {
                    val text = t.text.orEmpty()
                    messages.put(JSONObject().put("role", "assistant").put("content", text))
                    return Outcome.Answer(text)
                }
                messages.put(JSONObject().put("role", "assistant").put("content", t.text ?: "").put("tool_calls", JSONArray(t.calls.map { c ->
                    JSONObject().put("id", c.id).put("type", "function")
                        .put("function", JSONObject().put("name", c.name).put("arguments", c.args.toString()))
                })))
                waiting = t.calls
            }
            var capped = false
            while (waiting.isNotEmpty()) {
                val c = waiting.first()
                val tool = tool(c.name)
                val result = when {
                    used >= maxCallsPerTurn -> { capped = true; "refused: the per-turn limit of $maxCallsPerTurn tool calls is reached; answer with what you have" }
                    tool == null -> "error: no tool named ${c.name}"
                    else -> AgentPolicy.refuse(c.name, c.args) ?: if (tool.confirm) when (decisions[c.id]) {
                        null -> return Outcome.Pending(c, AgentPolicy.describe(tool, c.args, currentUrl()))
                        Decision.DENY -> "denied: the user refused this action; do not retry it"
                        Decision.ALLOW -> run(c)
                    } else run(c)
                }
                if (!capped) used++
                messages.put(JSONObject().put("role", "tool").put("tool_call_id", c.id).put("content", result))
                waiting = waiting.drop(1)
            }
            // The model kept calling past the cap: it has been told; stop rather than loop.
            if (capped) return Outcome.Answer("(stopped: the per-turn limit of $maxCallsPerTurn tool calls was reached)")
        }
    }

    companion object {
        fun parseTools(arr: JSONArray?): List<AgentTool> = (0 until (arr?.length() ?: 0)).mapNotNull { arr!!.optJSONObject(it) }.map { o ->
            AgentTool(o.optString("id"), o.optString("label", o.optString("id")), o.optString("description"),
                o.optJSONObject("params") ?: JSONObject(), o.optBoolean("mutating", false), o.optBoolean("confirm", false),
                o.optString("route", "model"))
        }.filter { it.id.isNotBlank() }

        /** One model call's arguments text as JSON (an unreadable one becomes {} — the policy then refuses what needs args). */
        fun args(text: String?): JSONObject = runCatching { JSONObject(text ?: "{}") }.getOrElse { JSONObject() }
    }
}

/**
 * #802 I9 the seam between the shared browser screen and the app that runs the agent (the
 * model client lives in the app: libs:browser links no model). The app sets both; the screen
 * calls them off the main thread and shows what comes back. Unset = the add-on's rows say so.
 */
object BrowserAgentHost {
    /** One message from him → the answer text (or the waiting confirmation's sentence). */
    @Volatile var ask: ((text: String) -> String)? = null
    /** His decision on a waiting call → the answer text once the turn finishes (or the next confirmation). */
    @Volatile var decide: ((callId: String, allow: Boolean) -> String)? = null
}
