package com.jarvis.device

data class ModelCandidate(
    val id: String,
    val displayName: String,
    /** Size of the GGUF file on disk. */
    val fileBytes: Long,
    /** Peak RAM while loaded, including KV cache. Larger than fileBytes. */
    val residentBytes: Long,
    val expectedTokensPerSecond: Float,
    /** Relative ordering only; higher is better. */
    val quality: Int
)

private const val GB = 1024L * 1024L * 1024L

object ModelCatalog {
    val ALL: List<ModelCandidate> = listOf(
        ModelCandidate(
            id = "qwen3-8b-q4km",
            displayName = "Qwen3 8B",
            fileBytes = (4.9 * GB).toLong(),
            residentBytes = (5.5 * GB).toLong(),
            expectedTokensPerSecond = 10f,
            quality = 30
        ),
        ModelCandidate(
            id = "qwen3-4b-q4km",
            displayName = "Qwen3 4B",
            fileBytes = (3.0 * GB).toLong(),
            residentBytes = (3.4 * GB).toLong(),
            expectedTokensPerSecond = 20f,
            quality = 20
        ),
        ModelCandidate(
            id = "qwen3-1_7b-q4km",
            displayName = "Qwen3 1.7B",
            fileBytes = (1.1 * GB).toLong(),
            residentBytes = (1.4 * GB).toLong(),
            expectedTokensPerSecond = 45f,
            quality = 10
        )
    )
}

data class Recommendation(
    val best: ModelCandidate?,
    val alternatives: List<ModelCandidate>,
    val reasons: List<String>
)
