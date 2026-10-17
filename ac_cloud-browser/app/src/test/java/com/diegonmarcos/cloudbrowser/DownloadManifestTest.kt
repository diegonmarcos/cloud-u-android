package com.diegonmarcos.cloudbrowser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The download manager's manifest contract (Android 14+ foreground-service rules, target 35):
 * the service is typed dataSync and the app holds the matching typed FGS permission, the
 * notification, wake-lock and boot permissions, the job service is guarded by BIND_JOB_SERVICE,
 * and nothing download-related is exported.
 */
class DownloadManifestTest {
    private val android = "http://schemas.android.com/apk/res/android"

    private val doc by lazy {
        val f = listOf("src/main/AndroidManifest.xml", "app/src/main/AndroidManifest.xml").map(::File).first { it.exists() }
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(f).documentElement
    }

    private fun Element.all(tag: String): List<Element> =
        getElementsByTagName(tag).let { nl -> (0 until nl.length).map { nl.item(it) as Element } }

    private fun Element.a(name: String): String = getAttributeNS(android, name)

    private fun component(tag: String, cls: String): Element =
        doc.all(tag).firstOrNull { it.a("name") == cls } ?: throw AssertionError("no <$tag> $cls in the manifest")

    private val perms by lazy { doc.all("uses-permission").map { it.a("name") }.toSet() }

    @Test fun `the download service is a dataSync foreground service, not exported`() {
        val s = component("service", "com.diegonmarcos.superapp.browser.download.DlService")
        assertEquals("dataSync", s.a("foregroundServiceType"))
        assertEquals("false", s.a("exported"))
    }

    @Test fun `the app holds the typed FGS permission and the ones the service uses`() {
        for (p in listOf("FOREGROUND_SERVICE", "FOREGROUND_SERVICE_DATA_SYNC", "POST_NOTIFICATIONS", "WAKE_LOCK",
                "RECEIVE_BOOT_COMPLETED", "INTERNET", "ACCESS_NETWORK_STATE"))
            assertTrue("missing android.permission.$p", "android.permission.$p" in perms)
        val legacy = doc.all("uses-permission").first { it.a("name") == "android.permission.WRITE_EXTERNAL_STORAGE" }
        assertEquals("public Download/ writes are only for Android 8-9", "28", legacy.a("maxSdkVersion"))
    }

    @Test fun `the resume job, boot receiver and file provider are wired`() {
        val job = component("service", "com.diegonmarcos.superapp.browser.download.DlResumeJob")
        assertEquals("android.permission.BIND_JOB_SERVICE", job.a("permission"))
        assertEquals("false", job.a("exported"))
        val boot = component("receiver", "com.diegonmarcos.superapp.browser.download.DlBootReceiver")
        assertEquals("false", boot.a("exported"))
        val actions = boot.all("action").map { it.a("name") }
        assertTrue(actions.toString(), "android.intent.action.BOOT_COMPLETED" in actions)
        val fp = component("provider", "com.diegonmarcos.superapp.browser.download.DlFileProvider")
        assertEquals("\${applicationId}.dlfiles", fp.a("authorities"))
        assertEquals("false", fp.a("exported"))
        assertEquals("true", fp.a("grantUriPermissions"))
        assertNotNull(fp.all("meta-data").firstOrNull { it.a("resource") == "@xml/dl_file_paths" })
    }

    @Test fun `target SDK is one whose FGS rules this was written for`() {
        val gradle = listOf("build.gradle", "app/build.gradle").map(::File).first { it.exists() }.readText()
        val target = Regex("""targetSdk\s+(\d+)""").find(gradle)!!.groupValues[1].toInt()
        assertTrue("targetSdk $target: typed foreground services are required from 34", target >= 34)
    }
}
