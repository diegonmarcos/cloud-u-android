package io.element.android.x.fleet

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.diegonmarcos.superapp.fleetconfig.SetupStores
import io.element.android.libraries.preferences.api.store.PreferenceDataStoreFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * A `datastore:<name>` store of the fleet setup contract over Cloud Matrix's own DataStore instance
 * (from the app graph's [PreferenceDataStoreFactory]; a second instance over the same file is an error).
 * Keys keep their stored type; a new key takes its value's type. A write is one edit.
 * // ponytail: Cloud Mail has the same handler over its settings store; move both to a lib on a third.
 */
class DataStoreSetupHandler(private val store: () -> DataStore<Preferences>) : SetupStores.SetupHandler {
    override fun read(ctx: Context, file: String): Map<String, Any?>? = runBlocking {
        store().data.first().asMap().mapKeys { it.key.name }.ifEmpty { null }
    }

    override fun write(ctx: Context, file: String, set: Map<String, Any?>, remove: Set<String>): Boolean = runCatching {
        runBlocking {
            store().edit { p ->
                val held = p.asMap().keys.associateBy { it.name }
                for (k in remove) held[k]?.let { p.remove(it) }
                for ((k, v) in set) put(p, held[k]?.let { p[it] }, k, v)
            }
        }
        true
    }.getOrDefault(false)

    private fun put(p: MutablePreferences, old: Any?, name: String, v: Any?) {
        if (v == null) return
        fun n() = v as? Number
        when (old ?: v) {
            is Boolean -> p[booleanPreferencesKey(name)] = v.toString().toBoolean()
            is Int -> p[intPreferencesKey(name)] = n()?.toInt() ?: v.toString().toInt()
            is Long -> p[longPreferencesKey(name)] = n()?.toLong() ?: v.toString().toLong()
            is Float -> p[floatPreferencesKey(name)] = n()?.toFloat() ?: v.toString().toFloat()
            is Double -> p[doublePreferencesKey(name)] = n()?.toDouble() ?: v.toString().toDouble()
            is Collection<*> -> p[stringSetPreferencesKey(name)] = (v as? Collection<*>)?.map { it.toString() }?.toSet() ?: setOf(v.toString())
            else -> p[stringPreferencesKey(name)] = v.toString()
        }
    }

    companion object {
        /** The fixed-name stores the manifest declares as migrating; per-session ones stay unserved. */
        fun registerAll(factory: PreferenceDataStoreFactory) {
            SetupStores.register("datastore:elementx_preferences", DataStoreSetupHandler { factory.create("elementx_preferences") })
            SetupStores.register("datastore:elementx_featureflag", DataStoreSetupHandler { factory.create("elementx_featureflag") })
            SetupStores.register("datastore:elementx_rageshake", DataStoreSetupHandler { factory.create("elementx_rageshake") })
            SetupStores.register("datastore:elementx_voice_player", DataStoreSetupHandler { factory.create("elementx_voice_player") })
            SetupStores.register("datastore:vector_analytics", DataStoreSetupHandler { factory.create("vector_analytics") })
            SetupStores.register("datastore:pin_code_store", DataStoreSetupHandler { factory.create("pin_code_store") })
        }
    }
}
