package com.jarvis.model

data class ModelSpec(
    val id: String,
    val url: String,
    /** Lowercase hex SHA-256 of the complete file. */
    val sha256: String,
    val sizeBytes: Long
)

/**
 * Download sources for each catalog entry.
 *
 * The SHA-256 values below are placeholders until Task 12 pins them against the
 * real published files. Task 12 replaces them and is a hard gate before release:
 * an unverified model file is an arbitrary-code-execution risk.
 */
object ModelRegistry {

    private val SPECS = listOf(
        ModelSpec(
            id = "qwen3-8b-q4km",
            url = "https://huggingface.co/Qwen/Qwen3-8B-GGUF/resolve/main/Qwen3-8B-Q4_K_M.gguf",
            sha256 = "PIN_IN_TASK_12",
            sizeBytes = 4_920_000_000L
        ),
        ModelSpec(
            id = "qwen3-4b-q4km",
            url = "https://huggingface.co/Qwen/Qwen3-4B-GGUF/resolve/main/Qwen3-4B-Q4_K_M.gguf",
            sha256 = "PIN_IN_TASK_12",
            sizeBytes = 2_500_000_000L
        ),
        ModelSpec(
            id = "qwen3-1_7b-q4km",
            url = "https://huggingface.co/Qwen/Qwen3-1.7B-GGUF/resolve/main/Qwen3-1.7B-Q4_K_M.gguf",
            sha256 = "PIN_IN_TASK_12",
            sizeBytes = 1_100_000_000L
        )
    )

    fun specFor(candidateId: String): ModelSpec? = SPECS.firstOrNull { it.id == candidateId }
}
