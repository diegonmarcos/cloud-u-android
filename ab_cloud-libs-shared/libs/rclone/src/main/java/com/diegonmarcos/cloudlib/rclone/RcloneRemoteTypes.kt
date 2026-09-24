package com.diegonmarcos.cloudlib.rclone

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class RemoteField(val key: String, val label: String, val required: Boolean = false, val secret: Boolean = false, val default: String = "")

@Serializable
data class RemoteType(val type: String, val label: String, val fields: List<RemoteField> = emptyList())

@Serializable
private data class RemoteTypeCatalog(val types: List<RemoteType>)

/** The Remotes form's catalogue, from rclone-remote-types.json (an asset on Android, a file in the JVM suite). */
object RcloneRemoteTypes {
    const val ASSET = "rclone-remote-types.json"
    private val json = Json { ignoreUnknownKeys = true }
    fun parse(text: String): List<RemoteType> = json.decodeFromString(RemoteTypeCatalog.serializer(), text).types
}
