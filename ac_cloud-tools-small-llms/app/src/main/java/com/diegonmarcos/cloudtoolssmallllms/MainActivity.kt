package com.diegonmarcos.cloudtoolssmallllms

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import com.diegonmarcos.cloudtoolssmallllms.ui.ToolsModel
import com.diegonmarcos.cloudtoolssmallllms.ui.ToolsShell
import com.diegonmarcos.cloudtoolssmallllms.ui.ToolsTheme
import com.diegonmarcos.superapp.bottomnav.FleetChrome

/** The one Activity: edge-to-edge through the lib's FleetChrome, the island clears its own inset. */
class MainActivity : ComponentActivity() {
    private lateinit var model: ToolsModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FleetChrome.apply(this)
        model = ToolsModel(applicationContext, lifecycleScope)
        shared(intent)
        setContent { ToolsTheme { ToolsShell(model) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        shared(intent)
    }

    /** Share > Tools of plain text: into the chat box, on the Chat page; nothing is sent until the user does. */
    private fun shared(i: Intent?) {
        if (i?.action != Intent.ACTION_SEND) return
        val text = i.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() } ?: return
        model.draft = text
        model.section = Decl.nav.default()?.id ?: model.section
    }
}
