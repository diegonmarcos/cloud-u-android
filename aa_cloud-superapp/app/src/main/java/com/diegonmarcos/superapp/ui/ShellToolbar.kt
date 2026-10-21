package com.diegonmarcos.superapp.ui

import android.view.View
import android.view.ViewGroup
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import kotlin.random.Random
import kotlinx.coroutines.launch

/*
 * The shell's top row, in Compose. It was a MaterialToolbar installed as the support ActionBar (a nav-icon hamburger, an
 * options-menu Back item, an ActionBarDrawerToggle that was immediately disabled, and 200 lines of LauncherToolbarFx hooks:
 * a hierarchy listener that swallowed OEM long-press tooltips and an ObjectAnimator "tic" on the hamburger). Now the hamburger
 * and Back are two icons here, the tic is a coroutine tied to the activity being RESUMED, and nothing needs a tooltip consumer
 * because there is no toolbar View to show one.
 */

/** What the top row shows. ShellActivity writes it whenever the back stack, section or mode changes. */
object ShellToolbarState {
    /** At the Home root there is no hamburger or Back in the bar: the two corner orbs carry menu + wallet instead. */
    var atHomeRoot by mutableStateOf(true)
    /** The wallet orb's glyph, a drawable id (0 = the layout's own default). */
    var walletIcon by mutableIntStateOf(0)
}

/** The orb's glyph. The orb's disc and ripple are the host view's `bg_liquid_glass_pill`. */
@Composable
fun OrbGlyph(@DrawableRes res: Int, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Icon(painterResource(res), null, Modifier.size(24.dp), tint = LocalKitPalette.current.textPrimary)
    }
}

/**
 * Hamburger | (island) | Back. [tic] arms the hamburger's random low-amplitude tic every 3-5 s while the activity is RESUMED
 * (off with Configs > "All animations").
 */
@Composable
fun ShellToolbarRow(onMenu: () -> Unit, onBack: () -> Unit, tic: Boolean) {
    if (ShellToolbarState.atHomeRoot) return
    val ink = LocalKitPalette.current.textPrimary
    val x = remember { Animatable(0f) }
    val rot = remember { Animatable(0f) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val d = LocalDensity.current.density
    LaunchedEffect(tic) {
        if (!tic) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                kotlinx.coroutines.delay(3000L + Random.nextInt(2000))
                val flavour = Random.nextInt(3)
                val dur = 240 + Random.nextInt(160)
                val rotPeak = (Random.nextFloat() * 8f + 4f) * if (Random.nextBoolean()) 1f else -1f
                val xPeak = (Random.nextFloat() * 4f + 2f) * if (Random.nextBoolean()) 1f else -1f
                // 0 -> peak -> -0.6 peak -> 0.25 peak -> 0, as the old ObjectAnimator keyframes did.
                suspend fun wiggle(a: Animatable<Float, *>, peak: Float) {
                    for (k in listOf(peak, -peak * 0.6f, peak * 0.25f, 0f)) a.animateTo(k, tween(dur / 4))
                }
                kotlinx.coroutines.coroutineScope {
                    if (flavour != 1) launch { wiggle(x, xPeak) }
                    if (flavour != 0) launch { wiggle(rot, rotPeak) }
                }
            }
        }
    }
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.align(Alignment.CenterStart).size(56.dp).testTag("toolbar:menu")
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onMenu),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.ic_hamburger_asymmetric), stringResource(R.string.main_menu_open),
                Modifier.size(24.dp).graphicsLayer { translationX = x.value * d }.rotate(rot.value), tint = ink)
        }
        Box(
            Modifier.align(Alignment.CenterEnd).size(56.dp).testTag("toolbar:back")
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.ic_back), stringResource(R.string.action_back), Modifier.size(24.dp), tint = ink)
        }
    }
}

/**
 * WHERE THE TOP ROW ENDS AND PAGE CONTENT BEGINS: owned here, once, by the shell. No page declares, measures or guesses it.
 *
 * The top row is toolbar_island. Its offset is not a constant: in launcher mode ShellActivity seats it under the status
 * strip ([com.diegonmarcos.superapp.launcher.StripIslandGap]: the strip's height + a gap, and the strip grows after its
 * first layout), in the other modes 6 dp under the system bar. A content host padded by a typed number is therefore right
 * only in the mode the number was typed for; that is how the ADB Shell page and the Configs search bar ended up under the row.
 *
 * So the inset is the row's own bottom edge, applied to every content host the shell owns (the page container, the tablet
 * detail pane and its divider, the overlay host), and re-applied whenever the row moves or is shown/hidden:
 *
 *   inset = max(shell_top_inset, island.bottom)      while the island is shown and laid out
 *   inset = shell_top_inset                           otherwise (the declared floor, res/values/dimens.xml)
 *
 * A page lays out inside its host's padding, so it starts at or below the row by construction; a page that needs less
 * room than that cannot ask for it (no page reads the inset), and one that draws above it is caught by
 * ShellTopInsetGuardTest, which renders every registered page.
 */
object ShellTopInset {

    /** The pure rule: the declared floor, or the island's bottom edge when that is lower and the island is on screen. */
    fun insetPx(declaredPx: Int, islandShown: Boolean, islandBottomPx: Int): Int =
        if (islandShown && islandBottomPx > declaredPx) islandBottomPx else declaredPx

    /** The hosts that carry page content: padded by the inset (and the tablet divider, which is margin-inset). */
    private val HOSTS = intArrayOf(R.id.fragment_container, R.id.detail_container, R.id.overlay_container)

    /** The view [id] under this one, or null (the tablet-only hosts are absent on a phone). Not findViewById: this file draws no Views. */
    private fun View.viewOrNull(id: Int): View? = runCatching { androidx.core.view.ViewCompat.requireViewById<View>(this, id) }.getOrNull()

    /** [v]'s top in [ancestor]'s coordinates (the island and the hosts do not share a parent on a tablet). */
    private fun topIn(ancestor: View, v: View): Int {
        var t = 0
        var c: View = v
        while (c !== ancestor) {
            t += c.top
            c = c.parent as? View ?: break
        }
        return t
    }

    /**
     * The padding [host] needs right now: the island's bottom edge measured from [host]'s own top (both in the island's
     * parent's coordinates, so the shell's own status-bar padding cancels), floored at the declared dimen.
     */
    fun insetFor(root: View, host: View): Int {
        val declared = root.resources.getDimensionPixelSize(R.dimen.shell_top_inset)
        val island = root.viewOrNull(R.id.toolbar_island) ?: return declared
        val shared = island.parent as? View ?: return declared
        val shown = island.visibility == View.VISIBLE && island.height > 0 && host.height > 0
        return insetPx(declared, shown, topIn(shared, island) + island.height - topIn(shared, host))
    }

    /** The inset the page container needs right now (what every page below the row is laid out under). */
    fun currentInsetPx(root: View): Int = root.viewOrNull(R.id.fragment_container)?.let { insetFor(root, it) }
        ?: root.resources.getDimensionPixelSize(R.dimen.shell_top_inset)

    /** Pad every content host under [root] to the row's bottom edge. */
    fun apply(root: View) {
        for (id in HOSTS) {
            val host = root.viewOrNull(id) ?: continue
            val pad = insetFor(root, host)
            if (host.paddingTop != pad) host.setPadding(host.paddingLeft, pad, host.paddingRight, host.paddingBottom)
        }
        root.viewOrNull(R.id.pane_divider)?.let { d ->
            val lp = d.layoutParams as? ViewGroup.MarginLayoutParams ?: return@let
            val inset = insetFor(root, d.parent as? View ?: d)
            if (lp.topMargin != inset) { lp.topMargin = inset; d.layoutParams = lp }
        }
    }

    /** Seat the hosts now and follow the island from here on: every layout of the row (it moves when the strip grows) re-applies. */
    fun install(root: View) {
        val island = root.viewOrNull(R.id.toolbar_island) ?: return
        island.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> apply(root) }
        apply(root)
    }
}
