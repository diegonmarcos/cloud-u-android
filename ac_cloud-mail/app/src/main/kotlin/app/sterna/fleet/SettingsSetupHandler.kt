package app.sterna.fleet

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import app.sterna.core.data.settings.mailSettingsDataStore
import com.diegonmarcos.superapp.fleetconfig.SetupStores
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * The `datastore:settings` store of the fleet setup contract: Cloud Mail's settings DataStore (theme,
 * swipe actions, quiet hours, image allowlist, RSS feeds, …), read and written through the app's own
 * instance. Keys keep the type they are stored with; an incoming key the store does not hold yet takes
 * the type of its value. A write is one edit: all of it or none.
 */
object SettingsSetupHandler : SetupStores.SetupHandler {
    override fun read(ctx: Context, file: String): Map<String, Any?>? = runBlocking {
        ctx.mailSettingsDataStore().data.first().asMap().mapKeys { it.key.name }.ifEmpty { null }
    }

    override fun write(ctx: Context, file: String, set: Map<String, Any?>, remove: Set<String>): Boolean = runCatching {
        runBlocking {
            ctx.mailSettingsDataStore().edit { p ->
                val held = p.asMap().keys.associateBy { it.name }
                for (k in remove) held[k]?.let { p.remove(it) }
                for ((k, v) in set) put(p, held[k], k, v)
            }
        }
        true
    }.getOrDefault(false)

    @Suppress("UNCHECKED_CAST")
    private fun put(p: androidx.datastore.preferences.core.MutablePreferences, key: Preferences.Key<*>?, name: String, v: Any?) {
        if (v == null) { key?.let { p.remove(it) }; return }
        when (val old = key?.let { p[it] }) {
            is Boolean -> p[booleanPreferencesKey(name)] = v.toString().toBoolean()
            is Int -> p[intPreferencesKey(name)] = (v as? Number)?.toInt() ?: v.toString().toInt()
            is Long -> p[longPreferencesKey(name)] = (v as? Number)?.toLong() ?: v.toString().toLong()
            is Float -> p[floatPreferencesKey(name)] = (v as? Number)?.toFloat() ?: v.toString().toFloat()
            is Double -> p[doublePreferencesKey(name)] = (v as? Number)?.toDouble() ?: v.toString().toDouble()
            is Set<*> -> p[stringSetPreferencesKey(name)] = (v as? Collection<*>)?.map { it.toString() }?.toSet() ?: setOf(v.toString())
            else -> when (v) {
                is Boolean -> p[booleanPreferencesKey(name)] = v
                is Int -> p[intPreferencesKey(name)] = v
                is Long -> p[longPreferencesKey(name)] = v
                is Float -> p[floatPreferencesKey(name)] = v
                is Double -> p[doublePreferencesKey(name)] = v
                is Collection<*> -> p[stringSetPreferencesKey(name)] = v.map { it.toString() }.toSet()
                else -> p[stringPreferencesKey(name)] = v.toString()
            }.also { if (old != null && old !is String) Unit }
        }
    }

    fun register() = SetupStores.register("datastore:settings", this)
}
