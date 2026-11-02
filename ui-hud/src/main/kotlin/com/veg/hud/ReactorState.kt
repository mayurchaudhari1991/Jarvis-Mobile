package com.veg.hud

import com.veg.device.ThermalState

enum class ReactorMode { IDLE, THINKING, GENERATING, THERMAL, LOW_POWER }

/**
 * Everything the reactor is allowed to draw from.
 *
 * The design rule is that no element animates unless it reflects real state, so
 * this class is the only input to the renderer. If a value is not here, it
 * cannot influence the picture.
 */
data class ReactorState(
    val mode: ReactorMode,
    /** Measured, not estimated. Zero when not generating. */
    val tokensPerSecond: Float,
    val thermalState: ThermalState,
    val batteryPercent: Int,
    val isCharging: Boolean
)

/** Ring rotation multiplier. */
fun ReactorState.spinScale(): Float = when (mode) {
    ReactorMode.IDLE -> 0.32f
    ReactorMode.THINKING -> 0.85f
    ReactorMode.GENERATING -> 1.25f
    ReactorMode.THERMAL -> 0.50f
    ReactorMode.LOW_POWER -> 0.20f
}

/**
 * Milliseconds between token pulse rings, derived from measured throughput.
 * Zero means no pulses, which is what makes a stalled model visibly stall.
 */
fun ReactorState.pulseIntervalMillis(): Long {
    if (mode != ReactorMode.GENERATING && mode != ReactorMode.THERMAL) return 0L
    if (tokensPerSecond <= 0f) return 0L
    return (1000f / tokensPerSecond).toLong().coerceIn(16L, 2000L)
}

fun ReactorState.isWarmPalette(): Boolean =
    mode == ReactorMode.THERMAL || thermalState >= ThermalState.SEVERE

fun ReactorState.dimFactor(): Float = when (mode) {
    ReactorMode.LOW_POWER -> 0.45f
    else -> 1.0f
}

/**
 * Resolves the single mode to display from independent signals.
 *
 * Priority is deliberate: thermal outranks generating because a hot phone is
 * the more important thing to communicate, and generating outranks low power
 * because the user is actively waiting on output.
 */
fun reactorModeFor(
    isGenerating: Boolean,
    isThinking: Boolean,
    thermal: ThermalState,
    batteryPercent: Int,
    isCharging: Boolean
): ReactorMode = when {
    thermal >= ThermalState.SEVERE -> ReactorMode.THERMAL
    isGenerating -> ReactorMode.GENERATING
    isThinking -> ReactorMode.THINKING
    !isCharging && batteryPercent <= LOW_BATTERY_PERCENT -> ReactorMode.LOW_POWER
    else -> ReactorMode.IDLE
}

private const val LOW_BATTERY_PERCENT = 15
