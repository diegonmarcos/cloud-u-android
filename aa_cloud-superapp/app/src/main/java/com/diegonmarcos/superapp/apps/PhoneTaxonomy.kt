package com.diegonmarcos.superapp.apps

/**
 * packageName → phone-taxonomy SECTION.
 *
 * phone/apps sorts folders into sections ("Tools · Data Apps", "Services ·
 * Buro", …) and a folder joins its section through the single prefix character
 * its label starts with. That rule already existed, but only inside the
 * launcher's own rendering, so no other surface could ask the one useful
 * question: which section is this app in?
 *
 * Apps RSS needs exactly that, to filter the notification stream by the same
 * taxonomy the user organised their apps with. Both sides read the same
 * build.json, so a section added there is filterable the moment it ships —
 * there is no second list to keep in sync.
 */
object PhoneTaxonomy {

    private val folders: List<PhoneFolders.Folder> by lazy { PhoneFolders.loadFromBuildConfig() }
    private val labelById: Map<String, String> by lazy { folders.associate { it.id to it.label } }

    /** Memoised per package — a notification stream re-renders on every filter
     *  tap and every arriving notification, and classify() walks every folder's
     *  keyword list, which is pure waste to repeat for a package we just saw. */
    private val cache = HashMap<String, String>()

    /**
     * Section prefix character of the folder [packageName] classifies into, or
     * "" for an app landing in a prefix-less folder (Misc, New) — those belong
     * to no section and so are only ever shown by an "All" choice.
     */
    fun sectionPrefixOf(packageName: String, label: String): String {
        if (packageName.isBlank() || folders.isEmpty()) return ""
        cache[packageName]?.let { return it }
        val folderLabel = labelById[PhoneAppClassifier.classify(packageName, label, folders)]
        val first = folderLabel?.firstOrNull()
        val prefix = if (first == null || first.isLetterOrDigit()) "" else first.toString()
        cache[packageName] = prefix
        return prefix
    }
}
