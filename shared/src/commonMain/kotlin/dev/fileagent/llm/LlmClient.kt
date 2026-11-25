package dev.fileagent.llm

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

data class ToolSpec(val name: String, val description: String, val inputSchema: JsonObject)

data class ToolCall(val id: String, val name: String, val input: JsonObject)

data class AssistantTurn(
    val text: String,
    val toolCalls: List<ToolCall>,
    /** Raw content blocks, echoed back into history on the next call. */
    val rawContent: JsonArray,
    val stopReason: String?,
)

/**
 * Swap this to change model provider (Claude, OpenRouter, an on-device model...).
 * Messages use the Anthropic Messages shape: {"role": ..., "content": [...]}.
 */
interface LlmClient {
    suspend fun complete(
        system: String,
        messages: List<JsonObject>,
        tools: List<ToolSpec>,
    ): AssistantTurn
}
