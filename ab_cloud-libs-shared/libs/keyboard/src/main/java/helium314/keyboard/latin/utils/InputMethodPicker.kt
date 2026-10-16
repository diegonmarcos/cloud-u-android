// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.latin.utils

import android.view.inputmethod.InputMethodInfo
import android.view.inputmethod.InputMethodSubtype
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.superapp.uikit.KitImeComposeView
import com.diegonmarcos.superapp.uikit.KitPalette
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import helium314.keyboard.compat.ImeCompat.switchInputMethodAndSubtype
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.R
import helium314.keyboard.latin.RichInputMethodManager
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.SubtypeLocaleUtils.displayName

/**
 * The input method / subtype picker, as a Compose panel hosted above the keys like the other bars
 * (an AlertDialog attached to the keyboard window is a View surface and cannot be themed with the
 * keyboard). Similar to what showSubtypePicker does in
 * https://github.com/rkkr/simple-keyboard/blob/master/app/src/main/java/rkr/simplekeyboard/inputmethod/latin/RichInputMethodManager.java
 */
class InputMethodPickerView(private val latinIme: LatinIME) : KitImeComposeView(latinIme) {

    private class Entry(val imi: InputMethodInfo, val subtype: InputMethodSubtype?, val title: String, val subtitle: String)

    private var entries by mutableStateOf<List<Entry>>(emptyList())
    private var currentIndex by mutableStateOf(0)
    private var onClose: Runnable? = null

    override fun palette(): KitPalette = Settings.getValues().mColors.toKitPalette()

    /** Reads the enabled input methods and subtypes now; [close] hides the panel again. */
    fun load(richImm: RichInputMethodManager, close: Runnable) {
        onClose = close
        val pm = latinIme.packageManager
        val thisImi = richImm.inputMethodInfoOfThisIme
        val currentSubtype = richImm.currentSubtype.rawSubtype
        val enabledImis = richImm.inputMethodManager.enabledInputMethodList
            .sortedBy { it.hashCode() }.sortedBy { it.loadLabel(pm).toString() } // first label, then hashCode
        val enabledSubtypes = mutableListOf<Pair<InputMethodInfo, InputMethodSubtype?>>()
        var currentSubtypeIndex = 0
        enabledImis.forEach { imi ->
            val subtypes = if (imi != thisImi) richImm.getEnabledInputMethodSubtypes(imi, true)
                else richImm.getEnabledInputMethodSubtypes(imi, true).sortedBy { it.displayName() }
            if (subtypes.isEmpty()) {
                enabledSubtypes.add(imi to null)
            } else {
                subtypes.forEach {
                    if (!it.isAuxiliary) {
                        enabledSubtypes.add(imi to it)
                        if (imi == thisImi && it == currentSubtype)
                            currentSubtypeIndex = enabledSubtypes.lastIndex
                    }
                }
            }
        }
        entries = enabledSubtypes.map { (imi, subtype) ->
            val subtypeName = if (imi == thisImi) subtype?.displayName()
                else subtype?.getDisplayName(latinIme, imi.packageName, imi.serviceInfo.applicationInfo)
            Entry(imi, subtype,
                (subtypeName?.ifBlank { imi.loadLabel(pm) } ?: imi.loadLabel(pm)).toString(),
                if (subtype == null) "" else imi.loadLabel(pm).toString())
        }
        currentIndex = currentSubtypeIndex
    }

    private fun pick(entry: Entry) {
        onClose?.run()
        val thisImi = RichInputMethodManager.getInstance().inputMethodInfoOfThisIme
        if (entry.imi == thisImi)
            latinIme.switchToSubtype(entry.subtype)
        else if (entry.subtype != null)
            latinIme.switchInputMethodAndSubtype(entry.imi, entry.subtype)
        else
            latinIme.switchInputMethod(entry.imi.id)
    }

    @Composable
    override fun Body() {
        val p = LocalKitPalette.current
        Column(Modifier.fillMaxWidth().background(p.surface).padding(vertical = 4.dp)) {
            Text(stringResource(R.string.select_input_method), color = p.textSecondary, fontSize = 14.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 260.dp)) {
                itemsIndexed(entries) { index, entry ->
                    Column(Modifier.fillMaxWidth().clickable { pick(entry) }.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text(entry.title, color = p.textPrimary, fontSize = 14.4.sp,
                            fontWeight = if (index == currentIndex) FontWeight.Bold else FontWeight.Normal)
                        if (entry.subtitle.isNotEmpty())
                            Text(entry.subtitle, color = p.textSecondary, fontSize = 13.6.sp)
                    }
                }
            }
            Text("✕", color = p.textSecondary, fontSize = 18.sp,
                modifier = Modifier.fillMaxWidth().clickable { onClose?.run() }.padding(horizontal = 16.dp, vertical = 6.dp))
        }
    }
}
