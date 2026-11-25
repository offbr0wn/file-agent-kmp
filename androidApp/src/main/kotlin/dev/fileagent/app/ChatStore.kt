package dev.fileagent.app

import dev.fileagent.agent.Plan
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * One line in a chat.
 * kind: "user", "assistant", "activity" (tool steps, one per line), "plan", "error", "note".
 * status: activity "running" / "done" / "failed"; plan "pending" / "applied" / "discarded".
 */
data class ChatItem(
    val kind: String,
    val text: String,
    val status: String = "",
    /** Pictures the user attached (files in the app's storage), or for "files" the paths shown. */
    val images: List<String> = emptyList(),
    /** For "files": the paths the user ticked. */
    val picked: List<String> = emptyList(),
)

data class ChatMeta(val id: String, val title: String, val updated: Long)

data class SavedChat(
    val id: String,
    val title: String,
    val updated: Long,
    val items: List<ChatItem>,
    /** The AI conversation, so a chat can continue after a restart. */
    val sessionJson: String?,
    /** A plan waiting for Apply / Discard. */
    val pendingPlan: Plan?,
    /** Folders this chat may use (place names). */
    val places: List<String> = emptyList(),
)

/** Saves every chat as a small JSON file in the app's private storage. */
class ChatStore(private val dir: File) {
    private val json = Json { ignoreUnknownKeys = true }

    init {
        dir.mkdirs()
    }

    fun newId(): String = System.currentTimeMillis().toString(36)

    fun list(): List<ChatMeta> =
        dir.listFiles { f -> f.extension == "json" }.orEmpty().mapNotNull { f ->
            runCatching {
                val o = JSONObject(f.readText())
                ChatMeta(o.getString("id"), o.optString("title", "Chat"), o.optLong("updated"))
            }.getOrNull()
        }.sortedByDescending { it.updated }

    fun load(id: String): SavedChat? = runCatching {
        val o = JSONObject(File(dir, "$id.json").readText())
        val arr = o.getJSONArray("items")
        SavedChat(
            id = o.getString("id"),
            title = o.optString("title", "Chat"),
            updated = o.optLong("updated"),
            items = (0 until arr.length()).map {
                val i = arr.getJSONObject(it)
                ChatItem(
                    i.getString("kind"), i.optString("text"), i.optString("status"),
                    i.optJSONArray("images")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
                    i.optJSONArray("picked")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
                )
            },
            sessionJson = o.optString("session").ifBlank { null },
            pendingPlan = o.optString("plan").ifBlank { null }
                ?.let { json.decodeFromString(Plan.serializer(), it) },
            places = o.optJSONArray("places")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
        )
    }.getOrNull()

    fun save(chat: SavedChat) {
        val items = JSONArray()
        chat.items.forEach {
            val o = JSONObject().put("kind", it.kind).put("text", it.text).put("status", it.status)
            if (it.images.isNotEmpty()) o.put("images", JSONArray(it.images))
            if (it.picked.isNotEmpty()) o.put("picked", JSONArray(it.picked))
            items.put(o)
        }
        val o = JSONObject()
            .put("id", chat.id)
            .put("title", chat.title)
            .put("updated", chat.updated)
            .put("items", items)
            .put("session", chat.sessionJson ?: "")
            .put("plan", chat.pendingPlan?.let { json.encodeToString(Plan.serializer(), it) } ?: "")
            .put("places", JSONArray(chat.places))
        File(dir, "${chat.id}.json").writeText(o.toString())
    }

    fun delete(id: String) {
        // Pictures sent in this chat go too.
        load(id)?.items?.filter { it.kind == "user" }?.flatMap { it.images }?.forEach { runCatching { File(it).delete() } }
        File(dir, "$id.json").delete()
    }
}
