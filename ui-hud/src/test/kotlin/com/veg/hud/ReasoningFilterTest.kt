package com.veg.hud

import org.junit.Assert.assertEquals
import org.junit.Test

class ReasoningFilterTest {

    @Test fun `plain text passes through unchanged`() {
        assertEquals("Hello! How can I help you today", ReasoningFilter.visible("Hello! How can I help you today"))
    }

    @Test fun `a complete think block is removed`() {
        assertEquals(
            "Hello! How can I help you today",
            ReasoningFilter.visible("<think>\n\n</think>\n\nHello! How can I help you today")
        )
    }

    @Test fun `an unclosed think block withholds everything after it`() {
        assertEquals("", ReasoningFilter.visible("<think>reasoning about the answer"))
    }

    @Test fun `a partial opening tag is withheld until it resolves`() {
        assertEquals("Hello", ReasoningFilter.visible("Hello <thi"))
    }

    @Test fun `text before a think block is shown`() {
        assertEquals(
            "Sure, here's the answer:",
            ReasoningFilter.visible("Sure, here's the answer:<think>let me think</think>")
        )
    }

    @Test fun `multiple think blocks are all removed`() {
        assertEquals(
            "Part one Part two",
            ReasoningFilter.visible("<think>a</think>Part one <think>b</think>Part two")
        )
    }

    @Test fun `a lone angle bracket that is not a tag is shown`() {
        assertEquals("3 < 5 is true", ReasoningFilter.visible("3 < 5 is true"))
    }

    @Test fun `whitespace left by a removed block is trimmed`() {
        assertEquals(
            "Hello! How can I help you today",
            ReasoningFilter.visible("<think>\n\n</think>\n\nHello! How can I help you today")
        )
    }

    @Test fun `isReasoning is true inside an unclosed block`() {
        assertEquals(true, ReasoningFilter.isReasoning("<think>reasoning about the answer"))
    }

    @Test fun `isReasoning is false after the block closes`() {
        assertEquals(
            false,
            ReasoningFilter.isReasoning("<think>reasoning</think>Here is the answer")
        )
    }

    @Test fun `isReasoning is false for plain text`() {
        assertEquals(false, ReasoningFilter.isReasoning("Hello! How can I help you today"))
    }
}
