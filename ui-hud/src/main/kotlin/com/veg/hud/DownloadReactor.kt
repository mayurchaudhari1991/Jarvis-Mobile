package com.veg.hud

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** The clock wraps every this many milliseconds. Long enough that the longest
 *  ring period (far layer) completes several cycles before the wrap is
 *  visible as a seam, short enough to keep float precision comfortable. */
private const val CYCLE_MILLIS = 180_000

/**
 * Depth plane a ring or node sits on. This is what turns a flat diagram into
 * a sense of space: far things are big, dim, desaturated and slow; near
 * things are small, bright and quick. Nothing else in the composition needs
 * more *elements* to read as alive — it needs this range.
 */
enum class Depth(val alphaScale: Float, val strokeScale: Float, val speedScale: Float) {
    // Pushed hard toward invisible: this is the backdrop the bright core and
    // outer rings need to contrast against, not a visual layer in its own
    // right. (v4 feedback: 20-25% alpha here left the whole image sitting in
    // a narrow, muddy brightness band.)
    FAR(alphaScale = 0.08f, strokeScale = 0.6f, speedScale = 0.4f),
    MID(alphaScale = 0.55f, strokeScale = 1.0f, speedScale = 1.0f),
    NEAR(alphaScale = 1.0f, strokeScale = 1.5f, speedScale = 1.8f)
}

data class DownloadRingDef(
    val radiusFraction: Float,
    /** Seconds for one full rotation. Deliberately non-harmonic across rings
     *  (see [DownloadReactorGeometry.RINGS]) so the assembly never visibly repeats. */
    val periodSeconds: Float,
    /** +1 clockwise, -1 counter-clockwise. */
    val direction: Float,
    val depth: Depth,
    val segments: Int,
    val gapFraction: Float,
    val strokeWidth: Float,
    val hot: Boolean
)

data class DownloadNodeDef(
    val radiusFraction: Float,
    val angleOffset: Float,
    val periodSeconds: Float,
    val phaseOffset: Float,
    val depth: Depth
)

/**
 * An outer-band ring: large radius, but — unlike [Depth.FAR] — bright and
 * animated in its own right. The biggest visual real estate on screen should
 * not be the least active part of it, so these carry a richer vocabulary
 * than a plain [DownloadRingDef]: segments that breathe in length, a
 * traveling luminance sweep independent of rotation, and (via
 * [ticksPerBand]) illuminated tick groups.
 */
data class DownloadOuterRingDef(
    val radiusFraction: Float,
    val periodSeconds: Float,
    val direction: Float,
    val segments: Int,
    val gapFraction: Float,
    val strokeWidth: Float,
    val hot: Boolean,
    /** Seconds for one full lengthen/shorten cycle of each segment's gap. */
    val breathePeriodSeconds: Float,
    /** Seconds for one full lap of the traveling luminance sweep. */
    val sweepPeriodSeconds: Float,
    val ticksPerBand: Int
)

/** A short, near-white arc riding a ring faster than the ring's own rotation
 *  — the catch-the-eye glint of light on a turning edge. Kept few and short
 *  on purpose: this is a highlight, not a second ring. */
data class SpecularHitDef(
    val ringIndex: Int,
    val angleOffset: Float,
    val sweepDegrees: Float,
    /** Multiple of the host ring's angular speed; sign can flip the direction. */
    val speedMultiplier: Float
)

/**
 * Pure layout + timing for the download reactor. No Canvas here — everything
 * is data and pure functions of an elapsed-seconds clock, so it is testable
 * without an emulator and the drawing code in [DownloadReactor] only walks it.
 *
 * Three deliberate design choices live here, all requested after v1/v2 read
 * as mechanical:
 *  - three depth layers (far/mid/near) rather than one flat plane
 *  - non-harmonic per-ring periods so no two rings ever line back up visibly
 *  - deterministic "events" (pulses, flares) derived from the clock but
 *    spaced irregularly per-ring, so they read as unscheduled
 */
object DownloadReactorGeometry {

    val RINGS: List<DownloadRingDef> = listOf(
        // Far backdrop: a single, nearly-invisible ring — texture for the
        // eye to fall past, not a layer competing for attention. The outer
        // brightness now lives in OUTER_RINGS instead.
        DownloadRingDef(0.86f, 71.0f, +1f, Depth.FAR, segments = 4, gapFraction = 0.4f, strokeWidth = 1.0f, hot = false),

        // Mid layer: the main rings, moderate brightness. This is where the
        // bulk of the "reactor" read comes from.
        DownloadRingDef(0.68f, 17.5f, +1f, Depth.MID, segments = 4, gapFraction = 0.42f, strokeWidth = 2.6f, hot = true),
        DownloadRingDef(0.58f, 11.1f, -1f, Depth.MID, segments = 7, gapFraction = 0.18f, strokeWidth = 1.4f, hot = false),
        DownloadRingDef(0.48f, 25.3f, +1f, Depth.MID, segments = 2, gapFraction = 0.85f, strokeWidth = 3.4f, hot = true),
        DownloadRingDef(0.40f, 7.3f, -1f, Depth.MID, segments = 6, gapFraction = 0.3f, strokeWidth = 1.6f, hot = false),

        // Near layer: small, bright, quick — a few accents, not a crowd.
        DownloadRingDef(0.30f, 4.7f, +1f, Depth.NEAR, segments = 3, gapFraction = 0.6f, strokeWidth = 2.0f, hot = true)
    )

    /**
     * The outer band: large radius, but bright and independently animated —
     * the biggest visual real estate on screen carries the richest motion
     * vocabulary rather than the least. A counter-rotating pair (indices 0
     * and 1) creates apparent relative motion; index 2 is a thin, fast
     * framing ring near the assembly's outer edge.
     */
    val OUTER_RINGS: List<DownloadOuterRingDef> = listOf(
        DownloadOuterRingDef(
            radiusFraction = 0.78f, periodSeconds = 19.0f, direction = +1f,
            segments = 5, gapFraction = 0.5f, strokeWidth = 3.0f, hot = true,
            breathePeriodSeconds = 12.5f, sweepPeriodSeconds = 8.5f, ticksPerBand = 40
        ),
        DownloadOuterRingDef(
            radiusFraction = 0.70f, periodSeconds = 13.5f, direction = -1f,
            segments = 7, gapFraction = 0.32f, strokeWidth = 2.2f, hot = false,
            breathePeriodSeconds = 9.0f, sweepPeriodSeconds = 6.1f, ticksPerBand = 56
        ),
        DownloadOuterRingDef(
            radiusFraction = 0.965f, periodSeconds = 5.9f, direction = +1f,
            segments = 10, gapFraction = 0.2f, strokeWidth = 1.1f, hot = false,
            breathePeriodSeconds = 15.0f, sweepPeriodSeconds = 4.3f, ticksPerBand = 0
        )
    )

    /**
     * The largest radius, in units of the shared `radius` scalar used
     * throughout drawing, that any pixel of [OUTER_RINGS] can reach: a
     * ring's own radiusFraction plus its tick overhang (ticks are drawn out
     * to `radiusFraction * 1.02`) when it has ticks. [DownloadReactor] sizes
     * the whole assembly against this value — after also subtracting stroke
     * half-width — so nothing can fall outside the canvas regardless of how
     * the ring list changes.
     */
    val OUTERMOST_RADIUS_FRACTION: Float = OUTER_RINGS.maxOf { ring ->
        val tickOverhang = if (ring.ticksPerBand > 0) ring.radiusFraction * 0.02f else 0f
        ring.radiusFraction + tickOverhang
    }

    /** A handful of short, near-white glints riding the outer rings faster
     *  than their own rotation. Deliberately few (4-6) and short (10-20deg)
     *  per the design brief — more or longer reads as noise, not sparkle. */
    val SPECULAR_HITS: List<SpecularHitDef> = listOf(
        SpecularHitDef(ringIndex = 1, angleOffset = 0.4f, sweepDegrees = 14f, speedMultiplier = 2.4f),
        SpecularHitDef(ringIndex = 1, angleOffset = 3.1f, sweepDegrees = 11f, speedMultiplier = -1.8f),
        SpecularHitDef(ringIndex = 2, angleOffset = 1.7f, sweepDegrees = 16f, speedMultiplier = 2.1f),
        SpecularHitDef(ringIndex = 3, angleOffset = 5.0f, sweepDegrees = 12f, speedMultiplier = -2.6f),
        SpecularHitDef(ringIndex = 4, angleOffset = 2.2f, sweepDegrees = 18f, speedMultiplier = 1.9f)
    )

    val NODES: List<DownloadNodeDef> = listOf(
        DownloadNodeDef(0.34f, angleOffset = 0.6f, periodSeconds = 9.4f, phaseOffset = 0.1f, depth = Depth.NEAR),
        DownloadNodeDef(0.63f, angleOffset = 2.7f, periodSeconds = 13.9f, phaseOffset = 0.55f, depth = Depth.NEAR),
        DownloadNodeDef(0.45f, angleOffset = 4.4f, periodSeconds = 6.2f, phaseOffset = 0.8f, depth = Depth.NEAR)
    )

    /** Radians of rotation at [elapsedSeconds], periodic in [DownloadRingDef.periodSeconds]. */
    fun ringRotationRadians(ring: DownloadRingDef, elapsedSeconds: Float): Float {
        val cycles = elapsedSeconds / ring.periodSeconds
        return ring.direction * cycles * 2f * PI.toFloat()
    }

    /** Radians of rotation for an outer ring, same shape as [ringRotationRadians]. */
    fun outerRingRotationRadians(ring: DownloadOuterRingDef, elapsedSeconds: Float): Float {
        val cycles = elapsedSeconds / ring.periodSeconds
        return ring.direction * cycles * 2f * PI.toFloat()
    }

    /**
     * Radians of rotation for a specular hit: it shares its host ring's
     * angular position but multiplied by [SpecularHitDef.speedMultiplier], so
     * it visibly slides across the host ring's own segments rather than
     * staying pinned to one — the glint of light sweeping a turning edge.
     */
    fun specularHitRotationRadians(
        hit: SpecularHitDef,
        hostRing: DownloadRingDef,
        elapsedSeconds: Float
    ): Float {
        val cycles = elapsedSeconds / hostRing.periodSeconds
        return hit.speedMultiplier * cycles * 2f * PI.toFloat()
    }

    /**
     * Fraction (0..1) of how "open" an outer ring's segment gaps are right
     * now, breathing between narrow and wide on [DownloadOuterRingDef.breathePeriodSeconds].
     * 0.5 reproduces the ring's declared [DownloadOuterRingDef.gapFraction];
     * the value swings +-0.5 around it so segments visibly lengthen and
     * shorten rather than only rotating.
     */
    fun outerSegmentBreathe(ring: DownloadOuterRingDef, elapsedSeconds: Float): Float {
        val drift = breathe(elapsedSeconds, ring.breathePeriodSeconds) * 0.3f
        return (ring.gapFraction + drift).coerceIn(0.08f, 0.95f)
    }

    /**
     * Angular position (radians, 0..2pi) of a traveling luminance sweep along
     * an outer ring's circumference — a bright band that moves independently
     * of the ring's own rotation, like a signal propagating along it.
     */
    fun outerSweepAngle(ring: DownloadOuterRingDef, elapsedSeconds: Float): Float {
        val cycles = (elapsedSeconds / ring.sweepPeriodSeconds) % 1f
        return cycles * 2f * PI.toFloat()
    }

    /**
     * How close [angle] (radians) is to the sweep's current position, in
     * 0..1, falling off over roughly a third of the circle so the sweep
     * reads as a soft traveling band rather than a single lit point.
     */
    fun outerSweepProximity(ring: DownloadOuterRingDef, angle: Float, elapsedSeconds: Float): Float {
        val sweep = outerSweepAngle(ring, elapsedSeconds)
        val twoPi = 2f * PI.toFloat()
        var delta = (angle - sweep) % twoPi
        if (delta > PI.toFloat()) delta -= twoPi
        if (delta < -PI.toFloat()) delta += twoPi
        val width = twoPi / 6f
        return (1f - (Math.abs(delta) / width)).coerceIn(0f, 1f)
    }

    /**
     * Seconds between an outer ring's discrete structural events (segment
     * split/rejoin, momentary full brighten). Derived from the ring's own
     * breathe period so it does not land on a shared beat with anything else,
     * clamped into the 6-12s range the brief calls for.
     */
    fun outerEventIntervalSeconds(ring: DownloadOuterRingDef): Float =
        (ring.breathePeriodSeconds * 0.7f).coerceIn(6f, 12f)

    private const val OUTER_EVENT_LIFETIME_SECONDS = 1.4f

    /**
     * Progress (0..1) of the outer ring's current structural event, or null
     * if none is active right now. Same determinism contract as
     * [activePulseEvents]: a pure function of the clock.
     */
    fun outerEventProgress(ring: DownloadOuterRingDef, elapsedSeconds: Float): Float? {
        val interval = outerEventIntervalSeconds(ring)
        val timeIntoLifetime = (elapsedSeconds % interval)
        if (timeIntoLifetime >= OUTER_EVENT_LIFETIME_SECONDS) return null
        return (timeIntoLifetime / OUTER_EVENT_LIFETIME_SECONDS).coerceIn(0f, 1f)
    }

    /**
     * Whether tick index [tickIndex] of [ticksPerBand] total is currently lit
     * by the traveling-group illumination — groups of ticks light up and
     * move around the band together rather than sitting statically lit.
     */
    fun tickIlluminated(tickIndex: Int, ticksPerBand: Int, sweepAngle: Float, groupSize: Int): Boolean {
        if (ticksPerBand <= 0) return false
        val tickAngle = (tickIndex.toFloat() / ticksPerBand) * 2f * PI.toFloat()
        val twoPi = 2f * PI.toFloat()
        var delta = (tickAngle - sweepAngle) % twoPi
        if (delta < 0f) delta += twoPi
        val groupWidth = twoPi * (groupSize.toFloat() / ticksPerBand)
        return delta < groupWidth
    }

    /** Arc spans in radians, evenly spaced with a gap between each. */
    fun arcsFor(ring: DownloadRingDef): List<ClosedFloatingPointRange<Float>> {
        val slot = (2 * Math.PI / ring.segments).toFloat()
        return (0 until ring.segments).map { i ->
            val start = i * slot
            start..(start + slot * ring.gapFraction)
        }
    }

    /**
     * Sine-based drift in -1..1 with the given period. Used for organic
     * breathing on radius/alpha rather than linear motion — linear reads as
     * machinery, eased motion reads as alive.
     */
    fun breathe(elapsedSeconds: Float, periodSeconds: Float, phase: Float = 0f): Float =
        sin((elapsedSeconds / periodSeconds) * 2f * PI.toFloat() + phase)

    /**
     * Seconds between this ring's emergent pulse events. Derived from the
     * ring's own period (not a shared constant) so different rings' events
     * never land in lockstep — that is what keeps them reading as
     * unscheduled rather than a synchronized light show.
     */
    fun pulseIntervalSecondsFor(ring: DownloadRingDef): Float =
        (ring.periodSeconds * 0.37f + ring.radiusFraction).coerceIn(2.5f, 24f)

    private const val PULSE_LIFETIME_SECONDS = 1.8f

    /**
     * Progress (0..1) of every pulse currently traveling ring's circumference,
     * derived purely from [elapsedSeconds] and the ring's own interval — same
     * inputs always produce the same output, so this is deterministic, but
     * because each ring's interval differs, pulses across rings never
     * coincide on a visible beat.
     */
    fun activePulseEvents(ring: DownloadRingDef, elapsedSeconds: Float): List<Float> {
        val interval = pulseIntervalSecondsFor(ring)
        if (interval <= 0f) return emptyList()

        val progressInCycle = (elapsedSeconds % interval) / interval
        val timeIntoLifetime = progressInCycle * interval
        val results = mutableListOf<Float>()
        if (timeIntoLifetime < PULSE_LIFETIME_SECONDS) {
            results += (timeIntoLifetime / PULSE_LIFETIME_SECONDS).coerceIn(0f, 1f)
        }
        return results
    }

    /**
     * Deterministic flare intensity (0..1) for a node — a brief brightening
     * rather than constant brightness, on the node's own period so it does
     * not sync with any ring.
     */
    fun nodeFlareIntensity(node: DownloadNodeDef, elapsedSeconds: Float): Float {
        val raw = breathe(elapsedSeconds, node.periodSeconds, node.phaseOffset)
        // Bias toward the trough so flares are brief bright moments rather
        // than a slow constant pulse — cube the positive lobe.
        val positive = ((raw + 1f) / 2f).coerceIn(0f, 1f)
        return (positive * positive * positive)
    }

    /** How many rings have "arrived" at this fraction, outer rings arriving last. */
    fun assembledRingCount(fraction: Float, totalRings: Int): Int {
        val clamped = fraction.coerceIn(0f, 1f)
        return (clamped * totalRings).toInt().coerceIn(0, totalRings)
    }

    /** Core brightness scales with fraction so a glance still shows progress,
     *  but never bottoms out to invisible — there is always a live core. */
    fun coreBrightness(fraction: Float): Float =
        0.45f + fraction.coerceIn(0f, 1f) * 0.55f

    private const val OUTWARD_PULSE_LIFETIME_SECONDS = 1.0f
    private const val MIN_OUTWARD_EMISSIONS_PER_SECOND = 0.25f
    private const val MAX_OUTWARD_EMISSIONS_PER_SECOND = 3f

    /**
     * Progress (0..1) of every energy pulse currently traveling outward from
     * the core, oldest first. [emissionsPerSecond] should be derived from
     * live throughput (bytes/sec) so the rate at which the core visibly
     * "feeds" the rings stays state-bound rather than decorative — more
     * throughput, more frequent outward pulses.
     */
    fun outwardPulseProgress(elapsedSeconds: Float, emissionsPerSecond: Float): List<Float> {
        val rate = emissionsPerSecond.coerceIn(
            MIN_OUTWARD_EMISSIONS_PER_SECOND,
            MAX_OUTWARD_EMISSIONS_PER_SECOND
        )
        val interval = 1f / rate
        val results = mutableListOf<Float>()
        var emittedAt = elapsedSeconds - (elapsedSeconds % interval)
        while (elapsedSeconds - emittedAt < OUTWARD_PULSE_LIFETIME_SECONDS) {
            val age = (elapsedSeconds - emittedAt) / OUTWARD_PULSE_LIFETIME_SECONDS
            if (age in 0f..1f) results += age
            emittedAt -= interval
            if (emittedAt < 0f) break
        }
        return results
    }
}

/**
 * The download reactor: a layered, alive visual whose progress-linked core
 * still reads at a glance, but whose motion is what earns sustained
 * attention. Every ring has its own non-harmonic period; a single
 * [rememberInfiniteTransition] drives one normalised clock and every element
 * derives its own phase from it, so only one animation runs regardless of
 * element count.
 *
 * v1 filled the screen but was visually identical at 6% and 94% complete.
 * v2 added more elements but kept everything on one flat plane, one flat
 * rate, two colours, so it still read as a technical diagram. v4 restored
 * the v1 composition with depth/rhythm/events but sat in a narrow, muted
 * brightness band with no contrast. This version pushes dynamic range hard:
 * a near-blinding core, a nearly-invisible far backdrop, and — because the
 * outer rings occupy the most screen area — the outer band is now a bright,
 * independently-animated structural layer rather than dim haze, carrying
 * its own vocabulary (segment breathing, a traveling luminance sweep,
 * counter-rotating pairs, illuminated tick groups, discrete structural
 * events) plus a few short specular glints and outward energy pulses tied
 * to live throughput.
 */
@Composable
fun DownloadReactor(
    fraction: Float,
    bytesPerSecond: Double,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "download-reactor")
    val clock by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(CYCLE_MILLIS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "download-reactor-clock"
    )

    val clampedFraction = fraction.coerceIn(0f, 1f)
    // Active transfer (bytes actually moving) nudges the near layer's
    // apparent liveliness a little faster; stalled reads as visibly calmer
    // without freezing solid.
    val activity = if (bytesPerSecond > 0.0) 1f else 0.55f
    // Outward energy pulses emit faster with real throughput, in MB/s,
    // mapped into a readable 0.25-5 events/sec range.
    val emissionsPerSecond = (0.4f + (bytesPerSecond / 1_000_000.0).toFloat() * 0.25f)
        .coerceIn(0.25f, 3f)

    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val elapsedSeconds = clock * (CYCLE_MILLIS / 1000f)

        // The constraint has to be on the outermost DRAWN PIXEL, not a ring's
        // centre-line: tick marks reach past their host ring's radius, and
        // stroke width extends half its width past the centre-line on each
        // side (the glow pass is 3x strokeWidth). Budgeting from the
        // centre-line, as an earlier version did, let the assembly run past
        // the screen edge on all four sides. min() of width/height is
        // essential here — the canvas is taller than wide, so budgeting off
        // width alone leaves it correct horizontally but clipped top/bottom.
        val safeRadius = min(size.width, size.height) / 2f * 0.86f
        val maxStrokeHalf = DownloadReactorGeometry.OUTER_RINGS.maxOf { it.strokeWidth } * 3f / 2f
        // OUTERMOST_RADIUS_FRACTION already folds in tick overhang, so solve
        // for the radius scalar such that
        // radius * OUTERMOST_RADIUS_FRACTION + maxStrokeHalf == safeRadius.
        // Every ring elsewhere is expressed as a fraction of this same
        // scalar, so this one division scales the whole assembly in
        // lockstep and nothing can escape regardless of how many rings exist.
        val radius = (safeRadius - maxStrokeHalf) / DownloadReactorGeometry.OUTERMOST_RADIUS_FRACTION

        drawAmbientWash(center, radius, clampedFraction)
        drawFarLayer(center, radius, elapsedSeconds)
        drawOuterLayer(center, radius, elapsedSeconds, activity)
        drawMidLayer(center, radius, elapsedSeconds, clampedFraction, activity)
        drawNearLayer(center, radius, elapsedSeconds, activity)
        drawSpecularHits(center, radius, elapsedSeconds)
        drawNodes(center, radius, elapsedSeconds)
        drawOutwardPulses(center, radius, elapsedSeconds, emissionsPerSecond, clampedFraction)
        drawCore(center, radius, elapsedSeconds, clampedFraction)
    }
}

private fun DrawScope.drawAmbientWash(center: Offset, radius: Float, fraction: Float) {
    val outer = radius * 1.5f
    val brightness = DownloadReactorGeometry.coreBrightness(fraction)
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                VegColors.CoolDeep.copy(alpha = 0.05f * brightness),
                Color(0xFF0B2A6B).copy(alpha = 0.02f),
                Color.Transparent
            ),
            center = center,
            radius = outer
        ),
        radius = outer,
        center = center
    )
}

private fun DrawScope.drawFarLayer(center: Offset, radius: Float, elapsedSeconds: Float) {
    val deepBlue = Color(0xFF1C3F91)
    for (ring in DownloadReactorGeometry.RINGS) {
        if (ring.depth != Depth.FAR) continue
        drawRing(center, radius, ring, elapsedSeconds, color = deepBlue, extraAlpha = 1f)
    }
}

/**
 * The outer band: large radius, bright, independently animated. This is the
 * layer the v4->v5 feedback singled out — the biggest area on screen was
 * previously the dimmest and least active part of the image, which is
 * backwards. Segments breathe in width, a luminance sweep travels the
 * circumference independent of rotation, tick groups illuminate as they
 * pass, and each ring occasionally has a discrete structural moment.
 */
private fun DrawScope.drawOuterLayer(
    center: Offset,
    radius: Float,
    elapsedSeconds: Float,
    activity: Float
) {
    for (ring in DownloadReactorGeometry.OUTER_RINGS) {
        val ringRadius = radius * ring.radiusFraction
        val rotation = DownloadReactorGeometry.outerRingRotationRadians(ring, elapsedSeconds)
        val color = if (ring.hot) VegColors.Warm else VegColors.Cool
        val gapFraction = DownloadReactorGeometry.outerSegmentBreathe(ring, elapsedSeconds)
        val sweepAngle = DownloadReactorGeometry.outerSweepAngle(ring, elapsedSeconds)
        val event = DownloadReactorGeometry.outerEventProgress(ring, elapsedSeconds)
        // A structural event briefly brightens and extends the whole ring,
        // then relaxes back — a discrete moment rather than continuous motion.
        val eventBoost = event?.let { p -> 1f - Math.abs(p - 0.5f) * 2f } ?: 0f

        val slot = (2 * Math.PI / ring.segments).toFloat()
        for (i in 0 until ring.segments) {
            val segStart = i * slot
            val segSweep = slot * (gapFraction + eventBoost * (1f - gapFraction) * 0.9f)
            val startDegrees = Math.toDegrees((segStart + rotation).toDouble()).toFloat()
            val sweepDegrees = Math.toDegrees(segSweep.toDouble()).toFloat()

            val midAngle = segStart + rotation + segSweep / 2f
            val sweepProximity = DownloadReactorGeometry.outerSweepProximity(ring, midAngle, elapsedSeconds)
            val alpha = (0.72f + sweepProximity * 0.25f + eventBoost * 0.2f).coerceIn(0f, 1f)

            // Glow pass then crisp edge, same technique as the inner rings —
            // this is what makes the extra brightness read as light, not paint.
            drawArc(
                color = color.copy(alpha = alpha * 0.35f),
                startAngle = startDegrees,
                sweepAngle = sweepDegrees,
                useCenter = false,
                topLeft = Offset(center.x - ringRadius, center.y - ringRadius),
                size = Size(ringRadius * 2, ringRadius * 2),
                style = Stroke(width = ring.strokeWidth * 3f)
            )
            drawArc(
                color = (if (sweepProximity > 0.7f) Color.White else color).copy(alpha = alpha),
                startAngle = startDegrees,
                sweepAngle = sweepDegrees,
                useCenter = false,
                topLeft = Offset(center.x - ringRadius, center.y - ringRadius),
                size = Size(ringRadius * 2, ringRadius * 2),
                style = Stroke(width = ring.strokeWidth)
            )
        }

        if (ring.ticksPerBand > 0) {
            drawOuterTicks(center, ringRadius, ring, sweepAngle, color)
        }
    }
}

private fun DrawScope.drawOuterTicks(
    center: Offset,
    ringRadius: Float,
    ring: DownloadOuterRingDef,
    sweepAngle: Float,
    color: Color
) {
    val groupSize = (ring.ticksPerBand / 8).coerceAtLeast(2)
    // Sample a bounded subset of ticks rather than every one, so a dense
    // band does not blow the per-frame draw budget: the illuminated group
    // covers ~1/8 of the circle, so of the (at most) 16 evenly spaced
    // candidates checked here, only a couple ever draw at once.
    val step = (ring.ticksPerBand / 16).coerceAtLeast(1)
    var i = 0
    while (i < ring.ticksPerBand) {
        if (DownloadReactorGeometry.tickIlluminated(i, ring.ticksPerBand, sweepAngle, groupSize)) {
            val angle = (i.toFloat() / ring.ticksPerBand) * 2f * PI.toFloat()
            val inner = center + Offset(cos(angle) * ringRadius * 0.985f, sin(angle) * ringRadius * 0.985f)
            val outer = center + Offset(cos(angle) * ringRadius * 1.02f, sin(angle) * ringRadius * 1.02f)
            drawLine(color = color.copy(alpha = 0.8f), start = inner, end = outer, strokeWidth = 1.2f)
        }
        i += step
    }
}

private fun DrawScope.drawMidLayer(
    center: Offset,
    radius: Float,
    elapsedSeconds: Float,
    fraction: Float,
    activity: Float
) {
    val assembled = DownloadReactorGeometry.assembledRingCount(
        fraction,
        totalRings = DownloadReactorGeometry.RINGS.count { it.depth == Depth.MID }
    )
    var midIndex = 0
    for (ring in DownloadReactorGeometry.RINGS) {
        if (ring.depth != Depth.MID) continue
        val arrived = midIndex < assembled || fraction >= 0.999f
        midIndex++
        // Rings that have not "arrived" yet are present but very faint —
        // progress is still legible without the picture ever looking empty.
        val presence = if (arrived) 1f else 0.22f
        val color = if (ring.hot) VegColors.Warm else VegColors.Cool
        drawRing(center, radius, ring, elapsedSeconds, color = color, extraAlpha = presence)
        drawRingPulses(center, radius, ring, elapsedSeconds, color, extraAlpha = presence * activity)
    }
}

private fun DrawScope.drawNearLayer(
    center: Offset,
    radius: Float,
    elapsedSeconds: Float,
    activity: Float
) {
    for (ring in DownloadReactorGeometry.RINGS) {
        if (ring.depth != Depth.NEAR) continue
        val breathe = DownloadReactorGeometry.breathe(elapsedSeconds, ring.periodSeconds * 0.5f)
        val radiusJitter = 1f + breathe * 0.015f
        drawRing(
            center,
            radius * radiusJitter,
            ring,
            elapsedSeconds,
            color = Color.White,
            extraAlpha = 0.85f
        )
        drawRingPulses(center, radius, ring, elapsedSeconds, Color.White, extraAlpha = activity)
    }
}

/** A handful of short, near-white glints sliding across the outer rings
 *  faster than the ring's own rotation — light catching a turning edge. */
private fun DrawScope.drawSpecularHits(center: Offset, radius: Float, elapsedSeconds: Float) {
    for (hit in DownloadReactorGeometry.SPECULAR_HITS) {
        val hostRing = DownloadReactorGeometry.RINGS.getOrNull(hit.ringIndex) ?: continue
        val ringRadius = radius * hostRing.radiusFraction
        val rotation = DownloadReactorGeometry.specularHitRotationRadians(hit, hostRing, elapsedSeconds)
        val startDegrees = Math.toDegrees((hit.angleOffset + rotation).toDouble()).toFloat()

        drawArc(
            color = Color.White.copy(alpha = 0.9f),
            startAngle = startDegrees,
            sweepAngle = hit.sweepDegrees,
            useCenter = false,
            topLeft = Offset(center.x - ringRadius, center.y - ringRadius),
            size = Size(ringRadius * 2, ringRadius * 2),
            style = Stroke(width = hostRing.strokeWidth * 1.6f)
        )
    }
}

/** Energy pulses traveling outward from the core along radial paths, fading
 *  as they go — the core visibly feeding the rings. Rate is tied to live
 *  throughput via [emissionsPerSecond] so this stays state-bound. */
private fun DrawScope.drawOutwardPulses(
    center: Offset,
    radius: Float,
    elapsedSeconds: Float,
    emissionsPerSecond: Float,
    fraction: Float
) {
    val innerRadius = radius * 0.10f
    val outerRadius = radius * 0.97f
    val angles = floatArrayOf(0.5f, 2.0f, 3.6f, 5.1f)
    val progresses = DownloadReactorGeometry.outwardPulseProgress(elapsedSeconds, emissionsPerSecond)

    for ((index, progress) in progresses.withIndex()) {
        val angle = angles[index % angles.size] + index * 0.7f
        val dist = innerRadius + (outerRadius - innerRadius) * progress
        val fade = 1f - progress
        val start = center + Offset(cos(angle) * dist, sin(angle) * dist)
        val end = center + Offset(cos(angle) * (dist + radius * 0.05f), sin(angle) * (dist + radius * 0.05f))
        drawLine(
            color = Color.White.copy(alpha = 0.75f * fade),
            start = start,
            end = end,
            strokeWidth = 1.6f,
            blendMode = BlendMode.Plus
        )
    }
}

private fun DrawScope.drawRing(
    center: Offset,
    radius: Float,
    ring: DownloadRingDef,
    elapsedSeconds: Float,
    color: Color,
    extraAlpha: Float
) {
    val ringRadius = radius * ring.radiusFraction
    val rotation = DownloadReactorGeometry.ringRotationRadians(ring, elapsedSeconds)
    val depth = ring.depth
    val baseAlpha = depth.alphaScale * extraAlpha

    // Two passes: a wide faint glow stroke, then a crisp thin edge on top —
    // this is what makes a stroke read as emitted light rather than a line.
    for (pass in 0..1) {
        val glow = pass == 0
        val alpha = if (glow) baseAlpha * 0.30f else baseAlpha
        val width = (if (glow) ring.strokeWidth * 3f else ring.strokeWidth) * depth.strokeScale
        for (arc in DownloadReactorGeometry.arcsFor(ring)) {
            val startDegrees = Math.toDegrees((arc.start + rotation).toDouble()).toFloat()
            val sweepDegrees = Math.toDegrees((arc.endInclusive - arc.start).toDouble()).toFloat()
            drawArc(
                color = color.copy(alpha = alpha.coerceIn(0f, 1f)),
                startAngle = startDegrees,
                sweepAngle = sweepDegrees,
                useCenter = false,
                topLeft = Offset(center.x - ringRadius, center.y - ringRadius),
                size = Size(ringRadius * 2, ringRadius * 2),
                style = Stroke(width = width)
            )
        }
    }
}

/** A bright pulse traveling around one ring's circumference, then fading. */
private fun DrawScope.drawRingPulses(
    center: Offset,
    radius: Float,
    ring: DownloadRingDef,
    elapsedSeconds: Float,
    color: Color,
    extraAlpha: Float
) {
    if (extraAlpha <= 0f) return
    val ringRadius = radius * ring.radiusFraction
    val rotation = DownloadReactorGeometry.ringRotationRadians(ring, elapsedSeconds)

    for (progress in DownloadReactorGeometry.activePulseEvents(ring, elapsedSeconds)) {
        val angle = rotation + progress * 2f * PI.toFloat()
        val fade = (1f - progress)
        val point = center + Offset(cos(angle) * ringRadius, sin(angle) * ringRadius)
        drawCircle(
            color = Color.White.copy(alpha = 0.9f * fade * extraAlpha),
            radius = 3.2f * ring.depth.strokeScale,
            center = point
        )
        drawCircle(
            color = color.copy(alpha = 0.5f * fade * extraAlpha),
            radius = 8f * ring.depth.strokeScale,
            center = point
        )
    }
}

private fun DrawScope.drawNodes(center: Offset, radius: Float, elapsedSeconds: Float) {
    for (node in DownloadReactorGeometry.NODES) {
        val orbitAngle = node.angleOffset + (elapsedSeconds / node.periodSeconds) * 2f * PI.toFloat()
        val nodeRadius = radius * node.radiusFraction
        val point = center + Offset(cos(orbitAngle) * nodeRadius, sin(orbitAngle) * nodeRadius)
        val flare = DownloadReactorGeometry.nodeFlareIntensity(node, elapsedSeconds)
        val baseAlpha = 0.35f + flare * 0.65f

        drawCircle(
            color = Color.White.copy(alpha = baseAlpha),
            radius = 2.2f + flare * 2f,
            center = point
        )
        if (flare > 0.4f) {
            drawCircle(
                color = VegColors.Amber.copy(alpha = (flare - 0.4f) * 0.8f),
                radius = 6f + flare * 6f,
                center = point
            )
        }
    }
}

/**
 * The core: the single brightest point in the composition, deliberately
 * blinding rather than merely "bright-ish". Three additive radial layers —
 * a broad soft halo, a mid cyan glow, and a tight pure-white centre — read
 * as a genuine light source instead of a flat gradient disc. [BlendMode.Plus]
 * on the bloom layers means overlaps with the near ring / outward pulses
 * actually brighten rather than just alpha-composite over them.
 */
private fun DrawScope.drawCore(
    center: Offset,
    radius: Float,
    elapsedSeconds: Float,
    fraction: Float
) {
    val brightness = DownloadReactorGeometry.coreBrightness(fraction)
    val breath = DownloadReactorGeometry.breathe(elapsedSeconds, periodSeconds = 5.3f) * 0.035f
    val coreRadius = radius * (0.145f + fraction * 0.045f + breath)

    // Broad faint halo — reaches far out, this is what makes the core feel
    // like it is genuinely lighting the space around it.
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                VegColors.Cool.copy(alpha = 0.55f * brightness),
                VegColors.CoolDeep.copy(alpha = 0.18f * brightness),
                Color.Transparent
            ),
            center = center,
            radius = coreRadius * 3.4f
        ),
        radius = coreRadius * 3.4f,
        center = center,
        blendMode = BlendMode.Plus
    )

    // Mid cyan glow.
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.85f * brightness),
                VegColors.Cool.copy(alpha = 0.75f * brightness),
                Color.Transparent
            ),
            center = center,
            radius = coreRadius * 1.6f
        ),
        radius = coreRadius * 1.6f,
        center = center,
        blendMode = BlendMode.Plus
    )

    // Tight pure-white centre — the single brightest point on screen.
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                Color.White.copy(alpha = (0.98f * brightness).coerceAtMost(1f)),
                Color.White.copy(alpha = 0.7f * brightness),
                Color.Transparent
            ),
            center = center,
            radius = coreRadius
        ),
        radius = coreRadius,
        center = center,
        blendMode = BlendMode.Plus
    )

    val reticleRadius = radius * 0.10f
    drawCircle(
        color = Color.White.copy(alpha = 0.6f * brightness),
        radius = reticleRadius,
        center = center,
        style = Stroke(width = 1f)
    )

    for (i in 0 until 4) {
        val angle = i * PI.toFloat() / 2f + elapsedSeconds / 8f * 2f * PI.toFloat()
        drawLine(
            color = VegColors.Amber.copy(alpha = 0.6f * brightness),
            start = center + Offset(cos(angle) * reticleRadius * 1.25f, sin(angle) * reticleRadius * 1.25f),
            end = center + Offset(cos(angle) * reticleRadius * 1.85f, sin(angle) * reticleRadius * 1.85f),
            strokeWidth = 1f
        )
    }
}
