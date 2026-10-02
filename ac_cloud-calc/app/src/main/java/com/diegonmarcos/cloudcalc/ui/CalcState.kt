package com.diegonmarcos.cloudcalc.ui

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.diegonmarcos.cloudcalc.Declarations
import com.diegonmarcos.cloudcalc.Logic
import com.diegonmarcos.cloudcalc.engine.CalcApi

/** What every tab shares: the selected tab and mode per tab, the history, and a pending insert. */
class CalcState(private val prefs: SharedPreferences?) {
    var tab by mutableStateOf(Declarations.defaultTab.takeIf { d -> Declarations.tabs.any { it.id == d } } ?: Declarations.tabs.first().id)
    val modeByTab = mutableStateMapOf<String, String>()
    /** #770 the tab last shown in each section, so switching sections comes back to it. */
    val tabBySection = mutableStateMapOf<String, String>()

    /** The section [tab] belongs to: the segmented row at the top shows it selected. */
    val section: String get() = Declarations.sectionOf(tab)

    /** Show section [id]: its last tab, else its first. An unknown or empty section changes nothing. */
    fun showSection(id: String) {
        val tabs = Declarations.tabsOf(id)
        if (tabs.isEmpty()) return
        tabBySection[section] = tab
        tab = tabBySection[id]?.takeIf { t -> tabs.any { it.id == t } } ?: tabs.first().id
    }
    val history = mutableStateListOf<Logic.Entry>().apply { addAll(Logic.decode(prefs?.getString(KEY, null))) }

    /** Text a history tap sends to an expression mode: (mode id, text). */
    var pending by mutableStateOf<Pair<String, String>?>(null)

    fun remember(e: Logic.Entry, max: Int) {
        val next = Logic.remember(history.toList(), e, max)
        history.clear(); history.addAll(next)
        prefs?.edit()?.putString(KEY, Logic.encode(next))?.apply()
    }

    fun clearHistory() {
        history.clear()
        prefs?.edit()?.remove(KEY)?.apply()
    }

    /** Show [modeId] and hand it [text]: an expression mode, else the first one declared. */
    fun send(modeId: String, text: String) {
        val target = Declarations.mode(modeId)?.takeIf { it.kind == KIND_EXPRESSION }
            ?: Declarations.modes.firstOrNull { it.kind == KIND_EXPRESSION } ?: return
        modeByTab[target.tab] = target.id
        tab = target.tab
        pending = target.id to text
    }

    /** Open mode [modeId] on its tab (a Clock notification tap); an unknown id changes nothing. */
    fun show(modeId: String?) {
        val m = Declarations.mode(modeId ?: return) ?: return
        modeByTab[m.tab] = m.id
        tab = m.tab
    }

    companion object {
        const val KEY = "history"
        const val KIND_EXPRESSION = "expression"
    }
}

val LocalCalcApi = staticCompositionLocalOf<CalcApi> { error("no CalcApi provided") }
val LocalCalcState = staticCompositionLocalOf<CalcState> { error("no CalcState provided") }
