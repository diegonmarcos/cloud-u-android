package app.sterna.core.data.rss

/**
 * The events this reader needs from an XML pull parser, normalised across the two ways the
 * platform exposes the stream: Android's `android.util.Xml` returns a content-access pull parser
 * whose events are Kotlin properties, while the JVM `org.xmlpull.v1` reference (kxml2) exposes the
 * same stream as Java getters. Both build an [RssPull]; the parsing logic talks only to this, so it
 * never has to know which is underneath — which is also exactly how a JVM test feeds it a real
 * kxml2 parser without touching Android.
 */
internal enum class RssEvent {
    START_DOCUMENT,
    START_TAG,
    END_TAG,
    TEXT,
    CDSECT,
    END_DOCUMENT,
    /** Any event the reader does not care about (comments, PI, entities…). */
    OTHER,
}

/** The slice of XML pull parsing this reader needs, and the sizes its events come in. */
internal interface RssPull {
    /** Point the pull parser at [input] and advance to its first event. This is the ONLY place a
     *  concrete parser is created; every adapter owns its own construction and event mapping. */
    fun open(reader: java.io.Reader)

    /** Advance to the next event and return it. */
    fun next(): RssEvent

    /** Advance and return the event, skipping until the next START_TAG or END_DOCUMENT. */
    fun nextTag(): RssEvent

    /** The current element's or entity's name, or null when the event has none. */
    fun name(): String?

    /** The text of a TEXT/CDSECT event. */
    fun text(): String

    /** The named attribute of the current START_TAG, or null when absent. */
    fun attribute(localName: String): String?
}