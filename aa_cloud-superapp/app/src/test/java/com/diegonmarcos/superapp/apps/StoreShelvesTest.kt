package com.diegonmarcos.superapp.apps

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.superapp.appstore.StoreCloudFragment
import com.diegonmarcos.superapp.appstore.StorePhoneFragment
import com.diegonmarcos.superapp.launcher.SectionPages
import com.diegonmarcos.superapp.launcher.Sections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #563 — the Store, asserted on what the shipped app RESOLVES, not on what a
 * source file says.
 *
 * Grouping: [StoreShelves.of] is the one answer both Store tabs draw their
 * headings from. It is called here exactly as AppStoreHost.classify calls it,
 * over the taxonomy baked into this build's BuildConfig, and the heading it
 * returns is read back. The two expectations are the brief's own acceptance
 * examples (cloud-drive → Data Apps; WhatsApp → Tools Inboxes & AI / Chat).
 *
 * Routing: the Store page and its two tabs are looked up in the resolved
 * section model and the fragment each tab's factory actually builds is
 * checked, so a tab id that drifts from its SectionPages route fails here
 * rather than as an empty strip on the phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class StoreShelvesTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    private val drive = "com.diegonmarcos.clouddrive"
    private val whatsapp = "com.whatsapp"
    private val shelves by lazy {
        StoreShelves.of(ctx, mapOf(drive to "cloud-drive", whatsapp to "WhatsApp"))
    }

    @Test
    fun `cloud-drive is shelved under Data Apps`() {
        val shelf = shelves[drive]
        assertNotNull("cloud-drive got no shelf at all — the Store would draw it unheaded. Got: $shelves", shelf)
        assertTrue("cloud-drive heading is '${shelf!!.heading}', not a Data Apps shelf",
            shelf.heading.contains("Data Apps"))
    }

    @Test
    fun `WhatsApp is shelved under Tools Inboxes and AI, Chat`() {
        val shelf = shelves[whatsapp]
        assertNotNull("WhatsApp got no shelf — got: $shelves", shelf)
        assertTrue("WhatsApp heading is '${shelf!!.heading}'",
            shelf.heading.startsWith("Tools") && shelf.heading.contains("Inboxes & AI") &&
                shelf.heading.endsWith("/ Chat"))
    }

    @Test
    fun `shelves sort in the taxonomy's declared order`() {
        // Data Apps precedes Inboxes & AI in ui.phone_sections (#312), so a
        // store sorted by Shelf.order draws Data Apps first — the same order
        // All Apps draws them in.
        val dataApps = shelves.getValue(drive).order
        val inboxes = shelves.getValue(whatsapp).order
        assertTrue("Data Apps order '$dataApps' does not sort before Inboxes order '$inboxes'", dataApps < inboxes)
    }

    @Test
    fun `the Store page resolves to its two tabs and each builds its own fragment`() {
        val config = Sections.all().firstNotNullOfOrNull { sec ->
            sec.allPages.firstOrNull { it.label == "Store" && it.tabs.size == 2 }?.let { sec to it }
        }
        assertNotNull("no page titled Store with two tabs in the resolved sections", config)
        val (section, store) = config!!
        val pages = SectionPages.pagesFor(section.id, includeHidden = true).associateBy { it.id }
        val cloudTab = pages[store.tabs[0]]
        val phoneTab = pages[store.tabs[1]]
        assertNotNull("tab ${store.tabs[0]} is not a page of ${section.id}", cloudTab)
        assertNotNull("tab ${store.tabs[1]} is not a page of ${section.id}", phoneTab)
        assertTrue("the fleet tab builds ${cloudTab!!.factory()::class.simpleName}",
            cloudTab.factory() is StoreCloudFragment)
        assertTrue("the phone tab builds ${phoneTab!!.factory()::class.simpleName}",
            phoneTab.factory() is StorePhoneFragment)
        assertTrue("the fleet tab's title lost its display word: '${cloudTab.label}'",
            cloudTab.label.contains("Constellation"))
        assertEquals("the store's own id must not carry the old name", false,
            (store.id + store.tabs.joinToString()).contains("constellation", ignoreCase = true))
    }
}
