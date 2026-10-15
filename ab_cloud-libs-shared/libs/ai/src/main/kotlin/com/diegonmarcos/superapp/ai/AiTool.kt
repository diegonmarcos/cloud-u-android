package com.diegonmarcos.superapp.ai

import org.json.JSONObject

/**
 * The tool-plugin interface: an app hands [AiStandardHost] tools and the model may call them. This lib
 * ships NO tool (no per-app tools yet): an app that wants one implements this and passes it in.
 * A [mutating] tool never runs on the model's say-so alone: the host puts the consent card
 * ([AiConsentCard], text from [sentence]) on screen and runs it only after Allow; Deny tells the model "denied".
 */
interface AiTool {
    /** The function name the model calls. */
    val name: String
    val description: String
    /** The JSON-Schema object (text) of the parameters. */
    val schema: String
    /** True for anything that changes state: it needs the user's consent each time. */
    val mutating: Boolean get() = false
    /** The exact action in words, for the consent card ("Click the Buy button on example.com"). */
    fun sentence(args: JSONObject): String = name
    /** Runs the tool; the result goes back to the model as text. Blocking. */
    fun run(args: JSONObject): String
}
