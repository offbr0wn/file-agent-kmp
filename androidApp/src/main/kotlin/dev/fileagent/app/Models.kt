package dev.fileagent.app

import android.content.SharedPreferences
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * One model a provider offers.
 * [vision]: true if it can look at pictures, false if not, null if the provider did not say.
 */
data class ModelInfo(val id: String, val name: String, val vision: Boolean?) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).apply { if (vision != null) put("vision", vision) }

    companion object {
        fun fromJson(o: JSONObject) = ModelInfo(
            id = o.getString("id"),
            name = o.optString("name").ifBlank { prettyModelName(o.getString("id")) },
            vision = if (o.has("vision")) o.getBoolean("vision") else null,
        )
    }
}

/**
 * Short, readable name for a model id.
 * "claude-sonnet-5-5" -> "Claude Sonnet 5.5", "deepseek/deepseek-v4.1-flash-1017" -> "DeepSeek V4.1 Flash".
 */
fun prettyModelName(id: String): String {
    val parts = id.substringAfterLast('/').split('-', '_', ' ').filter { it.isNotBlank() }
    val out = mutableListOf<String>()
    for (p in parts) {
        val isNum = p.all { it.isDigit() }
        when {
            // Dates and build numbers (20250929, 1017) make names long: drop them.
            isNum && p.length >= 4 -> {}
            // "5" "5" -> "5.5"
            isNum && out.isNotEmpty() && out.last().last().isDigit() -> out[out.lastIndex] = out.last() + "." + p
            p.equals("deepseek", true) -> out += "DeepSeek"
            p.length <= 3 && p.any { it.isDigit() } -> out += p.uppercase() // v4.1 -> V4.1
            else -> out += p.replaceFirstChar { it.uppercase() }
        }
    }
    return out.joinToString(" ").ifBlank { id }
}

/** Asks each provider which models it has, and remembers the answer. */
class ModelCatalog(private val http: HttpClient, private val prefs: SharedPreferences) {

    /** What we know right now: the last list fetched, or the built-in list. */
    fun list(p: Provider): List<ModelInfo> =
        runCatching {
            val a = JSONArray(prefs.getString("models_${p.name}", null) ?: return p.defaults)
            (0 until a.length()).map { ModelInfo.fromJson(a.getJSONObject(it)) }
        }.getOrNull()?.takeIf { it.isNotEmpty() } ?: p.defaults

    /** Info for [id]; a typed name we have never seen gets a pretty name and unknown vision. */
    fun info(p: Provider, id: String): ModelInfo =
        list(p).firstOrNull { it.id == id } ?: p.defaults.firstOrNull { it.id == id } ?: ModelInfo(id, prettyModelName(id), null)

    /** Fetches the live list. Returns null (and keeps the old list) if it fails. */
    suspend fun refresh(p: Provider, key: String): List<ModelInfo>? = withContext(Dispatchers.IO) {
        if (key.isBlank()) return@withContext null
        runCatching {
            val res = http.get(p.modelsUrl) {
                when (p) {
                    Provider.Claude -> {
                        header("x-api-key", key)
                        header("anthropic-version", "2023-06-01")
                    }
                    Provider.DeepSeek -> header("Authorization", "Bearer $key")
                }
            }
            if (!res.status.isSuccess()) return@runCatching null
            val data = JSONObject(res.bodyAsText()).optJSONArray("data") ?: return@runCatching null
            val models = (0 until data.length()).mapNotNull { i -> parse(p, data.getJSONObject(i)) }
            models.takeIf { it.isNotEmpty() }?.also { list ->
                prefs.edit().putString("models_${p.name}", JSONArray(list.map { it.toJson() }).toString()).apply()
            }
        }.getOrNull()
    }

    private fun parse(p: Provider, o: JSONObject): ModelInfo? {
        val id = o.optString("id").ifBlank { return null }
        return when (p) {
            Provider.Claude -> {
                if (!id.startsWith("claude")) return null
                val life = o.optString("lifecycle")
                if (life.isNotBlank() && life != "active") return null
                val caps = o.optJSONObject("capabilities")
                val vision = caps?.optJSONObject("image_input")?.optBoolean("supported") ?: true
                ModelInfo(id, o.optString("display_name").ifBlank { prettyModelName(id) }.removePrefix("Claude ").let { "Claude $it" }, vision)
            }
            Provider.DeepSeek -> {
                val mods = o.optJSONArray("input_modalities")
                val vision = mods?.let { a -> (0 until a.length()).any { a.optString(it) == "image" } }
                val name = o.optString("name").replace('-', ' ').ifBlank { prettyModelName(id) }
                ModelInfo(id, name, vision)
            }
        }
    }
}
