package com.veg.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelRecommenderTest {

    private val recommender = ModelRecommender()

    private fun readings(
        totalGb: Double,
        availableGb: Double,
        perAppLimitGb: Double = 8.0,
        freeStorageGb: Double = 60.0,
        cores: Int = 8,
        performanceCores: Int = 6,
        soc: String = "SM8650",
        thermal: Float = 0.2f,
        battery: Int = 80,
        charging: Boolean = false,
        deviceName: String = "Test Device"
    ) = DeviceReadings(
        deviceName = deviceName,
        totalRamBytes = (totalGb * 1024 * 1024 * 1024).toLong(),
        availableRamBytes = (availableGb * 1024 * 1024 * 1024).toLong(),
        perAppMemoryLimitBytes = (perAppLimitGb * 1024 * 1024 * 1024).toLong(),
        freeStorageBytes = (freeStorageGb * 1024 * 1024 * 1024).toLong(),
        cpuCores = cores,
        performanceCores = performanceCores,
        socModel = soc,
        thermalHeadroom = thermal,
        batteryPercent = battery,
        isCharging = charging
    )

    @Test
    fun `flagship with 12GB recommends the 8B model`() {
        val r = recommender.recommend(readings(totalGb = 12.0, availableGb = 8.5, perAppLimitGb = 8.0, freeStorageGb = 60.0))
        assertEquals("qwen3-8b-q4km", r.best?.id)
    }

    @Test
    fun `no viable model returns null best with a reason`() {
        val r = recommender.recommend(readings(totalGb = 2.0, availableGb = 0.4, perAppLimitGb = 0.5, freeStorageGb = 0.5))
        assertEquals(null, r.best)
        assertTrue(r.reasons.isNotEmpty())
    }

    @Test
    fun `a larger RAM budget never yields a lower quality model`() {
        val small = recommender.recommend(readings(totalGb = 8.0, availableGb = 2.5, freeStorageGb = 60.0))
        val large = recommender.recommend(readings(totalGb = 12.0, availableGb = 8.5, freeStorageGb = 60.0))
        val smallQuality = small.best?.quality ?: 0
        val largeQuality = large.best?.quality ?: 0
        assertTrue(
            "more RAM ($largeQuality) should not recommend worse than less RAM ($smallQuality)",
            largeQuality >= smallQuality
        )
    }

    @Test
    fun `the recommended model always fits the RAM budget`() {
        for (availableGb in listOf(1.5, 2.2, 3.5, 5.5, 8.5)) {
            val r = recommender.recommend(readings(totalGb = 12.0, availableGb = availableGb, freeStorageGb = 60.0))
            val best = r.best ?: continue
            val budget = (availableGb * 1024 * 1024 * 1024 * 0.85).toLong()
            val need = (best.residentBytes * 1.25).toLong()
            assertTrue(
                "at ${availableGb}GB free, ${best.displayName} needs $need but budget is $budget",
                need <= budget
            )
        }
    }

    @Test
    fun `the recommended model always fits in free storage`() {
        for (freeStorageGb in listOf(3.0, 4.0, 8.0, 60.0)) {
            val r = recommender.recommend(readings(totalGb = 12.0, availableGb = 8.5, freeStorageGb = freeStorageGb))
            val best = r.best ?: continue
            val storageBudget = (freeStorageGb * 1024 * 1024 * 1024).toLong() - 2L * 1024 * 1024 * 1024
            assertTrue(
                "${best.displayName} is ${best.fileBytes} bytes but only $storageBudget available",
                best.fileBytes <= storageBudget
            )
        }
    }

    @Test
    fun `alternatives exclude the recommendation and are ordered by tier then quality`() {
        val r = recommender.recommend(readings(totalGb = 12.0, availableGb = 8.5, freeStorageGb = 60.0))

        assertTrue(
            "alternatives must not repeat the recommendation",
            r.alternatives.none { it.id == r.best?.id }
        )

        // Alternatives are ordered by viability tier first, then by quality
        // within a tier. A high-quality TIGHT model correctly sorts after every
        // COMFORTABLE one, so asserting raw quality-descending across the whole
        // list would be wrong.
        val tiersInOrder = r.alternatives.map { candidate ->
            r.scored.first { it.candidate.id == candidate.id }.viability.ordinal
        }
        assertEquals("tiers should be non-decreasing", tiersInOrder.sorted(), tiersInOrder)

        for (tier in tiersInOrder.distinct()) {
            val qualitiesInTier = r.alternatives
                .filter { candidate ->
                    r.scored.first { it.candidate.id == candidate.id }.viability.ordinal == tier
                }
                .map { it.quality }
            assertEquals(
                "within tier $tier, quality should be descending",
                qualitiesInTier.sortedDescending(),
                qualitiesInTier
            )
        }
    }

    @Test
    fun `recommendation explains itself`() {
        val r = recommender.recommend(readings(totalGb = 12.0, availableGb = 8.5, perAppLimitGb = 8.0, freeStorageGb = 60.0))
        assertTrue(r.reasons.any { it.contains("RAM", ignoreCase = true) })
    }

    @Test
    fun `headroom factor is applied so a model needs more available RAM than it is resident`() {
        val r = recommender.recommend(readings(totalGb = 12.0, availableGb = 6.0, perAppLimitGb = 8.0, freeStorageGb = 60.0))
        assertTrue(r.best?.id != "qwen3-8b-q4km")
    }

    @Test
    fun `every catalog model is scored with a tier`() {
        val r = recommender.recommend(readings(totalGb = 12.0, availableGb = 3.5, freeStorageGb = 160.0))
        assertEquals(ModelCatalog.ALL.size, r.scored.size)
    }

    @Test
    fun `a model larger than available RAM is offered as FROM_STORAGE not hidden`() {
        val r = recommender.recommend(readings(totalGb = 12.0, availableGb = 3.5, freeStorageGb = 160.0))
        val big = r.scored.first { it.candidate.id == "qwen3-8b-q4km" }
        assertEquals(Viability.FROM_STORAGE, big.viability)
        assertTrue("should still report a usable estimate", big.estimatedTokensPerSecond > 0f)
    }

    @Test
    fun `a model that does not fit on disk is UNAVAILABLE`() {
        val r = recommender.recommend(readings(totalGb = 12.0, availableGb = 8.5, freeStorageGb = 3.0))
        val big = r.scored.first { it.candidate.id == "qwen3-8b-q4km" }
        assertEquals(Viability.UNAVAILABLE, big.viability)
    }

    @Test
    fun `storage backed models are estimated slower than comfortable ones`() {
        val r = recommender.recommend(readings(totalGb = 12.0, availableGb = 3.5, freeStorageGb = 160.0))
        val small = r.scored.first { it.candidate.id == "qwen3-1_7b-q8" }
        val big = r.scored.first { it.candidate.id == "qwen3-8b-q4km" }
        assertEquals(Viability.COMFORTABLE, small.viability)
        assertTrue(big.estimatedTokensPerSecond < small.estimatedTokensPerSecond)
    }

    @Test
    fun `scored models are ordered by tier then by estimated speed`() {
        val r = recommender.recommend(readings(totalGb = 12.0, availableGb = 3.5, freeStorageGb = 160.0))
        val tiers = r.scored.map { it.viability.ordinal }
        assertEquals(tiers.sorted(), tiers)

        val storageBacked = r.scored.filter { it.viability == Viability.FROM_STORAGE }
        if (storageBacked.size > 1) {
            val speeds = storageBacked.map { it.estimatedTokensPerSecond }
            assertEquals(speeds.sortedDescending(), speeds)
        }
    }
}
