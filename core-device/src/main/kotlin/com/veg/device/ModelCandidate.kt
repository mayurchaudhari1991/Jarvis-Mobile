package com.veg.device

data class ModelCandidate(
    val id: String,
    /** Product tier name shown to the user, e.g. "Prajna". */
    val tierName: String,
    /**
     * Plain-language descriptor of what this tier is FOR, shown beside the
     * tier name. Deliberately not a translation of the Sanskrit — a user who
     * does not read Sanskrit needs to know what they are choosing, and
     * "swift" does not tell them that while "Fast, good for quick answers"
     * does.
     */
    val tierMeaning: String,
    /** Upstream model name, shown as attribution. Apache 2.0 requires this. */
    val displayName: String,
    /** Size of the GGUF file on disk. */
    val fileBytes: Long,
    /** Peak RAM while loaded, including KV cache. Larger than fileBytes. */
    val residentBytes: Long,
    val expectedTokensPerSecond: Float,
    /**
     * Whether [expectedTokensPerSecond] was measured on real hardware or
     * derived from a measurement of a different model.
     *
     * The original values were guessed and proved roughly 8x optimistic,
     * which is exactly the kind of error that hides when estimate and
     * measurement look identical in the code.
     */
    val throughputMeasured: Boolean,
    /** Relative ordering only; higher is better. */
    val quality: Int
)

/**
 * Catalog of downloadable models with their tier metadata and throughput
 * estimates.
 *
 * [ModelCandidate.expectedTokensPerSecond] values were originally guessed
 * from model size alone and were off by roughly 8x for the one figure the
 * user could check against reality. They have since been recalibrated from
 * two on-device measurements (OnePlus 13R, SM8650) — see
 * docs/superpowers/measurements/2026-08-20-oneplus-13r.md for the raw data:
 *
 *  - Laghu (Qwen3 0.6B, Q8_0): measured directly, ~6.5 tok/s.
 *  - Tvara++ (Qwen3.5 2B, Q6_K): measured directly, ~4.0 tok/s.
 *
 * Every other entry is DERIVED, not measured, using two rules:
 *
 *  1. Parameter-count scaling: fitting throughput ~ params^-0.6 through the
 *     two measured anchors (0.6B and ~2.0B) gives the exponent. This is
 *     weaker than naive inverse-proportional (params^-1) scaling, consistent
 *     with decode throughput on ARM mobile CPUs being memory-bandwidth bound
 *     rather than compute bound, where cache and bandwidth effects soften
 *     the size penalty.
 *  2. Quantization scaling: relative speed is assumed proportional to
 *     1 / bits-per-weight, using approximate GGUF bits-per-weight of
 *     Q4_K_M=4.5, Q5_K_M=5.5, Q6_K=6.6, Q8_0=8.5. This matches the general
 *     shape reported in llama.cpp community ARM benchmarks (quantization
 *     format discussions in the ggml-org/llama.cpp repository), which put
 *     Q4_K_M at roughly 1.5-2x the decode speed of Q6_K on ARM — our
 *     bits-per-weight ratio (6.6/4.5 ≈ 1.47) sits at the low end of that
 *     range. This factor is INFERRED, not measured independently.
 *
 * If a future device measurement contradicts a derived value, replace it
 * with the measurement and flip [ModelCandidate.throughputMeasured] to true
 * rather than adjusting the derivation rule to fit.
 */
object ModelCatalog {
    val ALL: List<ModelCandidate> = listOf(
        ModelCandidate(
            id = "qwen3-0_6b-q8",
            tierName = "Laghu",
            tierMeaning = "Smallest and fastest",
            displayName = "Qwen3 0.6B",
            fileBytes = 639_446_688L,
            residentBytes = 780_000_000L,
            // Measured on a OnePlus 13R (SM8650), 2026-08-20. See
            // docs/superpowers/measurements/2026-08-20-oneplus-13r.md.
            expectedTokensPerSecond = 6.5f,
            throughputMeasured = true,
            quality = 5
        ),
        ModelCandidate(
            id = "qwen35-2b-q4km",
            tierName = "Tvara",
            tierMeaning = "Fast, good for quick answers",
            displayName = "Qwen3.5 2B",
            fileBytes = 1_280_835_840L,
            residentBytes = 1_560_000_000L,
            // Derived: Tvara++ (Q6_K, measured) scaled to Q4_K_M using the
            // bits-per-weight ratio 6.6/4.5 (see ModelCandidate KDoc / measurements doc).
            expectedTokensPerSecond = 6.0f,
            throughputMeasured = false,
            quality = 14
        ),
        ModelCandidate(
            id = "qwen35-2b-q5km",
            tierName = "Tvara+",
            tierMeaning = "Fast, sharper answers",
            displayName = "Qwen3.5 2B (Q5)",
            fileBytes = 1_435_238_656L,
            residentBytes = 1_750_000_000L,
            // Derived: Tvara++ (Q6_K, measured) scaled to Q5_K_M via bpw ratio 6.6/5.5.
            expectedTokensPerSecond = 4.9f,
            throughputMeasured = false,
            quality = 16
        ),
        ModelCandidate(
            id = "qwen35-2b-q6k",
            tierName = "Tvara++",
            tierMeaning = "Fast, best quality",
            displayName = "Qwen3.5 2B (Q6)",
            fileBytes = 1_574_961_408L,
            residentBytes = 1_920_000_000L,
            // Measured on a OnePlus 13R (SM8650), 2026-08-20; 3 generations,
            // 2.0-5.5 tok/s live readings. See
            // docs/superpowers/measurements/2026-08-20-oneplus-13r.md.
            expectedTokensPerSecond = 4.0f,
            throughputMeasured = true,
            quality = 17
        ),
        ModelCandidate(
            id = "qwen3-1_7b-q8",
            tierName = "Sthira",
            tierMeaning = "Balanced speed and depth",
            displayName = "Qwen3 1.7B",
            fileBytes = 1_834_426_016L,
            residentBytes = 2_230_000_000L,
            // Derived: interpolated on the measured param-count/throughput curve
            // (throughput ~ params^-0.6, fit from the two measured anchors) at Q8_0.
            expectedTokensPerSecond = 3.5f,
            throughputMeasured = false,
            quality = 12
        ),
        ModelCandidate(
            id = "qwen3-4b-q4km",
            tierName = "Prajna",
            tierMeaning = "Smarter, thinks more carefully",
            displayName = "Qwen3 4B",
            fileBytes = 2_497_280_256L,
            residentBytes = 3_040_000_000L,
            // Derived: same params^-0.6 curve at Q4_K_M.
            expectedTokensPerSecond = 3.9f,
            throughputMeasured = false,
            quality = 20
        ),
        ModelCandidate(
            id = "qwen3-4b-q5km",
            tierName = "Prajna+",
            tierMeaning = "Smarter, sharper answers",
            displayName = "Qwen3 4B (Q5)",
            fileBytes = 2_889_513_184L,
            residentBytes = 3_520_000_000L,
            // Derived: same params^-0.6 curve at Q5_K_M.
            expectedTokensPerSecond = 3.2f,
            throughputMeasured = false,
            quality = 22
        ),
        ModelCandidate(
            id = "qwen3-4b-q6k",
            tierName = "Prajna++",
            tierMeaning = "Smarter, best quality",
            displayName = "Qwen3 4B (Q6)",
            fileBytes = 3_306_260_704L,
            residentBytes = 4_030_000_000L,
            // Derived: same params^-0.6 curve at Q6_K.
            expectedTokensPerSecond = 2.7f,
            throughputMeasured = false,
            quality = 23
        ),
        ModelCandidate(
            id = "qwen3-8b-q4km",
            tierName = "Medha",
            tierMeaning = "Most capable, slowest",
            displayName = "Qwen3 8B",
            fileBytes = 5_027_783_488L,
            residentBytes = 6_130_000_000L,
            // Derived: same params^-0.6 curve at Q4_K_M.
            expectedTokensPerSecond = 2.6f,
            throughputMeasured = false,
            quality = 30
        ),
        ModelCandidate(
            id = "qwen3-8b-q5km",
            tierName = "Medha+",
            tierMeaning = "Most capable, best quality",
            displayName = "Qwen3 8B (Q5)",
            fileBytes = 5_851_112_224L,
            residentBytes = 7_130_000_000L,
            // Derived: same params^-0.6 curve at Q5_K_M.
            expectedTokensPerSecond = 2.1f,
            throughputMeasured = false,
            quality = 32
        )
    )
}

/**
 * How well a model fits this device.
 *
 * llama.cpp mmaps weights from the .gguf file, so a model larger than free RAM
 * still runs — the OS pages it in and out. That is why FROM_STORAGE exists
 * rather than simply excluding the model. It is honest about the cost: paging
 * per token from flash can drop throughput below 1 tok/s.
 */
enum class Viability {
    /** Fits inside the safe RAM budget. Runs at full speed. */
    COMFORTABLE,
    /** Fits in available RAM but not with safety margin. Some paging expected. */
    TIGHT,
    /** Exceeds available RAM. Pages from storage — usable but slow. */
    FROM_STORAGE,
    /** The file does not fit on disk at all. */
    UNAVAILABLE
}

data class ScoredModel(
    val candidate: ModelCandidate,
    val viability: Viability,
    /** Estimated throughput on THIS device, accounting for paging. */
    val estimatedTokensPerSecond: Float
)

data class Recommendation(
    val best: ModelCandidate?,
    val alternatives: List<ModelCandidate>,
    val reasons: List<String>,
    /** Every catalog model with its tier, for the boot screen to display. */
    val scored: List<ScoredModel> = emptyList()
)
