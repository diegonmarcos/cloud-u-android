package com.diegonmarcos.superapp.search

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The scopes the app really ships (build.json::ui.search_scopes, baked into libs:search), each
 * entry point's defaults over them, and each entry remembering its own chips.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SearchScopesDeclaredTest {

    private val ctx get() = RuntimeEnvironment.getApplication()
    private val declared get() = SearchScopes.fromBuildConfig()

    private object Empty : SearchSource {
        override fun hitsFor(scope: SearchScope) = emptyList<SearchHit>()
    }

    @Test fun `the seven scopes are declared in the section order the results use`() {
        assertEquals(listOf("cloud-apps", "phone-apps", "cloud-configs", "phone-configs", "browser-fav", "browser-history", "browser-web"),
            declared.map { it.id })
        assertEquals(listOf(SearchKinds.CLOUD_APPS, SearchKinds.PHONE_APPS, SearchKinds.CLOUD_CONFIGS, SearchKinds.PHONE_CONFIGS,
            SearchKinds.BROWSER_FAV, SearchKinds.BROWSER_HISTORY, SearchKinds.BROWSER_WEB), declared.map { it.kind })
        assertEquals(listOf("Cloud apps", "Phone apps", "Cloud configs", "Phone configs", "Browser favourites", "Browser history", "Web"),
            declared.map { it.section })
    }

    @Test fun `Cloud Apps starts on apps and configs with every browser scope off`() {
        assertEquals(setOf("cloud-apps", "phone-apps", "cloud-configs", "phone-configs"),
            SearchEntry.CLOUD_APPS_PAGE.defaults(declared))
    }

    @Test fun `the Home star starts on the browser scopes and the configs, the apps off`() {
        assertEquals(setOf("browser-fav", "browser-history", "browser-web", "cloud-configs", "phone-configs"),
            SearchEntry.HOME_STAR.defaults(declared))
    }

    @Test fun `each entry point keeps its own last choice, and one never set starts on its defaults`() {
        val prefs = SearchScopePrefs(ctx)
        assertNull(prefs.load(SearchEntry.HOME_STAR))
        val star = SearchController(SearchEntry.HOME_STAR, Empty, prefs, declared)
        star.toggle("cloud-apps")                      // on, at the star only
        star.toggle("browser-web")                     // off, at the star only
        val expected = SearchEntry.HOME_STAR.defaults(declared) + "cloud-apps" - "browser-web"
        assertEquals(expected, prefs.load(SearchEntry.HOME_STAR))
        // Reopened, the star comes back on its choice; Cloud Apps and the sheet were never touched.
        assertEquals(expected, SearchController(SearchEntry.HOME_STAR, Empty, SearchScopePrefs(ctx), declared).selected)
        assertNull(prefs.load(SearchEntry.CLOUD_APPS_PAGE))
        assertEquals(SearchEntry.CLOUD_APPS_PAGE.defaults(declared),
            SearchController(SearchEntry.CLOUD_APPS_PAGE, Empty, SearchScopePrefs(ctx), declared).selected)
        assertEquals(SearchEntry.HOME_SHEET.defaults(declared),
            SearchController(SearchEntry.HOME_SHEET, Empty, SearchScopePrefs(ctx), declared).selected)
    }
}
