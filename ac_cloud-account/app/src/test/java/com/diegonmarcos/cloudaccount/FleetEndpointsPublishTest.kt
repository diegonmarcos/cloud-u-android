package com.diegonmarcos.cloudaccount

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.superapp.fleetconfig.FleetEndpointsContract
import com.diegonmarcos.superapp.profile.AccountData
import com.diegonmarcos.superapp.profile.AccountHost
import com.diegonmarcos.superapp.profile.AccountStore
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
 * #1093 Cloud Account publishing the fleet endpoint pairs to the other apps, on a real provider under Robolectric: a
 * package needs the `fleet.endpoints` grant for the pairs and `fleet.bearer` besides for the bearer, every declared
 * service comes out with its mesh and (where it has one) public endpoint, the mail reader takes only the mail
 * services of the same declaration, and the fleet bearer never appears in an entry or in the stored grants.
 */
@RunWith(RobolectricTestRunner::class)
class FleetEndpointsPublishTest {
    private lateinit var ctx: Context
    private val other = "com.example.other"
    private val bearer = "tok-FLEET-987654" // gitleaks:allow (a made-up test marker, not a credential)
    private val decl = """{"services":[
        {"id":"jmap","kind":"mail","mesh_dns":"stalwart.app","mesh_port":2443,"mesh_scheme":"https","public_domain":"jmap.example.org","policy":"mesh_first","health":"/h"},
        {"id":"me-api","kind":"data","mesh_dns":"cloud-me-api.app","mesh_port":8124,"mesh_scheme":"http","policy":"mesh_only","health":"/me/health"}]}"""

    @Before fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        ctx.getSharedPreferences("vault_test", Context.MODE_PRIVATE).edit().clear().commit()
        ConfigsPrefs.factory = { it.getSharedPreferences("vault_test", Context.MODE_PRIVATE) }
        val dir = File(ctx.filesDir, "account_test_fe").also { it.deleteRecursively() }
        AccountVault.storeSource = { AccountStore(dir, AccountStore.PlainIo) }
        AccountData.callerResolver = { other }
        AccountHost.fleetEndpointsDecl = { decl }
        AccountVault(ctx).putConnection("fleet.bearer", bearer)
    }

    @After fun tearDown() {
        ConfigsPrefs.factory = null
        AccountHost.fleetEndpointsDecl = { "" }
        AccountData.callerResolver = { c -> c.packageManager.getPackagesForUid(android.os.Binder.getCallingUid())?.firstOrNull() ?: c.packageName }
    }

    private fun call(method: String = AccountData.METHOD_FLEET_ENDPOINTS) = Robolectric.buildContentProvider(AccountData.Provider::class.java)
        .create(AccountData.AUTHORITY).get().call(method, null, null)

    private fun published() = FleetEndpointsContract.parse(call().getString(FleetEndpointsContract.KEY_JSON)!!)!!

    @Test fun aPackageNeedsTheEndpointsGrant() {
        assertFalse(call().getBoolean(AccountData.KEY_OK))
        AccountVault(ctx).grants.grant(other, FleetEndpointsContract.GRANT_ENDPOINTS)
        assertTrue(call().getBoolean(AccountData.KEY_OK))
    }

    @Test fun theMailGrantDoesNotOpenTheFleetEndpoints() {
        AccountVault(ctx).grants.grant(other, "mail.endpoints")
        assertFalse(call().getBoolean(AccountData.KEY_OK))
    }

    @Test fun theBearerNeedsItsOwnGrantAndIsAbsentOtherwise() {
        val v = AccountVault(ctx)
        v.grants.grant(other, FleetEndpointsContract.GRANT_ENDPOINTS)
        assertEquals("", published().bearer)
        v.grants.grant(other, FleetEndpointsContract.GRANT_BEARER)
        assertEquals(bearer, published().bearer)
        v.grants.revoke(other, FleetEndpointsContract.GRANT_BEARER)
        assertEquals("a revoked bearer grant is honoured at once", "", published().bearer)
    }

    @Test fun everyDeclaredServiceGetsItsEndpointsAndAMeshOnlyOneNoPublicUrl() {
        AccountVault(ctx).grants.grant(other, FleetEndpointsContract.GRANT_ENDPOINTS)
        val e = published().entries.associateBy { it.service }
        assertEquals(setOf("jmap", "me-api"), e.keys)
        assertEquals("https://stalwart.app:2443", e.getValue("jmap").mesh); assertEquals("https://jmap.example.org", e.getValue("jmap").public)
        assertEquals("http://cloud-me-api.app:8124", e.getValue("me-api").mesh); assertEquals("", e.getValue("me-api").public)
        assertEquals(FleetEndpointsContract.POLICY_MESH_ONLY, e.getValue("me-api").policy)
    }

    @Test fun aDeclarationOfMeshOnlyServicesSendsNoBearerEvenToAGrantedPackage() {
        AccountHost.fleetEndpointsDecl = { decl.replace("\"public_domain\":\"jmap.example.org\",\"policy\":\"mesh_first\"", "\"policy\":\"mesh_only\"") }
        AccountVault(ctx).grants.grant(other, FleetEndpointsContract.GRANT_ENDPOINTS)
        AccountVault(ctx).grants.grant(other, FleetEndpointsContract.GRANT_BEARER)
        assertEquals("nothing has a public endpoint, so there is no path the bearer could ride", "", published().bearer)
    }

    @Test fun noDeclarationPublishesNothing() {
        AccountHost.fleetEndpointsDecl = { "" }
        AccountVault(ctx).grants.grant(other, FleetEndpointsContract.GRANT_ENDPOINTS)
        assertTrue(published().entries.isEmpty())
    }

    @Test fun theBearerIsNotInTheEntriesOrTheGrants() {
        val v = AccountVault(ctx)
        v.grants.grant(other, FleetEndpointsContract.GRANT_ENDPOINTS)
        val doc = JSONObject(call().getString(FleetEndpointsContract.KEY_JSON)!!)
        assertFalse(doc.getJSONArray("services").toString().contains(bearer))
        assertFalse(v.grants.asJson().toString().contains(bearer))
    }

    @Test fun theMailReaderTakesOnlyTheMailServicesOfTheSameDeclaration() {
        AccountVault(ctx).grants.grant(other, "mail.endpoints")
        // the mail view of the one declaration: kind=mail only, so no other service's public host can match a mail account
        assertEquals(listOf("jmap"), FleetEndpointsContract.parseDecl(AccountHost.mailEndpointsDecl()).map { it.id })
        assertTrue(call(AccountData.METHOD_MAIL_ENDPOINTS).getBoolean(AccountData.KEY_OK))
    }
}
