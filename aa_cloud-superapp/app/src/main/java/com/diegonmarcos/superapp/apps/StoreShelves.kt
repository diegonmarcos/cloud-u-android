package com.diegonmarcos.superapp.apps

import android.content.Context
import com.diegonmarcos.superapp.appstore.AppStoreHost

/**
 * The Store's grouping (#563), answered by the CENTRAL classification and
 * nothing else: [PhoneTaxonomy] picks the folder, and the folder joins its
 * section through the same label-prefix rule [PhoneAppsFragment.renderAllApps]
 * buckets All Apps with. So cloud-drive's `ui.external_apps` folder
 * (prod_storage, ".Storage") lands it under "Tools · Data Apps / Storage", and
 * WhatsApp's `pkg:com.whatsapp` keyword under "Tools · Inboxes & AI / Chat" —
 * the same place the launcher already draws both.
 *
 * There is no store-side table on purpose. #170/#102/#405 each deleted a
 * second copy of a mapping; libs:appstore cannot read this app's build.json
 * anyway, so it asks through [AppStoreHost.classify] and this is the answer.
 *
 * Sectionless folders (Misc, the Others sink) get no shelf, the same way All
 * Apps draws them under no section of its own.
 */
object StoreShelves {

    fun of(ctx: Context, labelsByPackage: Map<String, String>): Map<String, AppStoreHost.Shelf> {
        if (labelsByPackage.isEmpty()) return emptyMap()
        // One batch: the metadata pass probes each declared intent category
        // once per call, not once per package.
        PhoneTaxonomy.prime(ctx, labelsByPackage)
        val sections = PhoneSections.loadFromBuildConfig()
        val folders = PhoneTaxonomy.folders.associateBy { it.id }
        val out = HashMap<String, AppStoreHost.Shelf>()
        for ((pkg, label) in labelsByPackage) {
            val folder = folders[PhoneTaxonomy.folderIdOf(pkg, label, ctx)] ?: continue
            val section = sections.firstOrNull { folder.label.startsWith(it.prefix) } ?: continue
            out[pkg] = AppStoreHost.Shelf(
                heading = folder.label, // MUTATION #563: section dropped
                // The folder's declared order, whose hundred-block IS its
                // section's position (#312), so shelves sort in All Apps order.
                order = folder.id, // MUTATION #563: declared order ignored
            )
        }
        return out
    }
}
