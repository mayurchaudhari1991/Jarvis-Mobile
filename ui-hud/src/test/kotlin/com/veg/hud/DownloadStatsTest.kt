package com.veg.hud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadStatsTest {

    private val total = 1_000_000_000L

    @Test
    fun `no samples yields zero speed and no eta`() {
        val s = DownloadStatsCalculator.compute(emptyList(), total)
        assertEquals(0.0, s.bytesPerSecond, 0.001)
        assertNull(s.secondsRemaining)
        assertEquals(0f, s.fractionComplete, 0.001f)
    }

    @Test
    fun `eta is withheld until enough samples exist`() {
        val two = listOf(ProgressSample(0L, 0L), ProgressSample(1_000_000L, 1000L))
        assertNull("two samples is not enough for an ETA", DownloadStatsCalculator.compute(two, total).secondsRemaining)
    }

    @Test
    fun `speed is computed across the window`() {
        val samples = (0..3).map { ProgressSample(it * 1_000_000L, it * 1000L) }
        val s = DownloadStatsCalculator.compute(samples, total)
        assertEquals(1_000_000.0, s.bytesPerSecond, 1000.0)
    }

    @Test
    fun `eta reflects remaining bytes at current speed`() {
        val samples = (0..3).map { ProgressSample(it * 1_000_000L, it * 1000L) }
        val s = DownloadStatsCalculator.compute(samples, 10_000_000L)
        assertEquals(7L, s.secondsRemaining)
    }

    @Test
    fun `a stalled transfer reports zero speed and no eta`() {
        val samples = (0..3).map { ProgressSample(5_000_000L, it * 1000L) }
        val s = DownloadStatsCalculator.compute(samples, total)
        assertEquals(0.0, s.bytesPerSecond, 0.001)
        assertNull(s.secondsRemaining)
    }

    @Test
    fun `only the most recent window is used`() {
        val slowThenFast = (0..7).map { ProgressSample(it * 100_000L, it * 1000L) } +
            (8..15).map { ProgressSample(700_000L + (it - 7) * 5_000_000L, it * 1000L) }
        val s = DownloadStatsCalculator.compute(slowThenFast, total)
        assertTrue("recent fast samples should dominate, got ${s.bytesPerSecond}", s.bytesPerSecond > 1_000_000)
    }

    @Test
    fun `fraction complete is clamped`() {
        val over = listOf(ProgressSample(2_000_000_000L, 0L), ProgressSample(2_000_000_000L, 1000L))
        assertEquals(1f, DownloadStatsCalculator.compute(over, total).fractionComplete, 0.001f)
    }

    @Test
    fun `speed formatting switches units`() {
        assertEquals("—", DownloadStatsCalculator.formatSpeed(0.0))
        assertEquals("512 KB/s", DownloadStatsCalculator.formatSpeed(524_288.0))
        assertEquals("2.0 MB/s", DownloadStatsCalculator.formatSpeed(2_097_152.0))
    }

    @Test
    fun `remaining time formatting is human readable`() {
        assertEquals("estimating…", DownloadStatsCalculator.formatRemaining(null))
        assertEquals("45s left", DownloadStatsCalculator.formatRemaining(45))
        assertEquals("2m 30s left", DownloadStatsCalculator.formatRemaining(150))
        assertEquals("1h 5m left", DownloadStatsCalculator.formatRemaining(3900))
    }

    @Test
    fun `samples arriving in the same instant do not produce absurd speed`() {
        // A buffered read flushing after a stall (or the first emission after
        // resuming from a .part offset) can land several progress samples
        // within the same millisecond. Dividing a large byte delta by a
        // near-zero elapsed time previously produced speeds like 135 MB/s on
        // a phone.
        val burst = listOf(
            ProgressSample(0L, 1000L),
            ProgressSample(50_000_000L, 1000L),
            ProgressSample(100_000_000L, 1001L)
        )
        assertEquals(0.0, DownloadStatsCalculator.compute(burst, total).bytesPerSecond, 0.001)
    }

    @Test
    fun `a window shorter than the minimum reports no speed`() {
        val tooFast = listOf(
            ProgressSample(0L, 0L),
            ProgressSample(10_000_000L, 200L)
        )
        assertEquals(0.0, DownloadStatsCalculator.compute(tooFast, total).bytesPerSecond, 0.001)
    }
}
