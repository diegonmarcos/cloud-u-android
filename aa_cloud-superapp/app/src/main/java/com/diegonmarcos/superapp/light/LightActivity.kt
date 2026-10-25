package com.diegonmarcos.superapp.light

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.ComposeView
import com.diegonmarcos.superapp.recovery.RecoveryPalette
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import com.diegonmarcos.superapp.uikit.kitComposeView

/**
 * The Light page (#1155). The page, the engine and the torch service are libs:light's (shared with Cloud Calc's Toolkit > Light);
 * this activity is the SuperApp's host for it: the kit palette's ink over the recovery page colour.
 */
class LightActivity : ComponentActivity() {
    private val engine by lazy { Light.engine(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val screen: ComposeView = kitComposeView(RecoveryPalette.kit) {   // a Compose host, not a View screen
            LightPanel(engine, textColor = LocalKitPalette.current.textPrimary, background = RecoveryPalette.page)
        }
        setContentView(screen)
    }

    override fun onStop() {
        engine.onLeave()   // torch survives only for a running timer or Keep on (the service holds it)
        super.onStop()
    }
}
