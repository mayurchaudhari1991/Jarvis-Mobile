package com.veg.hud

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.veg.device.ModelCandidate
import com.veg.model.DownloadProgress
import kotlinx.coroutines.delay

private const val GB = 1024f * 1024f * 1024f
private const val LINE_ROTATE_MILLIS = 10_000L

/**
 * Full-screen replacement for [BootScreen] while a model download is active.
 *
 * Fourth attempt at this screen. v1 filled the screen with a reactor but
 * looked identical at 6% and 94% complete — rotation alone doesn't read as
 * progress. v2 added more elements (lock flashes, orbiting nodes) but kept
 * everything on one flat plane at one constant rate in two colours, so it
 * still read as a technical diagram. v3 threw the visual away entirely for
 * rotating text cards, which was worse: one small card marooned in a wall of
 * black — removing the visual removed the screen's centre of gravity.
 *
 * This version restores the v1 composition — the reactor as the large,
 * upper-two-thirds hero — but makes the reactor itself alive: layered depth,
 * non-harmonic per-ring rhythm, irregular emergent pulses, organic easing.
 * See [DownloadReactor]. Below it, a compact telemetry strip (kept from v3)
 * carries the numbers and a single rotating line drawn from
 * [DownloadCards.forModel] — enough to read without reintroducing the full
 * card surface that stole the centre of gravity in v3.
 */
@Composable
fun DownloadScreen(
    candidate: ModelCandidate,
    progress: DownloadProgress,
    stats: DownloadStats,
    onCancel: () -> Unit,
    onContinueInBackground: () -> Unit,
    modifier: Modifier = Modifier
) {
    DownloadScreenInternal(
        candidate = candidate,
        progress = progress,
        stats = stats,
        onCancel = onCancel,
        onContinueInBackground = onContinueInBackground,
        modifier = modifier
    )
}

/** Same as [DownloadScreen], with [initialLineIndex] exposed for previews. */
@Composable
private fun DownloadScreenInternal(
    candidate: ModelCandidate,
    progress: DownloadProgress,
    stats: DownloadStats,
    onCancel: () -> Unit,
    onContinueInBackground: () -> Unit,
    modifier: Modifier = Modifier,
    initialLineIndex: Int = 0
) {
    val animatedFraction by animateFloatAsState(
        targetValue = stats.fractionComplete,
        animationSpec = tween(durationMillis = 400),
        label = "download-progress"
    )

    val verifying = progress is DownloadProgress.Verifying
    val stalled = !verifying && stats.bytesPerSecond <= 0.0
    val failed = progress is DownloadProgress.Failed

    val bytesDownloaded = when (progress) {
        is DownloadProgress.Running -> progress.bytesDownloaded
        else -> (stats.fractionComplete * candidate.fileBytes).toLong()
    }

    val lines = remember(candidate.id) { DownloadCards.forModel(candidate).map { it.heading } }
    var lineIndex by remember(candidate.id) { mutableIntStateOf(initialLineIndex) }

    LaunchedEffect(candidate.id) {
        while (true) {
            delay(LINE_ROTATE_MILLIS)
            lineIndex = (lineIndex + 1) % lines.size
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(VegColors.Ground)
            .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(top = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = candidate.tierName, style = MonoStyle, color = VegColors.Cool)
            Text(text = candidate.tierMeaning, fontSize = 12.sp, color = VegColors.Neutral)
        }

        // The reactor — the hero of the screen, filling the upper space.
        DownloadReactor(
            fraction = stats.fractionComplete,
            bytesPerSecond = stats.bytesPerSecond,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        )

        // Compact telemetry strip.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(top = 4.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = if (verifying) "VERIFYING" else statusLabel(stalled, failed),
                    style = MonoStyle,
                    color = if (failed) VegColors.Red else VegColors.CoolDeep
                )
                Text(
                    text = "${(stats.fractionComplete * 100).toInt()}%",
                    style = MonoStyle,
                    color = VegColors.Text
                )
            }

            Box(
                Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .background(VegColors.Hairline, RoundedCornerShape(3.dp))
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(animatedFraction)
                        .height(6.dp)
                        .background(
                            if (failed) VegColors.Red else VegColors.Cool,
                            RoundedCornerShape(3.dp)
                        )
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${gb(bytesDownloaded)} / ${gb(candidate.fileBytes)} GB",
                    style = MonoStyle,
                    color = VegColors.NeutralDim
                )
                Text(
                    text = DownloadStatsCalculator.formatSpeed(stats.bytesPerSecond),
                    style = MonoStyle,
                    color = VegColors.Neutral
                )
            }

            Text(
                text = DownloadStatsCalculator.formatRemaining(stats.secondsRemaining),
                style = MonoStyle,
                color = VegColors.Neutral
            )

            if (progress is DownloadProgress.Failed) {
                Text(progress.reason, color = VegColors.Red, fontSize = 12.sp)
            } else {
                AnimatedContent(
                    targetState = lineIndex,
                    transitionSpec = {
                        fadeIn(animationSpec = tween(500)) togetherWith
                            fadeOut(animationSpec = tween(500))
                    },
                    label = "download-line"
                ) { index ->
                    Text(
                        text = "\"${lines[index]}\"",
                        fontSize = 13.sp,
                        color = VegColors.Neutral,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        // Actions — background is the primary, expected action.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(VegColors.Panel)
                .padding(horizontal = 20.dp)
                .padding(top = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "CONTINUE IN BACKGROUND",
                style = MonoStyle,
                color = VegColors.Ground,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(VegColors.Cool, RoundedCornerShape(6.dp))
                    .clickable { onContinueInBackground() }
                    .padding(vertical = 14.dp)
            )

            Text(
                text = "Cancel",
                fontSize = 13.sp,
                color = VegColors.NeutralDim,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onCancel() }
                    .padding(vertical = 8.dp)
            )
        }
    }
}

private fun statusLabel(stalled: Boolean, failed: Boolean): String = when {
    failed -> "FAILED"
    stalled -> "WAITING"
    else -> "DOWNLOADING"
}

private fun gb(bytes: Long): String = String.format("%.2f", bytes / GB)

@Preview(showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun DownloadScreenPreview() {
    VegTheme {
        DownloadScreenInternal(
            candidate = com.veg.device.ModelCatalog.ALL.first { it.id == "qwen35-2b-q6k" },
            progress = DownloadProgress.Running(bytesDownloaded = 551_236_493L, totalBytes = 1_574_961_408L),
            stats = DownloadStats(
                bytesPerSecond = 6_500_000.0,
                secondsRemaining = 146L,
                fractionComplete = 0.35f
            ),
            onCancel = {},
            onContinueInBackground = {},
            initialLineIndex = 1
        )
    }
}
