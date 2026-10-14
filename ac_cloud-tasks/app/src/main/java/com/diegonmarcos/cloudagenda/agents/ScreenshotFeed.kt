package com.diegonmarcos.cloudagenda.agents

import org.json.JSONArray

/**
 * Where the screenshot agent's raw material comes from: the OCR text and
 * image metadata Cloud Drive's screenshot agent writes for each screenshot
 * ([DriveScreenshotFeed] reads its v1 contract).
 *
 * The Calendar side only ever sees this interface. Each record handed to the
 * page is one JSON object:
 *   { "id": stable id of the screenshot, "text": OCR text,
 *     "takenAt": epoch millis or 0, "place": human place name or "",
 *     "source": file name,
 *     "sourceApp": app the screenshot was taken in or "",
 *     "entities": [{type: date|time|address, text}] }
 * The page's parser (assets/agents.js) turns those into event candidates.
 */
interface ScreenshotFeed {
    /** Null when the feed can be read, else one sentence saying why not. */
    fun unavailableReason(): String?

    /** Every record currently available, oldest first. Never throws. */
    fun records(): JSONArray
}
