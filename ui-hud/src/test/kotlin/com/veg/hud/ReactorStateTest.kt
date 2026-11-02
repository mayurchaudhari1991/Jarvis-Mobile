package com.veg.hud

import com.veg.device.ThermalState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReactorStateTest {

    private fun state(
        mode: ReactorMode = ReactorMode.IDLE,
        tps: Float = 0f,
        thermal: ThermalState = ThermalState.NOMINAL,
        battery: Int = 80,
        charging: Boolean = false
    ) = ReactorState(mode, tps, thermal, battery, charging)

    @Test
    fun `generating spins faster than idle`() {
        assertTrue(state(mode = ReactorMode.GENERATING).spinScale() > state(mode = ReactorMode.IDLE).spinScale())
    }

    @Test
    fun `thermal spins slower than generating`() {
        assertTrue(state(mode = ReactorMode.THERMAL).spinScale() < state(mode = ReactorMode.GENERATING).spinScale())
    }

    @Test
    fun `pulse interval is derived from measured throughput`() {
        // 10 tok/s means one pulse every 100 ms
        assertEquals(100L, state(mode = ReactorMode.GENERATING, tps = 10f).pulseIntervalMillis())
        assertEquals(50L, state(mode = ReactorMode.GENERATING, tps = 20f).pulseIntervalMillis())
    }

    @Test
    fun `idle emits no pulses`() {
        assertEquals(0L, state(mode = ReactorMode.IDLE).pulseIntervalMillis())
    }

    @Test
    fun `zero throughput does not divide by zero`() {
        assertEquals(0L, state(mode = ReactorMode.GENERATING, tps = 0f).pulseIntervalMillis())
    }

    @Test
    fun `thermal mode uses the warm palette`() {
        assertTrue(state(mode = ReactorMode.THERMAL).isWarmPalette())
        assertFalse(state(mode = ReactorMode.GENERATING).isWarmPalette())
    }

    @Test
    fun `low power dims the reactor`() {
        assertTrue(state(mode = ReactorMode.LOW_POWER).dimFactor() < state(mode = ReactorMode.IDLE).dimFactor())
    }

    @Test
    fun `mode resolution puts thermal above generating`() {
        val mode = reactorModeFor(
            isGenerating = true,
            isThinking = false,
            thermal = ThermalState.SEVERE,
            batteryPercent = 80,
            isCharging = false
        )
        assertEquals(ReactorMode.THERMAL, mode)
    }

    @Test
    fun `mode resolution puts generating above low power`() {
        val mode = reactorModeFor(
            isGenerating = true,
            isThinking = false,
            thermal = ThermalState.NOMINAL,
            batteryPercent = 5,
            isCharging = false
        )
        assertEquals(ReactorMode.GENERATING, mode)
    }

    @Test
    fun `low battery while charging is not low power`() {
        val mode = reactorModeFor(
            isGenerating = false,
            isThinking = false,
            thermal = ThermalState.NOMINAL,
            batteryPercent = 5,
            isCharging = true
        )
        assertEquals(ReactorMode.IDLE, mode)
    }

    @Test
    fun `thinking is distinct from generating`() {
        val mode = reactorModeFor(
            isGenerating = false,
            isThinking = true,
            thermal = ThermalState.NOMINAL,
            batteryPercent = 80,
            isCharging = false
        )
        assertEquals(ReactorMode.THINKING, mode)
    }
}
