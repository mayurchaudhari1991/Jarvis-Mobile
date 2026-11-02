package com.veg.hud

data class RingDef(
    val radiusFraction: Float,
    /** Radians per second. Sign sets direction; adjacent rings counter-rotate. */
    val speed: Float,
    val segments: Int,
    /** Share of each segment's slot that is drawn rather than left as a gap. */
    val gapFraction: Float,
    val strokeWidth: Float,
    val hot: Boolean
)

data class TickBand(
    val radiusFraction: Float,
    val count: Int,
    val length: Float,
    val speed: Float,
    val majorEvery: Int
)

/**
 * Static layout of the reactor assembly.
 *
 * Pure data and pure functions so the arrangement is testable without a Canvas:
 * the drawing code in Reactor.kt only walks these lists.
 */
object ReactorGeometry {

    val RINGS: List<RingDef> = listOf(
        RingDef(0.30f, 0.55f, 3, 0.55f, 2.2f, hot = true),
        RingDef(0.40f, -0.34f, 6, 0.30f, 1.2f, hot = false),
        RingDef(0.50f, 0.22f, 2, 0.85f, 4.0f, hot = true),
        RingDef(0.58f, -0.62f, 9, 0.18f, 1.0f, hot = false),
        RingDef(0.68f, 0.16f, 4, 0.42f, 2.6f, hot = true),
        RingDef(0.78f, -0.26f, 12, 0.14f, 1.0f, hot = false),
        RingDef(0.88f, 0.11f, 3, 0.62f, 3.2f, hot = false),
        RingDef(0.97f, -0.44f, 7, 0.22f, 1.4f, hot = true)
    )

    val TICK_BANDS: List<TickBand> = listOf(
        TickBand(0.45f, 90, 4f, 0.05f, 5),
        TickBand(0.73f, 120, 5f, -0.03f, 6),
        TickBand(1.06f, 150, 7f, 0.02f, 10)
    )

    /** Arc spans in radians, evenly spaced with a gap between each. */
    fun arcsFor(ring: RingDef): List<ClosedFloatingPointRange<Float>> {
        val slot = (2 * Math.PI / ring.segments).toFloat()
        return (0 until ring.segments).map { i ->
            val start = i * slot
            start..(start + slot * ring.gapFraction)
        }
    }

    /**
     * Ages of the token pulses currently on screen, oldest first, each in 0..1.
     *
     * Derived from wall-clock time and the interval rather than accumulated
     * state, so the animation is correct even if frames are dropped under load.
     */
    fun activePulses(nowMillis: Long, intervalMillis: Long, lifetimeMillis: Long): List<Float> {
        if (intervalMillis <= 0L || lifetimeMillis <= 0L) return emptyList()

        val ages = mutableListOf<Float>()
        var emittedAt = nowMillis - (nowMillis % intervalMillis)

        while (nowMillis - emittedAt < lifetimeMillis) {
            val age = (nowMillis - emittedAt).toFloat() / lifetimeMillis
            if (age in 0f..1f) ages += age
            emittedAt -= intervalMillis
            if (emittedAt < 0) break
        }

        return ages
    }
}
