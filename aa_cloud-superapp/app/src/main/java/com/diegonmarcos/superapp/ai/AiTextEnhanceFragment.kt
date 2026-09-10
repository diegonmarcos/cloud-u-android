package com.diegonmarcos.superapp.ai

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.texttools.TextToolsClient
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Configs ▸ AI ▸ Text Enhance — WHAT EACH APP IN THE FLEET IS SET TO, and the way into the screen
 * that changes it. A read-only fleet view with a door, deliberately, and this is the reasoning.
 *
 * THE OWNER'S RULE IS THAT A COPY IS AN INDEPENDENTLY EDITABLE DUPLICATE, NOT A SHARED LINK. They
 * said so after cloud-mail's Text pages turned out to be the keyboard's pages, which left them able
 * to read every mail setting and change none of them. That complaint was real and the fix — mail's
 * own registry, mail's own preferences, mail's own editor — is the shape any THIRD copy here should
 * have taken.
 *
 * IT IS NOT THE SHAPE THIS TAB HAS, because of one fact that decides it: cloud-superapp has no Text
 * Enhance feature. It links neither libs:keyboard nor the engines behind the binder; there is no
 * ENHANCE key here, no compose field, no rewrite path, and nothing in this application that could
 * ever spend an Enhance setting. An independently editable copy in this app would be a page of
 * controls that changes nothing anywhere — a style the owner picks, a tone they set, and no text
 * this app can rewrite with either. That is the same defect as a search box that does not search,
 * and it is the one the rest of this page was built to avoid.
 *
 * So the honest thing this tab CAN be is what it is: the fleet's Enhance settings gathered on one
 * screen, each with a button that opens the app that owns them. Nothing is shared, nothing is
 * overwritten, and the owner still edits each app's settings in that app — one tap away, which is
 * the part the old shared-link failure never gave them.
 *
 * WHAT WOULD CHANGE THIS ANSWER: cloud-superapp growing a surface that rewrites text — a compose
 * field, a note editor, a share target. On the day it does, this tab should become an owned,
 * editable copy seeded from `settingsSnapshot()`, exactly as MailTextToolsPrefs.seedFromKeyboard
 * does it, and the settings would then have something to spend themselves on.
 *
 * WHY THE KEYBOARD'S VALUES SHOW AND MAIL'S DO NOT: the keyboard serves `settingsSnapshot()` over
 * the binder, so its choices can be asked for. cloud-mail serves nothing — it is a CALLER — so
 * there is no channel to read its copy, and this says so rather than showing blanks that would read
 * as settings mail had lost.
 */
class AiTextEnhanceFragment : Fragment() {

    private lateinit var client: TextToolsClient
    private lateinit var column: LinearLayout
    private val mainThread = Handler(Looper.getMainLooper())
    private val calls = Executors.newCachedThreadPool()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        val context = inflater.context
        client = TextToolsClient(context)
        val (scroll, page) = AiViews.page(context)
        column = page
        render(null, timedOut = false)
        load()
        return scroll
    }

    override fun onDestroy() {
        super.onDestroy()
        calls.shutdownNow()
    }

    /** The same deadline discipline as Tokens Fleet: a wedged peer ends the wait with a sentence. */
    private fun load() {
        val task = calls.submit<String?> { client.settingsSnapshot() }
        calls.execute {
            val outcome = runCatching { task.get(PEER_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
            val timedOut = outcome.exceptionOrNull() is TimeoutException
            if (timedOut) task.cancel(true)
            mainThread.post { if (isAdded) render(outcome.getOrNull(), timedOut) }
        }
    }

    private fun render(snapshotJson: String?, timedOut: Boolean) {
        val context = requireContext()
        column.removeAllViews()
        column.addView(AiViews.heading(context, getString(R.string.ai_enhance_title)))
        column.addView(AiViews.caption(context, getString(R.string.ai_enhance_intro)))

        AiFleetRoster.peers.forEach { peer ->
            when (peer.role) {
                AiFleetRoster.Role.SERVES -> renderServingPeer(peer, snapshotJson, timedOut)
                AiFleetRoster.Role.ROUTES -> renderRoutingPeer(peer)
                // The console has no Enhance settings of its own — see this class's header for why
                // that is a decision rather than an omission.
                AiFleetRoster.Role.CONSOLE, AiFleetRoster.Role.UNKNOWN -> Unit
            }
        }
        column.addView(AiViews.caption(context, getString(R.string.ai_enhance_ownership_note)))
    }

    private fun renderServingPeer(peer: AiFleetRoster.Peer, snapshotJson: String?, timedOut: Boolean) {
        val context = requireContext()
        column.addView(AiViews.heading(context, peer.label))

        val settings = runCatching { snapshotJson?.let { JSONObject(it) } }.getOrNull()
        if (settings == null) {
            column.addView(
                AiViews.status(
                    context,
                    getString(if (timedOut) R.string.ai_peer_timed_out else R.string.ai_enhance_unreadable),
                    needsAttention = true,
                )
            )
        } else {
            // Printed as the raw stored ids rather than as pretty names, and that is deliberate:
            // the labels for these live in the keyboard's own string resources, and inventing a
            // second set here would put a different name on one setting in two places. The id is
            // what the owner will see in the keyboard's own picker too.
            ENHANCE_KEYS.forEach { (labelRes, key) ->
                val value = settings.optString(key)
                if (value.isNotEmpty()) {
                    column.addView(AiViews.body(context, getString(labelRes, value)))
                }
            }
        }
        column.addView(openButtonRow(peer))
    }

    private fun renderRoutingPeer(peer: AiFleetRoster.Peer) {
        val context = requireContext()
        column.addView(AiViews.heading(context, peer.label))
        column.addView(AiViews.caption(context, getString(R.string.ai_enhance_own_copy)))
        column.addView(openButtonRow(peer))
    }

    /**
     * The door into the app that owns these settings.
     *
     * An app with no launch intent is not installed, and the button says so instead of being shown
     * and doing nothing when pressed — a dead button reads as a broken feature.
     */
    private fun openButtonRow(peer: AiFleetRoster.Peer): View {
        val context = requireContext()
        val launch = context.packageManager.getLaunchIntentForPackage(peer.packageName)
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        if (launch == null) {
            row.addView(AiViews.status(context, getString(R.string.ai_peer_not_installed), needsAttention = true))
            return row
        }
        row.addView(Button(context).apply {
            text = getString(R.string.ai_enhance_open, peer.label)
            setOnClickListener { startActivity(launch) }
        })
        return row
    }

    companion object {
        fun newInstance() = AiTextEnhanceFragment()

        private const val PEER_TIMEOUT_MS = 4_000L

        /**
         * Which of `settingsSnapshot()`'s keys this tab shows, and under which label.
         *
         * The KEY STRINGS are the serving app's preference names and are matched literally, because
         * that snapshot is a wire format between two separately shipped apps — see
         * ITextTools.settingsSnapshot. A key the serving app stops sending simply stops appearing,
         * which is why each row is skipped when empty rather than printed blank.
         */
        private val ENHANCE_KEYS = listOf(
            R.string.ai_enhance_provider to "ai_provider",
            R.string.ai_enhance_style to "enhance_style",
            R.string.ai_enhance_tone to "enhance_tone",
            R.string.ai_enhance_length to "enhance_length",
            R.string.ai_enhance_language to "enhance_language",
            R.string.ai_enhance_summary to "summary_style",
        )
    }
}
