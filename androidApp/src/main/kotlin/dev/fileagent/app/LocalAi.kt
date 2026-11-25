package dev.fileagent.app

import android.app.ActivityManager
import android.app.DownloadManager
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.ThinkingConfig
import com.google.ai.edge.litertlm.tool
import dev.fileagent.llm.AssistantTurn
import dev.fileagent.llm.LlmClient
import dev.fileagent.llm.ToolCall
import dev.fileagent.llm.ToolSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/** Where the on-phone model stands. status: "none", "downloading", "ready" or "failed". */
data class LocalModelState(val status: String = "none", val percent: Int = 0, val note: String? = null)

/**
 * The model file for the on-phone AI (Gemma, about 2.6 GB). It is downloaded once by
 * Android's own download service, so it keeps going if the app is closed and picks up
 * again after a lost connection.
 */
class LocalModel(private val context: Context, private val prefs: SharedPreferences) {
    private val dm get() = context.getSystemService(DownloadManager::class.java)

    val file: File get() = File(context.getExternalFilesDir(null), "models/$FILE")

    val isReady: Boolean
        get() = prefs.getLong(KEY_SIZE, -1) > 0 && file.isFile && file.length() == prefs.getLong(KEY_SIZE, -1)

    /** Phone memory in GB. The model wants about 6 GB or more to run well. */
    val ramGb: Int
        get() = runCatching {
            val info = ActivityManager.MemoryInfo()
            context.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
            ((info.totalMem + (1L shl 29)) shr 30).toInt()
        }.getOrDefault(0)

    val hasRoom: Boolean
        get() = (context.getExternalFilesDir(null)?.usableSpace ?: 0L) > 3L * 1024 * 1024 * 1024

    fun start() {
        if (isReady) return
        cancel()
        file.parentFile?.mkdirs()
        val request = DownloadManager.Request(Uri.parse(URL))
            .setTitle("File Agent: on-phone AI")
            .setDescription("One-time download, about 2.6 GB")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, null, "models/$FILE")
            .setAllowedOverMetered(true)
        prefs.edit().putLong(KEY_ID, dm.enqueue(request)).apply()
    }

    /** Stops a download and removes the model file. */
    fun cancel() {
        val id = prefs.getLong(KEY_ID, -1)
        if (id >= 0) runCatching { dm.remove(id) }
        runCatching { file.delete() }
        prefs.edit().remove(KEY_ID).remove(KEY_SIZE).apply()
    }

    fun state(): LocalModelState {
        if (isReady) return LocalModelState("ready", 100)
        val id = prefs.getLong(KEY_ID, -1)
        if (id < 0) return LocalModelState("none")
        return runCatching {
            dm.query(DownloadManager.Query().setFilterById(id)).use { c ->
                if (!c.moveToFirst()) return@use LocalModelState("none")
                val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                val pct = if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else 0
                when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        if (file.isFile && file.length() > 0) {
                            prefs.edit().putLong(KEY_SIZE, file.length()).remove(KEY_ID).apply()
                            LocalModelState("ready", 100)
                        } else LocalModelState("failed", 0, "The download went missing. Try again.")
                    }
                    DownloadManager.STATUS_FAILED -> LocalModelState("failed", pct, "Download failed. Check your connection and space.")
                    DownloadManager.STATUS_PAUSED -> LocalModelState("downloading", pct, "Waiting for the network")
                    else -> LocalModelState("downloading", pct)
                }
            }
        }.getOrDefault(LocalModelState("none"))
    }

    companion object {
        private const val FILE = "gemma-4-E2B-it.litertlm"
        private const val URL = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/$FILE"
        private const val KEY_ID = "local_model_download"
        private const val KEY_SIZE = "local_model_size"
    }
}

/**
 * Runs the AI on the phone itself with Google's LiteRT-LM. Nothing is sent anywhere.
 * It speaks the same message shape as the cloud clients, so the agent does not care which is used.
 */
class LocalLlm(
    private val context: Context,
    private val model: LocalModel,
    private val prefs: SharedPreferences,
) : LlmClient {
    private var engine: Engine? = null
    private var gpuUnproven = false
    private val lock = Mutex()
    private var callCount = 0

    /** Frees the model's memory. */
    suspend fun unload() = lock.withLock {
        runCatching { engine?.close() }
        engine = null
    }

    private fun load(): Engine {
        engine?.let { return it }
        val path = model.file.absolutePath
        // The graphics chip is much faster, but not every phone can run the model on it. The flag is
        // set before trying and only cleared after a full reply, so a phone that crashes on it uses
        // the slower, safe path next time.
        if (!prefs.getBoolean(KEY_GPU_BAD, false)) {
            prefs.edit().putBoolean(KEY_GPU_BAD, true).commit()
            val gpu = runCatching {
                Engine(EngineConfig(modelPath = path, backend = Backend.GPU(), maxNumTokens = MAX_TOKENS, cacheDir = context.cacheDir.absolutePath))
                    .also { it.initialize() }
            }.getOrNull()
            if (gpu != null) {
                gpuUnproven = true
                engine = gpu
                return gpu
            }
        }
        return Engine(EngineConfig(modelPath = path, backend = Backend.CPU(), maxNumTokens = MAX_TOKENS, cacheDir = context.cacheDir.absolutePath))
            .also { it.initialize(); engine = it }
    }

    override suspend fun complete(system: String, messages: List<JsonObject>, tools: List<ToolSpec>): AssistantTurn =
        lock.withLock {
            withContext(Dispatchers.Default) {
                check(model.isReady) { "Download the on-phone AI first: Settings, AI, On phone." }
                val local = toLocal(messages)
                check(local.isNotEmpty()) { "Nothing to send" }
                val config = ConversationConfig(
                    systemInstruction = Contents.of(system),
                    initialMessages = local.dropLast(1),
                    tools = tools.map { tool(SpecTool(it)) },
                    // The app runs the tools itself, so every change still goes through the plan.
                    automaticToolCalling = false,
                    thinkingConfig = ThinkingConfig(enableThinking = false),
                )
                val reply = load().createConversation(config).use { it.sendMessage(local.last()) }
                if (gpuUnproven) {
                    gpuUnproven = false
                    prefs.edit().putBoolean(KEY_GPU_BAD, false).apply()
                }
                val text = reply.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }.trim()
                val calls = reply.toolCalls.map { call ->
                    ToolCall("local_${++callCount}", call.name, toJson(call.arguments) as? JsonObject ?: JsonObject(emptyMap()))
                }
                val raw = buildJsonArray {
                    if (text.isNotBlank()) add(buildJsonObject { put("type", "text"); put("text", text) })
                    calls.forEach { c ->
                        add(buildJsonObject { put("type", "tool_use"); put("id", c.id); put("name", c.name); put("input", c.input) })
                    }
                }
                AssistantTurn(text, calls, raw, if (calls.isEmpty()) "end_turn" else "tool_use")
            }
        }

    /** Describes one of the app's tools to the model. The model only asks; the app does the work. */
    private class SpecTool(private val spec: ToolSpec) : OpenApiTool {
        override fun getToolDescriptionJsonString(): String = buildJsonObject {
            put("name", spec.name)
            put("description", spec.description)
            put("parameters", spec.inputSchema)
        }.toString()

        override fun execute(paramsJsonString: String): String = "{}"
    }

    /** Turns the chat history (Anthropic shape) into the on-phone library's messages. */
    private fun toLocal(messages: List<JsonObject>): List<Message> {
        val toolNames = mutableMapOf<String, String>()
        val out = mutableListOf<Message>()
        // Old tool results are cut short so a long chat still fits in the small model's memory.
        val lastResultIndex = messages.indexOfLast { m -> blocks(m).any { str(it, "type") == "tool_result" } }
        messages.forEachIndexed { index, m ->
            val role = str(m, "role")
            val content = m["content"]
            if (content is JsonPrimitive) {
                out += if (role == "assistant") Message.model(content.content) else Message.user(content.content)
                return@forEachIndexed
            }
            val parts = blocks(m)
            val text = parts.filter { str(it, "type") == "text" }.joinToString("\n") { str(it, "text") }.trim()
            if (role == "assistant") {
                val calls = parts.filter { str(it, "type") == "tool_use" }.map { b ->
                    toolNames[str(b, "id")] = str(b, "name")
                    @Suppress("UNCHECKED_CAST")
                    com.google.ai.edge.litertlm.ToolCall(str(b, "name"), (toAny(b["input"] ?: JsonNull) as? Map<String, Any?>).orEmpty())
                }
                if (text.isNotBlank() || calls.isNotEmpty()) {
                    out += Message.model(
                        contents = if (text.isBlank()) Contents.of(emptyList<Content>()) else Contents.of(text),
                        toolCalls = calls,
                    )
                }
            } else {
                val results = parts.filter { str(it, "type") == "tool_result" }
                if (results.isNotEmpty()) {
                    val limit = if (index == lastResultIndex) 5000 else 500
                    out += Message.tool(Contents.of(results.map { r ->
                        Content.ToolResponse(toolNames[str(r, "tool_use_id")] ?: "tool", textOf(r["content"]).take(limit))
                    }))
                }
                if (text.isNotBlank()) out += Message.user(text)
            }
        }
        return out
    }

    private fun blocks(m: JsonObject): List<JsonObject> = (m["content"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

    private fun str(o: JsonObject, key: String): String = (o[key] as? JsonPrimitive)?.content.orEmpty()

    private fun textOf(e: JsonElement?): String = when (e) {
        null, JsonNull -> ""
        is JsonPrimitive -> e.content
        is JsonArray -> e.joinToString("\n") { b -> (b as? JsonObject)?.let { str(it, "text") } ?: textOf(b) }
        is JsonObject -> e.toString()
    }

    private fun toAny(e: JsonElement): Any? = when (e) {
        JsonNull -> null
        is JsonPrimitive -> if (e.isString) e.content else e.content.toBooleanStrictOrNull() ?: e.content.toLongOrNull() ?: e.content.toDoubleOrNull() ?: e.content
        is JsonArray -> e.map { toAny(it) }
        is JsonObject -> e.mapValues { toAny(it.value) }
    }

    private fun toJson(v: Any?): JsonElement = when (v) {
        null -> JsonNull
        is String -> JsonPrimitive(v)
        is Boolean -> JsonPrimitive(v)
        is Number -> if (v.toDouble() % 1.0 == 0.0) JsonPrimitive(v.toLong()) else JsonPrimitive(v.toDouble())
        is Map<*, *> -> JsonObject(v.entries.associate { it.key.toString() to toJson(it.value) })
        is Iterable<*> -> JsonArray(v.map { toJson(it) })
        else -> JsonPrimitive(v.toString())
    }

    companion object {
        private const val KEY_GPU_BAD = "local_gpu_bad"
        private const val MAX_TOKENS = 8192
    }
}
