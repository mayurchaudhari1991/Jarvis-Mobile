package com.jarvis.device

/**
 * Picks the best model the device can actually hold.
 *
 * Thresholds are deliberately conservative: a model that technically fits but
 * then thermally chokes is a worse outcome than a smaller model that runs well.
 */
class ModelRecommender {

    fun recommend(readings: DeviceReadings): Recommendation {
        val reasons = mutableListOf<String>()

        // The app cannot use all available RAM: Android reclaims aggressively and
        // the KV cache grows with context. Require headroom above resident size.
        val ramBudget = (readings.availableRamBytes * AVAILABLE_RAM_USABLE).toLong()

        val storageBudget = readings.freeStorageBytes - STORAGE_RESERVE_BYTES

        val viable = ModelCatalog.ALL.filter { candidate ->
            val need = candidate.residentBytes * RAM_SAFETY_FACTOR
            val fitsRam = need <= ramBudget && need <= readings.perAppMemoryLimitBytes
            val fitsDisk = candidate.fileBytes <= storageBudget
            fitsRam && fitsDisk
        }.sortedByDescending { it.quality }

        val best = viable.firstOrNull()

        if (best == null) {
            reasons += "No model fits this device. " +
                "Usable RAM budget ${gb(ramBudget)} GB, free storage ${gb(storageBudget)} GB."
            return Recommendation(best = null, alternatives = emptyList(), reasons = reasons)
        }

        reasons += "RAM: ${gb(readings.availableRamBytes)} GB available of " +
            "${gb(readings.totalRamBytes)} GB, per-app limit ${gb(readings.perAppMemoryLimitBytes)} GB."
        reasons += "Storage: ${gb(readings.freeStorageBytes)} GB free."
        reasons += "${best.displayName} needs ${gb(best.residentBytes)} GB resident, " +
            "expected ~${best.expectedTokensPerSecond.toInt()} tok/s on ${readings.cpuCores} cores."

        if (ModelCatalog.ALL.first().id != best.id) {
            reasons += "Larger models were excluded because they exceed the safe RAM budget."
        }

        return Recommendation(
            best = best,
            alternatives = viable.drop(1),
            reasons = reasons
        )
    }

    private fun gb(bytes: Long): String =
        String.format("%.1f", bytes.toDouble() / (1024 * 1024 * 1024))

    private companion object {
        /** Never plan to use more than this share of currently-available RAM. */
        const val AVAILABLE_RAM_USABLE = 0.85

        /** Resident size is scaled by this to cover KV cache growth and fragmentation. */
        const val RAM_SAFETY_FACTOR = 1.25

        /** Leave this much disk free after the download. */
        const val STORAGE_RESERVE_BYTES = 2L * 1024 * 1024 * 1024
    }
}
