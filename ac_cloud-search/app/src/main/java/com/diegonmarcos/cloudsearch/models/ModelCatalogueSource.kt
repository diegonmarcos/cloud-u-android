package com.diegonmarcos.cloudsearch.models

import com.diegonmarcos.cloudsearch.data.Services
import com.diegonmarcos.superapp.modelcatalogue.CatalogueFetch
import com.diegonmarcos.superapp.modelcatalogue.FileCatalogueStore
import com.diegonmarcos.superapp.modelcatalogue.ModelCatalogue
import com.diegonmarcos.superapp.modelcatalogue.ModelCatalogueRepository
import java.io.File

/**
 * The model catalogue as this app holds it: the curated selection libs:model-catalogue merges into
 * assets (models/catalogue.json) and its prices through [ModelCatalogueRepository] on the app's one
 * network door ([Services.http]), cached under filesDir/model-catalogue so the last prices read
 * offline. [catalogue] is null only when the bundled file cannot be read, which the page says rather
 * than drawing an empty table. The catalogue itself is the shared lib's (Cloud Code's Chat reads the
 * same one); this app shows every section of it.
 */
class ModelCatalogueSource private constructor(private val services: Services) {
    val catalogue: ModelCatalogue.Catalogue? = runCatching {
        ModelCatalogue.parse(services.app.assets.open(ModelCatalogue.ASSET).bufferedReader().use { it.readText() })
    }.getOrNull()

    val prices: ModelCatalogueRepository? = catalogue?.let {
        val fetch = CatalogueFetch { url ->
            services.http.get(url, emptyMap(), services.cfg.timeoutMs).takeIf { r -> r.code in 200..299 }?.body
        }
        ModelCatalogueRepository(it, fetch, FileCatalogueStore(File(services.app.filesDir, CACHE_DIR)), System::currentTimeMillis)
    }

    companion object {
        const val CACHE_DIR = "model-catalogue"

        @Volatile private var instance: ModelCatalogueSource? = null

        /** One per [Services] (a test that installs its own gets its own). Reads an asset: call it off the main thread. */
        fun of(s: Services): ModelCatalogueSource = instance?.takeIf { it.services === s } ?: synchronized(this) {
            instance?.takeIf { it.services === s } ?: ModelCatalogueSource(s).also { instance = it }
        }
    }
}
