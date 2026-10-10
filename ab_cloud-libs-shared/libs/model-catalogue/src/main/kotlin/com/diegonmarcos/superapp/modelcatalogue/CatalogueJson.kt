package com.diegonmarcos.superapp.modelcatalogue

import com.diegonmarcos.superapp.modelcatalogue.ModelCatalogueRepository.Priced
import org.json.JSONArray
import org.json.JSONObject

/**
 * The catalogue as a host that does not draw with Compose receives it (Cloud Code's WebView, through
 * its Cordova plugin): the SAME ordering, selectability and cell text [ModelCataloguePage] draws,
 * computed here once, so the web page only lays the table out. Nothing is formatted twice.
 */
object CatalogueJson {
    /** [cat] already narrowed with [ModelCatalogue.Catalogue.only]; [asOf] from [ModelCatalogueRepository.asOf]. */
    fun shown(cat: ModelCatalogue.Catalogue, priced: Priced?, asOf: String): JSONObject {
        val groups = JSONArray()
        for (g in cat.groups) {
            val sections = JSONArray()
            for (s in g.sections) {
                val rows = JSONArray()
                for (r in ModelCatalogue.ordered(s, priced?.prices, priced?.missing.orEmpty())) {
                    rows.put(
                        JSONObject()
                            .put("provider", r.row.provider).put("name", r.row.name).put("id", r.row.id)
                            .put("params", ModelCatalogue.est(r.row.params, r.row.paramsEst))
                            .put("license", ModelCatalogue.est(r.row.license, r.row.licenseEst))
                            .put("input", ModelCatalogue.inputCell(s, r.price))
                            .put("output", ModelCatalogue.outputCell(s, r.price))
                            .put("anthropic", r.row.anthropic).put("listed", r.listed).put("selectable", r.selectable),
                    )
                }
                sections.put(
                    JSONObject().put("id", s.id).put("label", s.label).put("note", s.note).put("chat", s.chat)
                        .put("reference_row", s.needsReferenceRow).put("rows", rows),
                )
            }
            groups.put(JSONObject().put("id", g.id).put("label", g.label).put("sections", sections))
        }
        return JSONObject().put("as_of", asOf).put("origin", (priced?.origin ?: ModelCatalogueRepository.Origin.SNAPSHOT).name.lowercase())
            .put("groups", groups)
    }
}
