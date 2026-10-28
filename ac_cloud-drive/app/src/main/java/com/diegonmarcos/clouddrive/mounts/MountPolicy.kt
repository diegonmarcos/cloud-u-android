package com.diegonmarcos.clouddrive.mounts

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json

/**
 * build.json::mount_keeper, baked by app/build.gradle and decoded by Declarations.mountKeeperJson. Pure JVM (kotlinx), so the
 * unit suite parses the repository's own build.json with the same code the phone runs.
 * Defaults are the safe reading of an absent block: keeper off.
 */
@Serializable
data class MountPolicy(
    val enabled: Boolean = false,
    @SerialName("start_on_app_open") val startOnAppOpen: Boolean = false,
    @SerialName("restore_on_boot") val restoreOnBoot: Boolean = false,
    @SerialName("restore_on_update") val restoreOnUpdate: Boolean = false,
    @SerialName("autoconnect_declared") val autoconnectDeclared: Boolean = false,
    @SerialName("health_interval_seconds") val healthIntervalSeconds: Long = 60,
    @SerialName("backoff_initial_seconds") val backoffInitialSeconds: Long = 5,
    @SerialName("backoff_max_seconds") val backoffMaxSeconds: Long = 300,
    @SerialName("wake_lock_during_transfers") val wakeLockDuringTransfers: Boolean = true,
    @SerialName("wake_lock_max_minutes") val wakeLockMaxMinutes: Long = 30,
    @SerialName("notification_channel") val notificationChannel: String = "mount-keeper",
    @SerialName("oem_battery_guides") val oemBatteryGuides: List<OemGuide> = emptyList(),
) {
    @Serializable
    data class OemGuide(
        val manufacturer: String,
        val title: String,
        val steps: String,
        @SerialName("intent_component") val intentComponent: String = "",
    )

    fun guideFor(manufacturer: String): OemGuide? = oemBatteryGuides.firstOrNull { it.manufacturer.equals(manufacturer.trim(), ignoreCase = true) }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        fun parse(text: String): MountPolicy = if (text.isBlank()) MountPolicy() else runCatching { json.decodeFromString(serializer(), text) }.getOrDefault(MountPolicy())
    }
}
