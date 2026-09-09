package app.sterna.contacts

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract.CommonDataKinds.Email
import androidx.core.content.ContextCompat

/** Reads email addresses from the device's contacts — only when the user has granted the permission. */
object AndroidContacts {
    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Address-book matches for [q], with the contact's thumbnail URI when they have a picture.
     */
    fun query(context: Context, q: String, limit: Int): List<ContactSuggestion> {
        if (!hasPermission(context) || q.isBlank()) return emptyList()
        val results = mutableListOf<ContactSuggestion>()
        val projection = arrayOf(Email.ADDRESS, Email.DISPLAY_NAME_PRIMARY, Email.PHOTO_THUMBNAIL_URI)
        val selection = "${Email.ADDRESS} LIKE ? OR ${Email.DISPLAY_NAME_PRIMARY} LIKE ?"
        val arg = "%$q%"
        runCatching {
            context.contentResolver.query(
                Email.CONTENT_URI, projection, selection, arrayOf(arg, arg), null,
            )?.use { cursor ->
                val addressIdx = cursor.getColumnIndexOrThrow(Email.ADDRESS)
                val nameIdx = cursor.getColumnIndexOrThrow(Email.DISPLAY_NAME_PRIMARY)
                val photoIdx = cursor.getColumnIndex(Email.PHOTO_THUMBNAIL_URI)
                val seen = HashSet<String>()
                while (cursor.moveToNext() && results.size < limit) {
                    val email = cursor.getString(addressIdx)?.trim() ?: continue
                    if (!email.contains('@') || !seen.add(email.lowercase())) continue
                    results += ContactSuggestion(
                        email = email,
                        name = cursor.getString(nameIdx)?.trim()?.ifBlank { null },
                        photoUri = if (photoIdx >= 0) {
                            cursor.getString(photoIdx)?.trim()?.ifBlank { null }
                        } else {
                            null
                        },
                    )
                }
            }
        }
        return results
    }
}
