package com.veg.assistant

import android.content.Context
import com.veg.data.VegDatabase
import com.veg.device.ModelRecommender
import com.veg.device.RuntimeTuner
import com.veg.device.android.AndroidDeviceProbe
import com.veg.inference.InferenceEngine
import com.veg.inference.LlamaEngine
import com.veg.model.ModelDownloader
import com.veg.model.ModelRepository
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Manual dependency wiring.
 *
 * A DI framework would add build time and indirection for one screen graph;
 * this container is the whole object graph and stays readable at a glance.
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val modelsDir: File = File(appContext.filesDir, "models").apply { mkdirs() }

    val probe = AndroidDeviceProbe(appContext)
    val recommender = ModelRecommender()
    val tuner = RuntimeTuner()

    val db: VegDatabase = VegDatabase.open(appContext)

    val modelRepository = ModelRepository(modelsDir)

    private val httpClient = OkHttpClient.Builder()
        // A multi-gigabyte download over mobile needs a generous read timeout;
        // the default 10s aborts on any brief signal drop.
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    val downloader = ModelDownloader(httpClient, modelsDir)

    // Real inference through llama.cpp. The interface boundary is what let every
    // UI task be built and tested against FakeEngine long before the native
    // layer existed; swapping it was a single line, as intended.
    val engine: InferenceEngine = LlamaEngine()
}
