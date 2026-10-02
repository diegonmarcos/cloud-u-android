package com.diegonmarcos.cloudsearch

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.diegonmarcos.cloudsearch.data.Services
import com.diegonmarcos.cloudsearch.ui.SearchShell
import com.diegonmarcos.cloudsearch.ui.SearchState

/** The one Activity: edge-to-edge, the island draws its own inset (the cloud-calc shape). */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val state = SearchState(Services.get(applicationContext))
        setContent { SearchShell(state) }
    }
}
