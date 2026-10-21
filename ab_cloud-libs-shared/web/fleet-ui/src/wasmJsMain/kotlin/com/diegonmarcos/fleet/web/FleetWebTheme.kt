package com.diegonmarcos.fleet.web

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.diegonmarcos.superapp.uikit.CloudKitTheme
import com.diegonmarcos.superapp.uikit.KitPalette

/**
 * The page colour of every wasm page (#876). CloudKitTheme keeps Material's `background` transparent
 * because on Android the window behind the Compose root is the background. A wasm page has no such
 * window: the Compose canvas is the page and is cleared to opaque white, so the palette's light text
 * (made for a dark surface) sat on white. Every web root wraps in this instead of a bare CloudKitTheme;
 * the colour is the one index.html gives the body, so the first paint and the page agree.
 */
val FleetWebBackground = Color(0xFF0B0D10)

@Composable
fun FleetWebTheme(palette: KitPalette, content: @Composable () -> Unit) {
    CloudKitTheme(palette) {
        Box(Modifier.fillMaxSize().background(FleetWebBackground)) { content() }
    }
}
