package com.diegonmarcos.superapp.configs

import android.app.ActivityManager
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.fragment.app.FragmentActivity
import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.notificationcenter.BadgeCustomization
import com.diegonmarcos.superapp.notificationcenter.BadgeDeclaration
import com.diegonmarcos.superapp.notificationcenter.BadgeServices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * #535 — Configs ▸ Panel ▸ Push renders the badge AS POSTED, not a sentence
 * about it, and gives it a button that does what the badge's own tap does.
 *
 * Driven through the real fragment against the SHIPPED declaration
 * ([BadgeServices.declared]): a notification is posted on a declared badge
 * channel exactly as its producer would, and the assertions read the rendered
 * view tree. Expected strings are read back off the posted Notification, so
 * nothing here passes on a value this file typed twice.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PushPaneLiveBadgeTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    private fun all(v: View): List<View> =
        listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()

    private fun render(): View {
        val act = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        val f = PushFragment.newInstance()
        act.supportFragmentManager.beginTransaction().add(android.R.id.content, f).commitNow()
        return f.requireView()
    }

    /** A badge that needs no grant, so its state is decided by its service and
     *  its notification alone — the two things these tests control. */
    private fun badge(): BadgeDeclaration.Badge =
        BadgeDeclaration.badges(BadgeServices.declared)
            .first { it.channel.isNotBlank() && it.requires.isEmpty() && it.service.isNotBlank() }

    private fun ownerRunning(b: BadgeDeclaration.Badge) {
        shadowOf(ctx.getSystemService(ActivityManager::class.java)).setServices(listOf(
            ActivityManager.RunningServiceInfo().apply { service = ComponentName(ctx.packageName, b.service) },
        ))
    }

    private fun stateRow(texts: List<String>, b: BadgeDeclaration.Badge, state: Int) =
        texts.filter { it.startsWith(ctx.getString(state) + " ·") }

    private fun post(b: BadgeDeclaration.Badge): Notification {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(b.channel, b.label, NotificationManager.IMPORTANCE_LOW))
        val tap = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, FragmentActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(ctx, b.channel)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("${b.label} as posted")
            .setContentText("value ${System.nanoTime()}")
            .setContentIntent(tap)
            .build()
        nm.notify(b.id.hashCode(), n)
        return n
    }

    @Test
    fun `a posted badge renders its own title and text and an Open button that fires its tap`() {
        val b = badge()
        val n = post(b)
        val view = render()
        val texts = all(view).filterIsInstance<TextView>().map { it.text.toString() }

        val title = n.extras.getCharSequence(Notification.EXTRA_TITLE).toString()
        val text = n.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        assertTrue("posted title '$title' not rendered; pane shows $texts", texts.contains(title))
        // The platform template drew it, not a TextView this fragment made: only
        // the template's title carries android.R.id.title.
        assertTrue(
            "title '$title' is not in the notification template's own title view — the pane drew it itself",
            all(view).any { it.id == android.R.id.title && (it as? TextView)?.text?.toString() == title },
        )
        assertTrue("posted text '$text' not rendered; pane shows $texts", texts.any { it.contains(text) })
        if (b.shows.isNotBlank())
            assertFalse("the pane still prints the declared description", texts.any { it.contains(b.shows) })

        val open = all(view).filterIsInstance<Button>()
            .firstOrNull { it.text == ctx.getString(R.string.push_open) }
        assertNotNull("no Open button for the posted badge", open)
        open!!.performClick()
        val started = shadowOf(ctx as Application).nextStartedActivity
        assertEquals(
            "Open did not fire the notification's own contentIntent",
            FragmentActivity::class.java.name, started?.component?.className,
        )
    }

    /** Owner running, nothing posted — the swiped-away badge. The previous
     *  pane called this LIVE because it only asked whether the service ran. */
    @Test
    fun `a running owner with nothing posted reads not-in-the-shade`() {
        val b = badge()
        ownerRunning(b)
        val texts = all(render()).filterIsInstance<TextView>().map { it.text.toString() }
        assertTrue(
            "${b.id}: service running, nothing posted, yet the pane said LIVE: $texts",
            stateRow(texts, b, R.string.push_state_live).isEmpty(),
        )
        assertTrue(
            "${b.id}: expected NOT IN THE SHADE: $texts",
            stateRow(texts, b, R.string.push_state_dead).isNotEmpty(),
        )
    }

    /** The control for the test above: running AND posted is LIVE, so that
     *  one cannot pass on a pane that never says LIVE at all. */
    @Test
    fun `a running owner with its badge posted reads LIVE`() {
        val b = badge()
        ownerRunning(b)
        post(b)
        val texts = all(render()).filterIsInstance<TextView>().map { it.text.toString() }
        assertTrue("${b.id}: running and posted, not LIVE: $texts",
            stateRow(texts, b, R.string.push_state_live).isNotEmpty())
    }

    /** Launch all — the way back from a badge that was cleared: every declared
     *  badge that may launch has its owning service started. */
    @Test
    fun `Launch all starts the owner of every launchable badge`() {
        val b = badge()
        val view = render()
        val launchAll = all(view).filterIsInstance<Button>()
            .firstOrNull { it.text == ctx.getString(R.string.push_launch_all) }
        assertNotNull("no Launch all button", launchAll)
        launchAll!!.performClick()
        val app = shadowOf(ctx as Application)
        val started = generateSequence { app.nextStartedService }.mapNotNull { it.component?.className }.toSet()
        assertTrue("${b.id}: its owner ${b.service} was not started; started $started", b.service in started)
    }

    /** "Keep it pinned" is read at post time: on by default, and switching it
     *  off is what a producer sees. It was decoration for six of seven badges. */
    @Test
    fun `pinned follows the Keep it pinned switch`() {
        val b = badge()
        assertTrue("${b.id} is declared persistent, so it pins by default", BadgeServices.pinned(ctx, b.id))
        BadgeCustomization.set(ctx, b, BadgeCustomization.KEY_PERSISTENT, false)
        assertFalse("${b.id}: switched off, still pinned", BadgeServices.pinned(ctx, b.id))
    }
}
