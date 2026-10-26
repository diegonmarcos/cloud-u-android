package app.sterna.bench

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.sterna.container

/**
 * Poses ONE local account so the emulator nav walk (1_cicd/src/scripts/cloud-android-nav-walk.py, workflow test-nav-runtime.yml)
 * gets past onboarding and reaches Mail's shared chrome (first row, tabs, bottom nav, vertical side menu). The account points at
 * an unresolvable host and holds a throwaway placeholder password: nothing is ever contacted or sent, the screens simply open
 * with an empty, offline mailbox, which is all the walk needs.
 *
 * Exists only in the `-PtestApp` build (src/testApp is registered on no other variant, see app/build.gradle.kts), so the release
 * APK has neither this class nor its manifest entry. The walk builds that variant itself in CI; it is never published.
 *
 *   adb shell am broadcast -n com.diegonmarcos.comms.mail.test/app.sterna.bench.NavWalkSeedReceiver -a app.sterna.test.navwalk.SEED
 */
class NavWalkSeedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val store = (context.applicationContext as Application).container.accountStore
        if (store.hasAccount()) { Log.i(TAG, "seeded: an account is already present"); return }
        store.add(
            server = "https://mail.navwalk.invalid",
            username = "walk@navwalk.invalid",
            password = "navwalk-placeholder",
            accountName = "Nav walk",
        )
        Log.i(TAG, "seeded: one offline account")
    }

    private companion object { const val TAG = "NavWalkSeed" }
}
