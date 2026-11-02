package com.veg.hud

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.veg.data.Message

@Composable
fun ChatScreen(
    state: ReactorState,
    messages: List<Message>,
    streamingText: String,
    residentGb: Float,
    isReady: Boolean,
    onSend: (String) -> Unit,
    onOpenAutomationSettings: () -> Unit = {},
    isGenerating: Boolean = false,
    onStop: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Follow the newest content as tokens arrive.
    LaunchedEffect(messages.size, streamingText) {
        val lastIndex = messages.size
        if (lastIndex > 0) listState.animateScrollToItem(lastIndex)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(VegColors.Ground)
            .windowInsetsPadding(
                WindowInsets.systemBars
                    .union(WindowInsets.displayCutout)
                    .union(WindowInsets.ime)
            )
    ) {
        Column(Modifier.fillMaxSize()) {

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(254.dp)
            ) {
                Reactor(state = state, modifier = Modifier.fillMaxSize())
                Text(
                    text = captionFor(state),
                    style = MonoStyle,
                    color = VegColors.NeutralDim,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 10.dp)
                )
            }

            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(VegColors.HairlineLo)
            )

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 16.dp)
            ) {
                items(messages, key = { it.id }) { message ->
                    val content = if (message.role == Message.ROLE_ASSISTANT) {
                        ReasoningFilter.visible(message.content)
                    } else {
                        message.content
                    }
                    MessageRow(message.role, content)
                }
                val visibleStreamingText = ReasoningFilter.visible(streamingText)
                if (visibleStreamingText.isNotEmpty()) {
                    item(key = "streaming") {
                        MessageRow(Message.ROLE_ASSISTANT, visibleStreamingText)
                    }
                } else if (ReasoningFilter.isReasoning(streamingText)) {
                    // The model is inside an unclosed <think> block: nothing
                    // displayable exists yet, but the user should not see a
                    // dead screen while hundreds of tokens are produced.
                    item(key = "thinking") {
                        ThinkingIndicator(tokenCount = streamingText.trim().split("\\s+".toRegex())
                            .count { it.isNotEmpty() })
                    }
                }
            }

            Composer(
                draft = draft,
                onDraftChange = { draft = it },
                isReady = isReady,
                isGenerating = isGenerating,
                onSend = {
                    if (draft.isNotBlank()) {
                        onSend(draft)
                        draft = ""
                    }
                },
                onStop = onStop
            )
        }

        TelemetryChips(
            chips = chipsFor(state, residentGb),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 12.dp, end = 12.dp)
        )

        Text(
            text = "AUTO",
            style = MonoStyle,
            color = VegColors.Neutral,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(top = 12.dp, start = 12.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(VegColors.Panel.copy(alpha = 0.72f))
                .border(1.dp, VegColors.HairlineLo, RoundedCornerShape(999.dp))
                .clickable { onOpenAutomationSettings() }
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .semantics { contentDescription = "Automation settings" }
        )
    }
}

private fun captionFor(state: ReactorState): String = when (state.mode) {
    ReactorMode.IDLE -> "STANDBY"
    ReactorMode.THINKING -> "THINKING"
    ReactorMode.GENERATING -> "GENERATING"
    ReactorMode.THERMAL -> "THROTTLING"
    ReactorMode.LOW_POWER -> "LOW POWER"
}

@Composable
private fun MessageRow(role: String, content: String) {
    if (role == Message.ROLE_USER) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Text(
                text = content,
                color = VegColors.Text,
                fontSize = 13.sp,
                modifier = Modifier
                    .background(VegColors.PanelHi, RoundedCornerShape(12.dp, 12.dp, 3.dp, 12.dp))
                    .border(1.dp, VegColors.Hairline, RoundedCornerShape(12.dp, 12.dp, 3.dp, 12.dp))
                    .padding(horizontal = 12.dp, vertical = 9.dp)
            )
        }
    } else {
        Column(Modifier.fillMaxWidth()) {
            Text("VEGA", style = MonoStyle, color = VegColors.CoolDeep)
            Text(
                text = content,
                color = VegColors.Text,
                fontSize = 13.sp,
                modifier = Modifier.padding(start = 12.dp, top = 4.dp)
            )
        }
    }
}

/**
 * Shown in place of the assistant bubble while the stream is inside an
 * unclosed <think> block. Never renders the reasoning content itself — the
 * user asked for it to be hidden — only that something is happening and
 * roughly how much has been produced.
 */
@Composable
private fun ThinkingIndicator(tokenCount: Int) {
    Column(Modifier.fillMaxWidth()) {
        Text("VEGA", style = MonoStyle, color = VegColors.CoolDeep)
        Text(
            text = "Thinking… ($tokenCount tokens)",
            style = MonoStyle,
            color = VegColors.NeutralDim,
            fontSize = 13.sp,
            modifier = Modifier.padding(start = 12.dp, top = 4.dp)
        )
    }
}

@Composable
private fun Composer(
    draft: String,
    onDraftChange: (String) -> Unit,
    isReady: Boolean,
    isGenerating: Boolean = false,
    onSend: () -> Unit,
    onStop: () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 13.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        Box(
            Modifier
                .weight(1f)
                .background(VegColors.Panel, RoundedCornerShape(999.dp))
                .border(1.dp, VegColors.Hairline, RoundedCornerShape(999.dp))
                .padding(horizontal = 14.dp, vertical = 9.dp)
        ) {
            if (draft.isEmpty()) {
                Text(
                    text = if (isReady) "Message" else "Waking up…",
                    color = VegColors.NeutralDim,
                    fontSize = 13.sp
                )
            }
            BasicTextField(
                value = draft,
                onValueChange = { if (isReady) onDraftChange(it) },
                readOnly = !isReady,
                textStyle = TextStyle(color = VegColors.Text, fontSize = 13.sp),
                cursorBrush = SolidColor(VegColors.Cool),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("composer-field")
            )
        }
        Text(
            text = if (isGenerating) "STOP" else "SEND",
            style = MonoStyle,
            color = if (isGenerating) VegColors.Warm else if (isReady) VegColors.Cool else VegColors.NeutralDim,
            modifier = Modifier
                .border(
                    1.dp,
                    if (isGenerating) VegColors.Warm else if (isReady) VegColors.CoolDeep else VegColors.Hairline,
                    RoundedCornerShape(999.dp)
                )
                .clickable(enabled = isReady || isGenerating) {
                    if (isGenerating) onStop() else onSend()
                }
                .padding(horizontal = 12.dp, vertical = 9.dp)
        )
    }
}
