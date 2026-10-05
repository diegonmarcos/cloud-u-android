package com.diegonmarcos.cloudaccount

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.fragment.compose.AndroidFragment
import com.diegonmarcos.superapp.profile.ProfileFragment
import com.diegonmarcos.superapp.updater.Updater

/**
 * #867 The Account page SuperApp shows under Config, as an app.
 *
 * libs:account's [ProfileFragment] draws the declared tab strip itself (Connect, Profiles,
 * Runtime, Drift: build.json::ui.profile.tabs), so this shell hosts that one fragment with
 * AndroidFragment and adds nothing of its own.
 */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    AndroidFragment<ProfileFragment>(Modifier.fillMaxSize().systemBarsPadding())
                }
            }
        }
        // Self-update, as every constellation app does.
        Updater.start(this)
    }
}
