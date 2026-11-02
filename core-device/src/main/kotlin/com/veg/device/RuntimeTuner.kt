package com.veg.device

data class RuntimeParams(
    val threadCount: Int,
    val contextTokens: Int,
    val batchSize: Int
)

enum class ThermalState { NOMINAL, FAIR, SEVERE, CRITICAL }

/**
 * Adapts inference parameters to transient device conditions.
 *
 * This never changes which model is loaded: thermal and battery readings move
 * minute to minute, and swapping a 5 GB model in response would cost far more
 * than it saves.
 */
class RuntimeTuner {

    fun tune(readings: DeviceReadings): RuntimeParams {
        val state = thermalState(readings.thermalHeadroom)

        // Use the performance cores, not a fraction of all cores. Threads on
        // efficiency cores gate the whole matmul barrier, so more threads
        // across mixed clusters is slower than fewer on fast ones.
        val baseThreads = readings.performanceCores.coerceIn(1, readings.cpuCores)

        val threads = when (state) {
            ThermalState.NOMINAL -> baseThreads
            ThermalState.FAIR -> maxOf(MIN_THREADS, baseThreads - 1)
            ThermalState.SEVERE -> maxOf(MIN_THREADS, baseThreads - 2)
            ThermalState.CRITICAL -> MIN_THREADS
        }.coerceAtMost(readings.cpuCores)

        val batterySaving = !readings.isCharging && readings.batteryPercent <= LOW_BATTERY_PERCENT

        val context = if (batterySaving) REDUCED_CONTEXT else FULL_CONTEXT
        val batch = if (batterySaving || state >= ThermalState.SEVERE) REDUCED_BATCH else FULL_BATCH

        return RuntimeParams(
            threadCount = threads,
            contextTokens = context,
            batchSize = batch
        )
    }

    fun thermalState(headroom: Float): ThermalState = when {
        headroom < 0.5f -> ThermalState.NOMINAL
        headroom < 0.75f -> ThermalState.FAIR
        headroom < 0.95f -> ThermalState.SEVERE
        else -> ThermalState.CRITICAL
    }

    private companion object {
        /**
         * Target floor for thread count under thermal pressure. Not an absolute
         * guarantee: the core-count ceiling applied afterwards wins on devices
         * with fewer cores than this, which is correct.
         */
        const val MIN_THREADS = 2
        const val LOW_BATTERY_PERCENT = 15
        const val FULL_CONTEXT = 4096
        const val REDUCED_CONTEXT = 2048
        const val FULL_BATCH = 512
        const val REDUCED_BATCH = 128
    }
}
