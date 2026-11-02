package com.veg.inference

import kotlinx.coroutines.flow.Flow
import java.io.File

data class ChatTurn(val role: String, val content: String)

sealed interface Token {
    data class Text(val value: String) : Token
    /** Terminal success. tokenCount and elapsedMillis feed the HUD throughput readout. */
    data class Complete(val tokenCount: Int, val elapsedMillis: Long) : Token
    /** Terminal failure. The flow completes normally after this; it does not throw. */
    data class Error(val message: String) : Token
}

/**
 * Streaming text generation.
 *
 * Deliberately knows nothing about the UI or about Room. Errors arrive as a
 * terminal [Token.Error] rather than an exception, so a UI collecting this flow
 * never needs a try/catch around collection.
 */
interface InferenceEngine {

    val isLoaded: Boolean

    suspend fun load(modelFile: File, config: InferenceConfig)

    fun generate(history: List<ChatTurn>, prompt: String): Flow<Token>

    suspend fun cancel()

    suspend fun unload()
}
