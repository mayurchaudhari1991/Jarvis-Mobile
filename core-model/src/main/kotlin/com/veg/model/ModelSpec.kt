package com.veg.model

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
 * Checksums are the SHA-256 of the published file contents, obtained from the
 * Hugging Face LFS metadata. If a download begins failing verification, the
 * upstream file was republished — re-fetch the hash and update here rather
 * than relaxing the check. An unverified multi-gigabyte file handed to a
 * native GGUF parser is not something to take on trust.
 */
object ModelRegistry {

    private val SPECS = listOf(
        ModelSpec("qwen3-0_6b-q8",
            "https://huggingface.co/Qwen/Qwen3-0.6B-GGUF/resolve/main/Qwen3-0.6B-Q8_0.gguf",
            "9465e63a22add5354d9bb4b99e90117043c7124007664907259bd16d043bb031", 639_446_688L),
        ModelSpec("qwen35-2b-q4km",
            "https://huggingface.co/unsloth/Qwen3.5-2B-GGUF/resolve/main/Qwen3.5-2B-Q4_K_M.gguf",
            "aaf42c8b7c3cab2bf3d69c355048d4a0ee9973d48f16c731c0520ee914699223", 1_280_835_840L),
        ModelSpec("qwen35-2b-q5km",
            "https://huggingface.co/unsloth/Qwen3.5-2B-GGUF/resolve/main/Qwen3.5-2B-Q5_K_M.gguf",
            "1885b3a9195f8cc09da9a7a7a75afdc1e8d5cbf9fc4a499c3961dddea37098ac", 1_435_238_656L),
        ModelSpec("qwen35-2b-q6k",
            "https://huggingface.co/unsloth/Qwen3.5-2B-GGUF/resolve/main/Qwen3.5-2B-Q6_K.gguf",
            "fc90339420b4298887aafb307a4291c55440b730133bbffe6ba9630503dcb548", 1_574_961_408L),
        ModelSpec("qwen3-1_7b-q8",
            "https://huggingface.co/Qwen/Qwen3-1.7B-GGUF/resolve/main/Qwen3-1.7B-Q8_0.gguf",
            "061b54daade076b5d3362dac252678d17da8c68f07560be70818cace6590cb1a", 1_834_426_016L),
        ModelSpec("qwen3-4b-q4km",
            "https://huggingface.co/Qwen/Qwen3-4B-GGUF/resolve/main/Qwen3-4B-Q4_K_M.gguf",
            "7485fe6f11af29433bc51cab58009521f205840f5b4ae3a32fa7f92e8534fdf5", 2_497_280_256L),
        ModelSpec("qwen3-4b-q5km",
            "https://huggingface.co/Qwen/Qwen3-4B-GGUF/resolve/main/Qwen3-4B-Q5_K_M.gguf",
            "aca596860e8cb40af6539e3f2ea40df305f42515deac56d49c08d39a02e6533f", 2_889_513_184L),
        ModelSpec("qwen3-4b-q6k",
            "https://huggingface.co/Qwen/Qwen3-4B-GGUF/resolve/main/Qwen3-4B-Q6_K.gguf",
            "8a08533841623c2a689763b9318d8e27b3e052ad5b38015d4be4c04fa96f68a3", 3_306_260_704L),
        ModelSpec("qwen3-8b-q4km",
            "https://huggingface.co/Qwen/Qwen3-8B-GGUF/resolve/main/Qwen3-8B-Q4_K_M.gguf",
            "d98cdcbd03e17ce47681435b5150e34c1417f50b5c0019dd560e4882c5745785", 5_027_783_488L),
        ModelSpec("qwen3-8b-q5km",
            "https://huggingface.co/Qwen/Qwen3-8B-GGUF/resolve/main/Qwen3-8B-Q5_K_M.gguf",
            "068bae163faa96ad48032daf4e071a6a28fe67d8dcc95367609c2ff165e52738", 5_851_112_224L)
    )

    fun specFor(candidateId: String): ModelSpec? = SPECS.firstOrNull { it.id == candidateId }
}
