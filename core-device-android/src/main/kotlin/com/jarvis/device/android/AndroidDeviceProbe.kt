package com.jarvis.device.android

import android.app.ActivityManager
import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import androidx.core.content.getSystemService
import com.jarvis.device.DeviceReadings

/**
 * Fills a [DeviceReadings] from Android system services.
 *
 * Every value here is best-effort: some OEM ROMs return nothing useful for
 * thermal headroom, so each read has an explicit, documented fallback rather
 * than throwing. A wrong-but-conservative reading degrades the recommendation;
 * a crash loses the app.
 */
class AndroidDeviceProbe(private val context: Context) {

    fun read(): DeviceReadings {
        val activityManager = context.getSystemService<ActivityManager>()
        val memInfo = ActivityManager.MemoryInfo().also {
            activityManager?.getMemoryInfo(it)
        }

        // getLargeMemoryClass is in MB and is the real per-app ceiling, which is
        // often far below total RAM.
        val perAppLimitBytes =
            (activityManager?.largeMemoryClass ?: DEFAULT_MEMORY_CLASS_MB).toLong() * 1024L * 1024L

        val freeStorage = try {
            val stat = StatFs(context.filesDir.absolutePath)
            stat.availableBlocksLong * stat.blockSizeLong
        } catch (e: IllegalArgumentException) {
            0L
        }

        return DeviceReadings(
            totalRamBytes = memInfo.totalMem,
            availableRamBytes = memInfo.availMem,
            perAppMemoryLimitBytes = perAppLimitBytes,
            freeStorageBytes = freeStorage,
            cpuCores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
            socModel = readSocModel(),
            thermalHeadroom = readThermalHeadroom(),
            batteryPercent = readBatteryPercent(),
            isCharging = readIsCharging()
        )
    }

    private fun readSocModel(): String {
        // Build.SOC_MODEL was added in API 31; this module's minSdk is 30, so it
        // must be guarded or it fails to resolve (NoSuchFieldError) on API 30.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val model = Build.SOC_MODEL
            if (model.isNotBlank() && model != Build.UNKNOWN) return model
        }
        val hardware = Build.HARDWARE
        return if (hardware.isNotBlank()) hardware else "unknown"
    }

    /**
     * Returns 0.0 (cold) to 1.0 (throttling now).
     *
     * getThermalHeadroom returns headroom remaining, so it is inverted here.
     * It also returns NaN when the platform has no recent sample, which is
     * common on the first call and on some OEM ROMs; NaN means "assume nominal"
     * rather than "assume the worst", since the tuner will re-read shortly.
     */
    private fun readThermalHeadroom(): Float {
        val pm = context.getSystemService<PowerManager>() ?: return 0f
        return try {
            val headroom = pm.getThermalHeadroom(THERMAL_FORECAST_SECONDS)
            if (headroom.isNaN()) 0f else (1f - headroom).coerceIn(0f, 1f)
        } catch (e: Exception) {
            0f
        }
    }

    private fun readBatteryPercent(): Int {
        val bm = context.getSystemService<BatteryManager>() ?: return 100
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return if (level in 0..100) level else 100
    }

    private fun readIsCharging(): Boolean {
        val bm = context.getSystemService<BatteryManager>() ?: return false
        return bm.isCharging
    }

    private companion object {
        const val THERMAL_FORECAST_SECONDS = 10
        const val DEFAULT_MEMORY_CLASS_MB = 256
    }
}
