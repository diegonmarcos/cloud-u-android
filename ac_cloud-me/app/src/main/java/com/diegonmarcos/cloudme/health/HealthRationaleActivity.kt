package com.diegonmarcos.cloudme.health

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.cloudme.MePermDecl
import com.diegonmarcos.cloudme.R

/**
 * The screen Health Connect opens from its permission request ("Read privacy policy") and, on Android 14+, from its app
 * privacy entry (the ViewPermissionUsageActivity alias). Without it Health Connect neither lists nor grants Cloud Me. The
 * text is build.json::ui.permissions.health.rationale.
 */
class HealthRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val decl = MePermDecl.baked
        setContent {
            Column(Modifier.fillMaxSize().background(colorResource(R.color.me_surface)).padding(24.dp).testTag("me:health:rationale")) {
                Text("Cloud Me and Health Connect", color = colorResource(R.color.me_text), fontSize = 20.sp)
                Text(decl.rationale, color = colorResource(R.color.me_text_dim), fontSize = 14.sp, modifier = Modifier.padding(top = 12.dp))
                Text("Reads ${decl.healthRead.size} record types, writes ${decl.healthWrite.size}.", color = colorResource(R.color.me_text_dim),
                    fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp))
            }
        }
    }
}
