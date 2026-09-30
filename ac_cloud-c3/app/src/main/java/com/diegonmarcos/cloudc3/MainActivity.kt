package com.diegonmarcos.cloudc3

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.diegonmarcos.cloudc3.ui.C3Screens
import com.diegonmarcos.cloudc3.ui.C3Shell
import com.diegonmarcos.cloudc3.ui.C3Theme

/**
 * #648 the app's one Activity. Edge-to-edge, because the bottom-nav island draws its own
 * inset (libs:bottomnav's [com.diegonmarcos.superapp.bottomnav.bottomNavInsets]) and the
 * shell consumes it once — with the default fitting the island would sit above the system
 * bar with a dead strip under it.
 *
 * Everything else is declared: [C3Shell] renders build.json::ui.tabs and hands the selected
 * id to [C3Screens.Content], which is the only tab-id dispatch in the app.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            C3Theme {
                C3Shell { tabId, reselectTick -> C3Screens.Content(tabId, reselectTick) }
            }
        }
    }
}
