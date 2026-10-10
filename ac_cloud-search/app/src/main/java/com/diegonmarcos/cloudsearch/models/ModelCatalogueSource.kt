package com.diegonmarcos.cloudsearch.models

import com.diegonmarcos.cloudsearch.core.Cache
import com.diegonmarcos.cloudsearch.core.models.ModelCatalogue
import com.diegonmarcos.cloudsearch.core.models.ModelCatalogueRepository
import com.diegonmarcos.cloudsearch.data.Services
import java.io.File

/**
 * The model catalogue as this app holds it: the curated selection from assets/models/catalogue.json
 * and its prices through [ModelCatalogueRepository] on the app's one network door ([Services.http]),
 * cached under filesDir/model-catalogue so the last prices read offline. [catalogue] is null only
 * when the bundled file cannot be read, which the page says rather than drawing an empty table.
 */
class ModelCatalogueSource private constructor(private val services: Services) {
    val catalogue: ModelCatalogue.Catalogue? = runCatching {
        ModelCatalogue.parse(services.app.assets.open(ASSET).bufferedReader().use { it.readText() })
    }.getOrNull()

    val prices: ModelCatalogueRepository? = catalogue?.let {
        ModelCatalogueRepository(it, services.http, Cache(File(services.app.filesDir, CACHE_DIR)), System::currentTimeMillis, services.cfg.timeoutMs)
    }

    companion object {
        const val ASSET = "models/catalogue.json"
        const val CACHE_DIR = "model-catalogue"

        @Volatile private var instance: ModelCatalogueSource? = null

        /** One per [Services] (a test that installs its own gets its own). Reads an asset: call it off the main thread. */
        fun of(s: Services): ModelCatalogueSource = instance?.takeIf { it.services === s } ?: synchronized(this) {
            instance?.takeIf { it.services === s } ?: ModelCatalogueSource(s).also { instance = it }
        }
    }
}
