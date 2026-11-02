package com.veg.inference

import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FakeEngineTest {

    private val config = InferenceConfig(threadCount = 4, contextTokens = 2048, batchSize = 256)

    @Test
    fun `starts unloaded and becomes loaded`() = runTest {
        val engine = FakeEngine(scriptedReply = "hello there", tokenDelayMillis = 0)
        assertFalse(engine.isLoaded)
        engine.load(File("ignored.gguf"), config)
        assertTrue(engine.isLoaded)
    }

    @Test
    fun `emits each word as a token then completes`() = runTest {
        val engine = FakeEngine(scriptedReply = "one two three", tokenDelayMillis = 0)
        engine.load(File("ignored.gguf"), config)

        val text = StringBuilder()
        var complete: Token.Complete? = null

        engine.generate(emptyList(), "prompt").test {
            var item = awaitItem()
            while (item !is Token.Complete) {
                text.append((item as Token.Text).value)
                item = awaitItem()
            }
            complete = item
            awaitComplete()
        }

        assertEquals("one two three", text.toString().trim())
        assertEquals(3, complete!!.tokenCount)
    }

    @Test
    fun `generating before load emits an error`() = runTest {
        val engine = FakeEngine(scriptedReply = "hi", tokenDelayMillis = 0)

        engine.generate(emptyList(), "prompt").test {
            val item = awaitItem()
            assertTrue("expected Error, got $item", item is Token.Error)
            awaitComplete()
        }
    }

    @Test
    fun `unload clears the loaded flag`() = runTest {
        val engine = FakeEngine(scriptedReply = "hi", tokenDelayMillis = 0)
        engine.load(File("ignored.gguf"), config)
        engine.unload()
        assertFalse(engine.isLoaded)
    }

    @Test
    fun `runtime params convert to inference config`() {
        val params = com.veg.device.RuntimeParams(threadCount = 6, contextTokens = 4096, batchSize = 512)
        val converted = params.toInferenceConfig()
        assertEquals(6, converted.threadCount)
        assertEquals(4096, converted.contextTokens)
        assertEquals(512, converted.batchSize)
    }
}
