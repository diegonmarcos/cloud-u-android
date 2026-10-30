package com.diegonmarcos.cloudcalc.ui

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.diegonmarcos.cloudcalc.Latex

/**
 * #1329 what the two LaTeX modes share: the user's saved formulas ("Mine"), the favourites, and the draft the Library's Edit
 * hands to the Editor. Saved and favourite formulas persist in the same preferences as History, under their own keys.
 */
class LatexModel(
    private val prefs: SharedPreferences?,
    private val now: () -> Long = System::currentTimeMillis,
    private val cfg: Latex.Config = Latex.config,
) {
    /** What the Editor opens with: the LaTeX, and the saved formula it edits (null = a new one). */
    data class Draft(val tex: String, val mineId: String?, val title: String)

    /** #1336 what the Graph page opens with: a catalog formula by id, or the LaTeX (and title) of a user's formula to convert. */
    data class GraphTarget(val formulaId: String?, val tex: String = "", val title: String = "")
    var graphTarget by mutableStateOf<GraphTarget?>(null)

    /** The Editor's current LaTeX, mirrored so the Graph page can plot it. */
    var editorText by mutableStateOf("")

    val mine = mutableStateListOf<Latex.Formula>().apply { addAll(Latex.decodeMine(prefs?.getString(MINE, null), cfg)) }
    val favourites = mutableStateListOf<String>().apply { addAll(Latex.decodeFavourites(prefs?.getString(FAV, null))) }
    var draft by mutableStateOf<Draft?>(null)

    /** Save [tex] under [title] to Mine: replaces the saved formula [id] when it exists, else adds a new one. Returns it. */
    fun saveMine(title: String, tex: String, id: String? = null): Latex.Formula {
        val name = title.trim().ifEmpty { tex.trim().take(40) }
        val at = mine.indexOfFirst { it.id == id }
        val f = Latex.Formula(
            if (at >= 0) mine[at].id else Latex.newMineId(mine.map { it.id }, now()),
            0, name, cfg.mine.id, tex, if (at >= 0) mine[at].description else "", mine = true,
        )
        if (at >= 0) mine[at] = f else mine.add(0, f)
        persist()
        return f
    }

    fun deleteMine(id: String) {
        if (!mine.removeAll { it.id == id }) return
        favourites.remove(id)
        persist()
    }

    fun toggleFavourite(id: String) {
        if (!favourites.remove(id)) favourites.add(id)
        persist()
    }

    fun isFavourite(id: String) = id in favourites

    private fun persist() {
        prefs?.edit()?.putString(MINE, Latex.encodeMine(mine.toList()))?.putString(FAV, Latex.encodeFavourites(favourites.toSet()))?.apply()
    }

    companion object {
        const val MINE = "latex_mine"
        const val FAV = "latex_favourites"
    }
}
