package app.sterna.ui.attachment

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import app.sterna.R
import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.mail.MailRepository
import app.sterna.core.data.storage.AttachmentMime
import app.sterna.core.data.storage.StorageRepository
import app.sterna.core.jmap.model.EmailBodyPart

/**
 * The ONE path from "the user asked for this file" to "another application has it".
 *
 * It exists because a second surface now offers attachments. The reader has opened them since
 * [app.sterna.ui.message.MessageViewModel.openAttachment], and the message LIST now offers the same
 * files a screen earlier. Copying that method would be copying a FileProvider authority, a grant
 * flag, a filename policy and a MIME decision -- and the copy that drifts is the one that stops
 * granting read permission, or the one that keeps trusting the sender's Content-Type after the other
 * stopped. There is one, and both view models call it.
 *
 * This is not a new escape hatch. This repository moved away from ad-hoc `startActivity(ACTION_VIEW)`
 * on a file, and what it moved TO is exactly the shape below: a `content://` URI from the app's own
 * FileProvider, a read grant scoped to the receiving app, and a CHOOSER rather than an implicit
 * launch. No file path ever leaves this process, and no app is picked on the sender's behalf.
 */
object AttachmentOpen {

        /**
         * Download [part], write it into the attachment cache and hand it to a chooser. Suspends until
         * the chooser is up. THROWS on any failure -- the caller owns how a failure is said, because
         * the reader and the list say it in different places, and a swallowed failure here would be
         * the silent no-op a tap must never be.
         */
    suspend fun openExternally(
        app: Application,
        repo: MailRepository,
        storage: StorageRepository,
        credentials: AccountCredentials,
        part: EmailBodyPart,
        ownerId: String,
    ) {
        val bytes = repo.downloadAttachment(credentials, part, ownerId)
        // The name is the sender's and is reduced to a safe one INSIDE cacheAttachment; the file
        // that comes back is the authority on what was actually written.
        val file = storage.cacheAttachment(part.name, bytes)
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
        val view = Intent(Intent.ACTION_VIEW)
            // The type comes from the name THIS APP wrote, through the platform's extension map,
            // and falls back to the sender's claim only when the file says nothing. See
            // [AttachmentMime] for why the claim cannot be the first answer.
            .setDataAndType(uri, AttachmentMime.of(part.type, mimeFromName(file.name)))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        app.startActivity(
            Intent.createChooser(view, app.getString(R.string.status_open_attachment))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

        /** The platform's own extension→type map, asked about the name on disk. Lowercased because
         *  the map is case sensitive and `REPORT.PDF` is a pdf. */
    private fun mimeFromName(name: String): String? {
        val extension = name.substringAfterLast('.', "").lowercase()
        if (extension.isEmpty()) return null
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
    }

        /**
         * Whether this device's active network charges for what it carries. FAIL-SAFE: an unknown
         * network, a null service and any exception all count as metered, which is the same rule the
         * fleet's auto-update gate chose -- being wrong towards "ask first" costs a dialog, being
         * wrong the other way costs the owner's data allowance.
         */
    fun isMetered(context: Context): Boolean = runCatching {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return true
        // `isActiveNetworkMetered` and not a capability check: it already honours the user's own
        // "this wi-fi is metered" marking, which is the answer they actually want respected.
        manager.isActiveNetworkMetered
    }.getOrDefault(true)
}
