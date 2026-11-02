package com.veg.hud

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private const val SPIN_UP_MILLIS = 1100
private const val HOLD_MILLIS = 300
private const val FADE_MILLIS = 200

/**
 * The launch sequence: the reactor spins up from stillness, holds, then hands
 * off to the app.
 *
 * Animates the same ring definitions the launcher icon was generated from, so
 * the static icon the user tapped appears to come alive rather than being
 * replaced by an unrelated screen.
 *
 * Tapping anywhere skips it. An animation the user cannot escape becomes an
 * irritation by the twentieth launch.
 */
@Composable
fun LaunchSequence(onFinished: () -> Unit) {
    val finish by rememberUpdatedState(onFinished)
    val progress = remember { Animatable(0f) }
    val fade = remember { Animatable(1f) }

    LaunchedEffect(Unit) {
        progress.animateTo(1f, tween(SPIN_UP_MILLIS, easing = FastOutSlowInEasing))
        delay(HOLD_MILLIS.toLong())
        fade.animateTo(0f, tween(FADE_MILLIS, easing = LinearEasing))
        finish()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(VegColors.Ground)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { finish() },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val p = progress.value
            val alpha = fade.value
            val center = Offset(size.width / 2f, size.height / 2f)
            val radius = min(size.width, size.height) * 0.22f

            // Rings begin at full radius, matching the static launcher icon,
            // then brighten and spin up. Growing them from zero would make
            // the handoff from the platform splash visible as a gap.
            for ((index, ring) in ReactorGeometry.RINGS.withIndex()) {
                // stagger: outer rings brighten slightly later
                val local = ((p - index * 0.04f) / (1f - index * 0.04f)).coerceIn(0f, 1f)

                val ringRadius = radius * ring.radiusFraction
                val rotation = ring.speed * p * p * 6f
                val color = if (ring.hot) VegColors.Warm else VegColors.Cool

                // 0.35 at rest so the icon pose is visible from frame one,
                // rising to full as the reactor ignites.
                val ringAlpha = (0.35f + 0.55f * local) * alpha

                for (arc in ReactorGeometry.arcsFor(ring)) {
                    val startDeg = Math.toDegrees((arc.start + rotation).toDouble()).toFloat()
                    val sweepDeg = Math.toDegrees((arc.endInclusive - arc.start).toDouble()).toFloat()
                    drawArc(
                        color = color.copy(alpha = ringAlpha),
                        startAngle = startDeg,
                        sweepAngle = sweepDeg,
                        useCenter = false,
                        topLeft = Offset(center.x - ringRadius, center.y - ringRadius),
                        size = Size(ringRadius * 2, ringRadius * 2),
                        style = Stroke(width = ring.strokeWidth)
                    )
                }
            }

            // core starts dim and brightens, rather than popping in partway through
            val coreP = (0.25f + 0.75f * p).coerceIn(0f, 1f)
            if (coreP > 0f) {
                val coreRadius = radius * 0.2f * coreP
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.95f * coreP * alpha),
                            VegColors.Cool.copy(alpha = 0.6f * coreP * alpha),
                            Color.Transparent
                        ),
                        center = center,
                        radius = coreRadius * 3f
                    ),
                    radius = coreRadius * 3f,
                    center = center
                )
            }
        }
    }
}
