package com.veg.hud

import com.veg.device.ModelCandidate

data class DownloadCard(
    val heading: String,
    val body: String,
    /** Example prompts, shown as tappable chips. Empty for most cards. */
    val examples: List<String> = emptyList()
)

/**
 * Content shown while a model downloads.
 *
 * A download is minutes of waiting, and an animated diagram is something you
 * glance at rather than watch. These cards give the user something to read
 * that they did not already know — what the model is, what it is good at,
 * and candidly what it is not — so the wait teaches them how to use the thing
 * they are waiting for.
 */
object DownloadCards {

    private const val GB = 1024f * 1024f * 1024f

    fun forModel(candidate: ModelCandidate): List<DownloadCard> {
        val paramText = paramTextFor(candidate.displayName)
        val paramShort = paramShortFor(candidate.displayName)
        val quantText = quantTextFor(candidate.id)
        val sizeGb = String.format("%.1f", candidate.fileBytes / GB)

        return listOf(
            DownloadCard(
                heading = "What you're downloading",
                body = "${candidate.tierName} is ${candidate.displayName}, running entirely on " +
                    "your phone. $paramText compressed to $quantText, about $sizeGb GB on disk."
            ),
            DownloadCard(
                heading = "Try this first",
                body = "Tap to copy. These play to what a local model does well.",
                examples = listOf(
                    "Summarise this in three bullets: …",
                    "Draft a polite reply declining a meeting",
                    "Rewrite this to sound more confident: …",
                    "What are three counterarguments to this?"
                )
            ),
            DownloadCard(
                heading = "Where it shines",
                body = shineTextFor(candidate)
            ),
            DownloadCard(
                heading = "Where it won't",
                body = "Recent events it was never trained on. Long multi-step arithmetic. " +
                    "Precise citations and exact quotes. A $paramShort model on a phone is not " +
                    "a frontier model — what it gives you is speed and privacy."
            ),
            DownloadCard(
                heading = "This is the last time it needs the internet",
                body = "After this download Vega runs offline. Your conversations are stored " +
                    "only on this phone, and nothing is sent anywhere. Aeroplane mode changes " +
                    "nothing."
            ),
            DownloadCard(
                heading = "What happens when it finishes",
                body = "The file is checked against its published fingerprint, then loaded " +
                    "into memory. The first reply takes a moment longer while the model warms " +
                    "up; after that it stays ready."
            )
        )
    }

    private fun shineTextFor(candidate: ModelCandidate): String {
        val billions = billionsFrom(candidate.displayName)
        return when {
            billions < 2f ->
                "Quick answers, summarising, rewriting, and drafting short messages. It is " +
                    "fast because it is small."
            billions <= 4f ->
                "Summarising, rewriting, drafting, and everyday questions. A good balance of " +
                    "speed and depth."
            else ->
                "Longer reasoning, nuanced writing, and multi-part questions. Slower, but it " +
                    "thinks more carefully."
        }
    }

    /** e.g. "0.6B" -> "600 million parameters", "2B" -> "2 billion parameters". */
    private fun paramTextFor(displayName: String): String {
        val billions = billionsFrom(displayName)
        return if (billions < 1f) {
            val millions = Math.round(billions * 1000)
            "$millions million parameters"
        } else {
            val rounded = if (billions == billions.toLong().toFloat()) {
                billions.toLong().toString()
            } else {
                String.format("%.1f", billions).trimEnd('0').trimEnd('.')
            }
            "$rounded billion parameters"
        }
    }

    /** e.g. "0.6B" -> "600M", "2B" -> "2B", "8B" -> "8B" — short form for the limitations card. */
    private fun paramShortFor(displayName: String): String {
        val billions = billionsFrom(displayName)
        return if (billions < 1f) {
            "${Math.round(billions * 1000)}M"
        } else {
            val rounded = if (billions == billions.toLong().toFloat()) {
                billions.toLong().toString()
            } else {
                String.format("%.1f", billions).trimEnd('0').trimEnd('.')
            }
            "${rounded}B"
        }
    }

    private fun billionsFrom(displayName: String): Float {
        val match = Regex("""(\d+(?:\.\d+)?)B""").find(displayName)
            ?: return 0f
        return match.groupValues[1].toFloatOrNull() ?: 0f
    }

    private fun quantTextFor(id: String): String = when {
        id.contains("q4km") -> "4 bits each"
        id.contains("q5km") -> "5 bits each"
        id.contains("q6k") -> "6 bits each"
        id.contains("q8") -> "8 bits each"
        else -> "a lower precision"
    }
}
