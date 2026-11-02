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

private const val PULSE_LIFETIME_MILLIS = 1600L
private const val CYCLE_MILLIS = 20_000

/**
 * The reactor assembly.
 *
 * Every animated property traces back to [state]; there is no decorative
 * motion. The single infinite transition drives one normalised clock, and each
 * element derives its own phase from it, so the whole assembly stays in sync
 * and only one animation is running regardless of element count.
 */
@Composable
fun Reactor(state: ReactorState, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "reactor")
    val clock by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(CYCLE_MILLIS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "clock"
    )

    val warm = state.isWarmPalette()
    val primary = if (warm) VegColors.Warm else VegColors.Cool
    val accent = if (warm) VegColors.Amber else VegColors.Warm
    val dim = state.dimFactor()
    val spin = state.spinScale()

    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = min(size.width, size.height) * 0.40f
        val elapsedSeconds = clock * (CYCLE_MILLIS / 1000f)

        drawAmbientWash(center, radius, primary, dim)
        drawTokenPulses(center, radius, state, accent, dim, elapsedSeconds)
        drawTickBands(center, radius, primary, dim, elapsedSeconds, spin)
        drawRings(center, radius, primary, accent, dim, elapsedSeconds, spin)
        drawCore(center, radius, primary, accent, dim, clock)
    }
}

private fun DrawScope.drawAmbientWash(center: Offset, radius: Float, color: Color, dim: Float) {
    val outer = radius * 1.7f
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(color.copy(alpha = 0.13f * dim), Color.Transparent),
            center = center,
            radius = outer
        ),
        radius = outer,
        center = center
    )
}

private fun DrawScope.drawTokenPulses(
    center: Offset,
    radius: Float,
    state: ReactorState,
    color: Color,
    dim: Float,
    elapsedSeconds: Float
) {
    val interval = state.pulseIntervalMillis()
    if (interval <= 0L) return

    val nowMillis = (elapsedSeconds * 1000f).toLong()
    for (age in ReactorGeometry.activePulses(nowMillis, interval, PULSE_LIFETIME_MILLIS)) {
        val fade = (1f - age) * (1f - age)
        drawCircle(
            color = color.copy(alpha = 0.36f * fade * dim),
            radius = radius * (0.34f + age * 1.5f),
            center = center,
            style = Stroke(width = 1.3f)
        )
    }
}

private fun DrawScope.drawTickBands(
    center: Offset,
    radius: Float,
    color: Color,
    dim: Float,
    elapsedSeconds: Float,
    spin: Float
) {
    for (band in ReactorGeometry.TICK_BANDS) {
        val bandRadius = radius * band.radiusFraction
        val rotation = elapsedSeconds * band.speed * spin
        for (i in 0 until band.count) {
            val angle = (i.toFloat() / band.count) * 2f * PI.toFloat() + rotation
            val major = i % band.majorEvery == 0
            val length = if (major) band.length * 2.1f else band.length
            val alpha = if (major) 0.46f else 0.13f
            drawLine(
                color = color.copy(alpha = alpha * dim),
                start = center + Offset(cos(angle) * bandRadius, sin(angle) * bandRadius),
                end = center + Offset(
                    cos(angle) * (bandRadius + length),
                    sin(angle) * (bandRadius + length)
                ),
                strokeWidth = 1f
            )
        }
    }
}

private fun DrawScope.drawRings(
    center: Offset,
    radius: Float,
    primary: Color,
    accent: Color,
    dim: Float,
    elapsedSeconds: Float,
    spin: Float
) {
    // Two passes per ring: a wide faint stroke reads as emitted light, and the
    // thin bright stroke on top keeps the edge crisp.
    for (pass in 0..1) {
        val glow = pass == 0
        for (ring in ReactorGeometry.RINGS) {
            val ringRadius = radius * ring.radiusFraction
            val rotation = elapsedSeconds * ring.speed * spin
            val color = if (ring.hot) accent else primary
            val alpha = if (glow) 0.16f else 0.85f
            val width = if (glow) ring.strokeWidth * 3.2f else ring.strokeWidth

            for (arc in ReactorGeometry.arcsFor(ring)) {
                val startDegrees = Math.toDegrees((arc.start + rotation).toDouble()).toFloat()
                val sweepDegrees =
                    Math.toDegrees((arc.endInclusive - arc.start).toDouble()).toFloat()
                drawArc(
                    color = color.copy(alpha = alpha * dim),
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
}

private fun DrawScope.drawCore(
    center: Offset,
    radius: Float,
    primary: Color,
    accent: Color,
    dim: Float,
    clock: Float
) {
    val breath = sin(clock * 2f * PI.toFloat() * 4f) * 0.03f
    val coreRadius = radius * (0.20f + breath)

    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.92f * dim),
                primary.copy(alpha = 0.60f * dim),
                Color.Transparent
            ),
            center = center,
            radius = coreRadius
        ),
        radius = coreRadius,
        center = center
    )

    val reticleRadius = radius * 0.115f
    drawCircle(
        color = Color.White.copy(alpha = 0.55f * dim),
        radius = reticleRadius,
        center = center,
        style = Stroke(width = 1f)
    )

    for (i in 0 until 4) {
        val angle = i * PI.toFloat() / 2f + clock * 2f * PI.toFloat()
        drawLine(
            color = accent.copy(alpha = 0.6f * dim),
            start = center + Offset(
                cos(angle) * reticleRadius * 1.25f,
                sin(angle) * reticleRadius * 1.25f
            ),
            end = center + Offset(
                cos(angle) * reticleRadius * 1.85f,
                sin(angle) * reticleRadius * 1.85f
            ),
            strokeWidth = 1f
        )
    }
}
