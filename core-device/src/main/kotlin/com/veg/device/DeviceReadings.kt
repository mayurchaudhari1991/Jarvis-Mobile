package com.veg.device

/**
 * A snapshot of everything the recommender is allowed to know about the device.
 *
 * Deliberately a plain data class with no Android types: the scoring logic stays
 * pure and testable, and the single Android adapter that fills this in lives
 * apart from it.
 */
data class DeviceReadings(
    /** User-facing device name, e.g. "OnePlus 13R". Distinct from socModel. */
    val deviceName: String,
    val totalRamBytes: Long,
    val availableRamBytes: Long,
    val perAppMemoryLimitBytes: Long,
    val freeStorageBytes: Long,
    val cpuCores: Int,
    /**
     * Cores fast enough to be worth scheduling inference on.
     *
     * On a big.LITTLE phone the efficiency cores are far slower than the
     * performance ones, and llama.cpp's matmul is a parallel barrier — every
     * thread waits for the slowest. Counting all cores means the little ones
     * gate the whole batch.
     */
    val performanceCores: Int,
    val socModel: String,
    /** 0.0 = cold, 1.0 = throttling now. From PowerManager.getThermalHeadroom. */
    val thermalHeadroom: Float,
    val batteryPercent: Int,
    val isCharging: Boolean
)
