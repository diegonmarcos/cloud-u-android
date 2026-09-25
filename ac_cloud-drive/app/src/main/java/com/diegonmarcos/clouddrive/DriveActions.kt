package com.diegonmarcos.clouddrive

/**
 * #579 what a screen may ask the Activity to do. The screens are Compose functions
 * with no Activity in hand; MainActivity implements this over its result launchers,
 * the FileProvider and the system intents. One seam, so a screen never builds an
 * Intent and a tester can grep every hand-off in one place.
 */
interface DriveActions {
    fun requestStorageAccess()
    fun requestTreeGrant()
    /** One of EngineActivity's ids (git, editor, rclone, mounts); [url] only for a git clone. */
    fun openEngine(engine: String, target: String, url: String = "")
    fun openImage(path: String, siblings: List<String>)
    fun openPdf(path: String)
    /** ACTION_VIEW through the FileProvider — whatever app claims the type. */
    fun openWith(path: String)
    fun share(paths: List<String>)
    fun shareText(title: String, text: String)
    fun copyText(text: String)
    fun openUrl(url: String)
    /** Launch an installed package, or its fallback URL; false when neither is possible. */
    fun launchApp(packageName: String, fallbackUrl: String): Boolean
}
