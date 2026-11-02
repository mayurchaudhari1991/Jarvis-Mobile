package com.veg.device

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the throughput figures in [ModelCatalog] — see the KDoc on
 * [ModelCatalog] for how the measured-vs-derived split and the derivation
 * rule work.
 */
class ModelCatalogTest {

    @Test
    fun `every catalog entry declares whether its throughput was measured`() {
        // Only entries actually run on real hardware may claim measured.
        // See docs/superpowers/measurements/2026-08-20-oneplus-13r.md.
        val measuredIds = setOf("qwen3-0_6b-q8", "qwen35-2b-q6k")

        for (candidate in ModelCatalog.ALL) {
            val expected = candidate.id in measuredIds
            assertTrue(
                "${candidate.id} declares throughputMeasured=${candidate.throughputMeasured} " +
                    "but should be $expected",
                candidate.throughputMeasured == expected
            )
        }

        // At least one entry must be measured, or the "measured" label means nothing.
        assertTrue(
            "no catalog entry is marked as measured",
            ModelCatalog.ALL.any { it.throughputMeasured }
        )
    }

    @Test
    fun `throughput estimates are ordered consistently with model size`() {
        // Within each quantization family, a bigger model (more resident
        // bytes, i.e. more parameters) must never be estimated as faster
        // than a smaller one — a recalibration that inverted that ordering
        // would be a bug, not a finding.
        val families = ModelCatalog.ALL.groupBy { quantSuffixOf(it.id) }

        for ((quant, models) in families) {
            val bySize = models.sortedBy { it.residentBytes }
            for (i in 0 until bySize.size - 1) {
                val smaller = bySize[i]
                val bigger = bySize[i + 1]
                assertTrue(
                    "within $quant, ${bigger.tierName} (${bigger.residentBytes} bytes, " +
                        "${bigger.expectedTokensPerSecond} tok/s) should not be faster than " +
                        "${smaller.tierName} (${smaller.residentBytes} bytes, " +
                        "${smaller.expectedTokensPerSecond} tok/s)",
                    bigger.expectedTokensPerSecond <= smaller.expectedTokensPerSecond
                )
            }
        }
    }

    /** Extracts the quantization suffix from a catalog id, e.g. "q6k" from "qwen35-2b-q6k". */
    private fun quantSuffixOf(id: String): String = id.substringAfterLast('-')
}
