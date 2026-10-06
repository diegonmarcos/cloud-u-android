package com.diegonmarcos.superapp.bottomnav

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle

/*
 * The wasm page's side of the platform seam declared in libs/bottomnav/src/commonMain/.../
 * NavPlatform.kt (#876). SuperApp's literals are NavTokens' defaults, so the page draws the very
 * numbers the Android resources declare; a browser has no animator scale or battery saver to ask,
 * nothing to buzz, and no TextView font padding.
 */

private val SUPERAPP = NavTokens()

@Composable
internal fun platformNavTokens(): NavTokens = SUPERAPP

@Composable
internal fun platformBarMotion(key: Any?): Boolean = true

private object NoHaptics : NavHaptics {
    override fun sectionChange() {}
}

@Composable
internal fun platformNavHaptics(): NavHaptics = NoHaptics

internal fun TextStyle.withPlatformFontPadding(): TextStyle = this
