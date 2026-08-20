package com.jarvis.inference

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File

/**
 * A scripted engine for tests and UI development.
 *
 * Exists so the entire interface can be built and tested without a 5 GB model:
 * set tokenDelayMillis to 100 to see the HUD behave as it would at 10 tok/s.
 */
class FakeEngine(
    private val scriptedReply: String,
    private val tokenDelayMillis: Long = 100
) : InferenceEngine {

    override var isLoaded: Boolean = false
        private set

    private var cancelled = false

    override suspend fun load(modelFile: File, config: InferenceConfig) {
        isLoaded = true
    }

    override fun generate(history: List<ChatTurn>, prompt: String): Flow<Token> = flow {
        if (!isLoaded) {
            emit(Token.Error("Model is not loaded"))
            return@flow
        }

        cancelled = false
        val words = scriptedReply.split(" ").filter { it.isNotEmpty() }
        var elapsed = 0L
        var emitted = 0

        for ((index, word) in words.withIndex()) {
            if (cancelled) break
            if (tokenDelayMillis > 0) {
                delay(tokenDelayMillis)
                elapsed += tokenDelayMillis
            }
            val suffix = if (index == words.lastIndex) "" else " "
            emit(Token.Text(word + suffix))
            emitted++
        }

        // With no delay configured there is no wall-clock to report, so a
        // nominal 1 ms per token keeps downstream throughput arithmetic finite
        // instead of dividing by zero.
        val reportedElapsed = if (elapsed > 0L) elapsed else emitted.toLong()

        emit(Token.Complete(tokenCount = emitted, elapsedMillis = reportedElapsed))
    }

    override suspend fun cancel() {
        cancelled = true
    }

    override suspend fun unload() {
        isLoaded = false
    }
}
