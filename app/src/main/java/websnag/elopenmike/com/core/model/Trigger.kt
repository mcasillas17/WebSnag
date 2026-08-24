package websnag.elopenmike.com.core.model

import kotlinx.serialization.Serializable

/**
 * Represents an environmental or physical stimulus that can activate or deactivate a rule.
 * Designed for extensibility to accommodate context-aware signals (Time, Location, BLE, Wi-Fi, Charging).
 */
@Serializable
sealed interface Trigger {
    val id: String
    val displayName: String

    /**
     * Triggered physically via an NFC tap.
     */
    @Serializable
    data class NfcTag(
        override val id: String,
        val tagUid: String,
        val customPayload: String? = null,
        override val displayName: String = "NFC Tag Tap"
    ) : Trigger

    /**
     * Triggered manually by user interaction in the app UI.
     */
    @Serializable
    data class Manual(
        override val id: String,
        override val displayName: String = "Manual Toggle"
    ) : Trigger

    /**
     * Future trigger: Triggered by scheduled time windows.
     */
    @Serializable
    data class TimeSchedule(
        override val id: String,
        val startHour: Int,
        val startMinute: Int,
        val endHour: Int,
        val endMinute: Int,
        val daysOfWeek: Set<Int>,
        override val displayName: String = "Time Schedule"
    ) : Trigger

    /**
     * Future trigger: Triggered by geofence or location context.
     */
    @Serializable
    data class Location(
        override val id: String,
        val label: String,
        val latitude: Double,
        val longitude: Double,
        val radiusMeters: Float,
        override val displayName: String = "Location: $label"
    ) : Trigger

    /**
     * Future trigger: Triggered by connected Wi-Fi SSID.
     */
    @Serializable
    data class WifiSsid(
        override val id: String,
        val ssid: String,
        override val displayName: String = "Wi-Fi: $ssid"
    ) : Trigger
}
