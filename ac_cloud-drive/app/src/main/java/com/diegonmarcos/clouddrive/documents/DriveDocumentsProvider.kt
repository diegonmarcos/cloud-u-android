package com.diegonmarcos.clouddrive.documents

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import com.diegonmarcos.clouddrive.R
import com.diegonmarcos.clouddrive.SharedStore
import java.io.File
import java.io.FileNotFoundException

/**
 * #1115 Cloud Drive as a SOURCE in the system file picker. A web page's <input type=file> and any
 * app's ACTION_OPEN_DOCUMENT / GET_CONTENT list only the apps that publish a DocumentsProvider
 * (android.content.action.DOCUMENTS_PROVIDER) or answer GET_CONTENT; Drive had neither, so it was
 * never offered. Read-only on purpose: a picker hands a file OUT of Drive; nothing here writes.
 *
 * Two roots, both plain Files because this app runs in cld.termux's uid (sharedUserId):
 *  - "drive": the fleet store (cloud-shared-store, SharedStore.root()),
 *  - "home":  cld.termux's home, the store's parent, offered only when this uid can read it.
 * A document id is `<root>:<path relative to the root>`; every id is resolved against its root's
 * canonical path, so `..` or a symlink cannot leave it.
 */
open class DriveDocumentsProvider : DocumentsProvider() {

    /** rootId -> directory; overridable so the JVM test can point it at a temp tree. */
    protected open fun roots(): Map<String, File> {
        val store = runCatching { SharedStore.root() }.getOrNull()
        val out = linkedMapOf<String, File>()
        if (store != null) out[ROOT_DRIVE] = store
        val home = store?.parentFile
        if (home != null && home.isDirectory && home.canRead()) out[ROOT_HOME] = home
        return out
    }

    private fun title(id: String): String = when (id) {
        ROOT_HOME -> context!!.getString(R.string.documents_root_home)
        else -> context!!.getString(R.string.app_name)
    }

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<String>?): Cursor {
        val c = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)
        for ((id, dir) in roots()) {
            c.newRow()
                .add(Root.COLUMN_ROOT_ID, id)
                .add(Root.COLUMN_DOCUMENT_ID, docId(id, dir, dir))
                .add(Root.COLUMN_TITLE, title(id))
                .add(Root.COLUMN_FLAGS, Root.FLAG_LOCAL_ONLY or Root.FLAG_SUPPORTS_IS_CHILD)
                .add(Root.COLUMN_MIME_TYPES, "*/*")
                .add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
        }
        return c
    }

    override fun queryDocument(documentId: String, projection: Array<String>?): Cursor {
        val c = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        addRow(c, documentId)
        return c
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<String>?, sortOrder: String?): Cursor {
        val c = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val (root, dir) = resolve(parentDocumentId)
        if (!dir.isDirectory) throw FileNotFoundException("not a directory: $parentDocumentId")
        dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))?.forEach {
            addRow(c, docId(root.first, root.second, it))
        }
        return c
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        if (mode.any { it == 'w' || it == 'a' || it == 't' }) throw SecurityException("Cloud Drive documents are read-only")
        val (_, file) = resolve(documentId)
        if (!file.isFile) throw FileNotFoundException("not a file: $documentId")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean = try {
        val parent = resolve(parentDocumentId).second.canonicalFile
        val child = resolve(documentId).second.canonicalFile
        child.path == parent.path || child.path.startsWith(parent.path + File.separator)
    } catch (_: FileNotFoundException) { false }

    private fun addRow(c: MatrixCursor, documentId: String) {
        val (root, file) = resolve(documentId)
        val flags = 0
        c.newRow()
            .add(Document.COLUMN_DOCUMENT_ID, docId(root.first, root.second, file))
            .add(Document.COLUMN_DISPLAY_NAME, if (file == root.second) title(root.first) else file.name)
            .add(Document.COLUMN_SIZE, if (file.isFile) file.length() else null)
            .add(Document.COLUMN_MIME_TYPE, mimeOf(file))
            .add(Document.COLUMN_LAST_MODIFIED, file.lastModified())
            .add(Document.COLUMN_FLAGS, flags)
    }

    /** `<root>:<relative path>`, relative to the root's own directory. */
    private fun docId(rootId: String, rootDir: File, file: File): String {
        val rel = file.canonicalFile.path.removePrefix(rootDir.canonicalFile.path).trimStart(File.separatorChar)
        return "$rootId:$rel"
    }

    /** A document id -> ((rootId, rootDir), file), refusing anything that resolves outside its root. */
    private fun resolve(documentId: String): Pair<Pair<String, File>, File> {
        val rootId = documentId.substringBefore(':')
        val rel = documentId.substringAfter(':', "")
        val dir = roots()[rootId] ?: throw FileNotFoundException("no such root: $rootId")
        val base = dir.canonicalFile
        val file = File(base, rel).canonicalFile
        if (file.path != base.path && !file.path.startsWith(base.path + File.separator))
            throw SecurityException("outside the root: $documentId")
        if (!file.exists()) throw FileNotFoundException("missing: $documentId")
        return (rootId to base) to file
    }

    private fun mimeOf(f: File): String {
        if (f.isDirectory) return Document.MIME_TYPE_DIR
        val ext = f.extension.lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    companion object {
        const val ROOT_DRIVE = "drive"
        const val ROOT_HOME = "home"
        private val DEFAULT_ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_FLAGS,
            Root.COLUMN_MIME_TYPES, Root.COLUMN_ICON,
        )
        private val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_SIZE,
            Document.COLUMN_MIME_TYPE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS,
        )
    }
}
