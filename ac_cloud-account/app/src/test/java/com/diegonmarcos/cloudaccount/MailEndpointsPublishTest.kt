package com.diegonmarcos.cloudaccount

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.superapp.fleetendpoints.MailEndpointsContract
import com.diegonmarcos.superapp.profile.AccountData
import com.diegonmarcos.superapp.profile.AccountHost
import com.diegonmarcos.superapp.profile.AccountStore
import com.diegonmarcos.superapp.profile.ProfilePrefs
import com.diegonmarcos.superapp.settings.AccountVault
import com.diegonmarcos.superapp.settings.ConfigsPrefs
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * #1090 Cloud Account publishing the mail endpoints, on a real provider under Robolectric: a package needs the
 * `mail.endpoints` grant for the endpoints and `fleet.bearer` besides for the bearer, both endpoints of a fleet
 * account come out of the declaration, an account on no declared service gets none, and the fleet bearer never
 * appears in an entry or in the stored grants.
 */
@RunWith(RobolectricTestRunner::class)
class MailEndpointsPublishTest {
    private lateinit var ctx: Context
    private val other = "com.example.other"
    private val bearer = "tok-FLEET-987654"
    private val decl = """{"services":[{"id":"jmap","mesh_dns":"stalwart.app","mesh_port":2443,"mesh_scheme":"https",
        "public_domain":"jmap.example.org","public_scheme":"https","policy":"mesh_first"}]}"""

    @Before fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        ctx.getSharedPreferences("vault_test", Context.MODE_PRIVATE).edit().clear().commit()
        ConfigsPrefs.factory = { it.getSharedPreferences("vault_test", Context.MODE_PRIVATE) }
        val dir = File(ctx.filesDir, "account_test_ep").also { it.deleteRecursively() }
        AccountVault.storeSource = { AccountStore(dir, AccountStore.PlainIo) }
        AccountData.callerResolver = { other }
        AccountHost.mailEndpointsDecl = { decl }
        ProfilePrefs(ctx).email = "me@example.org"
        AccountVault(ctx).apply {
            putConnection("fleet.bearer", bearer)
            putConnection("mail.endpoints.domain", "jmap.example.org")
            putConnection("mail.accounts.me.name", "me")
            putConnection("mail.accounts.me.pass_env", "ME_PW")
            putConnection("mail.accounts.bot.name", "bot")
            putConnection("mail.accounts.bot.pass_env", "BOT_PW")
        }
    }

    @After fun tearDown() {
        ConfigsPrefs.factory = null
        AccountHost.mailEndpointsDecl = { "" }
        AccountData.callerResolver = { c -> c.packageManager.getPackagesForUid(android.os.Binder.getCallingUid())?.firstOrNull() ?: c.packageName }
    }

    private fun call() = Robolectric.buildContentProvider(AccountData.Provider::class.java)
        .create(AccountData.AUTHORITY).get().call(AccountData.METHOD_MAIL_ENDPOINTS, null, null)

    @Test fun aPackageNeedsTheEndpointsGrant() {
        assertFalse(call().getBoolean(AccountData.KEY_OK))
        AccountVault(ctx).grants.grant(other, MailEndpointsContract.GRANT_ENDPOINTS)
        assertTrue(call().getBoolean(AccountData.KEY_OK))
    }

    @Test fun theBearerNeedsItsOwnGrantAndIsAbsentOtherwise() {
        val v = AccountVault(ctx)
        v.grants.grant(other, MailEndpointsContract.GRANT_ENDPOINTS)
        val without = MailEndpointsContract.parse(call().getString(MailEndpointsContract.KEY_JSON)!!)!!
        assertEquals("", without.bearer); assertTrue(without.entries.isNotEmpty())
        v.grants.grant(other, MailEndpointsContract.GRANT_BEARER)
        assertEquals(bearer, MailEndpointsContract.parse(call().getString(MailEndpointsContract.KEY_JSON)!!)!!.bearer)
        v.grants.revoke(other, MailEndpointsContract.GRANT_BEARER)
        assertEquals("a revoked bearer grant is honoured at once", "", MailEndpointsContract.parse(call().getString(MailEndpointsContract.KEY_JSON)!!)!!.bearer)
    }

    @Test fun everyDeclaredFleetAccountGetsBothEndpoints() {
        AccountVault(ctx).grants.grant(other, MailEndpointsContract.GRANT_ENDPOINTS)
        val p = MailEndpointsContract.parse(call().getString(MailEndpointsContract.KEY_JSON)!!)!!
        assertEquals(listOf("bot@example.org", "me@example.org"), p.entries.map { it.email }.sorted())
        for (e in p.entries) {
            assertEquals("https://stalwart.app:2443", e.mesh); assertEquals("https://jmap.example.org", e.public)
            assertEquals(MailEndpointsContract.POLICY_MESH_FIRST, e.policy)
        }
    }

    @Test fun anAccountOnNoDeclaredServiceGetsNothing() {
        AccountVault(ctx).putConnection("mail.endpoints.domain", "imap.elsewhere.example")
        AccountVault(ctx).grants.grant(other, MailEndpointsContract.GRANT_ENDPOINTS)
        AccountVault(ctx).grants.grant(other, MailEndpointsContract.GRANT_BEARER)
        val p = MailEndpointsContract.parse(call().getString(MailEndpointsContract.KEY_JSON)!!)!!
        assertTrue(p.entries.isEmpty()); assertEquals("no fleet account, no bearer either", "", p.bearer)
    }

    @Test fun noDeclarationPublishesNothing() {
        AccountHost.mailEndpointsDecl = { "" }
        AccountVault(ctx).grants.grant(other, MailEndpointsContract.GRANT_ENDPOINTS)
        assertTrue(MailEndpointsContract.parse(call().getString(MailEndpointsContract.KEY_JSON)!!)!!.entries.isEmpty())
    }

    @Test fun theBearerIsNotInTheEntriesOrTheGrants() {
        val v = AccountVault(ctx)
        v.grants.grant(other, MailEndpointsContract.GRANT_ENDPOINTS)
        val doc = JSONObject(call().getString(MailEndpointsContract.KEY_JSON)!!)
        assertFalse(doc.getJSONArray("accounts").toString().contains(bearer))
        assertFalse(v.grants.asJson().toString().contains(bearer))
    }

    @Test fun theMailAppIsGrantedItsEndpointsAndTheBearerOnceAndARevokeSticks() {
        val v = AccountVault(ctx)
        v.prefs.putText("grants_seeded", "1")        // an install from before this version: it must receive ONLY the new default
        v.seedDefaults()
        assertTrue(v.grants.allow(AccountVault.MAIL_PACKAGE, MailEndpointsContract.GRANT_ENDPOINTS))
        assertTrue(v.grants.allow(AccountVault.MAIL_PACKAGE, MailEndpointsContract.GRANT_BEARER))
        assertFalse("an earlier version's defaults are not put back on upgrade", v.grants.allow("com.diegonmarcos.cloudstore", "fleet.bearer"))
        v.grants.revoke(AccountVault.MAIL_PACKAGE); v.seedDefaults()
        assertFalse(v.grants.allow(AccountVault.MAIL_PACKAGE, MailEndpointsContract.GRANT_BEARER))
    }
}
