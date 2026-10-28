package com.diegonmarcos.superapp.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** One subpage of the Model Catalogue (Frontier, Core, Specialist, Medium, Small). */
data class AiCatalogueTab(val id: String, val label: String)

/** One model of a declared list: [id] is its OpenRouter id (a Medium / Small row with none is [local] only). */
data class AiCatalogueModel(
    val id: String, val name: String, val sub: String = "", val local: Boolean = false,
    /** The declared parameter count ("27B", "0.36B"); "" = not declared, so no size columns. */
    val params: String = "",
)

/** The size columns of one model, computed from its declared params by the catalogue's `sizes` block: Q8 disk, and RAM at Q8 / Q6 / Q4, in GB. */
data class AiSizes(val params: String, val diskQ8: Double, val ramQ8: Double, val ramQ6: Double, val ramQ4: Double) {
    /** "Params 3B · Q8 disk 3.2 GB · RAM Q8 / Q6 / Q4 3.7 / 3.0 / 2.6 GB", with the labels the catalogue declares. */
    fun text(l: AiSizeLabels): String = "${l.params} $params · ${l.diskQ8} ${gb(diskQ8)} GB · ${l.ram} ${gb(ramQ8)} / ${gb(ramQ6)} / ${gb(ramQ4)} GB"
    private fun gb(v: Double) = java.lang.String.format(java.util.Locale.ROOT, if (v < 10) "%.2f" else "%.1f", v)
}

data class AiSizeLabels(val params: String, val diskQ8: String, val ram: String)

/** A headed list: a company (Frontier), a domain (Specialist), or a tier (Medium / Small). */
data class AiCatalogueGroup(val id: String, val label: String, val models: List<AiCatalogueModel>)

/** One item of the Task Catalogue: [task] is the task it selects (its agent and model), null when the shared mapping has no such id. */
data class AiCatalogueItem(val id: String, val label: String, val task: AiTask?, val note: String? = null)

data class AiTaskCategory(val id: String, val label: String, val items: List<AiCatalogueItem>)
data class AiTaskGroup(val id: String, val label: String, val categories: List<AiTaskCategory>)

/**
 * The shared catalogues (assets/ai/ai-catalogue.json): the Model Catalogue's subpages and the Task Catalogue, parsed once.
 * Cloud Code (HTML) reads the same file through its resolver. [sharedTasks] is ai-tasks.json: a Medium & Small item
 * takes its agent and model from there by id; a Big LLM item declares its own.
 */
class AiCatalogue private constructor(root: JSONObject, sharedTasks: List<AiTask>) {
    val tabs: List<AiCatalogueTab> = root.getJSONArray("tabs").objs().map { AiCatalogueTab(it.getString("id"), it.getString("label")) }

    val frontier: List<AiCatalogueGroup> = root.getJSONObject("frontier").getJSONArray("companies").objs().map(::group)
    val specialist: List<AiCatalogueGroup> = root.getJSONObject("specialist").getJSONArray("domains").objs().map(::group)

    private val sizeCfg = root.getJSONObject("sizes")
    val sizeLabels = sizeCfg.getJSONObject("labels").let { AiSizeLabels(it.getString("params"), it.getString("disk_q8"), it.getString("ram")) }

    /** The size columns of [m] from its declared params; null when it declares none or they do not parse. */
    fun sizes(m: AiCatalogueModel): AiSizes? {
        val b = Regex("""^\s*([0-9]*\.?[0-9]+)\s*[Bb]\s*$""").find(m.params)?.groupValues?.get(1)?.toDoubleOrNull() ?: return null
        val bpw = sizeCfg.getJSONObject("bits_per_weight")
        val over = sizeCfg.getDouble("ram_overhead_gb")
        fun disk(q: String) = b * bpw.getDouble(q) / 8
        return AiSizes(m.params, disk("q8"), disk("q8") + over, disk("q6") + over, disk("q4") + over)
    }

    private val toolsRows = root.getJSONObject("tools_models").getJSONArray("models").objs()

    /** Cloud Tools' chat models of one tier ("medium" = 7B to 32B, "small" = at most 4B), as one list. */
    fun tier(tier: String): List<AiCatalogueModel> = toolsRows.filter { it.getString("tier") == tier }.map {
        val or = it.optString("openrouter_id")
        AiCatalogueModel(or.ifEmpty { it.getString("id") }, it.getString("name"), it.getString("params") + " · " + it.getString("role"), local = or.isEmpty(), params = it.getString("params"))
    }

    val groups: List<AiTaskGroup> = root.getJSONObject("tasks").getJSONArray("groups").objs().map { g ->
        AiTaskGroup(g.getString("id"), g.getString("label"), g.getJSONArray("categories").objs().map { c ->
            AiTaskCategory(c.getString("id"), c.getString("label"), c.getJSONArray("items").objs().map { i ->
                val id = i.getString("id")
                val label = i.getString("label")
                val own = if (i.has("model")) AiTask(id, label, i.getString("agent"), i.getString("model"), note = i.optString("note").ifBlank { null }) else null
                AiCatalogueItem(id, label, own ?: sharedTasks.firstOrNull { it.id == id }?.copy(label = label), i.optString("note").ifBlank { null })
            })
        })
    }

    /** Every task the catalogue can select: what ai-tasks.json declares plus the Big LLM items' own, each id once. */
    val tasks: List<AiTask> = (sharedTasks + groups.flatMap { g -> g.categories.flatMap { c -> c.items.mapNotNull { it.task } } }).distinctBy { it.id }

    private fun group(o: JSONObject) = AiCatalogueGroup(o.getString("id"), o.getString("label"), o.getJSONArray("models").objs().map {
        AiCatalogueModel(it.getString("id"), it.getString("name"), it.optString("note"), params = it.optString("params"))
    })

    companion object {
        const val ASSET = "ai/ai-catalogue.json"

        fun parse(text: String, sharedTasks: List<AiTask>) = AiCatalogue(JSONObject(text), sharedTasks)

        fun load(ctx: Context): AiCatalogue = parse(ctx.assets.open(ASSET).bufferedReader().use { it.readText() }, AiDesign.loadTasks(ctx))

        private fun JSONArray.objs() = (0 until length()).map { getJSONObject(it) }
    }
}
