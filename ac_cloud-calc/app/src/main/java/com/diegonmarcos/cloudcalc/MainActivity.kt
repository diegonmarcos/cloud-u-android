package com.diegonmarcos.cloudcalc

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.diegonmarcos.cloudcalc.engine.CalcClient
import com.diegonmarcos.cloudcalc.ui.CalcShell
import com.diegonmarcos.cloudcalc.ui.CalcState
import com.diegonmarcos.cloudcalc.ui.CalcTheme

/** The one Activity: edge-to-edge, the island draws its own inset (the cloud-c3 shape). */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val api = CalcClient(applicationContext)
        val state = CalcState(getSharedPreferences(PREFS, Context.MODE_PRIVATE))
        setContent { CalcTheme { CalcShell(api, state) } }
    }

    companion object {
        const val PREFS = "cloud_calc"
    }
}
