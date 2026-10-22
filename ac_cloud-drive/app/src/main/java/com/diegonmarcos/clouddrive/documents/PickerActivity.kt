package com.diegonmarcos.clouddrive.documents

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContract

/**
 * #1115 the GET_CONTENT / PICK answer. An app (or a WebView file input) that asks for ACTION_GET_CONTENT
 * or ACTION_PICK lists every activity with that filter; this is Drive's. It has no screen of its own:
 * it opens the system document picker on Drive's root (DriveDocumentsProvider) and relays the chosen
 * document back to the caller with a read grant, so the file is picked from Drive's store.
 */
class PickerActivity : ComponentActivity() {

    private val pick = registerForActivityResult(object : ActivityResultContract<Intent, Uri?>() {
        override fun createIntent(context: android.content.Context, input: Intent) = input
        override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
            if (resultCode == Activity.RESULT_OK) intent?.data else null
    }) { uri ->
        if (uri == null) setResult(Activity.RESULT_CANCELED)
        else setResult(Activity.RESULT_OK, Intent().setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        val root = DocumentsContract.buildDocumentUri("$packageName.documents", "${DriveDocumentsProvider.ROOT_DRIVE}:")
        val mimes = intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)
        pick.launch(
            Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(intent.type ?: "*/*")
                .putExtra(DocumentsContract.EXTRA_INITIAL_URI, root)
                .apply { if (mimes != null) putExtra(Intent.EXTRA_MIME_TYPES, mimes) },
        )
    }
}
