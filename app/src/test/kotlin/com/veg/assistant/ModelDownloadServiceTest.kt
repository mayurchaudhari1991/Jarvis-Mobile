package com.veg.assistant

import android.app.NotificationManager
import android.content.Intent
import androidx.core.content.getSystemService
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.runner.RunWith

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ModelDownloadServiceTest {

    @Test
    fun `service creates its notification channel on create`() {
        Robolectric.buildService(ModelDownloadService::class.java).create().get()

        val notificationManager = ApplicationProvider.getApplicationContext<android.content.Context>()
            .getSystemService<NotificationManager>()!!
        val channel = notificationManager.getNotificationChannel("model_download")

        assertNotNull(channel)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel!!.importance)
    }

    @Test
    fun `start intent carries the model id`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val intent = Intent(context, ModelDownloadService::class.java).apply {
            action = ModelDownloadService.ACTION_START
            putExtra(ModelDownloadService.EXTRA_MODEL_ID, "qwen35-2b-q6k")
        }

        assertEquals(ModelDownloadService.ACTION_START, intent.action)
        assertEquals("qwen35-2b-q6k", intent.getStringExtra(ModelDownloadService.EXTRA_MODEL_ID))
    }

    @Test
    fun `progress state flow starts null`() {
        assertNull(ModelDownloadService.progress.value)
    }
}
