package com.veg.hud

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Shown between "the model file finished downloading" and "the engine
 * reports loaded" — a window that can run several seconds for a multi-
 * gigabyte model (mmap plus warm-up), and which was previously silent: the
 * user landed on an empty chat with a composer that accepted input and
 * quietly failed against an unloaded engine.
 *
 * Reuses [DownloadReactor] at `fraction = 1f` (the transfer itself is done;
 * this is memory, not network) purely for the shared visual language — the
 * reactor's motion here is indeterminate by design, since load time cannot
 * be estimated the way byte-based download progress can. No fake progress
 * bar.
 */
@Composable
fun ModelLoadingScreen(
    tierName: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(VegColors.Ground)
            .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout)),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        DownloadReactor(
            fraction = 1f,
            // A modest, fixed, nonzero rate — enough to keep the near layer
            // and outward pulses lively (see DownloadReactor's `activity`
            // gate) without implying a measured transfer speed that does not
            // exist at this stage.
            bytesPerSecond = 2_000_000.0,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(text = tierName, style = MonoStyle, color = VegColors.Cool)
            Text(
                text = "Loading into memory",
                fontSize = 15.sp,
                color = VegColors.Text,
                textAlign = TextAlign.Center
            )
            Text(
                text = "This takes a moment the first time.\nIt stays ready afterwards.",
                fontSize = 12.sp,
                color = VegColors.NeutralDim,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * Shown when [EngineState.Failed] — a corrupt file, an ABI mismatch, or the
 * native library being unavailable. Styled like [BootScreen]'s existing
 * `DownloadProgress.Failed` branch (dark ground, mono label, red message,
 * bordered action) for visual consistency, but as its own screen since this
 * failure happens after boot, on the loading screen the user cannot leave
 * except via these two actions.
 */
@Composable
fun ModelLoadFailedScreen(
    message: String,
    onTryAgain: () -> Unit,
    onChooseDifferentModel: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(VegColors.Ground)
            .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = "LOAD FAILED", style = MonoStyle, color = VegColors.Red)
        Text(
            text = message,
            color = VegColors.Neutral,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp, bottom = 28.dp)
        )

        Text(
            text = "TRY AGAIN",
            style = MonoStyle,
            color = VegColors.Cool,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, VegColors.CoolDeep, RoundedCornerShape(6.dp))
                .background(VegColors.Cool.copy(alpha = 0.08f), RoundedCornerShape(6.dp))
                .clickable { onTryAgain() }
                .padding(vertical = 14.dp)
        )

        Text(
            text = "CHOOSE A DIFFERENT MODEL",
            style = MonoStyle,
            color = VegColors.Neutral,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp)
                .border(1.dp, VegColors.Hairline, RoundedCornerShape(6.dp))
                .clickable { onChooseDifferentModel() }
                .padding(vertical = 14.dp)
        )
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun ModelLoadingScreenPreview() {
    VegTheme {
        ModelLoadingScreen(tierName = "STHIRA")
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun ModelLoadFailedScreenPreview() {
    VegTheme {
        ModelLoadFailedScreen(
            message = "The model file is missing. Download it again.",
            onTryAgain = {},
            onChooseDifferentModel = {}
        )
    }
}
