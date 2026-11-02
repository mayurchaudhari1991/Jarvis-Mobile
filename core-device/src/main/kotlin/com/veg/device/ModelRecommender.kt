package com.veg.device

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

        // perAppMemoryLimitBytes is deliberately NOT a constraint here. It comes
        // from ActivityManager.largeMemoryClass, which caps the Java heap; model
        // weights are mmap'd into native memory and are not subject to it.
        // Measured on a real device: the app's native heap reached 10.5 GB against
        // a 512 MB largeMemoryClass. Gating on it rejected every model on every
        // device.
        val scored = ModelCatalog.ALL.map { candidate ->
            val need = candidate.residentBytes * RAM_SAFETY_FACTOR
            val fitsDisk = candidate.fileBytes <= storageBudget
            val viability = when {
                !fitsDisk -> Viability.UNAVAILABLE
                need <= ramBudget -> Viability.COMFORTABLE
                candidate.residentBytes <= readings.availableRamBytes -> Viability.TIGHT
                else -> Viability.FROM_STORAGE
            }
            ScoredModel(candidate, viability, estimateThroughput(candidate, viability, readings))
        }

        // Display order: best tier first, then the fastest ESTIMATED throughput
        // within a tier. Quality-descending would be wrong here — among
        // storage-backed models a higher-quality model is slower, so it would
        // rank the least usable option first.
        val sortedScored = scored.sortedWith(
            compareBy({ it.viability.ordinal }, { -it.estimatedTokensPerSecond })
        )

        // `best` is chosen independently of display order: the user wants the
        // most capable model their device can hold comfortably, which is a
        // quality question, not a speed one. Prefer COMFORTABLE over TIGHT,
        // then highest quality within that tier.
        val eligible = scored.filter {
            it.viability == Viability.COMFORTABLE || it.viability == Viability.TIGHT
        }
        val bestRanked = eligible
            .sortedWith(compareBy({ it.viability.ordinal }, { -it.candidate.quality }))
        val best = bestRanked.firstOrNull()?.candidate

        if (best == null) {
            reasons += "No model fits this device. " +
                "Usable RAM budget ${gb(ramBudget)} GB, free storage ${gb(storageBudget)} GB."
            return Recommendation(best = null, alternatives = emptyList(), reasons = reasons, scored = sortedScored)
        }

        reasons += "RAM: ${gb(readings.availableRamBytes)} GB available of " +
            "${gb(readings.totalRamBytes)} GB."
        reasons += "Storage: ${gb(readings.freeStorageBytes)} GB free."
        val estSuffix = if (best.throughputMeasured) "" else " (est.)"
        reasons += "${best.displayName} needs ${gb(best.residentBytes)} GB resident, " +
            "expected ~${String.format("%.1f", best.expectedTokensPerSecond)} tok/s$estSuffix on ${readings.cpuCores} cores."

        val alternatives = bestRanked.drop(1).map { it.candidate }

        return Recommendation(
            best = best,
            alternatives = alternatives,
            reasons = reasons,
            scored = sortedScored
        )
    }

    /**
     * Rough throughput estimate accounting for memory pressure.
     *
     * These are ESTIMATES, not measurements — real paging cost depends on flash
     * speed and access pattern. On-device measurement replaces these numbers.
     */
    private fun estimateThroughput(
        candidate: ModelCandidate,
        viability: Viability,
        readings: DeviceReadings
    ): Float = when (viability) {
        Viability.COMFORTABLE -> candidate.expectedTokensPerSecond
        Viability.TIGHT -> candidate.expectedTokensPerSecond * 0.6f
        Viability.FROM_STORAGE -> {
            val over = candidate.residentBytes.toFloat() / readings.availableRamBytes.toFloat()
            maxOf(0.4f, candidate.expectedTokensPerSecond / (over * over * 2f))
        }
        Viability.UNAVAILABLE -> 0f
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
