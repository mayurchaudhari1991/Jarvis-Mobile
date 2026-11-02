package com.veg.hud

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.veg.device.DeviceReadings
import com.veg.device.ModelCandidate
import com.veg.device.Recommendation
import com.veg.device.ScoredModel
import com.veg.device.Viability
import com.veg.model.DownloadProgress

private const val GB = 1024f * 1024f * 1024f

@Composable
fun BootScreen(
    readings: DeviceReadings,
    recommendation: Recommendation,
    progress: DownloadProgress?,
    onStart: (ModelCandidate) -> Unit,
    onRescan: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedId by remember(recommendation) {
        mutableStateOf(recommendation.best?.id ?: recommendation.scored.firstOrNull { it.viability != Viability.UNAVAILABLE }?.candidate?.id)
    }
    val selected = recommendation.scored.firstOrNull { it.candidate.id == selectedId }?.candidate

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(VegColors.Ground)
            .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(top = 16.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("SYSTEM SCAN", style = MonoStyle, color = VegColors.CoolDeep)

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                ScanRow("Device", readings.deviceName)
                ScanRow("Chipset", readings.socModel)
                ScanRow("Memory", "${gb(readings.totalRamBytes)} GB · ${gb(readings.availableRamBytes)} free")
                ScanRow("Cores", readings.cpuCores.toString())
                ScanRow("Storage", "${gb(readings.freeStorageBytes)} GB free")
                ScanRow("Thermal", if (readings.thermalHeadroom < 0.5f) "nominal" else "elevated")
            }

            val best = recommendation.best
            if (best == null) {
                Text(
                    text = "No model fits right now",
                    color = VegColors.Red,
                    fontSize = 17.sp
                )
                for (reason in recommendation.reasons) {
                    Text(reason, color = VegColors.Neutral, fontSize = 11.sp)
                }
                Text(
                    text = "Close background apps to free memory, then rescan.",
                    color = VegColors.NeutralDim,
                    fontSize = 12.sp
                )
            } else {
                Text("SELECT A MODEL", style = MonoStyle, color = VegColors.CoolDeep)

                for (scoredModel in recommendation.scored) {
                    ModelOption(
                        scored = scoredModel,
                        isRecommended = scoredModel.candidate.id == best.id,
                        isSelected = scoredModel.candidate.id == selectedId,
                        onSelect = { selectedId = scoredModel.candidate.id }
                    )
                }
            }

            // Running and Verifying are owned entirely by DownloadScreen, which
            // replaces this composable while a download is active — see
            // AppRoot. BootScreen only ever needs to render the absence of a
            // download or its terminal failure.
            when (progress) {
                null, is DownloadProgress.Running, DownloadProgress.Verifying, is DownloadProgress.Done -> {}

                is DownloadProgress.Failed -> Column {
                    Text(progress.reason, color = VegColors.Red, fontSize = 12.sp)
                    if (progress.canRetry && selected != null) {
                        Text(
                            text = "RETRY",
                            style = MonoStyle,
                            color = VegColors.Cool,
                            modifier = Modifier
                                .border(1.dp, VegColors.CoolDeep)
                                .clickable { onStart(selected) }
                                .padding(horizontal = 16.dp, vertical = 10.dp)
                        )
                    }
                }
            }
        }

        // pinned action bar
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(VegColors.Panel)
                .padding(horizontal = 20.dp)
                .padding(top = 12.dp, bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (selected != null) {
                Text(
                    text = "Downloads ${gb(selected.fileBytes)} GB over your current connection",
                    style = MonoStyle,
                    color = VegColors.NeutralDim
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "RESCAN",
                    style = MonoStyle,
                    color = VegColors.Neutral,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .weight(1f)
                        .border(1.dp, VegColors.Hairline, RoundedCornerShape(6.dp))
                        .clickable { onRescan() }
                        .padding(vertical = 14.dp)
                )
                if (selected != null) {
                    Text(
                        text = "DOWNLOAD AND PROCEED",
                        style = MonoStyle,
                        color = VegColors.Cool,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .weight(2f)
                            .border(1.dp, VegColors.CoolDeep, RoundedCornerShape(6.dp))
                            .background(VegColors.Cool.copy(alpha = 0.08f), RoundedCornerShape(6.dp))
                            .clickable { onStart(selected) }
                            .padding(vertical = 14.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ScanRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MonoStyle, color = VegColors.Neutral)
        Text(value, style = MonoStyle, color = VegColors.Text)
    }
}

@Composable
private fun ModelOption(
    scored: ScoredModel,
    isRecommended: Boolean,
    isSelected: Boolean,
    onSelect: () -> Unit
) {
    val enabled = scored.viability != Viability.UNAVAILABLE
    val tierColor = when (scored.viability) {
        Viability.COMFORTABLE -> VegColors.Cool
        Viability.TIGHT -> VegColors.Amber
        Viability.FROM_STORAGE -> VegColors.Red
        Viability.UNAVAILABLE -> VegColors.NeutralDim
    }
    val borderColor = when {
        isSelected -> VegColors.Cool
        else -> VegColors.Hairline
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(if (isSelected) 2.dp else 1.dp, borderColor, RoundedCornerShape(6.dp))
            .background(
                if (isSelected) VegColors.Cool.copy(alpha = 0.06f) else VegColors.Panel,
                RoundedCornerShape(6.dp)
            )
            .then(if (enabled) Modifier.clickable { onSelect() } else Modifier)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // radio indicator — one filled ring when selected
                Canvas(Modifier.size(14.dp)) {
                    drawCircle(
                        color = if (isSelected) VegColors.Cool else VegColors.NeutralDim,
                        radius = size.minDimension / 2f,
                        style = Stroke(width = 1.5f)
                    )
                    if (isSelected) {
                        drawCircle(color = VegColors.Cool, radius = size.minDimension / 4f)
                    }
                }
                Column {
                    Text(
                        text = scored.candidate.tierName,
                        color = if (enabled) VegColors.Text else VegColors.NeutralDim,
                        fontSize = 15.sp
                    )
                    // The descriptor is what a user without Sanskrit actually
                    // reads to choose, so it sits with the name, not in the
                    // attribution footnote.
                    Text(
                        text = scored.candidate.tierMeaning,
                        color = if (enabled) VegColors.Neutral else VegColors.NeutralDim,
                        fontSize = 12.sp
                    )
                }
            }
            if (isRecommended) {
                Text("RECOMMENDED", style = MonoStyle, color = VegColors.CoolDeep)
            }
        }

        Text(
            text = "${gb(scored.candidate.fileBytes)} GB  ·  ${tokensPerSecondLabel(scored)}  ·  ${tierLabel(scored.viability)}",
            style = MonoStyle,
            color = tierColor
        )

        Text(
            text = "powered by ${scored.candidate.displayName}",
            fontSize = 10.sp,
            color = VegColors.NeutralDim
        )

        if (scored.viability == Viability.FROM_STORAGE) {
            Text(
                text = "Exceeds free RAM — pages from storage, so it runs slowly.",
                fontSize = 11.sp,
                color = VegColors.NeutralDim
            )
        }
        if (scored.viability == Viability.UNAVAILABLE) {
            Text(
                text = "Not enough free storage for this file.",
                fontSize = 11.sp,
                color = VegColors.NeutralDim
            )
        }
    }
}

/**
 * "~4 tok/s" when [ModelCandidate.throughputMeasured] is true, "~4 tok/s
 * (est.)" when it is derived rather than measured on real hardware. The
 * suffix stops the UI implying a precision the number does not have —
 * the original unlabeled figures were guessed and were off by roughly 8x.
 */
private fun tokensPerSecondLabel(scored: ScoredModel): String {
    val value = String.format("%.1f", scored.estimatedTokensPerSecond)
    return if (scored.candidate.throughputMeasured) "~$value tok/s" else "~$value tok/s (est.)"
}

private fun tierLabel(v: Viability): String = when (v) {
    Viability.COMFORTABLE -> "fits in RAM"
    Viability.TIGHT -> "tight fit"
    Viability.FROM_STORAGE -> "from storage"
    Viability.UNAVAILABLE -> "unavailable"
}

private fun gb(bytes: Long): String = String.format("%.1f", bytes / GB)
