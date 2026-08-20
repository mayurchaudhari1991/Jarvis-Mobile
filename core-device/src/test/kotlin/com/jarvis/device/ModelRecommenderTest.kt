package com.jarvis.device

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
        soc: String = "SM8650",
        thermal: Float = 0.2f,
        battery: Int = 80,
        charging: Boolean = false
    ) = DeviceReadings(
        totalRamBytes = (totalGb * 1024 * 1024 * 1024).toLong(),
        availableRamBytes = (availableGb * 1024 * 1024 * 1024).toLong(),
        perAppMemoryLimitBytes = (perAppLimitGb * 1024 * 1024 * 1024).toLong(),
        freeStorageBytes = (freeStorageGb * 1024 * 1024 * 1024).toLong(),
        cpuCores = cores,
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
    fun `mid range with 8GB total falls back below 8B`() {
        val r = recommender.recommend(readings(totalGb = 8.0, availableGb = 5.5, perAppLimitGb = 6.0, freeStorageGb = 60.0))
        assertTrue(r.best?.id != "qwen3-8b-q4km")
        assertEquals("qwen3-4b-q4km", r.best?.id)
    }

    @Test
    fun `budget device with 4GB gets the smallest model`() {
        val r = recommender.recommend(readings(totalGb = 4.0, availableGb = 2.2, perAppLimitGb = 3.0, freeStorageGb = 60.0))
        assertEquals("qwen3-1_7b-q4km", r.best?.id)
    }

    @Test
    fun `insufficient storage excludes models that do not fit on disk`() {
        val r = recommender.recommend(readings(totalGb = 12.0, availableGb = 8.5, perAppLimitGb = 8.0, freeStorageGb = 4.0))
        assertEquals("qwen3-1_7b-q4km", r.best?.id)
    }

    @Test
    fun `no viable model returns null best with a reason`() {
        val r = recommender.recommend(readings(totalGb = 2.0, availableGb = 0.4, perAppLimitGb = 0.5, freeStorageGb = 0.5))
        assertEquals(null, r.best)
        assertTrue(r.reasons.isNotEmpty())
    }

    @Test
    fun `alternatives exclude the recommended model and stay ordered by quality`() {
        val r = recommender.recommend(readings(totalGb = 12.0, availableGb = 8.5, perAppLimitGb = 8.0, freeStorageGb = 60.0))
        assertTrue(r.alternatives.none { it.id == r.best?.id })
        val qualities = r.alternatives.map { it.quality }
        assertEquals(qualities.sortedDescending(), qualities)
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
    fun `per-app memory limit caps the choice below what RAM alone allows`() {
        val r = recommender.recommend(readings(totalGb = 12.0, availableGb = 8.5, perAppLimitGb = 5.0))
        assertEquals("qwen3-4b-q4km", r.best?.id)
    }
}
