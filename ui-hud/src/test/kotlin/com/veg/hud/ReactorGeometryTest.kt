package com.veg.hud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReactorGeometryTest {

    @Test
    fun `has eight rings as designed`() {
        assertEquals(8, ReactorGeometry.RINGS.size)
    }

    @Test
    fun `rings are ordered outward and stay within bounds`() {
        val radii = ReactorGeometry.RINGS.map { it.radiusFraction }
        assertEquals(radii.sorted(), radii)
        assertTrue(radii.all { it in 0.05f..1.0f })
    }

    @Test
    fun `adjacent rings counter rotate`() {
        val speeds = ReactorGeometry.RINGS.map { it.speed }
        for (i in 0 until speeds.size - 1) {
            assertTrue(
                "rings $i and ${i + 1} rotate the same way",
                speeds[i] * speeds[i + 1] < 0
            )
        }
    }

    @Test
    fun `every ring produces its declared number of arcs`() {
        for (ring in ReactorGeometry.RINGS) {
            assertEquals(ring.segments, ReactorGeometry.arcsFor(ring).size)
        }
    }

    @Test
    fun `arcs never overlap within a ring`() {
        for (ring in ReactorGeometry.RINGS) {
            val arcs = ReactorGeometry.arcsFor(ring)
            for (i in 0 until arcs.size - 1) {
                assertTrue(
                    "arc $i overlaps arc ${i + 1} on ring ${ring.radiusFraction}",
                    arcs[i].endInclusive <= arcs[i + 1].start
                )
            }
        }
    }

    @Test
    fun `zero interval produces no pulses`() {
        assertTrue(ReactorGeometry.activePulses(5000L, 0L, 1600L).isEmpty())
    }

    @Test
    fun `pulse ages are normalised between zero and one`() {
        val pulses = ReactorGeometry.activePulses(nowMillis = 5000L, intervalMillis = 100L, lifetimeMillis = 1600L)
        assertTrue(pulses.isNotEmpty())
        assertTrue(pulses.all { it in 0f..1f })
    }

    @Test
    fun `faster generation produces more simultaneous pulses`() {
        val slow = ReactorGeometry.activePulses(5000L, 200L, 1600L)
        val fast = ReactorGeometry.activePulses(5000L, 50L, 1600L)
        assertTrue(fast.size > slow.size)
    }
}
