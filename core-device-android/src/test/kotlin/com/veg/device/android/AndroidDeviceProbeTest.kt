package com.veg.device.android

import android.app.ActivityManager
import android.content.Context
import androidx.core.content.getSystemService
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class AndroidDeviceProbeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val probe = AndroidDeviceProbe(context)

    @Before
    fun seedMemoryInfo() {
        // Robolectric's ShadowActivityManager.getMemoryInfo() is a no-op until a
        // test seeds it: its backing MemoryInfo field is null by default, so the
        // shadow leaves the caller's object at Java defaults (totalMem = 0).
        // Inject known values so the probe has something real to read through.
        val activityManager = context.getSystemService<ActivityManager>()!!
        val fake = ActivityManager.MemoryInfo().apply {
            totalMem = 8L * 1024 * 1024 * 1024
            availMem = 3L * 1024 * 1024 * 1024
            threshold = 256L * 1024 * 1024
            lowMemory = false
        }
        shadowOf(activityManager).setMemoryInfo(fake)
    }

    @Test
    fun `reads plausible memory values`() {
        val r = probe.read()
        assertEquals(8L * 1024 * 1024 * 1024, r.totalRamBytes)
        assertEquals(3L * 1024 * 1024 * 1024, r.availableRamBytes)
        assertTrue("available should not exceed total", r.availableRamBytes <= r.totalRamBytes)
        assertTrue("per-app limit should be positive", r.perAppMemoryLimitBytes > 0)
    }

    @Test
    fun `reads plausible cpu and storage values`() {
        val r = probe.read()
        assertTrue("at least one core", r.cpuCores >= 1)
        assertTrue("storage not negative", r.freeStorageBytes >= 0)
    }

    @Test
    fun `performance cores fall back to a plausible value without sysfs`() {
        // Robolectric has no /sys/devices/system/cpu tree, so this exercises the
        // fallback path (half the core count) rather than the real sysfs read.
        val r = probe.read()
        assertTrue(
            "performanceCores should be between 1 and cpuCores, was ${r.performanceCores} of ${r.cpuCores}",
            r.performanceCores in 1..r.cpuCores
        )
    }

    @Test
    fun `thermal headroom is clamped into zero to one`() {
        val r = probe.read()
        assertTrue("headroom in range, was ${r.thermalHeadroom}", r.thermalHeadroom in 0f..1f)
    }

    @Test
    fun `battery percent is clamped into zero to one hundred`() {
        val r = probe.read()
        assertTrue("battery in range, was ${r.batteryPercent}", r.batteryPercent in 0..100)
    }

    @Test
    fun `soc model is never blank`() {
        val r = probe.read()
        assertTrue(r.socModel.isNotBlank())
    }

    @Test
    fun `device name is never blank and is not the chipset`() {
        val r = probe.read()
        assertTrue("device name should not be blank", r.deviceName.isNotBlank())
        assertTrue(
            "device name must not be the SoC model",
            !r.deviceName.equals(r.socModel, ignoreCase = true)
        )
    }
}
