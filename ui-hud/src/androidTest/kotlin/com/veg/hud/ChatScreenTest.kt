package com.veg.hud

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.onNodeWithContentDescription
import com.veg.data.Message
import com.veg.device.ThermalState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ChatScreenTest {

    @get:Rule val compose = createComposeRule()

    private val idle = ReactorState(
        mode = ReactorMode.IDLE,
        tokensPerSecond = 0f,
        thermalState = ThermalState.NOMINAL,
        batteryPercent = 80,
        isCharging = false
    )

    @Test
    fun rendersExistingMessages() {
        compose.setContent {
            VegTheme {
                ChatScreen(
                    state = idle,
                    messages = listOf(
                        Message(id = 1, conversationId = 1, role = Message.ROLE_USER, content = "hello", createdAt = 1),
                        Message(id = 2, conversationId = 1, role = Message.ROLE_ASSISTANT, content = "hi there", createdAt = 2)
                    ),
                    streamingText = "",
                    residentGb = 4.9f,
                    isReady = true,
                    onSend = {}
                )
            }
        }

        compose.onNodeWithText("hello").assertIsDisplayed()
        compose.onNodeWithText("hi there").assertIsDisplayed()
    }

    @Test
    fun showsStreamingTextWhileGenerating() {
        compose.setContent {
            VegTheme {
                ChatScreen(
                    state = idle.copy(mode = ReactorMode.GENERATING, tokensPerSecond = 10f),
                    messages = emptyList(),
                    streamingText = "partial reply",
                    residentGb = 5.2f,
                    isReady = true,
                    onSend = {}
                )
            }
        }

        compose.onNodeWithText("partial reply").assertIsDisplayed()
    }

    @Test
    fun sendingClearsTheDraftAndReportsTheText() {
        var sent: String? = null

        compose.setContent {
            VegTheme {
                ChatScreen(
                    state = idle,
                    messages = emptyList(),
                    streamingText = "",
                    residentGb = 4.9f,
                    isReady = true,
                    onSend = { sent = it }
                )
            }
        }

        compose.onNodeWithTag("composer-field").performTextInput("what is the plan")
        compose.onNodeWithText("SEND").performClick()

        assertEquals("what is the plan", sent)
    }

    @Test
    fun thermalStateSurfacesAsAWarningChip() {
        compose.setContent {
            VegTheme {
                ChatScreen(
                    state = idle.copy(mode = ReactorMode.THERMAL, thermalState = ThermalState.SEVERE),
                    messages = emptyList(),
                    streamingText = "",
                    residentGb = 5.2f,
                    isReady = true,
                    onSend = {}
                )
            }
        }

        compose.onNodeWithContentDescription("SEVERE").assertIsDisplayed()
        compose.onNodeWithText("THROTTLING").assertIsDisplayed()
    }
}
