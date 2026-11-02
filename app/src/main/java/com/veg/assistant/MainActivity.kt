package com.veg.assistant

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.veg.hud.VegTheme
import com.veg.hud.LaunchSequence

class MainActivity : ComponentActivity() {

    private lateinit var container: AppContainer

    // The foreground download service still works without this permission — it
    // just runs without a visible notification. Denial must never block the app.
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op either way */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        splash.setKeepOnScreenCondition { false }
        splash.setOnExitAnimationListener { provider ->
            provider.view.animate()
                .alpha(0f)
                .setDuration(90L)
                .withEndAction { provider.remove() }
                .start()
        }
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        container = AppContainer(this)

        requestNotificationPermissionIfNeeded()

        // Respect an explicit accessibility choice: if the user has turned
        // system animations off, do not force one on them.
        val animationsEnabled = Settings.Global.getFloat(
            contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        ) > 0f

        setContent {
            VegTheme {
                // Cold start only. This is instance state, not persisted, so
                // process death replays it but returning from background does not.
                var showLaunch by remember { mutableStateOf(animationsEnabled) }
                if (showLaunch) {
                    LaunchSequence(onFinished = { showLaunch = false })
                } else {
                    AppRoot(container)
                }
            }
        }
    }

    // On Android 13+, POST_NOTIFICATIONS must be requested at runtime or the
    // download service's progress notification is silently suppressed. Request
    // it once; if the user denies it, the download still runs in the
    // foreground service, just without a visible notification.
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // A ~5 GB resident model must not survive the activity; leaking it here
        // is the fastest way to get the process killed by the OS.
        if (isFinishing) {
            kotlinx.coroutines.runBlocking { container.engine.unload() }
        }
    }
}
