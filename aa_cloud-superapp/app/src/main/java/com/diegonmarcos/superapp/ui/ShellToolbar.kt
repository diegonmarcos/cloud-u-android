package com.diegonmarcos.superapp.ui

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
