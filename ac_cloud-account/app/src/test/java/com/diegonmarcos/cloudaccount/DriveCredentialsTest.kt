package com.diegonmarcos.cloudaccount

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.superapp.fleetendpoints.DriveCredentialsContract as D
import com.diegonmarcos.superapp.profile.AccountData
import com.diegonmarcos.superapp.profile.AccountHost
import com.diegonmarcos.superapp.profile.AccountStore
import com.diegonmarcos.superapp.profile.DriveCredentialStore
import com.diegonmarcos.superapp.settings.AccountVault
import com.diegonmarcos.superapp.settings.ConfigsPrefs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * The Drive / Git credentials Cloud Account holds and publishes to Cloud Drive (`drive_credentials`): an ed25519 key is generated or
 * imported into the vault, the declared ssh hosts become config entries, and each layer of the published document needs its own
 * grant (default deny, revocable). Every secret in here is built at run time or generated; none is a literal credential.
 */
@RunWith(RobolectricTestRunner::class)
class DriveCredentialsTest {
    private lateinit var ctx: Context
    private lateinit var vault: AccountVault
    private lateinit var store: DriveCredentialStore
    private val other = "com.example.drive"
    private val marker = "tok-" + "DRIVE-" + "777001" // a made-up marker, not a credential
    private val decl = """{"services":[{"id":"gitea","kind":"git","mesh_dns":"gitea.mesh.diegonmarcos.com","mesh_port":3002,"mesh_scheme":"http","policy":"mesh_only"}],
        "ssh_hosts":[{"host":"github.com","hostname":"github.com"},{"host":"gitea","service":"gitea","port":2222}]}"""

    @Before fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        ctx.getSharedPreferences("drive_test", Context.MODE_PRIVATE).edit().clear().commit()
        ConfigsPrefs.factory = { it.getSharedPreferences("drive_test", Context.MODE_PRIVATE) }
        val dir = File(ctx.filesDir, "account_test_drive").also { it.deleteRecursively() }
        AccountVault.storeSource = { AccountStore(dir, AccountStore.PlainIo) }
        AccountData.callerResolver = { other }
        AccountHost.fleetEndpointsDecl = { decl }
        vault = AccountVault(ctx); store = DriveCredentialStore(vault)
    }

    @After fun tearDown() {
        ConfigsPrefs.factory = null
        AccountHost.fleetEndpointsDecl = { "" }
        AccountData.callerResolver = { c -> c.packageManager.getPackagesForUid(android.os.Binder.getCallingUid())?.firstOrNull() ?: c.packageName }
    }

    private fun call() = Robolectric.buildContentProvider(AccountData.Provider::class.java).create(AccountData.AUTHORITY).get()
        .call(AccountData.METHOD_DRIVE_CREDENTIALS, null, null)
    private fun published() = D.parse(call().getString(D.KEY_JSON)!!)!!

    @Test fun generatingAnEd25519KeyHoldsItInTheVaultAndShowsAPublicKey() {
        assertTrue(store.generate("test"))
        assertTrue(store.publicKey().startsWith("ssh-ed25519 ")); assertTrue(store.fingerprint().isNotEmpty())
        assertTrue(store.privateKey().contains("PRIVATE KEY"))
        assertFalse("the private half is never in the public key", store.publicKey().contains("PRIVATE"))
    }

    @Test fun importingTheSameKeyKeepsItsPublicHalfAndAnotherKeyTypeIsRefused() {
        store.generate("a"); val pub = store.publicKey().substringBefore(" test").split(' ').take(2).joinToString(" "); val pem = store.privateKey()
        val fresh = DriveCredentialStore(AccountVault(ctx).also { it.putConnection(D.P_SSH_PRIVATE, ""); it.putConnection(D.P_SSH_PUBLIC, "") })
        assertTrue(fresh.importKey(pem, comment = "b").ok)
        assertEquals(pub, fresh.publicKey().split(' ').take(2).joinToString(" "))
        assertFalse(fresh.importKey("not a key").ok)
    }

    @Test fun theSshConfigComesFromTheDeclarationWithHostHostNameUserPortIdentityFile() {
        val h = store.sshHosts().associateBy { it.host }
        assertEquals("github.com", h.getValue("github.com").hostname)
        assertEquals("gitea.mesh.diegonmarcos.com", h.getValue("gitea").hostname); assertEquals(2222, h.getValue("gitea").port)
        assertEquals(DriveCredentialStore.IDENTITY_FILE, h.getValue("gitea").identityFile)
    }

    @Test fun aPackageNeedsTheReadGrantAndEachSecretLayerItsOwn() {
        store.generate("t")
        assertTrue(store.putRemote(D.Remote("gdrive", "drive", mapOf("scope" to "drive", "token" to marker))))
        assertTrue(store.putMount(D.Mount("nas", "sshfs", "", "nas.mesh.diegonmarcos.com", "diego", 22, "/data", "/mnt/nas", password = marker)))
        assertFalse("no grant, no answer", call().getBoolean(AccountData.KEY_OK))
        vault.grants.grant(other, D.GRANT_READ)
        val base = call().getString(D.KEY_JSON)!!
        assertFalse(base.contains(marker)); assertFalse(base.contains("PRIVATE KEY"))
        assertEquals(listOf("gdrive"), published().remotes); assertTrue(published().publicKey.startsWith("ssh-ed25519 "))
        assertTrue(published().sshConfig.contains("HostName gitea.mesh.diegonmarcos.com"))
        assertNull(published().rcloneConfig); assertNull(published().privateKey)
        vault.grants.grant(other, D.GRANT_RCLONE)
        assertTrue(published().rcloneConfig!!.contains(marker)); assertNull(published().privateKey)
        vault.grants.grant(other, D.GRANT_SSH_PRIVATE)
        assertTrue(published().privateKey!!.contains("PRIVATE KEY"))
        vault.grants.revoke(other, D.GRANT_SSH_PRIVATE)
        assertNull("a revoked layer is honoured at once", published().privateKey)
        assertFalse("the mount password needs drive.mounts", call().getString(D.KEY_JSON)!!.contains("mount_secrets"))
        vault.grants.grant(other, D.GRANT_MOUNTS)
        assertTrue(call().getString(D.KEY_JSON)!!.contains("mount_secrets"))
    }

    @Test fun theFleetEndpointsGrantDoesNotOpenTheDriveCredentials() {
        vault.grants.grant(other, "fleet.endpoints"); vault.grants.grant(other, "fleet.bearer")
        assertFalse(call().getBoolean(AccountData.KEY_OK))
    }

    @Test fun badRemoteAndMountNamesAreRefusedAndRemovalWorks() {
        assertFalse(store.putRemote(D.Remote("bad name", "drive", emptyMap())))
        assertFalse(store.putRemote(D.Remote("x", "ftp-unknown", emptyMap())))
        assertFalse(store.putMount(D.Mount("m", "sshfs", "", "", "u", 22, "/", "/mnt")))
        store.putRemote(D.Remote("g", "drive", mapOf("token" to marker))); store.removeRemote("g")
        assertTrue(store.remotes().isEmpty())
    }

    @Test fun drivePackageIsSeededWithTheDriveGrantsAndTheBearerEndpoints() {
        val g = AccountVault.DEFAULT_GRANTS.getValue(AccountVault.DRIVE_PACKAGE)
        for (k in D.GRANTS + listOf("fleet.endpoints", "fleet.bearer")) assertTrue(k, k in g)
    }

    private fun put(body: String) = Robolectric.buildContentProvider(AccountData.Provider::class.java).create(AccountData.AUTHORITY).get()
        .call(AccountData.METHOD_DRIVE_CREDENTIALS_PUT, body, null)

    @Test fun theWriteDoorNeedsItsOwnGrantAndStoresOrRemovesARemoteAndAMount() {
        val add = """{"rclone":{"name":"gdrive","type":"drive","fields":{"token":"$marker"}}}"""
        assertFalse("no grant, no write", put(add).getBoolean(AccountData.KEY_OK)); assertTrue(store.remotes().isEmpty())
        vault.grants.grant(other, D.GRANT_READ); vault.grants.grant(other, D.GRANT_RCLONE)
        assertFalse("the read and secret grants do not open the write door", put(add).getBoolean(AccountData.KEY_OK))
        vault.grants.grant(other, D.GRANT_WRITE)
        assertTrue(put(add).getBoolean(AccountData.KEY_OK)); assertEquals(listOf("gdrive"), store.remotes().map { it.name })
        assertTrue(put("""{"mount":{"name":"nas","kind":"sshfs","host":"nas.mesh.diegonmarcos.com","user":"d","port":22,"path":"/","mountpoint":"/mnt/nas"}}""").getBoolean(AccountData.KEY_OK))
        assertEquals(listOf("nas"), store.mounts().map { it.name })
        val bad = put("""{"rclone":{"name":"x","type":"ftp-unknown"}}""")
        assertFalse(bad.getBoolean(AccountData.KEY_OK)); assertFalse(bad.getString(AccountData.KEY_ERROR).orEmpty().contains(marker))
        assertTrue(put("""{"remove_rclone":"gdrive"}""").getBoolean(AccountData.KEY_OK)); assertTrue(store.remotes().isEmpty())
        vault.grants.revoke(other, D.GRANT_WRITE)
        assertFalse("a revoked write grant is honoured at once", put("""{"remove_mount":"nas"}""").getBoolean(AccountData.KEY_OK)); assertEquals(1, store.mounts().size)
    }
}
