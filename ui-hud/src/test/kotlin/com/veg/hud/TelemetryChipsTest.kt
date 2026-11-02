package com.veg.hud

import com.veg.device.ThermalState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryChipsTest {

    private fun state(
        mode: ReactorMode = ReactorMode.IDLE,
        tps: Float = 0f,
        thermal: ThermalState = ThermalState.NOMINAL
    ) = ReactorState(mode, tps, thermal, batteryPercent = 80, isCharging = false)

    @Test
    fun `idle shows memory and thermal but no throughput`() {
        val chips = chipsFor(state(), residentGb = 4.9f)
        assertTrue(chips.none { it.label.contains("tok/s") })
        assertTrue(chips.any { it.label.contains("4.9") })
        assertTrue(chips.any { it.label.contains("NOMINAL") })
    }

    @Test
    fun `generating shows an active throughput chip`() {
        val chips = chipsFor(state(mode = ReactorMode.GENERATING, tps = 10.4f), residentGb = 5.2f)
        val tps = chips.first { it.label.contains("tok/s") }
        assertEquals(ChipHighlight.ACTIVE, tps.highlight)
        assertTrue(tps.label.contains("10.4"))
    }

    @Test
    fun `severe thermal marks chips as warnings`() {
        val chips = chipsFor(
            state(mode = ReactorMode.THERMAL, tps = 6.1f, thermal = ThermalState.SEVERE),
            residentGb = 5.2f
        )
        assertTrue(chips.any { it.highlight == ChipHighlight.WARN })
        assertTrue(chips.any { it.label.contains("SEVERE") })
    }

    @Test
    fun `chip count stays small enough for a corner`() {
        val chips = chipsFor(state(mode = ReactorMode.GENERATING, tps = 10f), residentGb = 5.2f)
        assertTrue("too many chips for the corner: ${chips.size}", chips.size <= 3)
    }
}
