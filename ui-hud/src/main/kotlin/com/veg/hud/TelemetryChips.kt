package com.veg.hud

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.veg.device.ThermalState

enum class ChipHighlight { NONE, ACTIVE, WARN }

data class ChipData(val label: String, val highlight: ChipHighlight)

/**
 * Builds the corner telemetry chips.
 *
 * Kept to at most three: these sit in the corner precisely so they do not
 * compete with the reactor, and a fourth chip breaks that read.
 */
fun chipsFor(state: ReactorState, residentGb: Float): List<ChipData> {
    val chips = mutableListOf<ChipData>()

    val generating = state.mode == ReactorMode.GENERATING || state.mode == ReactorMode.THERMAL
    if (generating && state.tokensPerSecond > 0f) {
        chips += ChipData(
            label = String.format("%.1f tok/s", state.tokensPerSecond),
            highlight = if (state.isWarmPalette()) ChipHighlight.WARN else ChipHighlight.ACTIVE
        )
    }

    chips += ChipData(
        label = String.format("%.1f GB", residentGb),
        highlight = ChipHighlight.NONE
    )

    chips += ChipData(
        label = state.thermalState.name,
        highlight = if (state.thermalState >= ThermalState.SEVERE) ChipHighlight.WARN else ChipHighlight.NONE
    )

    return chips.take(3)
}

@Composable
fun TelemetryChips(chips: List<ChipData>, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        for (chip in chips) {
            val color = when (chip.highlight) {
                ChipHighlight.NONE -> VegColors.Neutral
                ChipHighlight.ACTIVE -> VegColors.Cool
                ChipHighlight.WARN -> VegColors.Amber
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                modifier = Modifier
                    .clip(CircleShape)
                    .background(VegColors.Panel.copy(alpha = 0.72f))
                    .border(1.dp, VegColors.HairlineLo, CircleShape)
                    .padding(horizontal = 8.dp, vertical = 2.dp)
                    .semantics { contentDescription = chip.label }
            ) {
                androidx.compose.foundation.Canvas(Modifier.size(4.dp)) {
                    drawCircle(color)
                }
                Text(text = chip.label, style = MonoStyle, color = color)
            }
        }
    }
}
