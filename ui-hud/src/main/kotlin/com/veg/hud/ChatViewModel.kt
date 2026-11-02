package com.veg.hud

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.veg.data.Conversation
import com.veg.data.VegDatabase
import com.veg.data.Message
import com.veg.device.DeviceReadings
import com.veg.device.RuntimeTuner
import com.veg.inference.ChatTurn
import com.veg.inference.InferenceEngine
import com.veg.inference.Token
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ChatUiState(
    val reactor: ReactorState,
    val messages: List<Message> = emptyList(),
    val streamingText: String = "",
    val residentGb: Float = 0f,
    val isReady: Boolean = false
)

/**
 * Owns one conversation and the generation lifecycle.
 *
 * Throughput in the reactor state is measured from real token arrivals rather
 * than the model's advertised speed, which is what makes the HUD honest when
 * the device throttles.
 */
class ChatViewModel(
    private val engine: InferenceEngine,
    private val db: VegDatabase,
    private val tuner: RuntimeTuner,
    private val readings: () -> DeviceReadings
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        ChatUiState(reactor = reactorStateNow(isGenerating = false, isThinking = false, tps = 0f))
    )
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var conversationId: Long? = null
    private var generation: Job? = null

    /**
     * Seeds a brief assistant greeting the moment the engine becomes ready,
     * so the chat screen is never just an empty list with an enabled
     * composer — the user gets an explicit signal that generation is live.
     *
     * Inserted through the same conversation/message path as any other
     * message (rather than a transient UI-only banner) so it persists and
     * reappears if the user backs out and returns.
     *
     * A no-op if messages already exist for this conversation — e.g. the
     * screen recomposed after a retry, or the user returned to an existing
     * chat — so the greeting is never duplicated.
     */
    fun seedReadyMessageIfNeeded() {
        viewModelScope.launch {
            if (conversationId != null || _uiState.value.messages.isNotEmpty()) return@launch

            val now = System.currentTimeMillis()
            val convId = db.conversations()
                .insert(Conversation(title = "New chat", createdAt = now, updatedAt = now))
                .also { conversationId = it }

            db.messages().insert(
                Message(
                    conversationId = convId,
                    role = Message.ROLE_ASSISTANT,
                    content = READY_GREETING,
                    createdAt = now
                )
            )
            refreshMessages(convId)
        }
    }

    fun send(text: String) {
        val prompt = text.trim()
        if (prompt.isEmpty()) return

        generation = viewModelScope.launch {
            val now = System.currentTimeMillis()
            val convId = conversationId ?: db.conversations()
                .insert(Conversation(title = prompt.take(40), createdAt = now, updatedAt = now))
                .also { conversationId = it }

            db.messages().insert(
                Message(conversationId = convId, role = Message.ROLE_USER, content = prompt, createdAt = now)
            )
            refreshMessages(convId)

            _uiState.value = _uiState.value.copy(
                reactor = reactorStateNow(isGenerating = false, isThinking = true, tps = 0f)
            )

            val history = db.messages().forConversation(convId)
                .dropLast(1)
                .map { ChatTurn(it.role, it.content) }

            val builder = StringBuilder()
            var tokenCount = 0
            val startedAt = System.currentTimeMillis()

            try {
                engine.generate(history, prompt).collect { token ->
                    when (token) {
                        is Token.Text -> {
                            builder.append(token.value)
                            tokenCount++
                            val elapsed = (System.currentTimeMillis() - startedAt).coerceAtLeast(1)
                            val tps = tokenCount * 1000f / elapsed
                            _uiState.value = _uiState.value.copy(
                                streamingText = builder.toString(),
                                reactor = reactorStateNow(isGenerating = true, isThinking = false, tps = tps)
                            )
                        }

                        is Token.Complete -> {
                            val tps = if (token.elapsedMillis > 0) {
                                token.tokenCount * 1000f / token.elapsedMillis
                            } else 0f
                            persistAssistantReply(convId, builder.toString(), tps)
                        }

                        is Token.Error -> {
                            db.messages().insert(
                                Message(
                                    conversationId = convId,
                                    role = Message.ROLE_ASSISTANT,
                                    content = token.message,
                                    createdAt = System.currentTimeMillis()
                                )
                            )
                            refreshMessages(convId)
                            _uiState.value = _uiState.value.copy(
                                streamingText = "",
                                reactor = reactorStateNow(isGenerating = false, isThinking = false, tps = 0f)
                            )
                        }
                    }
                }
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                // Stopped mid-generation: keep whatever was produced so far
                // rather than discarding it. Persisting here (instead of only
                // in the Token.Complete branch) is what makes stop() a "keep
                // partial output" action rather than a silent discard.
                val elapsed = (System.currentTimeMillis() - startedAt).coerceAtLeast(1)
                val tps = tokenCount * 1000f / elapsed
                withContext(NonCancellable) {
                    persistAssistantReply(convId, builder.toString(), tps)
                }
                throw cancellation
            }
        }
    }

    /**
     * Persists the assistant's turn, substituting an explanatory message when
     * the text the user would actually see is blank.
     *
     * Filtering happens at display time (see [ReasoningFilter]), so a raw
     * reply that is non-empty can still resolve to nothing visible — e.g. the
     * model spent its whole token budget inside an unclosed <think> block.
     * Persisting an empty string in that case is what produced the original
     * bug: a live token counter for minutes, then a blank bubble. The two
     * explanatory messages are kept distinguishable in code because they mean
     * different things: one is a truncation problem (reasoning ran out of
     * budget), the other is a genuine model failure (no output at all).
     */
    private suspend fun persistAssistantReply(convId: Long, raw: String, tps: Float) {
        val visible = ReasoningFilter.visible(raw)
        val content = if (visible.isNotBlank()) {
            raw
        } else if (raw.isNotBlank()) {
            TRUNCATED_REASONING_MESSAGE
        } else {
            EMPTY_RESPONSE_MESSAGE
        }

        db.messages().insert(
            Message(
                conversationId = convId,
                role = Message.ROLE_ASSISTANT,
                content = content,
                createdAt = System.currentTimeMillis(),
                tokensPerSecond = tps
            )
        )
        db.conversations().touch(convId, System.currentTimeMillis())
        refreshMessages(convId)

        _uiState.value = _uiState.value.copy(
            streamingText = "",
            reactor = reactorStateNow(isGenerating = false, isThinking = false, tps = 0f)
        )
    }

    fun stop() {
        generation?.cancel()
        viewModelScope.launch { engine.cancel() }
    }

    private suspend fun refreshMessages(conversationId: Long) {
        _uiState.value = _uiState.value.copy(
            messages = db.messages().forConversation(conversationId),
            isReady = true
        )
    }

    private fun reactorStateNow(isGenerating: Boolean, isThinking: Boolean, tps: Float): ReactorState {
        val current = readings()
        val thermal = tuner.thermalState(current.thermalHeadroom)
        return ReactorState(
            mode = reactorModeFor(
                isGenerating = isGenerating,
                isThinking = isThinking,
                thermal = thermal,
                batteryPercent = current.batteryPercent,
                isCharging = current.isCharging
            ),
            tokensPerSecond = tps,
            thermalState = thermal,
            batteryPercent = current.batteryPercent,
            isCharging = current.isCharging
        )
    }

    companion object {
        const val READY_GREETING =
            "Ready. I'm running entirely on this phone — no network, nothing leaves the device. Ask me anything."

        /** Raw text existed but was entirely reasoning that never closed — a truncation problem. */
        const val TRUNCATED_REASONING_MESSAGE =
            "I spent my whole thinking budget on that one and didn't reach an answer. Try asking more simply, or ask me to skip the reasoning."

        /** No raw text at all — a genuine model failure, not a budget issue. */
        const val EMPTY_RESPONSE_MESSAGE =
            "I didn't produce a reply to that. Try rephrasing."
    }
}
