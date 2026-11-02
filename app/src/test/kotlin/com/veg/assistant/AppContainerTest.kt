package com.veg.assistant

import android.app.ActivityManager
import androidx.core.content.getSystemService
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class AppContainerTest {

    private val container = AppContainer(ApplicationProvider.getApplicationContext())

    @Before
    fun seedMemoryInfo() {
        // Robolectric's ShadowActivityManager.getMemoryInfo() is a no-op until a
        // test seeds it: its backing MemoryInfo field is null by default, so the
        // shadow leaves the caller's object at Java defaults (totalMem = 0). Same
        // fix as core-device-android's AndroidDeviceProbeTest.
        val activityManager = ApplicationProvider.getApplicationContext<android.content.Context>()
            .getSystemService<ActivityManager>()!!
        val fake = ActivityManager.MemoryInfo().apply {
            totalMem = 8L * 1024 * 1024 * 1024
            availMem = 3L * 1024 * 1024 * 1024
            threshold = 256L * 1024 * 1024
            lowMemory = false
        }
        shadowOf(activityManager).setMemoryInfo(fake)
    }

    @Test
    fun `exposes every collaborator the app needs`() {
        assertNotNull(container.probe)
        assertNotNull(container.recommender)
        assertNotNull(container.tuner)
        assertNotNull(container.db)
        assertNotNull(container.modelRepository)
        assertNotNull(container.downloader)
        assertNotNull(container.engine)
    }

    @Test
    fun `creates the models directory under app storage`() {
        assertTrue(container.modelsDir.exists())
        assertTrue(container.modelsDir.isDirectory)
    }

    @Test
    fun `device probe returns a usable reading`() {
        val readings = container.probe.read()
        assertTrue(readings.totalRamBytes > 0)
        assertTrue(readings.cpuCores >= 1)
    }

    @Test
    fun `recommender produces a decision for the probed device`() {
        val recommendation = container.recommender.recommend(container.probe.read())
        assertTrue(recommendation.reasons.isNotEmpty())
    }
}
