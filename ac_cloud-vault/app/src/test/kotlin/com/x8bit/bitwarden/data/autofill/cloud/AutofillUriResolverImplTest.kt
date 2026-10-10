package com.x8bit.bitwarden.data.autofill.cloud

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.content.pm.SigningInfo
import com.bitwarden.network.model.DigitalAssetLinkCheckResponseJson
import com.bitwarden.network.service.DigitalAssetLinkService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AutofillUriResolverImplTest {

    private val vaultPackage = "com.diegonmarcos.cloudvault"
    private val fleetApp = "com.diegonmarcos.cloudmail"
    private val otherApp = "com.example.webapp"
    private val site = "https://login.example.com"

    private val packageManager: PackageManager = mockk {
        every { checkSignatures(vaultPackage, any<String>()) } returns PackageManager.SIGNATURE_NO_MATCH
        every { checkSignatures(vaultPackage, fleetApp) } returns PackageManager.SIGNATURE_MATCH
        every { getPackageInfo(otherApp, PackageManager.GET_SIGNING_CERTIFICATES) } returns PackageInfo().apply {
            signingInfo = mockk<SigningInfo> {
                every { apkContentsSigners } returns arrayOf(
                    mockk<Signature> { every { toByteArray() } returns byteArrayOf(1, 2, 3) },
                )
            }
        }
    }
    private val context: Context = mockk {
        every { packageName } returns vaultPackage
        every { this@mockk.packageManager } returns this@AutofillUriResolverImplTest.packageManager
    }
    private val assetLinks: DigitalAssetLinkService = mockk()
    private val resolver = AutofillUriResolverImpl(context = context, digitalAssetLinkService = assetLinks)

    private fun linked(value: Boolean) = Result.success(
        DigitalAssetLinkCheckResponseJson(linked = value, maxAge = null, debugString = null),
    )

    @Test
    fun `a known browser keeps its web domain without any network call`() = runTest {
        assertEquals(site, resolver.resolveForFill(site, "com.android.chrome"))
        assertEquals(site, resolver.resolveForFill(site, CLOUD_BROWSER_PACKAGE))
        coVerify(exactly = 0) { assetLinks.checkDigitalAssetLinksRelations(any(), any(), any(), any()) }
    }

    @Test
    fun `a fleet app signed with the vault's key keeps its web domain`() = runTest {
        assertEquals(site, resolver.resolveForFill(site, fleetApp))
    }

    @Test
    fun `another app keeps the web domain only when the site's asset links name it`() = runTest {
        coEvery {
            assetLinks.checkDigitalAssetLinksRelations(
                sourceWebSite = "https://login.example.com",
                targetPackageName = otherApp,
                targetCertificateFingerprint = CERT_SHA256,
                relations = any(),
            )
        } returns linked(true)
        assertEquals(site, resolver.resolveForFill(site, otherApp))
    }

    @Test
    fun `an unlinked app is matched by its own package`() = runTest {
        coEvery { assetLinks.checkDigitalAssetLinksRelations(any(), any(), any(), any()) } returns linked(false)
        assertEquals("androidapp://$otherApp", resolver.resolveForFill(site, otherApp))
    }

    @Test
    fun `a network failure fails closed to the app's own package`() = runTest {
        coEvery {
            assetLinks.checkDigitalAssetLinksRelations(any(), any(), any(), any())
        } returns Result.failure(IllegalStateException("offline"))
        assertEquals("androidapp://$otherApp", resolver.resolveForFill(site, otherApp))
    }

    @Test
    fun `an app whose signature cannot be read is never looked up`() = runTest {
        val hidden = "com.example.hidden"
        every { packageManager.getPackageInfo(hidden, PackageManager.GET_SIGNING_CERTIFICATES) } throws
            PackageManager.NameNotFoundException()
        assertEquals("androidapp://$hidden", resolver.resolveForFill(site, hidden))
        coVerify(exactly = 0) { assetLinks.checkDigitalAssetLinksRelations(any(), any(), any(), any()) }
    }

    @Test
    fun `a save from an unverified app writes the app URI and never waits on the network`() {
        assertEquals("androidapp://$otherApp", resolver.resolveForSave(site, otherApp))
        assertEquals(site, resolver.resolveForSave(site, "com.android.chrome"))
        coVerify(exactly = 0) { assetLinks.checkDigitalAssetLinksRelations(any(), any(), any(), any()) }
    }

    @Test
    fun `app URIs pass through untouched`() = runTest {
        val app = "androidapp://com.example.app"
        assertEquals(app, resolver.resolveForFill(app, "com.example.app"))
    }
}

/** SHA-256 of the fake signing cert {1, 2, 3}, as Digital Asset Links wants it. */
private const val CERT_SHA256 =
    "03:90:58:C6:F2:C0:CB:49:2C:53:3B:0A:4D:14:EF:77:CC:0F:78:AB:CC:CE:D5:28:7D:84:A1:A2:01:1C:FB:81"
