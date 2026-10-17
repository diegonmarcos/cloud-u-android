package com.diegonmarcos.cloudagenda

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import com.diegonmarcos.cloudagenda.data.AttachmentKind
import com.diegonmarcos.cloudagenda.data.ProjectSummaries
import com.diegonmarcos.cloudagenda.data.TasksDb
import com.diegonmarcos.cloudagenda.data.TasksPrefs
import com.diegonmarcos.cloudagenda.data.TasksRepository
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
        openProject(intent)
        env = AppEnv(this) { kind ->
            documentPicker.launch(if (kind == AttachmentKind.IMAGE) arrayOf("image/*") else arrayOf("*/*"))
        }
        setContent { TasksApp(env) }
        Updater.start(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // A project deep link while the app is open: select it, then rebuild so the page reads the new selection.
        if (openProject(intent)) recreate()
    }

    /**
     * `shortcut_action = project:<id>` (the SuperApp's Boards ▸ Tasks sends it through extapp:cloud-tasks#project:<id>):
     * select that project's first list before the page reads its selection. An unknown id leaves the selection alone.
     */
    private fun openProject(intent: Intent?): Boolean {
        val id = intent?.getStringExtra(EXTRA_TARGET)?.takeIf { it.startsWith(PROJECT_PREFIX) }?.removePrefix(PROJECT_PREFIX)
            ?: return false
        intent?.removeExtra(EXTRA_TARGET)
        val list = runCatching { ProjectSummaries.firstList(TasksRepository(TasksDb.shared(this)).snapshot(), id) }.getOrNull()
            ?: return false
        TasksPrefs(this).selectedListId = list
        return true
    }

    private companion object {
        const val EXTRA_TARGET = "shortcut_action"
        const val PROJECT_PREFIX = "project:"
    }
}
