package dev.fileagent.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.fileagent.agent.BIN_FOLDER
import dev.fileagent.agent.Plan
import dev.fileagent.agent.PlanExecutor
import dev.fileagent.agent.PlannedAction
import dev.fileagent.agent.UndoBatch
import dev.fileagent.fs.FileEntry
import dev.fileagent.fs.join
import dev.fileagent.rules.RuleSorter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A file open in the built-in viewer.
 * kind: "image", "video", "audio", "text", "pdf" or "other".
 * [file] is the file on the phone (or a cached copy of a NAS file). Null while a NAS
 * file loads, and for NAS video and audio, which play straight from the NAS.
 */
data class Viewing(
    val path: String,
    val name: String,
    val kind: String,
    val file: java.io.File? = null,
    val loading: String? = null,
    val error: String? = null,
)

/** Plays a NAS file without copying it first. */
private class NasMediaSource(private val reader: dev.fileagent.fs.RandomReader) : android.media.MediaDataSource() {
    @Synchronized
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= reader.size) return -1
        if (size == 0) return 0
        val n = reader.readAt(position, buffer, offset, size)
        return if (n <= 0) -1 else n
    }
    override fun getSize(): Long = reader.size
    @Synchronized
    override fun close() { runCatching { reader.close() } }
}

fun fileKind(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif" -> "image"
    "mp4", "mkv", "mov", "webm", "3gp", "avi", "m4v" -> "video"
    "mp3", "m4a", "wav", "ogg", "flac", "aac", "opus" -> "audio"
    "pdf" -> "pdf"
    "txt", "md", "json", "csv", "log", "xml", "yml", "yaml", "kt", "kts", "java", "py", "js", "ts", "html", "css",
    "ini", "conf", "srt", "sh", "toml", "gradle", "properties" -> "text"
    else -> "other"
}

data class UiState(
    val provider: Provider = Provider.Claude,
    val hasApiKey: Boolean = false,
    /** Model id sent to the selected provider, its short name, and whether it can see pictures (null: unknown). */
    val model: String = "",
    val modelName: String = "",
    val modelVision: Boolean? = null,
    /** Models each provider offers, as last fetched. */
    val modelLists: Map<Provider, List<ModelInfo>> = emptyMap(),
    val modelsLoading: Boolean = false,
    val busy: Boolean = false,
    // ---- open chat
    val chatId: String? = null,
    val title: String = "New chat",
    val items: List<ChatItem> = emptyList(),
    val plan: Plan? = null,
    /** Indexes of plan actions the user has ticked. */
    val selected: Set<Int> = emptySet(),
    val chats: List<ChatMeta> = emptyList(),
    /** Folders the open chat may use (place names). */
    val chatPlaces: List<String> = emptyList(),
    // ---- settings
    val showSettings: Boolean = false,
    val undo: List<UndoBatch> = emptyList(),
    val autoSort: Boolean = false,
    val places: List<PlaceConfig> = emptyList(),
    val placeTest: String? = null,
    val memory: List<String> = emptyList(),
    /** NAS drives found on the Wi-Fi, and whether the scan is still running. */
    val nasFound: List<FoundNas> = emptyList(),
    val nasScanning: Boolean = false,
    /** Shares a NAS offers, after signing in. Null until signed in. */
    val nasShares: List<String>? = null,
    /** The paid File Agent AI account: messages left and where to pay. */
    val hosted: HostedState = HostedState(),
    /** A short message about a payment (pending, failed, thank you). */
    val payNote: String? = null,
    /** The on-phone AI model: not there, downloading, or ready. */
    val localModel: LocalModelState = LocalModelState(),
    /** Phone memory in GB, to warn when the on-phone AI may not fit. */
    val ramGb: Int = 0,
    /** e.g. "1.0.142". */
    val appVersion: String = "",
    /** A newer build that can be installed. */
    val update: UpdateInfo? = null,
    /** "Checking", "Downloading 40%", "Up to date" or a short problem. Null when idle. */
    val updateStatus: String? = null,
    val updateBusy: Boolean = false,
    /** First-run guide finished. */
    val onboarded: Boolean = true,
    /** "short" or "detailed" replies. */
    val replyStyle: String = "short",
    /** "system", "light" or "dark". */
    val theme: String = "system",
    /** Why the app closed last time, if it crashed. */
    val crash: String? = null,
    /** File open in the built-in viewer, over any screen. */
    val viewer: Viewing? = null,
    // ---- file manager
    val showFiles: Boolean = false,
    /** Folder open in the file manager. "" is the list of places. */
    val filesPath: String = "",
    /** What is in that folder. Null while loading. */
    val filesEntries: List<FileEntry>? = null,
    val filesError: String? = null,
    val filesPicked: Set<String> = emptySet(),
    /** Files waiting for "Move here". */
    val filesClip: List<String> = emptyList(),
    /** e.g. "Moving 2 of 5" while the file manager works. */
    val filesBusy: String? = null,
    /** Short result line, e.g. "3 in bin", with an Undo button. */
    val filesNote: String? = null,
    /** Progress while a plan is being applied, e.g. "Moving 3 of 10". Null when idle. */
    val applying: String? = null,
    /** The NAS's own name from the last sign-in. */
    val nasServerName: String? = null,
    /** NAS addresses that can't be reached right now (off, or not on this network). */
    val nasOffline: Set<String> = emptySet(),
    /** Signing in to a NAS right now. */
    val nasSigningIn: Boolean = false,
    /** Why the last NAS sign-in failed, in a few words. */
    val nasError: String? = null,
    /** Reading size multiplier. */
    val textScale: Float = 1f,
    /** What is in the bins of the saved places (files, bytes). Null until counted. */
    val bin: Pair<Int, Long>? = null,
)

class AgentViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = AppGraph.get(app)
    private val runner = graph.runner
    private val updater = Updater(app)
    /** Executes plans on the saved places (same paths the AI used). */
    private fun executor() = PlanExecutor(graph.placesFs())

    private val _state = MutableStateFlow(
        UiState(
            provider = graph.provider,
            hasApiKey = graph.keyFor(graph.provider).isNotBlank(),
            model = graph.modelFor(graph.provider),
            modelName = graph.models.info(graph.provider, graph.modelFor(graph.provider)).name,
            modelVision = graph.models.info(graph.provider, graph.modelFor(graph.provider)).vision,
            undo = graph.loadUndo(),
            autoSort = graph.autoSortEnabled,
            places = graph.loadPlaces(),
            memory = graph.loadMemory(),
            textScale = graph.textScale,
            onboarded = graph.onboarded,
            replyStyle = graph.replyStyle,
            theme = graph.theme,
        )
    )
    val state: StateFlow<UiState> = _state

    init {
        runCatching { java.io.File(app.filesDir, "last-crash.txt").takeIf { it.exists() }?.readText() }
            .getOrNull()?.let { text -> _state.update { it.copy(crash = text) } }
        _state.update { it.copy(appVersion = updater.installedName, ramGb = graph.localModel.ramGb) }
        watchLocalModel()
        refreshHosted()
        if (BuildConfig.SELF_UPDATE) checkUpdate(quiet = true) // Play updates the Play version itself
        // Pin the answer, so leaving the first-run guide half way shows it again next time.
        if (!graph.onboarded) graph.onboarded = false
        checkNas()
        // Keep the model list (and which ones see pictures) up to date.
        if (graph.keyFor(graph.provider).isNotBlank()) refreshModels(graph.provider)
        // The bin keeps deleted files for 7 days, then clears itself.
        viewModelScope.launch(Dispatchers.IO) { purgeOldBin(7L * 24 * 60 * 60 * 1000) }
        // Mirror the background chat into the screen state.
        viewModelScope.launch {
            runner.state.collect { r ->
                _state.update { cur ->
                    val newPlan = r.plan
                    cur.copy(
                        busy = r.busy,
                        chatId = r.chatId,
                        title = r.title,
                        items = r.items,
                        chats = r.chats,
                        chatPlaces = r.places,
                        plan = newPlan,
                        selected = when {
                            newPlan == null -> emptySet()
                            newPlan == cur.plan -> cur.selected
                            r.chatId != cur.chatId -> newPlan.actions.indices.toSet()
                            else -> keepTicks(cur.plan, cur.selected, newPlan)
                        },
                        // The AI may have saved something with the remember tool.
                        memory = if (r.busy != cur.busy) graph.loadMemory() else cur.memory,
                    )
                }
            }
        }
    }

    // ---- chats -------------------------------------------------------------

    /** Sends a message. Runs in a foreground service so it keeps going when you switch apps. */
    fun send(text: String, images: List<android.net.Uri> = emptyList()) {
        if ((text.isBlank() && images.isEmpty()) || _state.value.busy) return
        if (images.isEmpty()) { AgentService.send(getApplication(), text); return }
        viewModelScope.launch {
            val paths = withContext(Dispatchers.IO) { images.mapNotNull { savePicture(it) } }
            AgentService.send(getApplication(), text, paths)
        }
    }

    /**
     * Copies a picked picture into the app, shrunk to 1568 px on the long side (the size
     * the AI reads best) and saved as JPEG. Returns the file path, or null if it can't be read.
     */
    private fun savePicture(uri: android.net.Uri): String? = runCatching {
        val app = getApplication<Application>()
        val src = android.graphics.ImageDecoder.createSource(app.contentResolver, uri)
        val bmp = android.graphics.ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
            val w = info.size.width
            val h = info.size.height
            val scale = minOf(1f, 1568f / maxOf(w, h))
            if (scale < 1f) decoder.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
            decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
        }
        val dir = java.io.File(app.filesDir, "chat-images").apply { mkdirs() }
        val file = java.io.File(dir, "${System.currentTimeMillis()}-${(1000..9999).random()}.jpg")
        file.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it) }
        bmp.recycle()
        file.absolutePath
    }.getOrNull()

    fun newChat() = runner.newChat()

    /**
     * Free sort with no AI: files in the chat's first folder go into Sorted/<type>.
     * Returns false when the chat has no folder yet.
     */
    fun quickSort(): Boolean {
        val place = _state.value.chatPlaces.firstOrNull() ?: return false
        viewModelScope.launch {
            val plan = withContext(Dispatchers.IO) {
                runCatching {
                    RuleSorter(graph.placesFs(listOf(place)), minAgeMillis = 0).plan(place, System.currentTimeMillis())
                }.getOrNull()
            } ?: return@launch
            runner.showQuickPlan("Quick sort $place", plan.copy(summary = "Sorted by type, no AI"))
        }
        return true
    }

    // ---- File Agent AI (paid) ---------------------------------------------------------

    /** Makes the private account if needed, then loads messages left. */
    fun refreshHosted(create: Boolean = false) {
        if (_state.value.hosted.loading) return
        if (!create && graph.hosted.token.isBlank()) return
        _state.update { it.copy(hosted = it.hosted.copy(loading = true, error = null)) }
        viewModelScope.launch {
            val r = runCatching {
                if (create) graph.hosted.ensure()
                graph.hosted.refresh()
            }
            _state.update {
                it.copy(hosted = r.getOrElse { e -> it.hosted.copy(loading = false, error = e.message ?: "Can't reach File Agent AI") })
            }
        }
    }

    /** Payments: Stripe links in the GitHub version, Google Play Billing in the Play version. */
    val store: Store = makeStore(app, graph.hosted)

    /** Starts buying Plus or a top-up. */
    fun buy(activity: android.app.Activity, product: Product) {
        viewModelScope.launch {
            val note = store.buy(activity, product, _state.value.hosted)
            _state.update { it.copy(payNote = note) }
        }
    }

    /** Loads prices and finishes half-done purchases. Called when the AI panel opens. */
    fun syncStore() = store.sync { note ->
        if (note != null) _state.update { it.copy(payNote = note) }
        refreshHosted()
    }

    /** The account key, for the user to keep as a backup. */
    fun hostedKey(): String = graph.hosted.token

    /** Signs in with a backed-up account key (after a reinstall or on a new phone). */
    fun restoreHosted(key: String): String? {
        val k = key.trim()
        if (!k.startsWith("fa_") || k.length < 40) return "That doesn't look like an account key"
        graph.hosted.setToken(k)
        refreshHosted()
        return null
    }

    // ---- on-phone AI ----------------------------------------------------------------

    private var localJob: kotlinx.coroutines.Job? = null

    /** Keeps the model's state fresh while it downloads, and turns the AI on when it lands. */
    private fun watchLocalModel() {
        localJob?.cancel()
        localJob = viewModelScope.launch {
            while (true) {
                val st = withContext(Dispatchers.IO) { graph.localModel.state() }
                _state.update {
                    it.copy(localModel = st, hasApiKey = if (it.provider == Provider.Local) st.status == "ready" else it.hasApiKey)
                }
                if (st.status != "downloading") break
                kotlinx.coroutines.delay(1000)
            }
        }
    }

    /** Starts the one-time model download. Returns a short problem, or null when started. */
    fun downloadLocalModel(): String? {
        if (!graph.localModel.hasRoom) return "Not enough free space. It needs about 3 GB."
        val r = runCatching { graph.localModel.start() }
        if (r.isFailure) return "Couldn't start the download"
        _state.update { it.copy(localModel = LocalModelState("downloading")) }
        watchLocalModel()
        return null
    }

    /** Stops a download, or deletes the model to free its space. */
    fun removeLocalModel() {
        viewModelScope.launch {
            graph.localLlm.unload()
            withContext(Dispatchers.IO) { graph.localModel.cancel() }
            watchLocalModel()
        }
    }

    // ---- in-app updates ------------------------------------------------------------

    /** Looks for a newer build. [quiet] (on app start) shows nothing unless there is one. */
    fun checkUpdate(quiet: Boolean = false) {
        if (_state.value.updateBusy) return
        _state.update { it.copy(updateBusy = true, updateStatus = if (quiet) null else "Checking") }
        viewModelScope.launch {
            val r = runCatching { updater.check() }
            _state.update {
                it.copy(
                    updateBusy = false,
                    update = r.getOrNull() ?: it.update.takeIf { _ -> r.isFailure },
                    updateStatus = when {
                        quiet -> null
                        r.isFailure -> r.exceptionOrNull()?.message ?: "Can't check right now"
                        r.getOrNull() == null -> "Up to date"
                        else -> null
                    },
                )
            }
        }
    }

    /** Downloads the update, then opens Android's install screen. */
    fun installUpdate() {
        val info = _state.value.update ?: return
        if (_state.value.updateBusy) return
        _state.update { it.copy(updateBusy = true, updateStatus = "Downloading 0%") }
        viewModelScope.launch {
            val r = runCatching {
                val apk = updater.download(info) { pct -> _state.update { it.copy(updateStatus = "Downloading $pct%") } }
                updater.install(apk)
            }
            _state.update {
                it.copy(updateBusy = false, updateStatus = r.exceptionOrNull()?.let { e -> e.message ?: "Update failed" } ?: "Tap Install when Android asks")
            }
        }
    }

    fun setReplyStyle(v: String) {
        graph.replyStyle = v
        _state.update { it.copy(replyStyle = v) }
    }

    fun setTheme(v: String) {
        graph.theme = v
        _state.update { it.copy(theme = v) }
    }

    fun finishOnboarding() {
        graph.onboarded = true
        _state.update { it.copy(onboarded = true) }
    }

    /** Ticks or unticks a phone folder in the first-run guide. */
    fun togglePhoneFolder(folder: String) {
        val existing = _state.value.places.firstOrNull { it.type == "local" && it.localPath == folder }
        if (existing != null) removePlace(existing.name)
        else addPlace(PlaceConfig(name = folder.substringAfterLast('/'), type = "local", localPath = folder))
    }

    fun setTextScale(v: Float) {
        graph.textScale = v
        _state.update { it.copy(textScale = v) }
    }

    fun openChat(id: String) = runner.openChat(id)

    fun deleteChat(id: String) = runner.deleteChat(id)

    /** Turns a folder on or off for the open chat. */
    fun toggleChatPlace(name: String) {
        val cur = _state.value.chatPlaces
        runner.setPlaces(if (name in cur) cur - name else cur + name)
    }

    /** After a follow-up edits the plan, keep the user's unticks; tick anything new. */
    private fun keepTicks(old: Plan?, selected: Set<Int>, new: Plan): Set<Int> {
        if (old == null) return new.actions.indices.toSet()
        val unticked = old.actions.filterIndexed { i, _ -> i !in selected }.toSet()
        return new.actions.indices.filter { new.actions[it] !in unticked }.toSet()
    }

    fun toggle(index: Int) = _state.update {
        it.copy(selected = if (index in it.selected) it.selected - index else it.selected + index)
    }

    /** Ticks or unticks a whole folder group of the plan at once. */
    fun toggleGroup(indices: List<Int>) = _state.update {
        val allOn = indices.all { i -> i in it.selected }
        it.copy(selected = if (allOn) it.selected - indices.toSet() else it.selected + indices)
    }

    fun applyPlan() {
        val s = _state.value
        val plan = s.plan ?: return
        if (s.applying != null) return // already running: ignore extra taps
        // Keep a ticked move's folder even if the user unticked the mkdir.
        val chosen = plan.actions.filterIndexed { i, a -> i in s.selected || a is PlannedAction.Mkdir }
        val files = chosen.count { it !is PlannedAction.Mkdir }
        val crossPlace = chosen.any { it is PlannedAction.Move && it.from.substringBefore('/') != it.to.substringBefore('/') }
        val verb = if (crossPlace) "Copying" else "Moving"
        _state.update { it.copy(applying = "$verb 1 of $files") }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                var step = 0
                executor().apply(plan.copy(actions = chosen), System.currentTimeMillis()) { i, _ ->
                    if (chosen[i] !is PlannedAction.Mkdir) {
                        step++
                        _state.update { it.copy(applying = "$verb $step of $files") }
                    }
                }
            }
            val undo = s.undo + result.batch
            graph.saveUndo(undo)
            val binned = result.batch.done.count { it is PlannedAction.Move && "$BIN_FOLDER/" in it.to }
            val moved = result.batch.done.count { it !is PlannedAction.Mkdir } - binned
            val why = result.failures.firstOrNull()?.second?.take(90)
            val msg = buildString {
                append(if (moved > 0 || binned == 0) "Moved $moved" else "")
                if (binned > 0) append(if (isEmpty()) "$binned in bin" else ", $binned in bin")
                if (result.failures.isNotEmpty()) append(", ${result.failures.size} failed: $why")
            }
            runner.planDone("applied", msg)
            _state.update { it.copy(undo = undo, applying = null) }
        }
    }

    fun discardPlan() = runner.planDone("discarded", "Discarded")

    fun undoLast() {
        val last = _state.value.undo.lastOrNull() ?: return
        viewModelScope.launch {
            val problems = withContext(Dispatchers.IO) { executor().undo(last) }
            val undo = _state.value.undo.dropLast(1)
            graph.saveUndo(undo)
            runner.addNote(if (problems.isEmpty()) "Undid it" else "Undid it, ${problems.size} stuck")
            _state.update { it.copy(undo = undo) }
        }
    }

    // ---- settings ----------------------------------------------------------

    fun showSettings(open: Boolean) {
        _state.update { it.copy(showSettings = open, placeTest = null, memory = graph.loadMemory()) }
        if (open) countBin()
    }

    // ---- NAS discovery ------------------------------------------------------------

    fun startNasScan() {
        checkNas()
        graph.nasScanner.start()
        viewModelScope.launch {
            graph.nasScanner.found.collect { f -> _state.update { it.copy(nasFound = f) } }
        }
        viewModelScope.launch {
            graph.nasScanner.scanning.collect { on -> _state.update { it.copy(nasScanning = on) } }
        }
    }

    fun stopNasScan() = graph.nasScanner.stop()

    /** Signs in to a NAS and lists its shares, so the user can tick instead of type. */
    fun loadShares(host: String, user: String, password: String) {
        if (_state.value.nasSigningIn) return
        _state.update { it.copy(nasShares = null, nasError = null, nasSigningIn = true) }
        viewModelScope.launch {
            val r = NasScanner.listShares(host, user, password)
            _state.update {
                it.copy(nasSigningIn = false, nasError = r.error, nasShares = if (r.error == null) r.shares else null, nasServerName = r.serverName)
            }
        }
    }

    // ---- file manager --------------------------------------------------------------

    fun showFiles(open: Boolean) {
        _state.update { it.copy(showFiles = open, filesPicked = emptySet(), filesNote = null) }
        if (open) { checkNas(); browse(_state.value.filesPath) }
    }

    /** Opens [path] in the file manager. "" lists the places. */
    fun browse(path: String, keepNote: Boolean = false) {
        val p = path.trim('/')
        _state.update {
            it.copy(filesPath = p, filesEntries = null, filesError = null, filesPicked = emptySet(),
                filesNote = if (keepNote) it.filesNote else null)
        }
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    graph.placesFs().list(p)
                        .filter { it.name != BIN_FOLDER }
                        .sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
                }
            }
            _state.update {
                if (it.filesPath != p) it
                else it.copy(filesEntries = r.getOrDefault(emptyList()), filesError = r.exceptionOrNull()?.let { e -> e.message ?: "Can't open this folder" } ?: it.filesError)
            }
        }
    }

    /** Goes up one folder. False when already at the list of places. */
    fun filesUp(): Boolean {
        val p = _state.value.filesPath
        if (p.isEmpty()) return false
        browse(p.substringBeforeLast('/', ""))
        return true
    }

    fun toggleFilePick(path: String) = _state.update {
        it.copy(filesPicked = if (path in it.filesPicked) it.filesPicked - path else it.filesPicked + path)
    }

    fun clearFilePicks() = _state.update { it.copy(filesPicked = emptySet()) }

    fun pickAllFiles() = _state.update { st -> st.copy(filesPicked = st.filesEntries.orEmpty().map { it.path }.toSet()) }

    /** Holds the picked files so the user can open another folder and tap "Move here". */
    fun cutPicked() = _state.update { it.copy(filesClip = it.filesPicked.toList(), filesPicked = emptySet(), filesNote = null) }

    fun cancelClip() = _state.update { it.copy(filesClip = emptyList()) }

    fun pasteHere() {
        val st = _state.value
        if (st.filesPath.isEmpty() || st.filesClip.isEmpty()) return
        val moves = st.filesClip
            .filter { it.substringBeforeLast('/', "") != st.filesPath }
            .map { PlannedAction.Move(it, join(st.filesPath, it.substringAfterLast('/')), "Moved in Files") }
        _state.update { it.copy(filesClip = emptyList()) }
        if (moves.isNotEmpty()) runFiles("Moving", "moved", moves)
    }

    fun binPickedFiles() {
        val picked = _state.value.filesPicked.toList()
        if (picked.isNotEmpty()) runFiles("Moving", "in bin", picked.map { PlannedAction.Delete(it, "Deleted in Files") })
    }

    /** Renames one file or folder. Returns a short problem, or null when started. */
    fun renameFile(path: String, newName: String): String? {
        val name = newName.trim()
        badName(name)?.let { return it }
        val to = join(path.substringBeforeLast('/', ""), name)
        if (to == path) return null
        runFiles("Renaming", "renamed", listOf(PlannedAction.Move(path, to, "Renamed in Files")))
        return null
    }

    fun newFolder(name: String): String? {
        val n = name.trim()
        badName(n)?.let { return it }
        val here = _state.value.filesPath
        if (here.isEmpty()) return "Open a place first"
        runFiles("Making folder", "folder made", listOf(PlannedAction.Mkdir(join(here, n), "Made in Files")))
        return null
    }

    private fun badName(name: String): String? = when {
        name.isEmpty() -> "Type a name"
        name.any { it in "/\\:*?\"<>|" } -> "Don't use / \\ : * ? \" < > |"
        name == "." || name == ".." -> "Pick another name"
        else -> null
    }

    /** Runs file manager changes through the same executor as plans, so Undo works. */
    private fun runFiles(verb: String, doneWord: String, actions: List<PlannedAction>) {
        if (_state.value.filesBusy != null) return
        val total = actions.size
        _state.update { it.copy(filesBusy = if (total > 1) "$verb 1 of $total" else verb, filesNote = null, filesError = null) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                executor().apply(Plan("Files", "Files", actions), System.currentTimeMillis()) { i, n ->
                    if (n > 1) _state.update { it.copy(filesBusy = "$verb ${i + 1} of $n") }
                }
            }
            val done = result.batch.done.size
            val undo = if (done > 0) _state.value.undo + result.batch else _state.value.undo
            graph.saveUndo(undo)
            val why = result.failures.firstOrNull()?.second?.take(90)
            _state.update {
                it.copy(
                    undo = undo, filesBusy = null,
                    filesNote = if (done > 0) (if (total == 1) doneWord.replaceFirstChar { c -> c.uppercase() } else "$done $doneWord") else null,
                    filesError = why?.let { w -> "${result.failures.size} failed: $w" },
                )
            }
            val err = _state.value.filesError
            browse(_state.value.filesPath, keepNote = true)
            if (err != null) _state.update { it.copy(filesError = err) }
        }
    }

    /** Undoes the last change, from the file manager. */
    fun filesUndo() {
        val last = _state.value.undo.lastOrNull() ?: return
        viewModelScope.launch {
            val problems = withContext(Dispatchers.IO) { executor().undo(last) }
            val undo = _state.value.undo.dropLast(1)
            graph.saveUndo(undo)
            _state.update { it.copy(undo = undo, filesNote = null) }
            browse(_state.value.filesPath)
            if (problems.isNotEmpty()) _state.update { it.copy(filesError = "${problems.size} could not be put back") }
        }
    }

    // ---- files shown in the chat -----------------------------------------------

    /** Remembers ticks in a picture grid; the AI is told about them with the next message. */
    fun setPicked(index: Int, picked: List<String>) = runner.setPicked(index, picked)

    /** Turns files ticked in a picture grid into a plan to move them to the bin. */
    fun binPicked(paths: List<String>) {
        if (paths.isEmpty() || _state.value.busy) return
        val n = paths.size
        val plan = Plan(
            "Move $n picked ${if (n == 1) "file" else "files"} to the bin",
            "$n picked ${if (n == 1) "file" else "files"} to the bin",
            paths.map { PlannedAction.Delete(it, "You picked it") },
        )
        runner.showQuickPlan(plan.request, plan)
    }

    // ---- built-in viewer ---------------------------------------------------------------

    private var viewJob: kotlinx.coroutines.Job? = null

    /** Shows a file inside the app. NAS video and audio stream; other NAS files load into a cache first. */
    fun viewFile(path: String) {
        viewJob?.cancel()
        val name = path.substringAfterLast('/')
        val kind = fileKind(name)
        val local = graph.localFileFor(path)?.takeIf { it.isFile }
        if (local != null || kind == "video" || kind == "audio") {
            _state.update { it.copy(viewer = Viewing(path, name, kind, local)) }
            return
        }
        _state.update { it.copy(viewer = Viewing(path, name, kind, loading = "Loading")) }
        val app = getApplication<Application>()
        viewJob = viewModelScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val fs = graph.placesFs()
                    val total = fs.info(path)?.sizeBytes ?: error("File not found")
                    require(total <= 300L * 1024 * 1024) { "Too big to show here" }
                    val dir = java.io.File(app.cacheDir, "view").apply { mkdirs() }
                    dir.listFiles()?.forEach { it.delete() } // one cached file at a time
                    val out = java.io.File(dir, name)
                    fs.openIn(path).use { input ->
                        out.outputStream().use { o ->
                            val buf = ByteArray(256 * 1024)
                            var done = 0L
                            var shown = -1
                            while (true) {
                                ensureActive()
                                val n = input.read(buf)
                                if (n < 0) break
                                o.write(buf, 0, n)
                                done += n
                                val pct = if (total > 0) (done * 100 / total).toInt() else 0
                                if (pct != shown) {
                                    shown = pct
                                    _state.update { st ->
                                        val v = st.viewer
                                        if (v != null && v.path == path) st.copy(viewer = v.copy(loading = "Loading $pct%")) else st
                                    }
                                }
                            }
                        }
                    }
                    out
                }
            }
            _state.update { st ->
                val v = st.viewer
                if (v == null || v.path != path) st
                else st.copy(viewer = v.copy(
                    file = r.getOrNull(), loading = null,
                    error = r.exceptionOrNull()?.let { it.message ?: "Can't open this file" },
                ))
            }
        }
    }

    fun clearCrash() {
        runCatching { java.io.File(getApplication<Application>().filesDir, "last-crash.txt").delete() }
        _state.update { it.copy(crash = null) }
    }

    fun closeViewer() {
        viewJob?.cancel()
        _state.update { it.copy(viewer = null) }
    }

    /** A stream for playing a NAS file. Call off the main thread. */
    fun mediaSource(path: String): android.media.MediaDataSource? =
        graph.placesFs().openRandom(path)?.let { NasMediaSource(it) }

    /** Hands a file to another app (gallery, player, PDF reader). */
    fun openWith(file: java.io.File): Boolean {
        val app = getApplication<Application>()
        return runCatching {
            val uri = androidx.core.content.FileProvider.getUriForFile(app, "${app.packageName}.files", file)
            val type = android.webkit.MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(file.extension.lowercase()) ?: "*/*"
            val view = android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(uri, type)
                .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            app.startActivity(
                android.content.Intent.createChooser(view, null).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        }.getOrDefault(false)
    }

    fun clearShares() = _state.update { it.copy(nasShares = null, nasError = null, nasSigningIn = false, nasServerName = null) }

    /**
     * Checks which saved NAS drives answer right now, so the ones that are off or on another
     * network can be hidden. Also learns a NAS's name the first time it is reachable.
     */
    fun checkNas() {
        val hosts = _state.value.places.filter { it.type == "smb" && it.host.isNotBlank() }.map { it.host }.distinct()
        if (hosts.isEmpty()) return
        viewModelScope.launch {
            val up = withContext(Dispatchers.IO) { hosts.associateWith { nasReachable(it) } }
            _state.update { it.copy(nasOffline = up.filterValues { ok -> !ok }.keys) }
            val unnamed = _state.value.places.filter { it.type == "smb" && it.server.isBlank() && up[it.host] == true }.map { it.host }.distinct()
            if (unnamed.isNotEmpty()) {
                val names = withContext(Dispatchers.IO) { unnamed.associateWith { nbstat(it) } }
                if (names.values.any { it != null }) {
                    savePlaces(_state.value.places.map { p -> names[p.host]?.takeIf { p.type == "smb" && p.server.isBlank() }?.let { p.copy(server = it) } ?: p })
                }
            }
        }
    }

    // ---- bin: deleted files wait here until emptied -----------------------------

    private fun countBin() = viewModelScope.launch {
        val info = withContext(Dispatchers.IO) {
            var files = 0
            var bytes = 0L
            fun walk(b: dev.fileagent.fs.RootBackend, path: String) {
                b.list(path).forEach { e ->
                    if (e.isDir) walk(b, e.path) else { files++; bytes += e.sizeBytes }
                }
            }
            graph.loadPlaces().forEach { p ->
                runCatching {
                    val b = graph.backendFor(p)
                    if (b.exists(BIN_FOLDER)) walk(b, BIN_FOLDER)
                }
            }
            files to bytes
        }
        _state.update { it.copy(bin = info) }
    }

    /** Removes bin batches (named by the time they were binned) older than [maxAgeMillis]. */
    private fun purgeOldBin(maxAgeMillis: Long) {
        val cutoff = System.currentTimeMillis() - maxAgeMillis
        graph.loadPlaces().forEach { p ->
            runCatching {
                val b = graph.backendFor(p)
                if (b.exists(BIN_FOLDER)) {
                    b.list(BIN_FOLDER).filter { it.isDir && (it.name.toLongOrNull() ?: Long.MAX_VALUE) < cutoff }
                        .forEach { runCatching { b.delete(it.path) } }
                }
            }
        }
    }

    /** Permanently removes everything in the bins. Undo for those deletes stops working. */
    fun emptyBin() = viewModelScope.launch {
        withContext(Dispatchers.IO) {
            graph.loadPlaces().forEach { p ->
                runCatching {
                    val b = graph.backendFor(p)
                    if (b.exists(BIN_FOLDER)) b.delete(BIN_FOLDER)
                }
            }
        }
        val undo = _state.value.undo.filterNot { batch ->
            batch.done.any { it is PlannedAction.Move && "$BIN_FOLDER/" in it.to }
        }
        graph.saveUndo(undo)
        _state.update { it.copy(undo = undo) }
        if (_state.value.chatId != null) runner.addNote("Bin emptied")
        countBin()
    }

    /** Saves a key for [provider] and makes it the active AI. A blank key keeps the saved one. */
    fun saveKey(provider: Provider, key: String) {
        val newKey = key.isNotBlank()
        if (newKey) graph.setKey(provider, key)
        graph.provider = provider
        _state.update { it.copy(provider = provider, hasApiKey = graph.keyFor(provider).isNotBlank()) }
        syncModel()
        if (newKey) refreshModels(provider)
    }

    fun hasKeyFor(provider: Provider) = graph.keyFor(provider).isNotBlank()

    fun modelFor(provider: Provider) = graph.modelFor(provider)

    /** The models [provider] offers: the last fetched list, or the built-in one. */
    fun modelsFor(provider: Provider): List<ModelInfo> = _state.value.modelLists[provider] ?: graph.models.list(provider)

    fun modelInfo(provider: Provider, id: String): ModelInfo = graph.models.info(provider, id)

    /** Asks the provider for its live model list. [key] lets the sheet try a key before saving it. */
    fun refreshModels(provider: Provider, key: String? = null) {
        val k = key?.takeIf { it.isNotBlank() } ?: graph.keyFor(provider)
        if (k.isBlank()) return
        _state.update { it.copy(modelsLoading = true) }
        viewModelScope.launch {
            graph.models.refresh(provider, k)
            _state.update { it.copy(modelsLoading = false, modelLists = it.modelLists + (provider to graph.models.list(provider))) }
            syncModel()
        }
    }

    /** Picks the model for [provider] by id. */
    fun setModel(provider: Provider, model: String) {
        if (model.isBlank()) return
        graph.setModel(provider, model)
        syncModel()
    }

    /** Copies the active model's id, short name and picture support into the screen state. */
    private fun syncModel() {
        val p = graph.provider
        val info = graph.models.info(p, graph.modelFor(p))
        _state.update { it.copy(model = info.id, modelName = info.name, modelVision = info.vision) }
    }

    fun removeMemory(fact: String) {
        graph.removeMemory(fact)
        _state.update { it.copy(memory = graph.loadMemory()) }
    }

    fun addMemory(fact: String) {
        graph.addMemory(fact)
        _state.update { it.copy(memory = graph.loadMemory()) }
    }

    fun phoneFolders(): List<String> = graph.phoneFolders()

    /** For file tiles: the real file on the phone, if the path is a phone path. */
    fun localFileFor(placePath: String) = graph.localFileFor(placePath)

    private fun savePlaces(list: List<PlaceConfig>) {
        graph.savePlaces(list)
        _state.update { it.copy(places = list) }
    }

    fun removePlace(name: String) {
        savePlaces(_state.value.places.filterNot { it.name == name })
        if (name in _state.value.chatPlaces) runner.setPlaces(_state.value.chatPlaces - name)
    }

    /** Saves a place and turns it on for the open chat. Names start every path, so keep them simple. */
    fun addPlace(place: PlaceConfig): String? {
        val name = place.name.trim()
        if (name.isEmpty() || name.contains('/')) return "Give it a short name without /"
        savePlaces(_state.value.places.filterNot { it.name == name } + place.copy(name = name))
        if (name !in _state.value.chatPlaces) runner.setPlaces(_state.value.chatPlaces + name)
        return null
    }

    fun testPlace(place: PlaceConfig) {
        _state.update { it.copy(placeTest = "Testing ${place.name}…") }
        viewModelScope.launch {
            val msg = withContext(Dispatchers.IO) {
                runCatching {
                    val n = graph.backendFor(place).list("").size
                    "${place.name}: connected, $n items found."
                }.getOrElse { "${place.name}: failed (${it.message ?: it::class.simpleName})" }
            }
            _state.update { it.copy(placeTest = msg) }
        }
    }

    fun setAutoSort(on: Boolean) {
        graph.autoSortEnabled = on
        AutoSortWorker.schedule(getApplication(), on)
        _state.update { it.copy(autoSort = on) }
    }
}

fun PlannedAction.label() = when (this) {
    is PlannedAction.Mkdir -> "New folder $path"
    is PlannedAction.Move -> "$from → $to"
    is PlannedAction.Delete -> "Delete $path"
}
