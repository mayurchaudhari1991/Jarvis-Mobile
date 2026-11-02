package com.veg.inference

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import java.io.File

/**
 * Runs a GGUF model through llama.cpp.
 *
 * Native calls are blocking, so generation runs on Dispatchers.IO. The native
 * layer calls back once per token; cancellation is cooperative and checked in
 * the native decode loop.
 */
class LlamaEngine : InferenceEngine {

    private var handle: Long = 0

    override val isLoaded: Boolean get() = handle != 0L

    private external fun nativeInit()
    private external fun nativeLoad(path: String, threads: Int, contextTokens: Int, batchSize: Int): Long
    private external fun nativeGenerate(handle: Long, prompt: String, maxTokens: Int, callback: TokenCallback)
    private external fun nativeCancel(handle: Long)
    private external fun nativeFree(handle: Long)

    /** Invoked from the native decode loop, once per token. */
    interface TokenCallback {
        fun onToken(text: String)
        fun onDone(tokenCount: Int)
        fun onError(message: String)
    }

    override suspend fun load(modelFile: File, config: InferenceConfig) {
        require(modelFile.exists()) { "Model file does not exist: ${modelFile.absolutePath}" }
        ensureLibraryLoaded().getOrElse { cause ->
            throw IllegalStateException(
                "Native inference library could not be loaded on this device", cause
            )
        }
        nativeInit()
        handle = nativeLoad(
            modelFile.absolutePath,
            config.threadCount,
            config.contextTokens,
            config.batchSize
        )
        check(handle != 0L) { "llama.cpp failed to load ${modelFile.name}" }
    }

    override fun generate(history: List<ChatTurn>, prompt: String): Flow<Token> = callbackFlow {
        val libraryFailure = ensureLibraryLoaded().exceptionOrNull()
        if (libraryFailure != null) {
            trySend(Token.Error("Native inference library unavailable: ${libraryFailure.message}"))
            close()
            return@callbackFlow
        }
        if (handle == 0L) {
            trySend(Token.Error("Model is not loaded"))
            close()
            return@callbackFlow
        }

        val startedAt = System.currentTimeMillis()

        val callback = object : TokenCallback {
            override fun onToken(text: String) {
                trySendBlocking(Token.Text(text))
            }

            override fun onDone(tokenCount: Int) {
                trySendBlocking(
                    Token.Complete(
                        tokenCount = tokenCount,
                        elapsedMillis = System.currentTimeMillis() - startedAt
                    )
                )
                close()
            }

            override fun onError(message: String) {
                trySendBlocking(Token.Error(message))
                close()
            }
        }

        nativeGenerate(handle, buildPrompt(history, prompt), MAX_GENERATED_TOKENS, callback)

        awaitClose { if (handle != 0L) nativeCancel(handle) }
    }.flowOn(Dispatchers.IO)

    override suspend fun cancel() {
        if (handle != 0L) nativeCancel(handle)
    }

    override suspend fun unload() {
        if (handle != 0L) {
            nativeFree(handle)
            handle = 0
        }
    }

    /** Qwen3 chat template. A mismatched template degrades output quality badly. */
    private fun buildPrompt(history: List<ChatTurn>, prompt: String): String = buildString {
        append("<|im_start|>system\n")
        append("You are VEGA, a concise and capable personal assistant running entirely on this device.")
        append("<|im_end|>\n")
        for (turn in history) {
            append("<|im_start|>${turn.role}\n${turn.content}<|im_end|>\n")
        }
        append("<|im_start|>user\n$prompt<|im_end|>\n")
        append("<|im_start|>assistant\n")
    }

    companion object {
        /**
         * Reasoning models spend a large share of their budget inside
         * <think> before answering. At 1024 a model could exhaust the whole
         * allowance mid-reasoning, never close the tag, and leave the user
         * with a blank reply after minutes of waiting.
         */
        private const val MAX_GENERATED_TOKENS = 2048

        @Volatile private var libraryState: Result<Unit>? = null

        /**
         * Loads the native library on first actual use, not at class-load time.
         *
         * An `init { System.loadLibrary(...) }` block runs when the class is
         * first touched — which is when AppContainer is constructed, at app
         * startup. If the .so is missing or built for another ABI that throws
         * UnsatisfiedLinkError before any UI exists, and the app dies on launch
         * with nothing the user can act on. Loading here instead lets the
         * failure travel back as a Token.Error, which the interface already
         * treats as the way errors reach the UI.
         *
         * The result is cached so a failure is not retried on every token.
         */
        internal fun ensureLibraryLoaded(): Result<Unit> {
            libraryState?.let { return it }
            synchronized(this) {
                libraryState?.let { return it }
                val outcome = runCatching { System.loadLibrary("veg_llama") }
                    .map { }
                libraryState = outcome
                return outcome
            }
        }
    }
}
