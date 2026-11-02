package com.veg.hud

/**
 * Removes a reasoning model's internal monologue from displayed text.
 *
 * Qwen3.5 and similar models emit <think>...</think> before their answer. That
 * is working-out, not a reply, and showing it makes the assistant look broken.
 *
 * Tokens arrive one at a time, so a naive replace would flash partial tags on
 * screen as "<", "<th", "<thi" stream in. This holds back any trailing text
 * that could still turn out to be the start of a tag.
 */
object ReasoningFilter {

    private const val OPEN = "<think>"
    private const val CLOSE = "</think>"

    /**
     * Returns the safely-displayable portion of a partial stream, with
     * reasoning blocks removed and any ambiguous tail withheld.
     */
    fun visible(raw: String): String {
        val result = StringBuilder()
        var i = 0

        while (i < raw.length) {
            val openIndex = raw.indexOf(OPEN, i)

            if (openIndex == -1) {
                // No complete open tag ahead. Check whether the remaining
                // tail could be the start of one and withhold it if so.
                val safeEnd = safeLength(raw, i)
                result.append(raw, i, safeEnd)
                break
            }

            // Text before the tag is safe to show.
            result.append(raw, i, openIndex)

            val closeIndex = raw.indexOf(CLOSE, openIndex + OPEN.length)
            if (closeIndex == -1) {
                // Unclosed think block: everything from here on is withheld.
                break
            }

            // Skip the whole block, including its content.
            i = closeIndex + CLOSE.length
        }

        return result.toString().trim()
    }

    /**
     * True when the stream is currently inside an unclosed reasoning block.
     *
     * The caller needs this to tell the user "still thinking" rather than
     * rendering an empty bubble while hundreds of tokens are produced.
     */
    fun isReasoning(raw: String): Boolean {
        var i = 0
        while (true) {
            val openIndex = raw.indexOf(OPEN, i)
            if (openIndex == -1) return false
            val closeIndex = raw.indexOf(CLOSE, openIndex + OPEN.length)
            if (closeIndex == -1) return true
            i = closeIndex + CLOSE.length
        }
    }

    /**
     * Returns the length of the prefix of `raw` (starting at [from]) that is
     * guaranteed not to be the start of an "<think>" tag, so it is safe to
     * display immediately.
     */
    private fun safeLength(raw: String, from: Int): Int {
        for (start in from until raw.length) {
            if (raw[start] != '<') continue
            val remaining = raw.length - start
            val compareLength = minOf(remaining, OPEN.length)
            if (raw.regionMatches(start, OPEN, 0, compareLength)) {
                // This '<' could be growing into "<think>" — withhold from here.
                return start
            }
        }
        return raw.length
    }
}
