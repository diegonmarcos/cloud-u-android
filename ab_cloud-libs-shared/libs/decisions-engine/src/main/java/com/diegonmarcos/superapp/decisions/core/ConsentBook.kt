package com.diegonmarcos.superapp.decisions.core

import org.json.JSONObject

/**
 * Per app, per use: may this app's state for this use be sent to the model at all. The default is the
 * use's declaration (off for any use that reads mail or git content, whatever else it says); only the
 * user's own choice, made in an app the policy names as a consent setter, changes it, and is kept per
 * phone: a new phone starts at the defaults.
 */
class ConsentBook(private val store: KvStore) {

    private var doc: JSONObject = store.read() ?: JSONObject()

    @Synchronized
    fun granted(app: String, use: UseDecl): Boolean =
        if (doc.has(key(app, use.name))) doc.optBoolean(key(app, use.name), false) else !use.consentRequired

    @Synchronized
    fun set(app: String, use: String, granted: Boolean) {
        doc.put(key(app, use), granted)
        store.write(doc)
    }

    private fun key(app: String, use: String) = "$app|$use"
}
