package com.veg.device.android

import android.app.ActivityManager
import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import android.provider.Settings
import androidx.core.content.getSystemService
import com.veg.device.DeviceReadings
import java.io.File

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

        val cpuCores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

        return DeviceReadings(
            deviceName = readDeviceName(),
            totalRamBytes = memInfo.totalMem,
            availableRamBytes = memInfo.availMem,
            perAppMemoryLimitBytes = perAppLimitBytes,
            freeStorageBytes = freeStorage,
            cpuCores = cpuCores,
            performanceCores = readPerformanceCores(cpuCores),
            socModel = readSocModel(),
            thermalHeadroom = readThermalHeadroom(),
            batteryPercent = readBatteryPercent(),
            isCharging = readIsCharging()
        )
    }

    /**
     * The name a user would recognise, e.g. "OnePlus 13R".
     *
     * Settings.Global.DEVICE_NAME is what the phone's own Settings app shows
     * and needs no permission. It is user-editable and absent on some ROMs, so
     * fall back through the manufacturer/model pair before giving up.
     */
    private fun readDeviceName(): String {
        val settingsName = try {
            Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
        } catch (e: Exception) {
            null
        }
        if (!settingsName.isNullOrBlank()) return settingsName

        val manufacturer = Build.MANUFACTURER.orEmpty().trim()
        val model = Build.MODEL.orEmpty().trim()
        return when {
            manufacturer.isNotEmpty() && model.isNotEmpty() -> "$manufacturer $model"
            model.isNotEmpty() -> model
            else -> "Unknown device"
        }
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

    /**
     * Counts cores in the fast clusters by reading each core's maximum
     * frequency from sysfs.
     *
     * Cores within ~11% of the fastest are treated as one performance tier;
     * anything slower is an efficiency core we do not want gating a parallel
     * matmul. sysfs is world-readable and needs no permission, but is absent
     * on some devices and emulators — the fallback is half the core count,
     * which is a reasonable guess for a typical big.LITTLE split.
     *
     * The threshold is 0.89, not 0.90: on an 8-core reference device with
     * clusters at 2.27 / 2.96 / 3.15 / 3.30 GHz, a 0.90 cutoff lands at
     * 2.97 GHz — just above the 2.96 GHz cluster — and wrongly drops it to
     * 4 performance cores instead of 6. 0.89 (2.937 GHz) clears that
     * cluster with margin while still excluding the 2.27 GHz efficiency
     * cores.
     */
    private fun readPerformanceCores(totalCores: Int): Int {
        val frequencies = (0 until totalCores).mapNotNull { cpu ->
            runCatching {
                File("/sys/devices/system/cpu/cpu$cpu/cpufreq/cpuinfo_max_freq")
                    .readText().trim().toLongOrNull()
            }.getOrNull()
        }
        if (frequencies.isEmpty()) return maxOf(1, totalCores / 2)

        val fastest = frequencies.max()
        val threshold = (fastest * 0.89).toLong()
        return frequencies.count { it >= threshold }.coerceAtLeast(1)
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
