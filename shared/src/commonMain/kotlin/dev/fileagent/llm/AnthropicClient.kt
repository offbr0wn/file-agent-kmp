package dev.fileagent.llm

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Calls an Anthropic Messages-format API with tool use.
 * Works for Claude and for DeepSeek's Anthropic-compatible endpoint (baseUrl https://api.deepseek.com/anthropic).
 */
class AnthropicClient(
    private val http: HttpClient,
    private val apiKey: () -> String,
    private val model: String = "claude-sonnet-5-5",
    private val maxTokens: Int = 8192,
    private val baseUrl: String = "https://api.anthropic.com",
    /** DeepSeek may think by default, which can use up the token budget before any tool call. */
    private val disableThinking: Boolean = false,
) : LlmClient {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun complete(
        system: String,
        messages: List<JsonObject>,
        tools: List<ToolSpec>,
    ): AssistantTurn {
        val body = buildJsonObject {
            put("model", model)
            put("max_tokens", maxTokens)
            put("system", system)
            if (disableThinking) putJsonObject("thinking") { put("type", "disabled") }
            putJsonArray("messages") { messages.forEach { add(it) } }
            putJsonArray("tools") {
                tools.forEach { t ->
                    addJsonObject {
                        put("name", t.name)
                        put("description", t.description)
                        put("input_schema", t.inputSchema)
                    }
                }
            }
        }

        val response = http.post("$baseUrl/v1/messages") {
            header("x-api-key", apiKey())
            header("anthropic-version", "2023-06-01")
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw AiApiException(response.status.value, readableError(text))
        }

        val root = json.parseToJsonElement(text).jsonObject
        val content = root["content"]?.jsonArray ?: JsonArray(emptyList())
        val texts = StringBuilder()
        val calls = mutableListOf<ToolCall>()
        content.forEach { el ->
            val block = el.jsonObject
            when (block["type"]?.jsonPrimitive?.content) {
                "text" -> texts.append(block["text"]?.jsonPrimitive?.content.orEmpty())
                "tool_use" -> calls += ToolCall(
                    id = block["id"]!!.jsonPrimitive.content,
                    name = block["name"]!!.jsonPrimitive.content,
                    input = block["input"]?.jsonObject ?: JsonObject(emptyMap()),
                )
            }
        }
        return AssistantTurn(
            text = texts.toString(),
            toolCalls = calls,
            rawContent = content,
            stopReason = root["stop_reason"]?.jsonPrimitive?.content,
        )
    }

    /** Pulls the plain message out of an error body (Anthropic or DeepSeek shape). */
    private fun readableError(body: String): String = runCatching {
        val err = json.parseToJsonElement(body).jsonObject["error"]?.jsonObject
        err?.get("message")?.jsonPrimitive?.content
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: body.take(300)
}

/** The AI service said no. [status] is the HTTP code, [reason] its message. */
class AiApiException(val status: Int, val reason: String) : IllegalStateException("AI error $status: $reason")
