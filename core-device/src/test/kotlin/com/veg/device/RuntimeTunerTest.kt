package com.veg.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeTunerTest {

    private val tuner = RuntimeTuner()

    private fun readings(
        cores: Int = 8,
        performanceCores: Int = 6,
        thermal: Float = 0.2f,
        battery: Int = 80,
        charging: Boolean = false,
        deviceName: String = "Test Device"
    ) = DeviceReadings(
        deviceName = deviceName,
        totalRamBytes = 12L * 1024 * 1024 * 1024,
        availableRamBytes = 7L * 1024 * 1024 * 1024,
        perAppMemoryLimitBytes = 8L * 1024 * 1024 * 1024,
        freeStorageBytes = 60L * 1024 * 1024 * 1024,
        cpuCores = cores,
        performanceCores = performanceCores,
        socModel = "SM8650",
        thermalHeadroom = thermal,
        batteryPercent = battery,
        isCharging = charging
    )

    @Test
    fun `cool device uses most cores but never all of them`() {
        val p = tuner.tune(readings(cores = 8, thermal = 0.1f))
        assertEquals(6, p.threadCount)
    }

    @Test
    fun `severe thermal drops thread count`() {
        val cool = tuner.tune(readings(cores = 8, thermal = 0.1f))
        val hot = tuner.tune(readings(cores = 8, thermal = 0.85f))
        assertTrue(hot.threadCount < cool.threadCount)
    }

    @Test
    fun `critical thermal drops to a minimum of two threads`() {
        val p = tuner.tune(readings(cores = 8, thermal = 0.99f))
        assertEquals(2, p.threadCount)
    }

    @Test
    fun `low battery unplugged reduces context`() {
        val normal = tuner.tune(readings(battery = 80, charging = false))
        val low = tuner.tune(readings(battery = 10, charging = false))
        assertTrue(low.contextTokens < normal.contextTokens)
    }

    @Test
    fun `low battery while charging does not reduce context`() {
        val normal = tuner.tune(readings(battery = 80, charging = false))
        val lowCharging = tuner.tune(readings(battery = 10, charging = true))
        assertEquals(normal.contextTokens, lowCharging.contextTokens)
    }

    @Test
    fun `thread count never exceeds core count`() {
        // cores=3 in NOMINAL: base = max(1, (3*0.75).toInt()) = 2, ceiling 3 -> 2
        // The ceiling binds on 1 core, where the computed target would be higher.
        val tiny = tuner.tune(readings(cores = 1, thermal = 0.85f))
        assertEquals(1, tiny.threadCount)
        assertTrue(tiny.threadCount <= 1)
    }

    @Test
    fun `thermal state maps headroom to named bands`() {
        assertEquals(ThermalState.NOMINAL, tuner.thermalState(0.1f))
        assertEquals(ThermalState.FAIR, tuner.thermalState(0.55f))
        assertEquals(ThermalState.SEVERE, tuner.thermalState(0.8f))
        assertEquals(ThermalState.CRITICAL, tuner.thermalState(0.97f))
    }

    @Test
    fun `thread count is clamped to core count even below the minimum`() {
        // A single-core device cannot run MIN_THREADS. The core ceiling wins,
        // which is correct: MIN_THREADS is a target, not a hard floor.
        val single = tuner.tune(readings(cores = 1, performanceCores = 1, thermal = 0.99f))
        assertEquals(1, single.threadCount)

        // A 2-core device where both cores are reported as "performance" — the
        // core-count ceiling still applies.
        val dual = tuner.tune(readings(cores = 2, performanceCores = 2, thermal = 0.1f))
        assertEquals(2, dual.threadCount)
    }

    @Test
    fun `batch size drops under severe thermal`() {
        assertEquals(512, tuner.tune(readings(thermal = 0.1f)).batchSize)
        assertEquals(128, tuner.tune(readings(thermal = 0.85f)).batchSize)
    }

    @Test
    fun `batch size drops on low battery when unplugged`() {
        assertEquals(128, tuner.tune(readings(battery = 10, charging = false)).batchSize)
        assertEquals(512, tuner.tune(readings(battery = 10, charging = true)).batchSize)
    }

    @Test
    fun `thread count follows performance cores not total cores`() {
        val bigLittle = tuner.tune(readings(cores = 8, performanceCores = 6, thermal = 0.1f))
        assertEquals(6, bigLittle.threadCount)
    }

    @Test
    fun `an all-efficiency device still gets at least one thread`() {
        val weak = tuner.tune(readings(cores = 4, performanceCores = 1, thermal = 0.1f))
        assertTrue(weak.threadCount >= 1)
    }

    @Test
    fun `performance cores never exceed total cores`() {
        val odd = tuner.tune(readings(cores = 4, performanceCores = 8, thermal = 0.1f))
        assertTrue(odd.threadCount <= 4)
    }
}
