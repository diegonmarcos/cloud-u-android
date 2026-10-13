package com.diegonmarcos.cloudtoolssmallllms.data

import android.content.Context
import com.diegonmarcos.cloudtoolssmallllms.core.Price
import com.diegonmarcos.cloudtoolssmallllms.core.PriceBook
import com.diegonmarcos.superapp.decisions.Http
import com.diegonmarcos.superapp.modelcatalogue.CatalogueFetch
import com.diegonmarcos.superapp.modelcatalogue.FileCatalogueStore
import com.diegonmarcos.superapp.modelcatalogue.ModelCatalogue
import com.diegonmarcos.superapp.modelcatalogue.ModelCatalogueRepository
import java.io.File

/**
 * The shared model catalogue (libs:model-catalogue) as this app prices with it: the curated
 * selection merged into assets, its prices through [ModelCatalogueRepository] (live from OpenRouter
 * at most once a day, cached under filesDir/[CACHE_DIR], the bundled snapshot offline). No price
 * fetcher of this app's own: the "Prices as of" date is the repository's.
 */
class Catalogue(context: Context, http: Http, timeoutMs: Int) {
    val catalogue: ModelCatalogue.Catalogue? = runCatching {
        ModelCatalogue.parse(context.assets.open(ModelCatalogue.ASSET).bufferedReader().use { it.readText() })
    }.getOrNull()

    private val repo: ModelCatalogueRepository? = catalogue?.let {
        val fetch = CatalogueFetch { url -> runCatching { http.send(url, null, null, timeoutMs) }.getOrNull()?.takeIf { r -> r.code in 200..299 }?.body }
        ModelCatalogueRepository(it, fetch, FileCatalogueStore(File(context.filesDir, CACHE_DIR)), System::currentTimeMillis)
    }

    @Volatile private var priced: ModelCatalogueRepository.Priced? = null

    /** The stored prices (cache or snapshot), no network. */
    fun stored(): ModelCatalogueRepository.Priced? = repo?.stored().also { priced = it }

    /** Fresh cache as is, else one refresh. Blocking: off the main thread. */
    fun load(): ModelCatalogueRepository.Priced? = repo?.load().also { priced = it }

    fun asOf(): String = priced?.let { p -> repo?.asOf(p) } ?: catalogue?.pricingAsOf.orEmpty()

    /** A model's list price per 1M tokens: live or cached when known, else the snapshot. */
    val book: PriceBook = PriceBook { id ->
        val row = catalogue?.rows?.firstOrNull { it.id == id } ?: return@PriceBook null
        val p = priced?.prices?.get(id) ?: row.snapshot ?: return@PriceBook null
        val i = p.input ?: return@PriceBook null
        val o = p.output ?: return@PriceBook null
        if (i < 0 || o < 0 || p.unit != ModelCatalogue.PriceUnit.TOKENS) null else Price(i, o)
    }

    /** Every chat-section model the catalogue offers, for the Settings worker picker. */
    fun chatModels(): List<String> = catalogue?.sections?.filter { it.chat }?.flatMap { it.rows }?.map { it.id }?.distinct().orEmpty()

    companion object {
        const val CACHE_DIR = "model-catalogue"
    }
}
