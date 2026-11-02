package com.veg.hud

import com.veg.device.ModelCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadCardsTest {

    private val small = ModelCatalog.ALL.first { it.id == "qwen3-0_6b-q8" }
    private val mid = ModelCatalog.ALL.first { it.id == "qwen35-2b-q6k" }
    private val large = ModelCatalog.ALL.first { it.id == "qwen3-8b-q5km" }

    @Test
    fun `every model gets the full set of cards`() {
        for (candidate in ModelCatalog.ALL) {
            val cards = DownloadCards.forModel(candidate)
            assertEquals(
                "expected 6 cards for ${candidate.id}, got ${cards.size}",
                6,
                cards.size
            )
            for (card in cards) {
                assertTrue(
                    "card heading should not be blank for ${candidate.id}",
                    card.heading.isNotBlank()
                )
                assertTrue(
                    "card body should not be blank for ${candidate.id}",
                    card.body.isNotBlank()
                )
            }
        }
    }

    @Test
    fun `a small model describes itself as small`() {
        val cards = DownloadCards.forModel(small)
        val shines = cards.first { it.heading == "Where it shines" }
        assertTrue(
            "expected small-model language, got: ${shines.body}",
            shines.body.contains("fast", ignoreCase = true) &&
                shines.body.contains("small", ignoreCase = true)
        )
    }

    @Test
    fun `a large model promises more careful reasoning`() {
        val cards = DownloadCards.forModel(large)
        val shines = cards.first { it.heading == "Where it shines" }
        assertTrue(
            "expected careful-reasoning language, got: ${shines.body}",
            shines.body.contains("carefully", ignoreCase = true) ||
                shines.body.contains("reasoning", ignoreCase = true)
        )
    }

    @Test
    fun `the limitations card names concrete weaknesses`() {
        val cards = DownloadCards.forModel(mid)
        val limitations = cards.first { it.heading == "Where it won't" }
        assertTrue(limitations.body.contains("recent events", ignoreCase = true))
        assertTrue(limitations.body.contains("arithmetic", ignoreCase = true))
        assertTrue(limitations.body.contains("citation", ignoreCase = true) || limitations.body.contains("quote", ignoreCase = true))
    }

    @Test
    fun `the try-this card offers tappable examples`() {
        val cards = DownloadCards.forModel(mid)
        val tryThis = cards.first { it.heading == "Try this first" }
        assertTrue(
            "expected several example prompts, got ${tryThis.examples.size}",
            tryThis.examples.size >= 3
        )
        for (example in tryThis.examples) {
            assertTrue(example.isNotBlank())
        }
    }

    @Test
    fun `parameter counts are described in words not just the model id`() {
        val cardsSmall = DownloadCards.forModel(small)
        val whatSmall = cardsSmall.first { it.heading == "What you're downloading" }
        assertTrue(
            "expected '600 million parameters' language, got: ${whatSmall.body}",
            whatSmall.body.contains("600 million parameters", ignoreCase = true)
        )

        val cardsMid = DownloadCards.forModel(mid)
        val whatMid = cardsMid.first { it.heading == "What you're downloading" }
        assertTrue(
            "expected '2 billion parameters' language, got: ${whatMid.body}",
            whatMid.body.contains("2 billion parameters", ignoreCase = true)
        )

        val cardsLarge = DownloadCards.forModel(large)
        val whatLarge = cardsLarge.first { it.heading == "What you're downloading" }
        assertTrue(
            "expected '8 billion parameters' language, got: ${whatLarge.body}",
            whatLarge.body.contains("8 billion parameters", ignoreCase = true)
        )
    }
}
