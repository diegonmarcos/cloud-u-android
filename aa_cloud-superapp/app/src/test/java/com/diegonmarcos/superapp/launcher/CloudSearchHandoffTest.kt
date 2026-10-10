package com.diegonmarcos.superapp.launcher

import android.app.Application
import android.app.SearchManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.superapp.fleetconfig.CloudSearchQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * #937 Cloud > Apps search, no match: the row Search "<text>" in Cloud Search builds the intent
 * Cloud Search reads (CloudSearchQuery), explicit to its package, and builds none - so the row opens
 * the app instead - when no installed Cloud Search takes a query. The package is READ from the
 * SuperApp's external_apps row, as the tile's launch reads it; the only literal is the typed text.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CloudSearchHandoffTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val app = Sections.externalApp(GroupedTilesFragment.CLOUD_SEARCH_TARGET.removePrefix("extapp:"))

    /** Installs [pkg] with a launcher activity and, when [takesQuery], the search filter Cloud Search declares. */
    private fun install(pkg: String, takesQuery: Boolean) {
        val spm = shadowOf(ctx.packageManager)
        spm.installPackage(PackageInfo().apply {
            packageName = pkg
            applicationInfo = ApplicationInfo().apply { packageName = pkg }
        })
        val main = ComponentName(pkg, "$pkg.MainActivity")
        spm.addActivityIfNotPresent(main)
        spm.addIntentFilterForActivity(main, IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) })
        if (takesQuery) spm.addIntentFilterForActivity(main, IntentFilter(Intent.ACTION_SEARCH).apply { addCategory(Intent.CATEGORY_DEFAULT) })
    }

    @Test fun theRowSendsTheTypedTextToCloudSearch() {
        val pkg = CloudSearchHandoff.packages(app).first()
        install(pkg, takesQuery = true)
        val i = CloudSearchHandoff.intent(ctx.packageManager, CloudSearchHandoff.packages(app), "  weather tomorrow ")
        assertNotNull("an installed Cloud Search that takes a query gets one", i)
        i!!
        assertEquals(Intent.ACTION_SEARCH, i.action)
        assertEquals(pkg, i.`package`)
        assertEquals("weather tomorrow", i.getStringExtra(SearchManager.QUERY))
        assertEquals("weather tomorrow", i.getStringExtra(CloudSearchQuery.EXTRA_QUERY))
        assertTrue("started from the launcher, it opens in its own task", i.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        // What Cloud Search reads out of it is what was typed.
        assertEquals("weather tomorrow", CloudSearchQuery.from(i))
    }

    @Test fun theRowNamesTheCloudSearchOfTheRoster() {
        val pkgs = CloudSearchHandoff.packages(app)
        assertTrue("external_apps has a cloud-search row with a package", pkgs.isNotEmpty())
        assertEquals(app!!.hubPackage, pkgs.first())
    }

    @Test fun noCloudSearchThatTakesAQueryMeansJustOpenIt() {
        val pkgs = CloudSearchHandoff.packages(app)
        assertNull("not installed", CloudSearchHandoff.intent(ctx.packageManager, pkgs, "x"))
        install(pkgs.first(), takesQuery = false)
        assertNull("an older Cloud Search without the search filter", CloudSearchHandoff.intent(ctx.packageManager, pkgs, "x"))
    }

    @Test fun theFirstCandidateThatTakesTheQueryWins() {
        install("com.example.old.search", takesQuery = false)
        install("com.example.new.search", takesQuery = true)
        val i = CloudSearchHandoff.intent(ctx.packageManager, listOf("com.example.old.search", "com.example.new.search"), "q")
        assertEquals("com.example.new.search", i?.`package`)
    }
}
