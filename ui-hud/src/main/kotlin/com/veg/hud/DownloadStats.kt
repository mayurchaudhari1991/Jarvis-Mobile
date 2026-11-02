package com.veg.hud

/** A progress observation: bytes downloaded at a wall-clock instant. */
data class ProgressSample(val bytes: Long, val atMillis: Long)

data class DownloadStats(
    val bytesPerSecond: Double,
    val secondsRemaining: Long?,
    val fractionComplete: Float
)

/**
 * Derives transfer speed and ETA from a window of progress observations.
 *
 * A rolling window rather than instantaneous deltas: the downloader emits once
 * per megabyte, and raw deltas on a mobile connection swing wildly enough that
 * an instantaneous ETA flickers between wildly different values and reads as
 * broken. ETA is withheld until enough samples exist to mean anything.
 */
object DownloadStatsCalculator {

    const val WINDOW_SIZE = 8
    const val MIN_SAMPLES_FOR_ETA = 3

    /**
     * Minimum elapsed time across the sample window before a speed is
     * reported at all. Several progress emissions can land within the same
     * millisecond — a buffered read flushing after a stall, or the first
     * sample after resuming from a `.part` offset — and dividing a real byte
     * delta by that near-zero interval produces impossible speeds (135 MB/s
     * on a phone). Below this window, report no speed rather than a spike.
     */
    const val MIN_WINDOW_MILLIS = 500L

    /** Upper bound on the ETA we will ever surface. A transient speed spike
     *  right after such a burst can otherwise imply "8s left" on a gigabyte
     *  that has barely started; capped rather than shown nonsensically. */
    private const val MAX_REMAINING_SECONDS = 24L * 3600L

    fun compute(samples: List<ProgressSample>, totalBytes: Long): DownloadStats {
        val latest = samples.lastOrNull()
        val fraction = if (totalBytes > 0 && latest != null) {
            (latest.bytes.toFloat() / totalBytes).coerceIn(0f, 1f)
        } else 0f

        if (samples.size < 2) {
            return DownloadStats(0.0, null, fraction)
        }

        val window = samples.takeLast(WINDOW_SIZE)
        val first = window.first()
        val last = window.last()
        val elapsedMillis = last.atMillis - first.atMillis
        val deltaBytes = last.bytes - first.bytes

        val speed = if (elapsedMillis >= MIN_WINDOW_MILLIS) {
            deltaBytes.toDouble() / (elapsedMillis / 1000.0)
        } else {
            0.0
        }

        val remaining = if (samples.size >= MIN_SAMPLES_FOR_ETA && speed > 0) {
            ((totalBytes - last.bytes) / speed).toLong().coerceIn(0L, MAX_REMAINING_SECONDS)
        } else null

        return DownloadStats(speed, remaining, fraction)
    }

    fun formatSpeed(bytesPerSecond: Double): String = when {
        bytesPerSecond <= 0 -> "—"
        bytesPerSecond >= 1_048_576 -> String.format("%.1f MB/s", bytesPerSecond / 1_048_576)
        else -> String.format("%.0f KB/s", bytesPerSecond / 1024)
    }

    fun formatRemaining(seconds: Long?): String = when {
        seconds == null -> "estimating…"
        seconds >= 3600 -> "${seconds / 3600}h ${(seconds % 3600) / 60}m left"
        seconds >= 60 -> "${seconds / 60}m ${seconds % 60}s left"
        else -> "${seconds}s left"
    }
}
