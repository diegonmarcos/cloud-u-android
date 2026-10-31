package com.diegonmarcos.cgomniverse

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.diegonmarcos.cgomniverse.ui.AppShell
import com.diegonmarcos.cgomniverse.ui.AppTheme
import com.diegonmarcos.superapp.bottomnav.FleetChrome

/** The one Activity: edge-to-edge through the fleet's FleetChrome; the bars clear their own insets. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FleetChrome.apply(this)
        // the start page and world, read once from the launching intent (Launch); state restores take over after that
        val section = intent?.getStringExtra(Launch.EXTRA_SECTION)
        val world = intent?.getStringExtra(Launch.EXTRA_WORLD)
        setContent { AppTheme { AppShell(startSection = section, startWorld = world) } }
    }
}
