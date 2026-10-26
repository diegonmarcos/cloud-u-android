package app.sterna.navwalk

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.sterna.container
import app.sterna.core.data.db.MailboxEntity
import app.sterna.core.data.db.SternaDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * Poses ONE local account so the emulator nav walk (1_cicd/src/scripts/cloud-android-nav-walk.py, workflow test-nav-runtime.yml)
 * gets past onboarding and reaches Mail's shared chrome (first row, tabs, bottom nav, vertical side menu). The account points at
 * an unresolvable host and holds a throwaway placeholder password: nothing is ever contacted or sent, the screens simply open
 * with an empty, offline mailbox, which is all the walk needs.
 *
 * Exists only in the `-PtestApp` build (src/navWalk, beside src/testApp, is registered on no other variant, see app/build.gradle.kts), so the release
 * APK has neither this class nor its manifest entry. The walk builds that variant itself in CI; it is never published.
 *
 *   adb shell am broadcast -n com.diegonmarcos.comms.mail.test/app.sterna.navwalk.NavWalkSeedReceiver -a app.sterna.test.navwalk.SEED
 */
class NavWalkSeedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val store = (context.applicationContext as Application).container.accountStore
        if (store.hasAccount()) { Log.i(TAG, "seeded: an account is already present"); return }
        val id = store.add(
            server = "https://mail.navwalk.invalid",
            username = "walk@navwalk.invalid",
            password = "navwalk-placeholder",
            accountName = "Nav walk",
        )
        // The folders a synced mailbox would have cached: the section's vertical side menu lists them, and without any it has nothing to draw.
        val folders = listOf("inbox" to "Inbox", "drafts" to "Drafts", "sent" to "Sent", "archive" to "Archive", "junk" to "Spam", "trash" to "Trash")
        val db = SternaDatabase.build(context)
        try {
            runBlocking(Dispatchers.IO) {
                db.mailboxDao().upsertAll(folders.mapIndexed { i, (role, name) ->
                    MailboxEntity(accountId = id, id = "navwalk-$role", name = name, role = role, sortOrder = i, totalEmails = 0, unreadEmails = 0)
                })
            }
        } finally { db.close() }
        Log.i(TAG, "seeded: one offline account with ${folders.size} folders")
    }

    private companion object { const val TAG = "NavWalkSeed" }
}
