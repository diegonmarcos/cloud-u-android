package com.diegonmarcos.cloudagenda

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import com.diegonmarcos.cloudagenda.data.AttachmentKind
import com.diegonmarcos.cloudagenda.ui.AppEnv
import com.diegonmarcos.cloudagenda.ui.TasksApp
import com.diegonmarcos.superapp.bottomnav.FleetChrome
import com.diegonmarcos.superapp.updater.Updater

/**
 * Cloud Tasks: one Compose activity. The window is the fleet's (FleetChrome: edge-to-edge, transparent bars), the
 * chrome and every native page are Compose (ui/), and the Events / Calendar / Invites pages are the legacy tasks.html
 * WebView hosted inside that shell (ui/LegacyWeb.kt).
 */
class MainActivity : ComponentActivity() {

    private lateinit var env: AppEnv

    private val documentPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) env.picked(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FleetChrome.apply(this)
        env = AppEnv(this) { kind ->
            documentPicker.launch(if (kind == AttachmentKind.IMAGE) arrayOf("image/*") else arrayOf("*/*"))
        }
        setContent { TasksApp(env) }
        Updater.start(this)
    }
}
