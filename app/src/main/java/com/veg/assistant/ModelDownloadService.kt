package com.veg.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.veg.model.DownloadProgress
import com.veg.model.ModelDownloader
import com.veg.model.ModelRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Runs a model download outside the Activity's lifecycle.
 *
 * The download previously ran in a coroutine scoped to a Composable, so Android
 * killed it within seconds of the app leaving the foreground and no model could
 * ever finish downloading. A foreground service keeps the process alive (with a
 * visible notification) for as long as the download is in progress.
 */
class ModelDownloadService : Service() {

    companion object {
        private const val CHANNEL_ID = "model_download"
        private const val NOTIFICATION_ID = 1001
        const val EXTRA_MODEL_ID = "model_id"
        const val ACTION_START = "com.veg.assistant.START_DOWNLOAD"
        const val ACTION_CANCEL = "com.veg.assistant.CANCEL_DOWNLOAD"

        /** Observed by the UI. Null when no download is active. */
        val progress = MutableStateFlow<DownloadProgress?>(null)

        fun start(context: Context, modelId: String) {
            val intent = Intent(context, ModelDownloadService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_MODEL_ID, modelId)
            }
            context.startForegroundService(intent)
        }

        fun cancel(context: Context) {
            val intent = Intent(context, ModelDownloadService::class.java).apply {
                action = ACTION_CANCEL
            }
            context.startService(intent)
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var downloadJob: Job? = null

    private lateinit var notificationManager: NotificationManager

    // Throttle notification updates: emitting on every progress update floods
    // NotificationManager and gets silently rate-limited/janky.
    private var lastNotificationUpdateMs = 0L
    private val notificationIntervalMs = 1000L

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                downloadJob?.cancel()
                progress.value = null
                notificationManager.cancel(NOTIFICATION_ID)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                // startForeground must be called within 5 seconds of
                // startForegroundService or the system throws
                // ForegroundServiceDidNotStartInTimeException.
                startForeground(NOTIFICATION_ID, buildNotification(progress.value))

                val modelId = intent?.getStringExtra(EXTRA_MODEL_ID)
                if (modelId != null && downloadJob?.isActive != true) {
                    beginDownload(modelId)
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun beginDownload(modelId: String) {
        val spec = ModelRegistry.specFor(modelId)
        if (spec == null) {
            progress.value = DownloadProgress.Failed("Unknown model: $modelId", canRetry = false)
            stopSelf()
            return
        }

        // Same modelsDir as AppContainer, so the existing .part file is resumed.
        val modelsDir = File(filesDir, "models").apply { mkdirs() }
        val httpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
        val downloader = ModelDownloader(httpClient, modelsDir)

        downloadJob = serviceScope.launch {
            downloader.download(spec).collect { update ->
                progress.value = update
                maybeUpdateNotification(update)

                if (update is DownloadProgress.Done || update is DownloadProgress.Failed) {
                    if (update is DownloadProgress.Done) {
                        notificationManager.cancel(NOTIFICATION_ID)
                    }
                    stopSelf()
                }
            }
        }
    }

    private fun maybeUpdateNotification(update: DownloadProgress) {
        val now = System.currentTimeMillis()
        val isTerminal = update is DownloadProgress.Done || update is DownloadProgress.Failed
        if (!isTerminal && now - lastNotificationUpdateMs < notificationIntervalMs) return
        lastNotificationUpdateMs = now
        notificationManager.notify(NOTIFICATION_ID, buildNotification(update))
    }

    private fun buildNotification(update: DownloadProgress?): Notification {
        val cancelIntent = Intent(this, ModelDownloadService::class.java).apply {
            action = ACTION_CANCEL
        }
        val cancelPendingIntent = PendingIntent.getService(
            this,
            0,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading model")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "Cancel", cancelPendingIntent)

        when (update) {
            is DownloadProgress.Running -> {
                val total = update.totalBytes
                if (total > 0) {
                    val percent = ((update.bytesDownloaded * 100) / total).toInt()
                    builder.setContentText("$percent%")
                    builder.setProgress(100, percent, false)
                } else {
                    builder.setContentText("Downloading…")
                    builder.setProgress(0, 0, true)
                }
            }
            is DownloadProgress.Verifying -> {
                builder.setContentText("Verifying…")
                builder.setProgress(0, 0, true)
            }
            is DownloadProgress.Done -> {
                builder.setContentText("Download complete")
                builder.setOngoing(false)
            }
            is DownloadProgress.Failed -> {
                builder.setContentText("Download failed: ${update.reason}")
                builder.setOngoing(false)
            }
            null -> {
                builder.setContentText("Starting download…")
                builder.setProgress(0, 0, true)
            }
        }

        return builder.build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Model downloads",
            // LOW: no sound/heads-up on every progress update.
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows progress while an on-device model downloads"
        }
        notificationManager.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
