package com.diegonmarcos.superapp.appstore

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp

/**
 * #627 — ONE TAB PER DECLARED STORE SOURCE, and nothing else.
 *
 * The tab strip is a RENDERING of [SourceResolver.Config.kinds], which is a
 * rendering of `resolver.kinds` in the install-source asset. There is no list of
 * stores in Kotlin: add `{"label": "…", "installer": "…", "fetches": false}`
 * under a new kind in that file, rank it in `order`, and the tab appears. Remove
 * it and the tab goes. That is the same by-construction rule #619 used for
 * Declared/Installed — the two COMPOSE here rather than replacing each other:
 * the source tab picks WHICH STORE, the filter picks declared-vs-installed.
 *
 * It lives in its own file, with [labels] separated from [Strip], so the
 * derivation can be asserted by a test that hands it a kind list it invented.
 * A hardcoded strip would pass a test that only ever sees today's five kinds;
 * one that is handed six has to draw six.
 */
object StoreSourceTabs {

    /** "All", then one label per declared kind, in declared order. The leading
     *  entry is the ABSENCE of a source filter, not a source — [ALL] is null
     *  everywhere a kind is expected. */
    fun labels(ctx: Context, kinds: List<SourceResolver.Kind>): List<String> =
        listOf(ctx.getString(R.string.store_phone_source_all)) + kinds.map { it.label }

    /**
     * The strip. [selected] is the chosen kind, or null for All; [onSelect] is
     * handed the same, and the caller re-renders.
     */
    @Composable
    fun Strip(
        kinds: List<SourceResolver.Kind>,
        selected: SourceResolver.Kind?,
        onSelect: (SourceResolver.Kind?) -> Unit,
        modifier: Modifier = Modifier,
    ) {
        val ctx = LocalContext.current
        val labels = labels(ctx, kinds)
        // The All pill and the per-kind pills are built by the SAME function
        // over the SAME derived list, so "All" cannot drift into a special case
        // that is styled or positioned differently from the rest.
        Row(modifier.testTag(TAG_STRIP).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            (listOf(null) + kinds).forEachIndexed { i, kind ->
                Pill(labels[i], kind?.id == selected?.id) { onSelect(kind) }
            }
        }
    }

    /** The second filter row, on both pages: the vendor's terms, not the store an app comes from. */
    enum class Trait(val label: Int) {
        PLAY_INTEGRITY(R.string.store_trait_play), PRIVATE(R.string.store_trait_private), OPEN_SOURCE(R.string.store_trait_oss)
    }

    @Composable
    fun Traits(selected: Trait?, onSelect: (Trait?) -> Unit, modifier: Modifier = Modifier) {
        val ctx = LocalContext.current
        Row(modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            (listOf(null) + Trait.values().toList()).forEach { t ->
                Pill(if (t == null) ctx.getString(R.string.store_trait_all) else ctx.getString(t.label), t == selected) { onSelect(t) }
            }
        }
    }

    @Composable
    private fun Pill(label: String, on: Boolean, onClick: () -> Unit) {
        val v = sdp(StoreDensity.S6)
        Text(
            label, color = Ink.WHITE.ink(), fontSize = StoreDensity.T_META.sp, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .padding(start = v / 2, top = v / 4, end = v / 2, bottom = v)
                .testTag(TAG_PREFIX + label)
                .background((if (on) SELECTED else IDLE).ink())
                .tap(onClick)
                .padding(horizontal = sdp(StoreDensity.S8), vertical = v),
        )
    }

    /** Same green the Declared/Installed pills wear — the two filters read as
     *  one control set because they are one control set. */
    private const val SELECTED = 0xFF48BB78.toInt()
    private const val IDLE = 0xFF2A2A33.toInt()

    /** Every pill is tagged with its own label, and the strip with [TAG_STRIP],
     *  so a test reads what was RENDERED rather than what this file says. */
    const val TAG_PREFIX = "store-source-tab:"
    const val TAG_STRIP = "store-source-tabs"
}
