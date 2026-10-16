package com.diegonmarcos.superapp.apps

import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.performSemanticsAction

/**
 * Reading the Store's composed pages: the tests used to walk the View tree by `tag`; they walk the
 * semantics tree by test tag and read the text, the ink and the mark the shared Store controls carry
 * ([com.diegonmarcos.superapp.appstore.StoreSemantics]).
 */
internal fun tagStartsWith(prefix: String) = SemanticsMatcher("test tag starts with $prefix") {
    it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true
}

internal fun SemanticsNode.tag(): String? = config.getOrNull(SemanticsProperties.TestTag)

/** The node's text (a merged button reads its label), "" when it has none. */
internal fun SemanticsNode.text(): String =
    config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()

internal fun SemanticsNode.isDisabled(): Boolean = config.contains(SemanticsProperties.Disabled)

internal fun SemanticsNode.walk(): List<SemanticsNode> = listOf(this) + children.flatMap { it.walk() }

/** Invokes the node's click action directly: no layout needed, so a row below the fold is as tappable as one above. */
internal fun androidx.compose.ui.test.SemanticsNodeInteraction.invokeClick() =
    performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)

internal fun hasStoreControl(kind: String) =
    SemanticsMatcher.expectValue(com.diegonmarcos.superapp.appstore.StoreSemantics.Control, kind)
