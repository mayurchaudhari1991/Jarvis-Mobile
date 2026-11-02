package com.veg.hud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-data/pure-function tests for [DownloadReactorGeometry].
 *
 * These cover the properties the brief calls out as the actual craft: layered
 * depth, non-harmonic per-element periods so the composition never visibly
 * repeats, deterministic-but-irregular emergent events, and organic
 * (sine-based) easing rather than linear motion. None of this touches Canvas,
 * so it is testable without an emulator.
 */
class DownloadReactorTest {

    @Test
    fun `has rings across all three depth layers`() {
        val depths = DownloadReactorGeometry.RINGS.map { it.depth }.toSet()
        assertEquals(setOf(Depth.FAR, Depth.MID, Depth.NEAR), depths)
    }

    @Test
    fun `far layer is dimmer and slower than near layer`() {
        val far = DownloadReactorGeometry.RINGS.filter { it.depth == Depth.FAR }
        val near = DownloadReactorGeometry.RINGS.filter { it.depth == Depth.NEAR }
        assertTrue(far.isNotEmpty() && near.isNotEmpty())

        val farAlpha = far.map { it.depth.alphaScale }.average()
        val nearAlpha = near.map { it.depth.alphaScale }.average()
        assertTrue("far layer should be dimmer than near layer", farAlpha < nearAlpha)

        val farPeriod = far.map { it.periodSeconds }.average()
        val nearPeriod = near.map { it.periodSeconds }.average()
        assertTrue("far layer should rotate slower (longer period) than near", farPeriod > nearPeriod)
    }

    @Test
    fun `no two ring periods share a common harmonic relationship`() {
        val periods = DownloadReactorGeometry.RINGS.map { it.periodSeconds }
        for (i in periods.indices) {
            for (j in periods.indices) {
                if (i == j) continue
                val ratio = periods[i] / periods[j]
                val nearestInt = Math.round(ratio)
                val distanceFromInteger = Math.abs(ratio - nearestInt)
                assertTrue(
                    "periods ${periods[i]} and ${periods[j]} are near-integer multiples ($ratio)",
                    distanceFromInteger > 0.05f || nearestInt == 0
                )
            }
        }
    }

    @Test
    fun `ring rotation completes exactly one full turn per declared period`() {
        val ring = DownloadReactorGeometry.RINGS.first()
        val angleAtZero = DownloadReactorGeometry.ringRotationRadians(ring, 0f)
        val angleAtPeriod = DownloadReactorGeometry.ringRotationRadians(ring, ring.periodSeconds)
        val fullTurn = ring.direction * 2f * Math.PI.toFloat()
        assertEquals(angleAtZero + fullTurn, angleAtPeriod, 1e-3f)
    }

    @Test
    fun `ring rotation direction matches sign`() {
        val cw = DownloadReactorGeometry.RINGS.first { it.direction > 0 }
        val ccw = DownloadReactorGeometry.RINGS.first { it.direction < 0 }
        assertTrue(DownloadReactorGeometry.ringRotationRadians(cw, 1f) > 0f)
        assertTrue(DownloadReactorGeometry.ringRotationRadians(ccw, 1f) < 0f)
    }

    @Test
    fun `breathe oscillates smoothly between bounds rather than jumping linearly`() {
        val period = 5f
        val samples = (0..100).map { i ->
            DownloadReactorGeometry.breathe(elapsedSeconds = i * period / 100f, periodSeconds = period)
        }
        assertTrue(samples.all { it in -1f..1f })
        // A sine wave is not monotonic across a full period — it must both rise and fall.
        val diffs = samples.zipWithNext { a, b -> b - a }
        assertTrue("expected a rising segment", diffs.any { it > 0f })
        assertTrue("expected a falling segment", diffs.any { it < 0f })
    }

    @Test
    fun `pulse events are deterministic for the same clock value`() {
        val ring = DownloadReactorGeometry.RINGS.first()
        val a = DownloadReactorGeometry.activePulseEvents(ring, elapsedSeconds = 12.34f)
        val b = DownloadReactorGeometry.activePulseEvents(ring, elapsedSeconds = 12.34f)
        assertEquals(a, b)
    }

    @Test
    fun `pulse events are irregularly spaced not on a shared clock`() {
        // Each ring's event interval is derived from its own period, so two
        // different rings should not emit pulses in lockstep.
        val ringA = DownloadReactorGeometry.RINGS[0]
        val ringB = DownloadReactorGeometry.RINGS[1]
        assertNotEquals(
            DownloadReactorGeometry.pulseIntervalSecondsFor(ringA),
            DownloadReactorGeometry.pulseIntervalSecondsFor(ringB)
        )
    }

    @Test
    fun `pulse progress is within unit range while active`() {
        val ring = DownloadReactorGeometry.RINGS.first()
        for (t in 0..200) {
            val events = DownloadReactorGeometry.activePulseEvents(ring, t * 0.37f)
            events.forEach { progress ->
                assertTrue("pulse progress $progress out of range", progress in 0f..1f)
            }
        }
    }

    @Test
    fun `node flare intensity is deterministic and bounded`() {
        val node = DownloadReactorGeometry.NODES.first()
        val intensity = DownloadReactorGeometry.nodeFlareIntensity(node, elapsedSeconds = 7.7f)
        assertTrue(intensity in 0f..1f)
        assertEquals(intensity, DownloadReactorGeometry.nodeFlareIntensity(node, elapsedSeconds = 7.7f), 1e-6f)
    }

    @Test
    fun `nodes exist only on the near layer as bright accents`() {
        assertTrue(DownloadReactorGeometry.NODES.isNotEmpty())
        assertTrue(DownloadReactorGeometry.NODES.all { it.depth == Depth.NEAR })
    }

    @Test
    fun `assembly fraction reflects progress and is clamped`() {
        assertEquals(0f, DownloadReactorGeometry.assembledRingCount(0f, totalRings = 8).let { it / 8f }, 0f)
        assertTrue(DownloadReactorGeometry.assembledRingCount(1f, totalRings = 8) == 8)
        assertTrue(DownloadReactorGeometry.assembledRingCount(-0.5f, totalRings = 8) >= 0)
        assertTrue(DownloadReactorGeometry.assembledRingCount(1.5f, totalRings = 8) <= 8)
    }

    @Test
    fun `core brightness increases monotonically with fraction`() {
        val low = DownloadReactorGeometry.coreBrightness(0.1f)
        val high = DownloadReactorGeometry.coreBrightness(0.9f)
        assertTrue(high > low)
    }

    @Test
    fun `depth alpha and stroke scale ordering is far lt mid lt near`() {
        assertTrue(Depth.FAR.alphaScale < Depth.MID.alphaScale)
        assertTrue(Depth.MID.alphaScale < Depth.NEAR.alphaScale)
        assertTrue(Depth.FAR.strokeScale <= Depth.MID.strokeScale)
    }

    @Test
    fun `far layer alpha is much dimmer than before so the core has something to contrast against`() {
        // v4 feedback: far layer sat around 20-25 percent alpha, which left no
        // contrast for the core to read as bright against. Pushed down hard.
        assertTrue(Depth.FAR.alphaScale <= 0.10f)
    }

    @Test
    fun `specular hits are short bright arcs, few in number`() {
        val hits = DownloadReactorGeometry.SPECULAR_HITS
        assertTrue("expect a handful, not a crowd", hits.size in 4..6)
        hits.forEach { hit ->
            assertTrue(
                "specular arc should be short (10-20 degrees), was ${hit.sweepDegrees}",
                hit.sweepDegrees in 10f..20f
            )
        }
    }

    @Test
    fun `specular hits move faster than their host ring`() {
        val hit = DownloadReactorGeometry.SPECULAR_HITS.first()
        val hostRing = DownloadReactorGeometry.RINGS[hit.ringIndex]
        assertTrue(
            "specular hit should orbit faster than its host ring",
            Math.abs(hit.speedMultiplier) > 1f
        )
        val hostAngle = DownloadReactorGeometry.ringRotationRadians(hostRing, 3f)
        val hitAngle = DownloadReactorGeometry.specularHitRotationRadians(hit, hostRing, 3f)
        assertNotEquals(hostAngle, hitAngle)
    }

    @Test
    fun `outward energy pulses are deterministic and bounded`() {
        val a = DownloadReactorGeometry.outwardPulseProgress(elapsedSeconds = 4.2f, emissionsPerSecond = 3f)
        val b = DownloadReactorGeometry.outwardPulseProgress(elapsedSeconds = 4.2f, emissionsPerSecond = 3f)
        assertEquals(a, b)
        a.forEach { progress -> assertTrue(progress in 0f..1f) }
    }

    @Test
    fun `outward energy pulses emit faster with higher throughput`() {
        val slow = DownloadReactorGeometry.outwardPulseProgress(elapsedSeconds = 10f, emissionsPerSecond = 0.5f)
        val fast = DownloadReactorGeometry.outwardPulseProgress(elapsedSeconds = 10f, emissionsPerSecond = 4f)
        assertTrue(fast.size >= slow.size)
    }

    @Test
    fun `outermost radius fraction accounts for tick overhang, not just ring radius`() {
        // A ring with ticks draws them out to radiusFraction * 1.02, so the
        // outermost fraction must be at least that far for whichever ring
        // reaches furthest once overhang is included — not simply the
        // largest radiusFraction on its own, which is what caused the v5
        // clipping bug (budgeting off the ring's centre-line).
        val expected = DownloadReactorGeometry.OUTER_RINGS.maxOf { ring ->
            val overhang = if (ring.ticksPerBand > 0) ring.radiusFraction * 0.02f else 0f
            ring.radiusFraction + overhang
        }
        assertEquals(expected, DownloadReactorGeometry.OUTERMOST_RADIUS_FRACTION, 1e-6f)
        assertTrue(
            "outermost fraction must be >= every ring's own radiusFraction",
            DownloadReactorGeometry.OUTER_RINGS.all {
                DownloadReactorGeometry.OUTERMOST_RADIUS_FRACTION >= it.radiusFraction
            }
        )
    }
}
