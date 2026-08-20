package com.jarvis.device

/**
 * A snapshot of everything the recommender is allowed to know about the device.
 *
 * Deliberately a plain data class with no Android types: the scoring logic stays
 * pure and testable, and the single Android adapter that fills this in lives
 * apart from it.
 */
data class DeviceReadings(
    val totalRamBytes: Long,
    val availableRamBytes: Long,
    val perAppMemoryLimitBytes: Long,
    val freeStorageBytes: Long,
    val cpuCores: Int,
    val socModel: String,
    /** 0.0 = cold, 1.0 = throttling now. From PowerManager.getThermalHeadroom. */
    val thermalHeadroom: Float,
    val batteryPercent: Int,
    val isCharging: Boolean
)
