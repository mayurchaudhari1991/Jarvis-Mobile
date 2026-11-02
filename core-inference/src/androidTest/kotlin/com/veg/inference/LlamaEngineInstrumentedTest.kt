package com.veg.inference

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LlamaEngineInstrumentedTest {

    // adb push lands the file at /data/local/tmp with SELinux label
    // shell_data_file, which an untrusted_app process cannot open directly on
    // this device's policy (llama_model_load_from_file fails silently, fast,
    // with no GGUF parse error). Copying into app-private storage first sidesteps
    // that: the app always owns and can read its own files dir.
    private val sourcePath = "/data/local/tmp/Qwen3-0.6B-Q8_0.gguf"

    private val modelFile: File
        get() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            return File(context.filesDir, "Qwen3-0.6B-Q8_0.gguf")
        }

    @Test
    fun loadsAndGeneratesText() = runBlocking {
        val source = File(sourcePath)
        assumeTrue(
            "No model at $sourcePath; push one to run this test: " +
                "adb push Qwen3-0.6B-Q8_0.gguf /data/local/tmp/",
            source.exists()
        )

        if (!modelFile.exists() || modelFile.length() != source.length()) {
            source.copyTo(modelFile, overwrite = true)
        }

        val engine = LlamaEngine()
        val startedAt = System.currentTimeMillis()
        engine.load(modelFile, InferenceConfig(threadCount = 4, contextTokens = 2048, batchSize = 256))
        assertTrue(engine.isLoaded)

        val tokens = engine.generate(emptyList(), "Reply with exactly the word: ready").toList()

        val text = tokens.filterIsInstance<Token.Text>().joinToString("") { it.value }
        val complete = tokens.filterIsInstance<Token.Complete>().firstOrNull()

        assertTrue("no text generated", text.isNotBlank())
        assertTrue("no completion event", complete != null)
        assertTrue("token count should be positive", complete!!.tokenCount > 0)

        val tokensPerSecond = complete.tokenCount * 1000.0 / complete.elapsedMillis
        println("VEGA_INSTRUMENTED_RESULT generatedText=\"$text\" tokenCount=${complete.tokenCount} " +
            "elapsedMillis=${complete.elapsedMillis} tokPerSec=$tokensPerSecond")

        engine.unload()
        assertTrue(!engine.isLoaded)
    }
}
