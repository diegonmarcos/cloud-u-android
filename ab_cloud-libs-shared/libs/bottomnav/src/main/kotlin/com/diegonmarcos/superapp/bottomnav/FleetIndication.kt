package com.diegonmarcos.superapp.bottomnav

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import kotlinx.coroutines.launch

/**
 * The press feedback of every island item and tab pill: black at 30% while pressed, 10% while
 * hovered or focused. These are the numbers of Compose's own default indication, which is what
 * SuperApp's island has always shown, because its host (a ComposeView in an XML layout) has no
 * MaterialTheme and so no ripple. An app whose island sits inside its own MaterialTheme would
 * otherwise inherit THAT theme's ripple: same pixels at rest, a different press. Passing this
 * indication explicitly takes the app's theme out of it.
 */
internal object FleetIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = Instance(interactionSource)
    override fun hashCode(): Int = -1
    override fun equals(other: Any?): Boolean = other === this

    private class Instance(private val source: InteractionSource) : Modifier.Node(), DrawModifierNode {
        private var pressed = false
        private var hovered = false
        private var focused = false

        override fun onAttach() {
            coroutineScope.launch {
                source.interactions.collect { interaction ->
                    when (interaction) {
                        is PressInteraction.Press -> pressed = true
                        is PressInteraction.Release, is PressInteraction.Cancel -> pressed = false
                        is HoverInteraction.Enter -> hovered = true
                        is HoverInteraction.Exit -> hovered = false
                        is FocusInteraction.Focus -> focused = true
                        is FocusInteraction.Unfocus -> focused = false
                    }
                    invalidateDraw()
                }
            }
        }

        override fun ContentDrawScope.draw() {
            drawContent()
            if (pressed) drawRect(Color.Black.copy(alpha = PRESSED), size = size)
            else if (hovered || focused) drawRect(Color.Black.copy(alpha = HOVERED), size = size)
        }
    }

    internal const val PRESSED = 0.3f
    internal const val HOVERED = 0.1f
}
