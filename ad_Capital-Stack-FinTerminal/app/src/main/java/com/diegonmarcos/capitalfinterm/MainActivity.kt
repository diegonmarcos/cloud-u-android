package com.diegonmarcos.capitalfinterm

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.diegonmarcos.capitalfinterm.ui.AppShell
import com.diegonmarcos.capitalfinterm.ui.AppTheme
import com.diegonmarcos.superapp.bottomnav.FleetChrome

/** The one Activity: edge-to-edge through the fleet's FleetChrome; the bars clear their own insets. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FleetChrome.apply(this)
        setContent { AppTheme { AppShell() } }
    }
}
