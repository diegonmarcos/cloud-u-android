package com.diegonmarcos.superapp.ai

import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.texttools.TextToolsClient
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Configs ▸ AI ▸ Tokens Fleet — the one place the owner can see and set the AI-Routing account and
 * model for the whole fleet, this app included.
 *
 * WHERE EVERYTHING ON THIS SCREEN COMES FROM, because none of it is authored here:
 *
 *  - The ROSTER of participating apps is `build.json::ui.ai_routing_peers` — see [AiFleetRoster].
 *  - The MODEL LIST, the prices, the chosen model and whether a key is held are read from the
 *    SERVING APP's own registry over [TextToolsClient.aiRoutingSnapshot], on every render.
 *
 * THERE IS NO MODEL LIST IN THIS APP AND THERE MUST NEVER BE ONE. The page this replaced carried a
 * hand-written table of a dozen models with hand-written prices, in three different units, and it
 * was already wrong: that is exactly the defect that produced this fleet's stale-price and
 * wrong-unit bugs inside one week. A console shows what the registry holds or it shows nothing.
 *
 * PRICES ARE PRINTED AS UNITED STATES DOLLARS PER MILLION TOKENS, to three decimals, with the unit
 * in the heading — the registry's own unit, unconverted. Three decimals because that is what
 * separates every price the registry holds without two of them rounding into one cell, and no
 * scaling because a column the screen scales disagrees with the provider's published price list and
 * the reader cannot tell which of the two is lying.
 *
 * WHAT THIS PAGE NEVER DOES WITH A KEY: it does not log one, it does not toast one, it does not
 * store one, and it does not put one in an Intent. A key exists here only inside the one binder
 * reply that answered a deliberate reveal, on the widget that asked for it. This fleet uploads
 * logcat from its own diagnostics screens, so a key reaching the log is a key leaving the phone.
 */
class AiTokensFleetFragment : Fragment() {

    private lateinit var client: TextToolsClient
    private lateinit var column: LinearLayout
    private val mainThread = Handler(Looper.getMainLooper())

    /**
     * Where every binder call runs, and why it is a GROWING pool rather than one thread.
     *
     * The calls BLOCK — [TextToolsClient] says so and does no threading of its own — so running one
     * on the main thread would freeze Configs behind a peer that has wedged. A single worker thread
     * would not be enough either, and the reason is the whole design of [withDeadline]: applying a
     * deadline means one thread waits while another makes the call, and a wedged call must not
     * leave the retry queued behind it forever. A pool that can grow gives the waiter its thread
     * and gives the next attempt a fresh one.
     *
     * The cost, stated: a peer that never answers strands the thread its call is parked on. That is
     * bounded by how many times the owner presses retry, and it is the right trade against a page
     * that cannot be retried at all.
     */
    private val calls = Executors.newCachedThreadPool()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        val context = inflater.context
        client = TextToolsClient(context)
        val (scroll, page) = AiViews.page(context)
        column = page
        renderLoading()
        refresh()
        return scroll
    }

    override fun onDestroy() {
        super.onDestroy()
        // Interrupts the waiters and lets go of the pool when the page closes, so a wedged keyboard
        // costs the threads already parked on it rather than a fresh set on every visit to this tab.
        calls.shutdownNow()
    }

    private fun renderLoading() {
        val context = requireContext()
        column.removeAllViews()
        column.addView(AiViews.heading(context, getString(R.string.ai_tokens_title)))
        column.addView(AiViews.caption(context, getString(R.string.ai_tokens_intro)))
        column.addView(AiViews.status(context, getString(R.string.ai_peer_asking), needsAttention = false))
    }

    /**
     * Ask the serving app for its routing state, WITH A DEADLINE, and render whatever came back.
     *
     * THE DEADLINE IS THE POINT OF THIS METHOD. A binder call has no timeout of its own: a peer
     * that has wedged never answers and never throws, so without a deadline this page would sit on
     * "asking…" for as long as the owner was willing to look at it. That is the failure the owner
     * meets first on a settings page that talks to another app, and it is the one this is built to
     * make impossible — the wait ends, and it ends with a sentence.
     */
    private fun refresh() {
        val installed = client.isServingAppInstalled()
        withDeadline({ client.aiRoutingSnapshot() }) { answer, timedOut ->
            val snapshot = AiFleetRoster.parseSnapshot(answer)
            val state = when {
                !installed -> AiFleetRoster.PeerState.NOT_INSTALLED
                timedOut -> AiFleetRoster.PeerState.TIMED_OUT
                // Installed, and the binder never came up: force-stopped, or still starting. A
                // different repair from an update, so it gets a different answer.
                !client.isConnected() -> AiFleetRoster.PeerState.UNREACHABLE
                // Bound, and answered nothing this app could read — a build older than the call.
                snapshot == null -> AiFleetRoster.PeerState.TOO_OLD
                else -> AiFleetRoster.PeerState.READY
            }
            render(state, snapshot)
        }
    }

    /**
     * Run one blocking peer call with a deadline, and hand the outcome back on the main thread.
     *
     * EVERY CALL TO ANOTHER APP ON THIS PAGE GOES THROUGH HERE, so there is exactly one place that
     * can forget the deadline and exactly one place to read to know that none of them did. A binder
     * call has no timeout of its own — a peer that has wedged never answers and never throws — so
     * without this the page would sit on "asking…" for as long as the owner was willing to look at
     * it. That is the failure the owner meets first on a settings page that talks to another app.
     *
     * [onResult] runs on the main thread and only while the fragment is still attached; `timedOut`
     * tells it the wait ended rather than the peer answering, which is a different sentence on
     * screen and not the same thing as an answer of null.
     */
    private fun <T> withDeadline(call: () -> T, onResult: (T?, Boolean) -> Unit) {
        // The page can be closed between a callback deciding to ask again and this running, and a
        // pool that has been shut down REJECTS rather than ignoring — which would crash Configs on
        // the way out of it. Nothing is owed to a fragment that is already gone.
        if (calls.isShutdown) return
        // Submitted first, waited on from a second thread: the deadline cannot be enforced by the
        // same thread that is blocked making the call.
        val task = calls.submit<T> { call() }
        calls.execute {
            val outcome = runCatching { task.get(PEER_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
            val timedOut = outcome.exceptionOrNull() is TimeoutException
            // Let go of the CALL, not just the wait: the peer may answer eventually and nothing
            // here wants that answer any more.
            if (timedOut) task.cancel(true)
            mainThread.post { if (isAdded) onResult(outcome.getOrNull(), timedOut) }
        }
    }

    private fun render(state: AiFleetRoster.PeerState, snapshot: AiFleetRoster.Snapshot?) {
        val context = requireContext()
        column.removeAllViews()
        column.addView(AiViews.heading(context, getString(R.string.ai_tokens_title)))
        column.addView(AiViews.caption(context, getString(R.string.ai_tokens_intro)))

        if (AiFleetRoster.peers.isEmpty()) {
            column.addView(AiViews.status(context, getString(R.string.ai_roster_empty), needsAttention = true))
            return
        }

        AiFleetRoster.peers.forEach { peer ->
            column.addView(AiViews.heading(context, peer.label))
            when (peer.role) {
                AiFleetRoster.Role.SERVES -> renderServingPeer(peer, state, snapshot)
                AiFleetRoster.Role.ROUTES -> renderRoutingPeer(peer)
                AiFleetRoster.Role.CONSOLE -> column.addView(
                    AiViews.status(context, getString(R.string.ai_peer_console), needsAttention = false)
                )
                AiFleetRoster.Role.UNKNOWN -> column.addView(
                    AiViews.status(
                        context,
                        getString(R.string.ai_peer_unknown_role, peer.packageName),
                        needsAttention = true,
                    )
                )
            }
        }
    }

    /**
     * The app that HOLDS the key and the registry. Its state line is the page's whole health, and
     * every editable control on this screen belongs to it, because it is the only app with
     * anything to edit.
     */
    private fun renderServingPeer(
        peer: AiFleetRoster.Peer,
        state: AiFleetRoster.PeerState,
        snapshot: AiFleetRoster.Snapshot?,
    ) {
        val context = requireContext()
        column.addView(AiViews.caption(context, peer.packageName))

        // Each of these is a DIFFERENT REPAIR — install it, open it, update it — so each gets its
        // own sentence. A page that answered "unavailable" to all three would send the owner to the
        // wrong one, and they would come back believing the feature is broken.
        val message = when (state) {
            AiFleetRoster.PeerState.NOT_INSTALLED -> R.string.ai_peer_not_installed
            AiFleetRoster.PeerState.UNREACHABLE -> R.string.ai_peer_unreachable
            AiFleetRoster.PeerState.TOO_OLD -> R.string.ai_peer_too_old
            AiFleetRoster.PeerState.TIMED_OUT -> R.string.ai_peer_timed_out
            else -> 0
        }
        if (message != 0) {
            column.addView(AiViews.status(context, getString(message), needsAttention = true))
            column.addView(retryButton())
            return
        }
        if (snapshot == null) return

        column.addView(AiViews.status(context, getString(R.string.ai_peer_ready), needsAttention = false))
        snapshot.providers.forEach { renderProvider(it) }
        column.addView(retryButton())
    }

    /**
     * An app that CALLS the serving app but holds no key of its own.
     *
     * It gets a stated role and no controls, because it has nothing this page could set: its model
     * choice lives in its own settings and its credential does not exist. Showing it an empty key
     * field would read as a key it had somehow lost.
     */
    private fun renderRoutingPeer(peer: AiFleetRoster.Peer) {
        val context = requireContext()
        column.addView(AiViews.caption(context, peer.packageName))
        val installed = requireContext().packageManager.getLaunchIntentForPackage(peer.packageName) != null
        column.addView(
            AiViews.status(
                context,
                getString(if (installed) R.string.ai_peer_routes else R.string.ai_peer_not_installed),
                needsAttention = !installed,
            )
        )
    }

    /** One provider of the serving app's registry: its key, and its model. */
    private fun renderProvider(provider: AiFleetRoster.ProviderRow) {
        val context = requireContext()
        column.addView(AiViews.body(context, provider.label))

        // ── the key ──────────────────────────────────────────────────────────────
        // MASKED BY DEFAULT AND ALWAYS. This view is only ever given the mask or, after a
        // deliberate reveal, the key itself; it is the single widget on this page that a
        // credential may reach.
        val keyView = AiViews.body(context, maskFor(provider))
        column.addView(keyView)

        if (!provider.needsToken) {
            column.addView(AiViews.caption(context, getString(R.string.ai_key_not_needed)))
        }

        val keyButtons = row()
        if (provider.keyPresent) {
            keyButtons.addView(button(R.string.ai_key_reveal) { revealKey(provider, keyView, it) })
        }
        keyButtons.addView(button(R.string.ai_key_set) { promptForKey(provider) })
        if (provider.keyPresent) {
            keyButtons.addView(button(R.string.ai_key_clear) { confirmClearKey(provider) })
        }
        column.addView(keyButtons)

        // ── the model ────────────────────────────────────────────────────────────
        if (provider.models.isEmpty()) {
            column.addView(AiViews.caption(context, getString(R.string.ai_models_none)))
            return
        }
        column.addView(AiViews.caption(context, getString(R.string.ai_model_price_heading)))
        val labels = provider.models.map { describe(it) }
        // Select by ID, never by position: the registry's order is the registry's business and a
        // stored position would point at a different model the moment a row is added or removed.
        val chosenIndex = provider.models.indexOfFirst { it.id == provider.chosenModel }
        if (chosenIndex < 0) {
            // The peer is routing to a model this build's registry does not list — the two apps
            // ship separately, so one can be a release ahead. SAY SO. The spinner below cannot show
            // a row that does not exist, and silently displaying the first one instead would tell
            // the owner their traffic goes somewhere it does not.
            column.addView(
                AiViews.status(
                    context,
                    getString(R.string.ai_model_unknown_chosen, provider.chosenModel),
                    needsAttention = true,
                )
            )
        }
        // What the spinner is CURRENTLY showing, so a selection event can be told from a repaint.
        // Android fires onItemSelected for the initial selection too, and treating that as a choice
        // would write to the peer on every render — and, when chosenIndex is -1, would write the
        // FIRST row, silently changing a setting the owner never touched to fix a mismatch they
        // were never shown.
        var showing = provider.models[chosenIndex.coerceAtLeast(0)].id
        val spinner = Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, labels)
            if (chosenIndex >= 0) setSelection(chosenIndex)
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long,
                ) {
                    val picked = provider.models[position].id
                    if (picked == showing) return
                    showing = picked
                    setModel(provider, picked)
                }

                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            }
        }
        column.addView(spinner)
        provider.pricingAsOf?.let {
            column.addView(AiViews.caption(context, getString(R.string.ai_pricing_as_of, it)))
        }
    }

    /**
     * A model as one line: name, then its two prices in the registry's unit.
     *
     * The unit is named in the heading above the picker rather than repeated on every row, and the
     * numbers are printed exactly as the registry holds them. [Locale.US] because these are US
     * dollar amounts quoted from a US price list — formatting them with a comma decimal separator
     * on a Spanish phone would print "1,250" for one and a quarter dollars.
     */
    private fun describe(model: AiFleetRoster.ModelRow): String {
        val prompt = model.promptUsdPerMillionTokens
        val completion = model.completionUsdPerMillionTokens
        if (prompt == null || completion == null) {
            return getString(R.string.ai_model_row_no_price, model.name)
        }
        return getString(
            R.string.ai_model_row,
            model.name,
            String.format(Locale.US, "%.3f", prompt),
            String.format(Locale.US, "%.3f", completion),
        )
    }

    private fun maskFor(provider: AiFleetRoster.ProviderRow): String = when {
        !provider.keyPresent -> getString(R.string.ai_key_absent)
        provider.keyHint.isEmpty() -> getString(R.string.ai_key_masked)
        else -> getString(R.string.ai_key_masked_with_hint, provider.keyHint)
    }

    /**
     * Show the key in plaintext, on the one widget that asked for it.
     *
     * NOT A TOAST AND NOT A LOG. A toast floats above whatever the owner opens next and is read by
     * whoever is standing behind them; a log line is collected by this fleet's own diagnostics
     * upload and leaves the phone. The reveal lives on the row it belongs to and the same button
     * puts it back.
     */
    private fun revealKey(provider: AiFleetRoster.ProviderRow, keyView: TextView, button: Button) {
        if (keyView.tag == REVEALED) {
            keyView.text = maskFor(provider)
            keyView.tag = null
            button.setText(R.string.ai_key_reveal)
            return
        }
        withDeadline({ client.revealAiKey(provider.id) }) { result, timedOut ->
            val text = result?.text
            if (text == null) {
                // The REASON is safe to show — it never contains the key, by construction on the
                // serving side — and "holds no key yet" arrives here, which is a state rather than
                // a fault. A reveal that timed out says so instead of reading as a missing key.
                keyView.text = when {
                    timedOut -> getString(R.string.ai_peer_timed_out)
                    else -> result?.error ?: getString(R.string.ai_key_reveal_failed)
                }
                return@withDeadline
            }
            keyView.text = text
            keyView.tag = REVEALED
            button.setText(R.string.ai_key_hide)
        }
    }

    /** Paste or replace a key. The field is a password field, so it is masked as it is typed. */
    private fun promptForKey(provider: AiFleetRoster.ProviderRow) {
        val context = requireContext()
        val input = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setHint(R.string.ai_key_hint_field)
        }
        AlertDialog.Builder(context)
            .setTitle(getString(R.string.ai_key_set_title, provider.label))
            .setView(input)
            .setPositiveButton(R.string.ai_key_save) { _, _ ->
                val typed = input.text.toString().trim()
                // An empty field means the owner changed their mind, and it must not reach the
                // peer: empty is "leave it alone" on the wire, so sending it would be a no-op the
                // page would then report as a save.
                if (typed.isEmpty()) return@setPositiveButton
                write(provider, apiKey = typed)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Removing a credential is asked about first — it cannot be undone from this screen. */
    private fun confirmClearKey(provider: AiFleetRoster.ProviderRow) {
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.ai_key_clear_title, provider.label))
            .setMessage(R.string.ai_key_clear_message)
            .setPositiveButton(R.string.ai_key_clear) { _, _ -> write(provider, clearKey = true) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun setModel(provider: AiFleetRoster.ProviderRow, modelId: String) =
        write(provider, modelId = modelId)

    /**
     * Send one change to the serving app and re-read the result.
     *
     * IT RE-READS RATHER THAN ASSUMING. The page could repaint from what it just sent, and it would
     * then show the owner their own intention rather than the peer's state — which is precisely how
     * a control panel comes to show five green lights because a preference said so. What is on
     * screen after a save is what the peer answered when asked again.
     */
    private fun write(
        provider: AiFleetRoster.ProviderRow,
        apiKey: String = "",
        modelId: String = "",
        clearKey: Boolean = false,
    ) {
        withDeadline({ client.setAiRouting(provider.id, apiKey, modelId, clearKey) }) { result, timedOut ->
            if (result?.ok != true) {
                // The reason, never the value: the value may be a credential. This is a screen
                // message and not a log line for the same reason.
                column.addView(
                    AiViews.status(
                        requireContext(),
                        when {
                            timedOut -> getString(R.string.ai_peer_timed_out)
                            else -> result?.error ?: getString(R.string.ai_save_failed)
                        },
                        needsAttention = true,
                    )
                )
                return@withDeadline
            }
            renderLoading()
            refresh()
        }
    }

    private fun retryButton(): View = row().apply {
        addView(button(R.string.ai_peer_retry) { renderLoading(); refresh() })
    }

    private fun row(): LinearLayout = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.HORIZONTAL
    }

    private fun button(textRes: Int, onClick: (Button) -> Unit): Button {
        val button = Button(requireContext())
        button.setText(textRes)
        button.setOnClickListener { onClick(button) }
        return button
    }

    companion object {
        fun newInstance() = AiTokensFleetFragment()

        /** Marks the key view as showing plaintext, so the same button can put it back. */
        private const val REVEALED = "revealed"

        /**
         * How long a peer gets to answer before the page stops waiting.
         *
         * Long enough for a cold start — the bind wakes the serving app's process, which is the
         * slow case and is normal — and short enough that the owner is not left looking at a page
         * that appears frozen. It bounds the WAIT, not the peer: a slow answer that arrives after
         * this is discarded, and the retry button is the way to ask again.
         */
        private const val PEER_TIMEOUT_MS = 4_000L
    }
}
