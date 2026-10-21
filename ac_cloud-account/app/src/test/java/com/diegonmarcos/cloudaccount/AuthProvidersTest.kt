package com.diegonmarcos.cloudaccount

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.cloudlib.auth.ProviderStatus
import com.diegonmarcos.cloudlib.auth.SignIn
import com.diegonmarcos.superapp.profile.AuthProviderLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** #1083 Connect ▸ Authentication providers ▸ Providers: the page is declared once and holds identities only. */
@RunWith(RobolectricTestRunner::class)
class AuthProvidersTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun theProvidersPageIsTheFirstLeafOfAuthenticationProviders() {
        val auth = MainActivity.NAV.section("connect")!!.page("auth")!!
        assertEquals(listOf("hub", "signin", "settings"), auth.pages.map { it.id })
        assertEquals("Providers", auth.pages.first().label)
    }

    @Test fun fourFamiliesComeFromTheOneDeclaration() {
        assertEquals(listOf("authelia", "google", "github", "cloudflare"), ProviderStatus.families(SignIn.providers).keys.toList())
    }

    @Test fun theLogKeepsAnIdentityPerFamilyAndNothingElse() {
        val log = AuthProviderLog(ctx)
        log.forget("github"); log.forget("google")
        assertTrue(log.all().isEmpty() || !log.all().containsKey("github"))
        log.record("github", "diegonmarcos")
        log.record("google", "")           // a blank identity is not recorded
        assertEquals("diegonmarcos", log.all()["github"])
        assertFalse(log.all().containsKey("google"))
        log.forget("github")
        assertFalse(log.all().containsKey("github"))
    }
}
