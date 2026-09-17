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
import app.sterna.contacts.AndroidContacts
import app.sterna.contacts.ContactPhotos
import app.sterna.contacts.SenderAvatarLoader

/** The avatar slot's size — the same for a photo, a logo and a monogram, so rows never resize. */
val ContactAvatarSize = 40.dp

/**
 * A sender's avatar, resolved down a declared fallback chain (task #464):
 *
 *  1. the device address book's photo for [email] — local and free, so it is always first;
 *  2. the sender DOMAIN's brand logo, fetched from the sender's own host and cached on disk
 *     (`app.sterna.contacts.SenderAvatarLoader` — metered-gated, and never a third-party service);
 *  3. the monogram, the fallback that was always there and stays the floor.
 *
 * Privacy is the design constraint, not an afterthought: the only network this avatar ever makes is
 * to the sender's OWN domain, and it sends only the domain — never an individual address, and
 * never a Gravatar-style lookup that would tell a third party who the user corresponds with.
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
    // then resolves the chain off the main thread. Keyed by the email and the device URI, so
    // recomposition never re-decodes a photo and never re-fetches a logo.
    val domain = SenderAvatarLoader.domainOf(email)
    val resolved by produceState<ImageBitmap?>(
        initialValue = ContactPhotos.cached(photoUri) ?: SenderAvatarLoader.cached(domain),
        email, photoUri,
    ) {
        value = resolve(context, email, photoUri)
    }
    Box(modifier.size(ContactAvatarSize)) {
        val image = resolved
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

/**
 * The chain's order, carried out off the main thread: the device photo first, then the domain logo,
 * then null (which the caller renders as a monogram). A failure at any step falls silently to the
 * next — an avatar is decoration and must never block or error a row.
 */
private suspend fun resolve(
    context: android.content.Context,
    email: String,
    photoUri: String?,
): ImageBitmap? {
    val device = when {
        photoUri != null -> ContactPhotos.load(context, photoUri)
        else -> AndroidContacts.photoUriFor(context, email)?.let { ContactPhotos.load(context, it) }
    }
    // The domain logo is only worth fetching when the device photo is not there — which is most
    // rows, and exactly the case Gmail fills with a sender brand mark.
    val domain = if (device != null) null else SenderAvatarLoader.load(context, SenderAvatarLoader.domainOf(email))
    return SenderAvatarLoader.pickAvatar(device, domain)
}
