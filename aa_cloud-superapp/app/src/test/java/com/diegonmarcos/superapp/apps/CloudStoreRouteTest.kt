package com.diegonmarcos.superapp.apps

import com.diegonmarcos.superapp.apps.CloudStoreHandoff.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** #894 The routing decision for a Store page: installed -> Cloud Store, open fails -> placeholder, absent -> embedded. */
class CloudStoreRouteTest {

    @Test fun `installed and opens - Cloud Store is opened`() =
        assertEquals(Route.OPENED_CLOUD_STORE, CloudStoreHandoff.decide(true) { true })

    @Test fun `installed but open fails - placeholder`() =
        assertEquals(Route.PLACEHOLDER, CloudStoreHandoff.decide(true) { false })

    @Test fun `absent - embedded Store, open is never attempted`() {
        var tried = false
        assertEquals(Route.EMBEDDED, CloudStoreHandoff.decide(false) { tried = true; true })
        assertFalse("open() must not run when Cloud Store is absent", tried)
    }

    @Test fun `every Store page maps to its Cloud Store tab`() {
        assertEquals("cloud", CloudStoreHandoff.tabFor("config", "store-cloud"))
        assertEquals("phone", CloudStoreHandoff.tabFor("config", "store-phone"))
        assertEquals("mesh", CloudStoreHandoff.tabFor("config", "apps-mesh"))
    }

    @Test fun `other pages and sections are not Store routes`() {
        assertNull(CloudStoreHandoff.tabFor("config", "ai"))
        assertNull(CloudStoreHandoff.tabFor("mail", "store-cloud"))
    }
}
