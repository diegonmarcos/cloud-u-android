// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.content.SharedPreferences

/** An in-memory SharedPreferences for the JVM tests (the module's tests run without Robolectric). */
class FakePrefs(initial: Map<String, Any?> = emptyMap()) : SharedPreferences {
    val data = LinkedHashMap<String, Any?>(initial)

    override fun getAll(): MutableMap<String, *> = LinkedHashMap(data)
    override fun getString(key: String, defValue: String?): String? = data[key] as? String ?: defValue
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? = defValues
    override fun getInt(key: String, defValue: Int): Int = data[key] as? Int ?: defValue
    override fun getLong(key: String, defValue: Long): Long = data[key] as? Long ?: defValue
    override fun getFloat(key: String, defValue: Float): Float = data[key] as? Float ?: defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean = data[key] as? Boolean ?: defValue
    override fun contains(key: String): Boolean = data.containsKey(key)
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val pending = LinkedHashMap<String, Any?>()
        private val removed = HashSet<String>()
        private var clear = false
        private fun put(k: String, v: Any?): SharedPreferences.Editor = also { pending[k] = v; removed.remove(k) }
        override fun putString(key: String, value: String?): SharedPreferences.Editor = put(key, value)
        override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor = put(key, values)
        override fun putInt(key: String, value: Int): SharedPreferences.Editor = put(key, value)
        override fun putLong(key: String, value: Long): SharedPreferences.Editor = put(key, value)
        override fun putFloat(key: String, value: Float): SharedPreferences.Editor = put(key, value)
        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = put(key, value)
        override fun remove(key: String): SharedPreferences.Editor = also { pending.remove(key); removed.add(key) }
        override fun clear(): SharedPreferences.Editor = also { clear = true }
        override fun commit(): Boolean { apply(); return true }
        override fun apply() {
            if (clear) data.clear()
            removed.forEach { data.remove(it) }
            data.putAll(pending)
        }
    }
}
