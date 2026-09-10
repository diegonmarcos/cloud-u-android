package com.diegonmarcos.superapp.ai

import android.util.Base64
import com.diegonmarcos.superapp.BuildConfig
import org.json.JSONObject

/**
 * WHO takes part in AI Routing, and WHAT the serving app currently holds.
 *
 * Two different kinds of fact live here and the split is the point of the file.
 *
 * The ROSTER — which packages are in AI Routing and what part each plays — is DECLARED, in
 * `build.json::ui.ai_routing_peers`, baked to [BuildConfig.UI_AI_ROUTING_PEERS_B64]. It is
 * declared because it cannot be discovered: every constellation app ships the fleet provider
 * FleetPeers keys off, so that marker answers "who is in the fleet" and not "who does AI
 * Routing", and using it would fill this page with apps that have nothing to show.
 *
 * The SNAPSHOT — which models exist, which one is chosen, whether a key is held — is READ FROM
 * THE SERVING APP over ITextTools, every time, and is never stored here. That is the whole
 * discipline this file exists to keep: a console that cached the model list would be a second
 * registry, and a second registry is what put a stale price and then a wrong unit on this
 * fleet's screens inside one week. There is no model id, model name or price anywhere in this
 * app's source; if it is on the screen, it came from the registry that owns it.
 */
object AiFleetRoster {

    /** What part a package plays in AI Routing — the `role` field of a roster entry. */
    enum class Role {
        /** Holds the registry and the provider key, and answers the binder. Exactly one app. */
        SERVES,

        /** Calls the binder and holds its own model choice, but no key of its own. */
        ROUTES,

        /** Holds neither, and exists to show and edit the others. This app. */
        CONSOLE,

        /** A role this build does not know — a roster edited by a newer build than this one. */
        UNKNOWN,
    }

    /** One declared participant. [label] is the roster's own name for it, not the launcher's. */
    class Peer(val packageName: String, val label: String, val role: Role)

    /**
     * How a peer is actually doing, right now, on this phone.
     *
     * EVERY ONE OF THESE IS A STATED OUTCOME AND NONE OF THEM IS A SPINNER. The failure the owner
     * meets first on a page like this is a peer that never answers and a page that waits for it, so
     * each way of not working gets its own name and its own sentence on screen. They are also
     * genuinely different repairs — install, open, update, paste a key — and a page that collapsed
     * them into "unavailable" would send the owner to the wrong one.
     */
    enum class PeerState {
        /** Not on the phone. The repair is an install. */
        NOT_INSTALLED,

        /** Installed, but the binder never connected — force-stopped, or still starting. */
        UNREACHABLE,

        /** Connected, and answered nothing: a build older than the call. The repair is an update. */
        TOO_OLD,

        /** Answered, and holds no provider key yet. Not a failure — an empty account. */
        NO_KEY,

        /** Answered and holds a key. */
        READY,

        /** Took longer than the deadline. The peer is wedged; the page moved on without it. */
        TIMED_OUT,

        /** Declared in the roster but not a peer this app can ask — see [Role.CONSOLE]. */
        NOT_APPLICABLE,
    }

    /**
     * One model row, exactly as the serving app's registry holds it. Every field is copied across
     * the binder and none is computed here.
     *
     * PRICES ARE UNITED STATES DOLLARS PER MILLION TOKENS. The names say so because this number
     * has been wrong on this fleet's screens twice — once as a per-token figure and once as cents —
     * and both times a bare `prompt` was what let it happen. Null means the registry quotes no
     * price for this model, which is a real state (the mesh bridge has none) and not a zero.
     */
    class ModelRow(
        val id: String,
        val name: String,
        val open: Boolean,
        val parametersInBillions: Int?,
        val quantisations: List<String>,
        val trainedFor: List<String>,
        val note: String?,
        val promptUsdPerMillionTokens: Double?,
        val completionUsdPerMillionTokens: Double?,
    )

    /** One provider of the serving app's registry, with what that app currently holds for it. */
    class ProviderRow(
        val id: String,
        val label: String,
        val needsToken: Boolean,
        val defaultModel: String,
        val chosenModel: String,
        val keyPresent: Boolean,
        /** At most the key's last four characters, or empty. Never enough to spend. */
        val keyHint: String,
        val pricingAsOf: String?,
        val models: List<ModelRow>,
    )

    /** The serving app's whole AI-Routing state for one render. Never cached. */
    class Snapshot(
        val servingPackage: String,
        val defaultProvider: String,
        val providers: List<ProviderRow>,
    )

    /**
     * The declared roster, in declaration order.
     *
     * A ROSTER THAT WILL NOT PARSE IS AN EMPTY ROSTER, not a crash. This blob is produced by the
     * build from build.json, so a broken one is a build mistake and not something a user did — but
     * Configs is where the owner goes to fix a misbehaving phone, and a Configs page that crashes
     * on open takes away the tool they came for. The page shows the empty case and says so.
     */
    val peers: List<Peer> by lazy {
        runCatching {
            val text = String(Base64.decode(BuildConfig.UI_AI_ROUTING_PEERS_B64, Base64.NO_WRAP), Charsets.UTF_8)
            val array = org.json.JSONArray(text)
            (0 until array.length()).map { index ->
                val entry = array.getJSONObject(index)
                Peer(
                    entry.getString("package"),
                    entry.optString("label").ifEmpty { entry.getString("package") },
                    roleOf(entry.optString("role")),
                )
            }
        }.getOrElse { emptyList() }
    }

    private fun roleOf(declared: String): Role = when (declared) {
        "serves" -> Role.SERVES
        "routes" -> Role.ROUTES
        "console" -> Role.CONSOLE
        // A role this build has never heard of is reported as unknown rather than guessed into one
        // of the others: guessing SERVES would make the page offer to write a key into an app that
        // may not hold one, and guessing ROUTES would hide a serving app.
        else -> Role.UNKNOWN
    }

    /**
     * Parse what the serving app answered. Null in, null out — and null out for anything that does
     * not parse, which is the same answer as "too old", deliberately.
     *
     * A REPLY THIS APP CANNOT READ IS NOT A REPLY. It crossed a process boundary from a build that
     * ships separately from this one, so a shape older or newer than expected is routine rather
     * than exceptional; treating it as "the peer did not answer" puts the owner in front of the
     * update instruction, which is the true repair either way.
     */
    fun parseSnapshot(json: String?): Snapshot? {
        if (json.isNullOrBlank()) return null
        return runCatching {
            val root = JSONObject(json)
            val providersJson = root.getJSONArray("providers")
            val providers = (0 until providersJson.length()).map { index ->
                val provider = providersJson.getJSONObject(index)
                val modelsJson = provider.getJSONArray("models")
                ProviderRow(
                    id = provider.getString("id"),
                    label = provider.optString("label").ifEmpty { provider.getString("id") },
                    needsToken = provider.optBoolean("needs_token", true),
                    defaultModel = provider.optString("default_model"),
                    chosenModel = provider.optString("chosen_model"),
                    keyPresent = provider.optBoolean("key_present"),
                    keyHint = provider.optString("key_hint"),
                    pricingAsOf = provider.optString("pricing_as_of").ifEmpty { null },
                    models = (0 until modelsJson.length()).map { modelIndex ->
                        val model = modelsJson.getJSONObject(modelIndex)
                        ModelRow(
                            id = model.getString("id"),
                            name = model.optString("name").ifEmpty { model.getString("id") },
                            open = model.optBoolean("open"),
                            parametersInBillions = model.optInt("params_b", 0).takeIf { it > 0 },
                            quantisations = model.stringList("quant"),
                            trainedFor = model.stringList("trained_for"),
                            note = model.optString("note").ifEmpty { null },
                            // optDouble answers NaN for an absent or null field, and NaN reaching a
                            // price column renders as "NaN" rather than as the unknown marker the
                            // registry meant. Turned into null here, once, at the boundary.
                            promptUsdPerMillionTokens = model.optDouble("prompt_usd_per_million").takeIf { !it.isNaN() },
                            completionUsdPerMillionTokens = model.optDouble("completion_usd_per_million").takeIf { !it.isNaN() },
                        )
                    },
                )
            }
            Snapshot(root.optString("app"), root.optString("default_provider"), providers)
        }.getOrNull()
    }

    private fun JSONObject.stringList(key: String): List<String> =
        optJSONArray(key)?.let { array -> (0 until array.length()).map { array.getString(it) } } ?: emptyList()
}
