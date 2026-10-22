package com.diegonmarcos.clouddrive.documents

import android.content.pm.ProviderInfo
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * #1115 the provider the system picker queries: it lists roots, children and opens a document, and
 * refuses to leave its root. attachInfo is the framework's own check that the declaration is the one
 * the picker accepts (exported, grantUriPermissions, MANAGE_DOCUMENTS), so it is exercised here too.
 */
@RunWith(RobolectricTestRunner::class)
class DriveDocumentsProviderTest {

    @get:Rule val tmp = TemporaryFolder()
    private lateinit var home: File
    private lateinit var provider: DriveDocumentsProvider

    @Before fun setUp() {
        home = tmp.newFolder("home")
        val store = File(home, "cloud-shared-store").apply { mkdirs() }
        File(store, "a.txt").writeText("hello drive")
        File(store, "sub").mkdirs()
        File(store, "sub/b.png").writeBytes(byteArrayOf(1, 2, 3))
        File(tmp.root, "secret.txt").writeText("outside")
        provider = object : DriveDocumentsProvider() {
            override fun roots() = linkedMapOf("drive" to store, "home" to home)
        }
        provider.attachInfo(RuntimeEnvironment.getApplication(), ProviderInfo().apply {
            authority = "com.diegonmarcos.clouddrive.documents"
            exported = true
            grantUriPermissions = true
            readPermission = "android.permission.MANAGE_DOCUMENTS"
            writePermission = "android.permission.MANAGE_DOCUMENTS"
        })
    }

    @Test fun listsBothRoots() {
        val c = provider.queryRoots(null)
        val ids = mutableListOf<String>()
        while (c.moveToNext()) {
            ids += c.getString(c.getColumnIndexOrThrow(Root.COLUMN_ROOT_ID))
            assertEquals("*/*", c.getString(c.getColumnIndexOrThrow(Root.COLUMN_MIME_TYPES)))
        }
        assertEquals(listOf("drive", "home"), ids)
    }

    @Test fun listsChildrenFoldersFirst() {
        val c = provider.queryChildDocuments("drive:", null, null as String?)
        val names = mutableListOf<String>()
        while (c.moveToNext()) names += c.getString(c.getColumnIndexOrThrow(Document.COLUMN_DISPLAY_NAME))
        assertEquals(listOf("sub", "a.txt"), names)
    }

    @Test fun describesADocument() {
        val c = provider.queryDocument("drive:sub/b.png", null)
        assertTrue(c.moveToFirst())
        assertEquals("image/png", c.getString(c.getColumnIndexOrThrow(Document.COLUMN_MIME_TYPE)))
        assertEquals(3L, c.getLong(c.getColumnIndexOrThrow(Document.COLUMN_SIZE)))
    }

    @Test fun opensADocumentReadOnly() {
        provider.openDocument("drive:a.txt", "r", null).use {
            assertEquals("hello drive", java.io.FileInputStream(it.fileDescriptor).readBytes().decodeToString())
        }
        try { provider.openDocument("drive:a.txt", "w", null); fail("write must be refused") } catch (_: SecurityException) { }
    }

    @Test fun cannotLeaveItsRoot() {
        try { provider.queryDocument("drive:../secret.txt", null); fail("traversal must be refused") } catch (_: SecurityException) { }
        assertFalse(provider.isChildDocument("drive:", "home:secret.txt"))
        assertTrue(provider.isChildDocument("drive:", "drive:sub/b.png"))
    }
}
