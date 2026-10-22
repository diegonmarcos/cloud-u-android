package app.cloudnotes.pro

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

object CapacitorConfig {

    @Serializable
    private data class Config(val cloudNotesVersion: String)
    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var config: Config

    fun init(context: Context) {
        val configJson = context.assets.open("capacitor.config.json")
            .bufferedReader()
            .readLines()
            .reduce { acc, s ->
                acc.trim().plus(s.trim())
            }
        config = json.decodeFromString(configJson)
    }

    fun getCloudNotesVersion() = config.cloudNotesVersion
}