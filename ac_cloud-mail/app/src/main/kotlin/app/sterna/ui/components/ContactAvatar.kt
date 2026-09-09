package app.sterna.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.sterna.contacts.ContactPhotos

/** The avatar slot's size — the same for a photo and for a monogram, so rows never resize. */
val ContactAvatarSize = 40.dp

/**
 * A contact's picture when the address book has one, their monogram otherwise.
 */
@Composable
fun ContactAvatar(
    email: String,
    name: String?,
    photoUri: String?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // Starts from whatever the cache already holds (no monogram flash on a re-typed character),
    // then decodes off the main thread. Keyed by the URI, so recomposition doesn't re-decode.
    val photo by produceState<ImageBitmap?>(ContactPhotos.cached(photoUri), photoUri) {
        value = ContactPhotos.load(context, photoUri)
    }
    Box(modifier.size(ContactAvatarSize)) {
        val image = photo
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(ContactAvatarSize).clip(CircleShape),
            )
        } else {
            Monogram(seed = email, label = name ?: email)
        }
    }
}
