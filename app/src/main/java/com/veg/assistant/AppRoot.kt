package com.veg.assistant

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.veg.device.ModelCandidate
import com.veg.hud.AutomationSettingsScreen
import com.veg.hud.BootScreen
import com.veg.hud.ChatScreen
import com.veg.hud.ChatViewModel
import com.veg.hud.DownloadScreen
import com.veg.hud.DownloadStatsCalculator
import com.veg.hud.ModelLoadFailedScreen
import com.veg.hud.ModelLoadingScreen
import com.veg.hud.ProgressSample
import com.veg.hud.ReactorMode
import com.veg.inference.toInferenceConfig
import com.veg.model.DownloadProgress
import com.veg.model.ModelRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface AppScreen {
    data object Boot : AppScreen
    data class Chat(val modelId: String) : AppScreen
    data class Automation(val returnTo: Chat) : AppScreen
}

/**
 * Lifecycle of the inference engine for the currently selected model.
 *
 * Models the handoff between "the model file finished downloading" and "you
 * can talk to it" explicitly, so it can be rendered and cannot silently
 * fail: [Loading] gets its own screen instead of an empty chat with a live
 * composer, and [Failed] turns a would-be crash (load() can throw on a
 * corrupt file, an ABI mismatch, or an unavailable native library) into a
 * readable message with retry/choose-different-model actions.
 */
sealed interface EngineState {
    data object Idle : EngineState
    data object Loading : EngineState
    data object Ready : EngineState
    data class Failed(val message: String) : EngineState
}

@Composable
fun AppRoot(container: AppContainer) {
    val context = LocalContext.current

    var scanNonce by remember { mutableIntStateOf(0) }
    val readings = remember(scanNonce) { container.probe.read() }
    val recommendation = remember(scanNonce) { container.recommender.recommend(readings) }

    var screen by remember {
        mutableStateOf<AppScreen>(
            // Skip the boot sequence entirely if a verified model is already present.
            recommendation.best
                ?.takeIf { container.modelRepository.isInstalled(it.id) }
                ?.let { AppScreen.Chat(it.id) }
                ?: AppScreen.Boot
        )
    }
    // The download itself runs in ModelDownloadService, outside this
    // Composable's lifecycle, so it survives the app leaving the foreground.
    // This screen only observes its progress.
    val progress by ModelDownloadService.progress.collectAsStateWithLifecycle()
    var downloadingCandidate by remember { mutableStateOf<ModelCandidate?>(null) }
    var samples by remember { mutableStateOf<List<ProgressSample>>(emptyList()) }
    // The download itself keeps running in ModelDownloadService regardless of
    // this flag — it only controls whether DownloadScreen is the visible
    // surface. Users overwhelmingly prefer to back out and let it finish
    // unattended, so "continue in background" is a first-class action rather
    // than requiring the OS back button.
    var showDownloadScreen by remember { mutableStateOf(true) }

    LaunchedEffect(progress) {
        val update = progress
        if (update is DownloadProgress.Running) {
            samples = (samples + ProgressSample(update.bytesDownloaded, System.currentTimeMillis()))
                .takeLast(16)
        }
        val candidateId = downloadingCandidate?.id
        if (update is DownloadProgress.Done && candidateId != null) {
            screen = AppScreen.Chat(candidateId)
        }
    }

    when (val current = screen) {
        AppScreen.Boot -> {
            val activeDownload = progress
            if (downloadingCandidate != null && showDownloadScreen &&
                (activeDownload is DownloadProgress.Running || activeDownload is DownloadProgress.Verifying)
            ) {
                val stats = DownloadStatsCalculator.compute(
                    samples,
                    downloadingCandidate!!.fileBytes
                )
                DownloadScreen(
                    candidate = downloadingCandidate!!,
                    progress = activeDownload,
                    stats = stats,
                    onCancel = {
                        ModelDownloadService.cancel(context)
                        downloadingCandidate = null
                        samples = emptyList()
                    },
                    onContinueInBackground = {
                        showDownloadScreen = false
                    }
                )
            } else {
                BootScreen(
                    readings = readings,
                    recommendation = recommendation,
                    progress = progress,
                    onStart = { candidate: ModelCandidate ->
                        val spec = ModelRegistry.specFor(candidate.id) ?: return@BootScreen
                        samples = emptyList()
                        downloadingCandidate = candidate
                        showDownloadScreen = true
                        ModelDownloadService.start(context, spec.id)
                    },
                    onRescan = {
                        downloadingCandidate = null
                        samples = emptyList()
                        scanNonce++
                    }
                )
            }
        }

        is AppScreen.Chat -> {
            val viewModel = remember(current.modelId) {
                ChatViewModel(
                    engine = container.engine,
                    db = container.db,
                    tuner = container.tuner,
                    readings = { container.probe.read() }
                )
            }

            var engineState by remember(current.modelId) { mutableStateOf<EngineState>(EngineState.Idle) }
            // Bumped by "TRY AGAIN" on the failure screen to re-run the load
            // LaunchedEffect below without needing to touch current.modelId.
            var loadNonce by remember(current.modelId) { mutableIntStateOf(0) }

            LaunchedEffect(current.modelId, loadNonce) {
                val file = container.modelRepository.installedFile(current.modelId)
                if (file == null) {
                    engineState = EngineState.Failed("The model file is missing. Download it again.")
                    return@LaunchedEffect
                }
                if (container.engine.isLoaded) {
                    engineState = EngineState.Ready
                    return@LaunchedEffect
                }
                engineState = EngineState.Loading
                engineState = try {
                    val params = container.tuner.tune(container.probe.read())
                    withContext(Dispatchers.IO) {
                        container.engine.load(file, params.toInferenceConfig())
                    }
                    EngineState.Ready
                } catch (e: Exception) {
                    // load() throws on a corrupt file, an ABI mismatch, or an
                    // unavailable native library. Uncaught here it kills the
                    // app on a screen the user cannot leave, so it becomes a
                    // readable failure instead.
                    EngineState.Failed(e.message ?: "The model could not be loaded.")
                }
            }

            LaunchedEffect(engineState) {
                if (engineState is EngineState.Ready) {
                    viewModel.seedReadyMessageIfNeeded()
                }
            }

            when (val loadState = engineState) {
                is EngineState.Failed -> ModelLoadFailedScreen(
                    message = loadState.message,
                    onTryAgain = { loadNonce++ },
                    onChooseDifferentModel = {
                        downloadingCandidate = null
                        samples = emptyList()
                        screen = AppScreen.Boot
                    },
                    modifier = Modifier
                )

                // Idle is the state for the single frame before the
                // LaunchedEffect above has run; rendered identically to
                // Loading so there is never a blank flash between them.
                EngineState.Loading, EngineState.Idle -> ModelLoadingScreen(
                    tierName = tierNameFor(current.modelId),
                    modifier = Modifier
                )

                EngineState.Ready -> {
                    val state by viewModel.uiState.collectAsStateWithLifecycle()

                    ChatScreen(
                        state = state.reactor,
                        messages = state.messages,
                        streamingText = state.streamingText,
                        residentGb = residentGbFor(current.modelId),
                        isReady = true,
                        onSend = viewModel::send,
                        onOpenAutomationSettings = { screen = AppScreen.Automation(current) },
                        isGenerating = state.reactor.mode == ReactorMode.GENERATING ||
                            state.reactor.mode == ReactorMode.THINKING,
                        onStop = viewModel::stop,
                        modifier = Modifier
                    )
                }
            }
        }

        is AppScreen.Automation -> {
            AutomationSettingsScreen(
                onBack = { screen = current.returnTo },
                modifier = Modifier
            )
        }
    }
}

private fun tierNameFor(modelId: String): String =
    com.veg.device.ModelCatalog.ALL
        .firstOrNull { it.id == modelId }
        ?.tierName
        ?: "VEGA"

private fun residentGbFor(modelId: String): Float =
    com.veg.device.ModelCatalog.ALL
        .firstOrNull { it.id == modelId }
        ?.let { it.residentBytes / (1024f * 1024f * 1024f) }
        ?: 0f
