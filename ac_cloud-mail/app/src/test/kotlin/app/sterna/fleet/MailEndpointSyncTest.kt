package app.sterna.fleet

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import app.sterna.core.data.account.FleetEndpointStore
import app.sterna.core.data.account.FleetEndpointWiring
import app.sterna.core.data.account.StoredAccount
import com.diegonmarcos.superapp.fleetendpoints.EndpointPolicy
import com.diegonmarcos.superapp.fleetendpoints.EndpointRouter
import com.diegonmarcos.superapp.fleetendpoints.FleetBearers
import com.diegonmarcos.superapp.fleetconfig.MailEndpointsContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/**
 * #1090 The pull from Cloud Account, end to end on a stand-in for its provider: the answer lands on the transport,
 * a refusal or a missing Account changes nothing, and nothing about the endpoints is written into the app's sources.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class MailEndpointSyncTest {
    /** Cloud Account's provider as Mail sees it: whatever [reply] holds. */
    class FakeAccount : ContentProvider() {
        override fun onCreate() = true
        override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
            calls += method
            return if (method == MailEndpointsContract.METHOD) reply else Bundle()
        }
        override fun query(u: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
        override fun getType(u: Uri): String? = null
        override fun insert(u: Uri, v: ContentValues?): Uri? = null
        override fun delete(u: Uri, s: String?, a: Array<out String>?) = 0
        override fun update(u: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
        companion object { var reply: Bundle = Bundle(); val calls = mutableListOf<String>() }
    }

    private lateinit var ctx: Context
    private val marker = "fleet-SECRET-tok-123"
    private val accounts = listOf(
        StoredAccount(id = "a", server = "https://jmap.example.org", username = "me@example.org"),
        StoredAccount(id = "g", server = "imap.gmail.example", username = "me@gmail.example"),
    )

    private class Mem : FleetEndpointStore.Blobs {
        val m = linkedMapOf<String, String>()
        override fun get(key: String) = m[key]
        override fun put(key: String, value: String) { m[key] = value }
        override fun remove(key: String) { m.remove(key) }
    }
    private val router = EndpointRouter(probe = { _, _, _ -> true })
    private val bearers = FleetBearers()
    private val wiring = FleetEndpointWiring(FleetEndpointStore(Mem(), { _, p -> p.reversed() }, { _, r -> r.reversed() }), router, bearers)

    private fun ok(json: String) = Bundle().apply { putBoolean(MailEndpointsContract.KEY_OK, true); putString(MailEndpointsContract.KEY_JSON, json) }
    private fun doc(bearer: String?) = MailEndpointsContract.toJson(
        listOf(MailEndpointsContract.Entry("me@example.org", "jmap", "https://stalwart.app:2443", "https://jmap.example.org", "mesh_first")), bearer)

    @Before fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        FakeAccount.calls.clear(); FakeAccount.reply = Bundle()
        Robolectric.buildContentProvider(FakeAccount::class.java).create(MailEndpointsContract.AUTHORITY)
    }

    private fun installAccount() = shadowOf(ctx.packageManager).installPackage(PackageInfo().apply {
        packageName = MailEndpointsContract.ACCOUNT_PACKAGE
        applicationInfo = ApplicationInfo().apply { packageName = MailEndpointsContract.ACCOUNT_PACKAGE }
    })

    @Test fun `what Account published lands on the transport`() {
        installAccount(); FakeAccount.reply = ok(doc(marker))
        val out = MailEndpointSync.pull(ctx, wiring, accounts, now = 9L) as MailEndpointSync.Outcome.Synced
        assertEquals(1, out.report.applied); assertTrue(out.report.bearerSynced)
        assertTrue(router.isRegistered("jmap.example.org")); assertEquals(marker, bearers.forHost("jmap.example.org"))
        assertFalse("the plain provider account is not a fleet account", router.isRegistered("imap.gmail.example"))
        assertEquals(listOf(MailEndpointsContract.METHOD), FakeAccount.calls)
    }

    @Test fun `no bearer shared means none on the wire`() {
        installAccount(); FakeAccount.reply = ok(doc(null))
        MailEndpointSync.pull(ctx, wiring, accounts)
        assertTrue(router.isRegistered("jmap.example.org")); assertNull(bearers.forHost("jmap.example.org"))
    }

    @Test fun `a refusal changes nothing`() {
        installAccount()
        FakeAccount.reply = Bundle().apply { putBoolean(MailEndpointsContract.KEY_OK, false); putString(MailEndpointsContract.KEY_ERROR, "no grant for com.diegonmarcos.comms.mail") }
        val out = MailEndpointSync.pull(ctx, wiring, accounts)
        assertTrue(out is MailEndpointSync.Outcome.Refused)
        assertFalse((out as MailEndpointSync.Outcome.Refused).why.contains("com.diegonmarcos"))
        assertFalse(router.isRegistered("jmap.example.org"))
    }

    @Test fun `an unreadable or foreign-version answer changes nothing`() {
        installAccount()
        FakeAccount.reply = ok("not json"); assertTrue(MailEndpointSync.pull(ctx, wiring, accounts) is MailEndpointSync.Outcome.Refused)
        FakeAccount.reply = ok("""{"v":9,"accounts":[]}"""); assertTrue(MailEndpointSync.pull(ctx, wiring, accounts) is MailEndpointSync.Outcome.Refused)
        assertFalse(router.isRegistered("jmap.example.org"))
    }

    @Test fun `without Cloud Account installed nothing is asked and nothing changes`() {
        FakeAccount.reply = ok(doc(marker))
        assertEquals(MailEndpointSync.Outcome.AccountMissing, MailEndpointSync.pull(ctx, wiring, accounts))
        assertTrue(FakeAccount.calls.isEmpty()); assertFalse(router.isRegistered("jmap.example.org"))
    }

    @Test fun `the policy words of the two ends are the same set`() {
        assertEquals(MailEndpointsContract.POLICIES.toSet(), EndpointPolicy.entries.map { it.wire }.toSet())
        assertEquals(EndpointPolicy.PUBLIC_ONLY, MailEndpointSync.toPublished(
            MailEndpointsContract.Entry("a@x.org", "j", "https://m.app", "https://p.example", MailEndpointsContract.POLICY_PUBLIC_ONLY)).policy)
    }

    @Test fun `no mesh url is written into the app's sources - they arrive from Cloud Account`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "app/src/main/res/values/strings.xml").isFile }
        val files = listOf("app/src/main", "core").map { File(root, it) }.flatMap { d -> d.walkTopDown().filter { f -> f.isFile && f.extension == "kt" && "/src/main/" in f.path.replace('\\', '/') }.toList() }
        assertTrue("the walk covered the tree", files.size > 100 && files.any { it.name == "MailEndpointSync.kt" })
        val literal = Regex("\"https?://[A-Za-z0-9.-]+\\.app\\b")
        val offenders = files.filter { literal.containsMatchIn(it.readText()) }.map { it.name }
        assertEquals("a mesh (<service>.app) URL literal in the sources", emptyList<String>(), offenders)
    }
}
