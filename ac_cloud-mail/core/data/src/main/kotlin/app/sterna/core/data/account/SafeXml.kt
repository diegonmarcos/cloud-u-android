package app.sterna.core.data.account

import org.w3c.dom.Element
import org.w3c.dom.Node
import javax.xml.parsers.DocumentBuilderFactory

/**
 * XML parser config for input this module did not write (K-9 import, mail-autoconfig); each
 * `setFeature` is wrapped in `runCatching` so an unsupported feature doesn't fail the whole parse.
 */
internal fun safeXmlDocumentBuilderFactory(): DocumentBuilderFactory =
    DocumentBuilderFactory.newInstance().apply {
        // Harden against XXE / entity-expansion — a settings file needs none of it.
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        runCatching { isXIncludeAware = false }
        runCatching { isExpandEntityReferences = false }
    }

/** The direct child ELEMENTS of this element, in document order. */
internal fun Element.childElements(): List<Element> {
    val out = mutableListOf<Element>()
    val nodes = childNodes
    for (i in 0 until nodes.length) {
        val node = nodes.item(i)
        if (node.nodeType == Node.ELEMENT_NODE) out += node as Element
    }
    return out
}

/** Trimmed text of the first direct child element named [tag], or "" if absent. */
internal fun Element.childText(tag: String): String =
    childElements().firstOrNull { it.tagName == tag }?.textContent?.trim().orEmpty()
