package app.sterna.ui.components

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.autofill.AutofillNode
import androidx.compose.ui.autofill.AutofillType
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalAutofill
import androidx.compose.ui.platform.LocalAutofillTree

/**
 * Register a field with Android's Autofill framework so password managers
 */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.autofill(
    types: List<AutofillType>,
    onFill: (String) -> Unit,
): Modifier = composed {
    val autofill = LocalAutofill.current
    val node = AutofillNode(onFill = onFill, autofillTypes = types)
    LocalAutofillTree.current += node
    this
        .onGloballyPositioned { node.boundingBox = it.boundsInWindow() }
        .onFocusChanged { state ->
            autofill?.run {
                // requestAutofill() requires the node's bounding box, which only exists once
                // onGloballyPositioned has run. On slow devices a field can take focus before
                if (state.isFocused) {
                    if (node.boundingBox != null) requestAutofillForNode(node)
                } else {
                    cancelAutofillForNode(node)
                }
            }
        }
}
