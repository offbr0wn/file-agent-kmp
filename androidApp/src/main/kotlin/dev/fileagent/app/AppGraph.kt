package dev.fileagent.app

import android.content.Context
import android.os.Environment
import dev.fileagent.agent.FileAgent
import dev.fileagent.agent.UndoBatch
import dev.fileagent.fs.AndroidFileSystem
import dev.fileagent.fs.PlacesFileSystem
import dev.fileagent.fs.RootBackend
import dev.fileagent.fs.SmbFileSystem
import org.json.JSONArray
import org.json.JSONObject
import dev.fileagent.llm.AnthropicClient
import dev.fileagent.llm.AssistantTurn
import dev.fileagent.llm.LlmClient
import dev.fileagent.llm.ToolSpec
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * AI providers. Both speak the Anthropic Messages format.
 * [defaults] are used until the live list is fetched from [modelsUrl].
 */
enum class Provider(
    val label: String,
    val keySite: String,
    val baseUrl: String,
    val modelsUrl: String,
    val defaults: List<ModelInfo>,
) {
    Claude("Claude", "console.anthropic.com", "https://api.anthropic.com", "https://api.anthropic.com/v1/models?limit=100",
        listOf(
            ModelInfo("claude-sonnet-5-5", "Claude Sonnet 5.5", true),
            ModelInfo("claude-haiku-5-5", "Claude Haiku 5.5", true),
            ModelInfo("claude-opus-5-5", "Claude Opus 5.5", true),
        )),
    DeepSeek("DeepSeek", "platform.deepseek.com", "https://api.deepseek.com/anthropic", "https://api.deepseek.com/models",
        listOf(
            ModelInfo("deepseek-flash", "DeepSeek V4.1 Flash", true),
            ModelInfo("deepseek-v4-pro", "DeepSeek V4 Pro", false),
        ));

    val model: String get() = defaults.first().id
}

/** A folder the AI is allowed to use: on the phone, or an SMB share on a NAS. */
data class PlaceConfig(
    val name: String,
    val type: String, // "local" or "smb"
    val enabled: Boolean = true,
    val localPath: String = "",
    val host: String = "",
    val share: String = "",
    val folder: String = "",
    val user: String = "",
    val password: String = "",
    /** The NAS's own name, like SHASHANK-NAS. Blank until known. */
    val server: String = "",
) {
    val description: String
        get() = if (type == "smb") "NAS · ${server.ifBlank { host }}${if (folder.isBlank()) "" else " · $folder"}"
        else "Phone: /$localPath"

    fun toJson(): JSONObject = JSONObject()
        .put("name", name).put("type", type).put("enabled", enabled)
        .put("localPath", localPath).put("host", host).put("share", share)
        .put("folder", folder).put("user", user).put("password", Vault.seal(password)).put("server", server)

    companion object {
        fun fromJson(o: JSONObject) = PlaceConfig(
            name = o.optString("name"), type = o.optString("type", "local"),
            enabled = o.optBoolean("enabled", true), localPath = o.optString("localPath"),
            host = o.optString("host"), share = o.optString("share"), folder = o.optString("folder"),
            user = o.optString("user"), password = Vault.open(o.optString("password")),
            server = o.optString("server"),
        )
    }
}

/** Tiny manual DI. */
class AppGraph(context: Context) {
    private val prefs = context.getSharedPreferences("file_agent", Context.MODE_PRIVATE)
    private val undoFile = File(context.filesDir, "undo.json")
    private val json = Json { ignoreUnknownKeys = true }

    val fs = AndroidFileSystem(Environment.getExternalStorageDirectory())

    private val http = HttpClient(OkHttp) {
        // Big folders make long AI requests: allow up to 3 min of silence per call.
        install(HttpTimeout) {
            connectTimeoutMillis = 30_000
            socketTimeoutMillis = 180_000
            requestTimeoutMillis = 300_000
        }
    }

    /** Always calls the selected provider with the selected model. Built per call so a model change applies at once. */
    val llm: LlmClient = object : LlmClient {
        override suspend fun complete(
            system: String,
            messages: List<JsonObject>,
            tools: List<ToolSpec>,
        ): AssistantTurn {
            val p = provider
            return AnthropicClient(
                http, apiKey = { keyFor(p) }, model = modelFor(p), baseUrl = p.baseUrl,
                disableThinking = p == Provider.DeepSeek,
            ).complete(system, messages, tools)
        }
    }

    /** Which models each provider has, fetched live. */
    val models = ModelCatalog(http, prefs)

    /** The model id to send for [p]: the user's choice, or the provider's first model. */
    fun modelFor(p: Provider): String {
        val saved = prefs.getString("model_${p.name}", null)?.takeIf { it.isNotBlank() } ?: return p.model
        // Names like "deepseek/deepseek-v4.1-flash-1017" are reseller names. DeepSeek itself calls it deepseek-flash.
        if (p == Provider.DeepSeek && '/' in saved) return p.model.also { setModel(p, it) }
        return saved
    }

    fun setModel(p: Provider, model: String) =
        prefs.edit().putString("model_${p.name}", model.trim()).apply()

    // ---- memory: facts the AI keeps across chats ---------------------------

    fun loadMemory(): List<String> = runCatching {
        val arr = JSONArray(prefs.getString("memory", "[]"))
        (0 until arr.length()).map { arr.getString(it) }
    }.getOrDefault(emptyList())

    fun saveMemory(facts: List<String>) {
        val arr = JSONArray()
        facts.takeLast(50).forEach { arr.put(it) }
        prefs.edit().putString("memory", arr.toString()).apply()
    }

    @Synchronized
    fun addMemory(fact: String) {
        val f = fact.trim()
        if (f.isEmpty()) return
        saveMemory(loadMemory().filterNot { it.equals(f, ignoreCase = true) } + f)
    }

    fun removeMemory(fact: String) = saveMemory(loadMemory() - fact)

    /** Finds NAS drives on the Wi-Fi. */
    val nasScanner = NasScanner(context)

    /** Saved chats, in app-private storage. */
    private val chatStore = ChatStore(File(context.filesDir, "chats"))

    init { tidyPlaceNames(); runCatching { sealSecrets() } }

    /** Old place name -> new name, for chats saved before a rename. */
    private fun placeRenames(): Map<String, String> = runCatching {
        val o = JSONObject(prefs.getString("place_renames", "{}") ?: "{}")
        o.keys().asSequence().associateWith { o.getString(it) }
    }.getOrDefault(emptyMap())

    /** Background AI runs, shared by the screen and [AgentService]. Uses only the allowed places. */
    val runner = AgentRunner(
        newAgent = { names ->
            FileAgent(llm, placesFs(names), memory = { loadMemory() }, onRemember = { addMemory(it) }, replyStyle = { replyStyle })
        },
        store = chatStore,
        defaultPlaces = { defaultChatPlaces() },
        fixPlaces = { names -> placeRenames().let { r -> names.map { r[it] ?: it }.distinct() } },
        onPlacesChosen = { lastChatPlaces = it },
    )

    /** Folders a new chat starts with: the last chat's choice, else the first saved place. */
    fun defaultChatPlaces(): List<String> {
        val names = loadPlaces().map { it.name }
        val last = lastChatPlaces.filter { it in names }
        return last.ifEmpty { names.take(1) }
    }

    var lastChatPlaces: List<String>
        get() = runCatching {
            val arr = JSONArray(prefs.getString("last_chat_places", "[]"))
            (0 until arr.length()).map { arr.getString(it) }
        }.getOrDefault(emptyList())
        set(v) {
            val arr = JSONArray()
            v.forEach { arr.put(it) }
            prefs.edit().putString("last_chat_places", arr.toString()).apply()
        }

    // ---- places ------------------------------------------------------------

    private val storage = Environment.getExternalStorageDirectory()

    // NOTE: NAS passwords are stored in app-private settings. Use Keystore for a real release.
    fun loadPlaces(): List<PlaceConfig> {
        val raw = prefs.getString("places", null)
            ?: return listOf(PlaceConfig(name = "Download", type = "local", localPath = "Download"))
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { PlaceConfig.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    /**
     * Older builds named NAS folders "192.168.1.65 Photos". Rename them to just the share
     * ("Photos") once, and carry the last chat's ticks over. Returns true if anything changed.
     */
    fun tidyPlaceNames(): Boolean {
        val places = loadPlaces()
        val taken = places.map { it.name }.toMutableSet()
        val renames = mutableMapOf<String, String>()
        val fixed = places.map { p ->
            val short = p.name.removePrefix(p.host).trim()
            if (p.type == "smb" && p.host.isNotBlank() && p.name.startsWith(p.host + " ") && short.isNotBlank() && short !in taken) {
                taken += short
                renames[p.name] = short
                p.copy(name = short)
            } else p
        }
        if (renames.isEmpty()) return false
        savePlaces(fixed)
        val all = JSONObject(placeRenames() + renames)
        prefs.edit().putString("place_renames", all.toString()).apply()
        lastChatPlaces = lastChatPlaces.map { renames[it] ?: it }
        return true
    }

    fun savePlaces(places: List<PlaceConfig>) {
        val arr = JSONArray()
        places.forEach { arr.put(it.toJson()) }
        prefs.edit().putString("places", arr.toString()).apply()
        cached = null
    }

    /** Top-level folders on the phone, to offer as places. */
    fun phoneFolders(): List<String> =
        storage.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") && it.name != "Android" }
            ?.map { it.name }?.sorted().orEmpty()

    fun backendFor(p: PlaceConfig): RootBackend =
        if (p.type == "smb") SmbFileSystem(p.host, p.share, p.user, p.password, p.folder)
        else AndroidFileSystem(java.io.File(storage, p.localPath))

    /** The phone file behind a place path like "Download/x.apk", or null for NAS paths. */
    fun localFileFor(placePath: String): java.io.File? {
        val place = placePath.substringBefore('/')
        val rest = placePath.substringAfter('/', "")
        val cfg = loadPlaces().firstOrNull { it.name == place && it.type == "local" } ?: return null
        return java.io.File(java.io.File(storage, cfg.localPath), rest)
    }

    private var cached: Pair<List<PlaceConfig>, PlacesFileSystem>? = null

    /**
     * The file system the AI and the plan executor use.
     * [names] limits it to one chat's folders; null means every saved place.
     */
    @Synchronized
    fun placesFs(names: Collection<String>? = null): PlacesFileSystem {
        val chosen = loadPlaces().filter { names == null || it.name in names }
        cached?.let { (cfg, fs) -> if (cfg == chosen) return fs }
        return PlacesFileSystem(chosen.associate { it.name to backendFor(it) }).also { cached = chosen to it }
    }

    var provider: Provider
        get() = runCatching { Provider.valueOf(prefs.getString("provider", null) ?: "") }
            .getOrDefault(Provider.Claude)
        set(v) = prefs.edit().putString("provider", v.name).apply()

    // NOTE: for a real release, store keys with Android Keystore-backed storage
    // or proxy calls through your own backend instead of shipping a raw key.
    fun keyFor(p: Provider): String = Vault.open(
        prefs.getString("api_key_${p.name}", null)
            ?: (if (p == Provider.Claude) prefs.getString("api_key", "") else "").orEmpty()
    )

    fun setKey(p: Provider, key: String) =
        prefs.edit().putString("api_key_${p.name}", Vault.seal(key.trim())).apply()

    /** One-time: lock AI keys and NAS passwords that older builds saved as plain text. */
    private fun sealSecrets() {
        Provider.entries.forEach { p ->
            val plain = keyFor(p)
            val raw = prefs.getString("api_key_${p.name}", null)
            if (plain.isNotEmpty() && (raw == null || !Vault.isSealed(raw))) setKey(p, plain)
        }
        if (prefs.contains("api_key")) prefs.edit().remove("api_key").apply()
        val places = prefs.getString("places", null)
        if (places != null && loadPlaces().any { it.password.isNotEmpty() } && !places.contains("\"password\":\"v1:")) {
            savePlaces(loadPlaces())
        }
    }

    /** Reading size: 1.0 normal, 1.15 large, 1.3 larger. */
    /** "short" or "detailed": how the AI talks. */
    var replyStyle: String
        get() = prefs.getString("reply_style", "short") ?: "short"
        set(v) = prefs.edit().putString("reply_style", v).apply()

    /** "system", "light" or "dark". */
    var theme: String
        get() = prefs.getString("theme", "system") ?: "system"
        set(v) = prefs.edit().putString("theme", v).apply()

    /** False until the first-run guide is finished. People who used the app before it existed skip it. */
    var onboarded: Boolean
        get() = prefs.getBoolean("onboarded", prefs.contains("places") || prefs.contains("last_chat_places"))
        set(v) = prefs.edit().putBoolean("onboarded", v).apply()

    var textScale: Float
        get() = prefs.getFloat("text_scale", 1f)
        set(v) = prefs.edit().putFloat("text_scale", v).apply()

    var autoSortEnabled: Boolean
        get() = prefs.getBoolean("auto_sort", false)
        set(v) = prefs.edit().putBoolean("auto_sort", v).apply()

    fun loadUndo(): List<UndoBatch> =
        runCatching { json.decodeFromString<List<UndoBatch>>(undoFile.readText()) }.getOrDefault(emptyList())

    fun saveUndo(batches: List<UndoBatch>) =
        undoFile.writeText(json.encodeToString(batches.takeLast(20)))

    companion object {
        @Volatile private var instance: AppGraph? = null
        fun get(context: Context) = instance ?: synchronized(this) {
            instance ?: AppGraph(context.applicationContext).also { instance = it }
        }
    }
}
