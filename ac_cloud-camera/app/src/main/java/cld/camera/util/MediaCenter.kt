package cld.camera.util

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import cld.camera.capturer.DEFAULT_MEDIA_STORE_CAPTURE_PATH
import cld.camera.capturer.SAF_URI_HOST_EXTERNAL_STORAGE

/**
 * The contract this camera uses to ask Cloud Media Center to open the folder it saves
 * captures into.
 *
 * Media Center had no external entry point for a single album. Its MainActivity never
 * reads its own [Intent] (it has no intent-filter beyond MAIN/LAUNCHER), and its
 * StandaloneActivity's VIEW filters are all scoped to one image/video item, not a
 * directory. So this action is declared by the matching half of this contract in
 * ac_cloud-media-center; the two sides must be changed together.
 *
 * The payload is a MediaStore RELATIVE_PATH ("DCIM/Camera"), NOT a constant: which
 * folder the camera writes to is a user setting. [captureFolderRelativePath] reads it
 * off the same value the capture path branches on, so the button follows the setting.
 */
object MediaCenter {

    const val PACKAGE = "com.diegonmarcos.mediacenter"

    /**
     * Constellation, the fleet installer. Named here because it is the answer to both
     * "Media Center is missing" and "Media Center is too old": on this fleet an app is
     * published as a release artefact and installed from it, so the recovery for either
     * state is the same app, not a store page.
     */
    const val CONSTELLATION_PACKAGE = "com.diegonmarcos.superapp"

    const val ACTION_VIEW_FOLDER = "com.diegonmarcos.mediacenter.action.VIEW_FOLDER"

    /** MediaStore RELATIVE_PATH of the folder to show, with no trailing separator. */
    const val EXTRA_RELATIVE_PATH = "com.diegonmarcos.mediacenter.extra.RELATIVE_PATH"

    /**
     * The folder the camera is currently configured to save into, as a MediaStore
     * RELATIVE_PATH, or null when that folder is one Media Center cannot address by
     * path.
     *
     * [storageLocation] is [cld.camera.CamConfig.storageLocation], the exact value
     * ImageSaver.obtainOutputUri()/VideoCapturer branch on:
     *
     *  - empty  — the MediaStore default, written with RELATIVE_PATH
     *             [DEFAULT_MEDIA_STORE_CAPTURE_PATH].
     *  - a SAF tree Uri — the user picked a directory, and captures are written into it
     *             with DocumentsContract instead.
     *
     * Returns null rather than a guess whenever the tree cannot be expressed as a
     * primary-volume relative path: a document provider that is not external storage
     * (Drive, a USB OTG provider) has no MediaStore path at all, and a secondary volume
     * (an SD card) has its own MediaStore volume that a RELATIVE_PATH query against the
     * primary one would silently miss. A null here surfaces as a message; guessing here
     * would open the wrong folder and look like it worked.
     */
    fun captureFolderRelativePath(storageLocation: String): String? {
        if (storageLocation.isEmpty()) {
            return DEFAULT_MEDIA_STORE_CAPTURE_PATH
        }

        val uri = try {
            Uri.parse(storageLocation)
        } catch (e: Exception) {
            return null
        }

        if (uri.host != SAF_URI_HOST_EXTERNAL_STORAGE) {
            return null
        }

        // "primary:DCIM/Camera" -> volume "primary", path "DCIM/Camera"
        val treeId = try {
            DocumentsContract.getTreeDocumentId(uri)
        } catch (e: Exception) {
            return null
        } ?: return null

        val separator = treeId.indexOf(':')
        if (separator < 0) return null

        if (treeId.substring(0, separator) != PRIMARY_VOLUME) return null

        val path = treeId.substring(separator + 1).trim('/')
        // The tree is the whole volume: there is no single album to open.
        return path.ifEmpty { null }
    }

    fun viewFolderIntent(relativePath: String): Intent =
        Intent(ACTION_VIEW_FOLDER)
            .setPackage(PACKAGE)
            .putExtra(EXTRA_RELATIVE_PATH, relativePath)

    private const val PRIMARY_VOLUME = "primary"
}
