package dev.fileagent.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import dev.fileagent.agent.AgentEvent
import dev.fileagent.agent.FileAgent
import dev.fileagent.agent.Plan
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The open chat. Lives outside the screen so it survives app switching. */
data class RunState(
    val busy: Boolean = false,
    val chatId: String? = null,
    val title: String = "New chat",
    val items: List<ChatItem> = emptyList(),
    /** Plan waiting for Apply / Discard. */
    val plan: Plan? = null,
    val error: String? = null,
    /** All saved chats, newest first. */
    val chats: List<ChatMeta> = emptyList(),
    /** Folders this chat may use (place names). */
    val places: List<String> = emptyList(),
)

/**
 * Runs the AI for the open chat, independent of any screen. One request at a time.
 * Every chat is saved, including the AI conversation, so it can continue later.
 */
class AgentRunner(
    private val newAgent: (places: List<String>) -> FileAgent,
    private val store: ChatStore,
    private val defaultPlaces: () -> List<String>,
    private val onPlacesChosen: (List<String>) -> Unit,
    /** Maps place names saved in old chats to their current names. */
    private val fixPlaces: (List<String>) -> List<String> = { it },
    /** Called once per message the user sends, before the AI runs. */
    private val onNewMessage: () -> Unit = {},
) {
    private val _state = MutableStateFlow(RunState(chats = store.list(), places = defaultPlaces()))
    val state: StateFlow<RunState> = _state
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The AI conversation behind the open chat. */
    private var session: FileAgent.Session? = null

    /** Folders the AI was last told about in this chat, to notice changes. */
    private var sentPlaces: List<String>? = null

    fun newChat() {
        if (_state.value.busy) return
        session = null
        sentPlaces = null
        _state.value = RunState(chats = store.list(), places = defaultPlaces())
    }

    /** Chooses which folders this chat may use. */
    fun setPlaces(names: List<String>) {
        if (_state.value.busy) return
        onPlacesChosen(names)
        _state.update { it.copy(places = names) }
        if (_state.value.chatId != null) scope.launch { persist() }
    }

    fun openChat(id: String) {
        if (_state.value.busy) return
        val chat = store.load(id) ?: return
        session = chat.sessionJson?.let { runCatching { FileAgent.importSession(it) }.getOrNull() }
        val places = fixPlaces(chat.places).ifEmpty { defaultPlaces() }
        sentPlaces = places
        _state.value = RunState(
            chatId = chat.id, title = chat.title, items = chat.items,
            plan = chat.pendingPlan, chats = store.list(), places = places,
        )
    }

    fun deleteChat(id: String) {
        if (_state.value.busy) return
        store.delete(id)
        if (_state.value.chatId == id) newChat() else _state.update { it.copy(chats = store.list()) }
    }

    /** Sends a message (and any pictures) in the open chat. The AI keeps the whole conversation as context. */
    fun send(text: String, images: List<String> = emptyList()): Boolean {
        if (_state.value.busy || (text.isBlank() && images.isEmpty())) return false
        onNewMessage()
        val places = _state.value.places
        val agent = newAgent(places) // fresh each time so the chat's folders are current
        // Tell the AI when the folders changed mid-chat. The bubble shows only what the user typed.
        val typed = text.trim().ifBlank { "(The user sent a picture.)" }
        // Ticks in the latest picture grid, so "the ones I selected" means something.
        val ticked = _state.value.items.lastOrNull { it.kind == "files" && it.picked.isNotEmpty() }?.picked.orEmpty()
        val said = if (ticked.isEmpty()) typed else
            "(In the picture grid the user has ticked these ${ticked.size} files. \"Selected\", \"ticked\" or \"picked\" " +
                "means these: ${ticked.joinToString(", ")})\n$typed"
        val prompt = if (sentPlaces != null && sentPlaces != places) {
            "(Folders you can use now: ${places.joinToString()}. Call list_dir with \"\" to see them.)\n$said"
        } else said
        val pictures = images.mapNotNull { path ->
            runCatching {
                FileAgent.ImageInput("image/jpeg", android.util.Base64.encodeToString(java.io.File(path).readBytes(), android.util.Base64.NO_WRAP))
            }.getOrNull()
        }
        sentPlaces = places
        val id = _state.value.chatId ?: store.newId()
        _state.update {
            it.copy(
                busy = true, chatId = id, error = null,
                title = if (it.items.isEmpty()) text.trim().ifBlank { "Picture" }.take(40) else it.title,
                items = it.items + ChatItem("user", text.trim(), images = images) + ChatItem("activity", "", "running"),
            )
        }
        val existing = session
        scope.launch {
            runCatching {
                if (existing == null) {
                    val s = agent.newSession(prompt, pictures)
                    session = s
                    agent.run(s, ::onEvent)
                } else {
                    agent.followUp(existing, prompt, ::onEvent, pictures)
                }
            }.recoverCatching { err ->
                // An old chat history can stop working after a model or provider change
                // (the service answers 400). Start the AI's memory of this chat fresh and try once more.
                if (existing == null || (err as? dev.fileagent.llm.AiApiException)?.status != 400) throw err
                _state.update { it.copy(items = closeActivity(it.items, "failed") + ChatItem("note", "Started fresh with this model") + ChatItem("activity", "", "running")) }
                val s = agent.newSession(prompt, pictures)
                session = s
                agent.run(s, ::onEvent)
            }.onSuccess { plan -> finish(plan) }
                .onFailure { err ->
                    val msg = err.message ?: err.toString()
                    _state.update {
                        it.copy(
                            busy = false, error = msg,
                            items = closeActivity(it.items, "failed") + ChatItem("error", msg),
                        )
                    }
                }
            persist()
        }
        return true
    }

    private fun onEvent(e: AgentEvent) = _state.update { st ->
        if (e is AgentEvent.Thinking) {
            // AI text shows as a chat bubble; empty-reply notes stay in the activity log.
            if (e.text.startsWith("(model returned")) st.copy(items = addActivity(st.items, e.text))
            else st.copy(items = st.items + ChatItem("assistant", e.text))
        } else if (e is AgentEvent.ShowFiles) {
            // A picture grid in the chat; the paths ride in the item's images list.
            st.copy(items = addActivity(st.items, e.describe()) + ChatItem("files", e.title, images = e.paths))
        } else {
            st.copy(items = addActivity(st.items, e.describe()))
        }
    }

    private fun addActivity(items: List<ChatItem>, line: String): List<ChatItem> {
        val i = items.indexOfLast { it.kind == "activity" }
        if (i < 0) return items + ChatItem("activity", line, "running")
        val a = items[i]
        return items.toMutableList().also {
            it[i] = a.copy(text = if (a.text.isEmpty()) line else a.text + "\n" + line)
        }
    }

    private fun closeActivity(items: List<ChatItem>, status: String): List<ChatItem> {
        val i = items.indexOfLast { it.kind == "activity" && it.status == "running" }
        if (i < 0) return items
        // Drop an activity row that never logged anything.
        if (items[i].text.isBlank()) return items.filterIndexed { idx, _ -> idx != i }
        return items.toMutableList().also { it[i] = items[i].copy(status = status) }
    }

    private fun finish(plan: Plan) = _state.update { st ->
        var items = closeActivity(st.items, "done")
        if (plan.actions.isNotEmpty()) {
            // One live plan card per chat: replace a pending one, or add a new one.
            val card = ChatItem("plan", plan.summary, "pending")
            val i = items.indexOfLast { it.kind == "plan" && it.status == "pending" }
            items = if (i >= 0) items.toMutableList().also { it[i] = card } else items + card
            st.copy(busy = false, plan = plan, items = items)
        } else {
            val lastUser = items.indexOfLast { it.kind == "user" }
            val answered = items.drop(lastUser + 1).any { it.kind == "assistant" }
            if (!answered) {
                items = items + ChatItem("assistant", plan.summary.ifBlank { "No changes needed." })
            }
            // A follow-up may have removed every planned change.
            val i = items.indexOfLast { it.kind == "plan" && it.status == "pending" }
            if (i >= 0) items = items.toMutableList().also { it[i] = it[i].copy(status = "discarded") }
            st.copy(busy = false, plan = null, items = items)
        }
    }

    /** After Apply or Discard: close the card, tell the AI, add a note. */
    fun planDone(outcome: String, note: String) {
        session?.let { FileAgent.planDone(it, outcome) }
        _state.update { st ->
            val i = st.items.indexOfLast { it.kind == "plan" && it.status == "pending" }
            val items = if (i >= 0) st.items.toMutableList().also { it[i] = it[i].copy(status = outcome) } else st.items
            st.copy(plan = null, items = items + ChatItem("note", note))
        }
        scope.launch { persist() }
    }

    /** Shows a plan made without the AI (Quick sort) in the open chat, ready to Apply. */
    /** Saves which files are ticked in the picture grid at [index]. */
    fun setPicked(index: Int, picked: List<String>) {
        _state.update { st ->
            val item = st.items.getOrNull(index)?.takeIf { it.kind == "files" } ?: return@update st
            st.copy(items = st.items.toMutableList().also { it[index] = item.copy(picked = picked) })
        }
        scope.launch { persist() }
    }

    fun showQuickPlan(request: String, plan: Plan) {
        if (_state.value.busy) return
        val id = _state.value.chatId ?: store.newId()
        _state.update { st ->
            val items = st.items + ChatItem("user", request) +
                if (plan.actions.any { it !is dev.fileagent.agent.PlannedAction.Mkdir }) ChatItem("plan", plan.summary, "pending")
                else ChatItem("assistant", "Already tidy. Nothing to sort.")
            st.copy(
                chatId = id, title = if (st.items.isEmpty()) request else st.title, items = items,
                plan = plan.takeIf { p -> p.actions.any { it !is dev.fileagent.agent.PlannedAction.Mkdir } },
                error = null,
            )
        }
        scope.launch { persist() }
    }

    fun addNote(note: String) {
        _state.update { it.copy(items = it.items + ChatItem("note", note)) }
        scope.launch { persist() }
    }

    private fun persist() {
        val st = _state.value
        val id = st.chatId ?: return
        runCatching {
            store.save(
                SavedChat(
                    id = id, title = st.title, updated = System.currentTimeMillis(), items = st.items,
                    sessionJson = session?.let { FileAgent.exportSession(it) }, pendingPlan = st.plan,
                    places = st.places,
                )
            )
        }
        _state.update { it.copy(chats = store.list()) }
    }
}

/**
 * Foreground service that keeps the app alive while the AI works,
 * so switching apps does not pause or kill the request.
 */
class AgentService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        startForeground(
            RUNNING_ID,
            notification("Working on your request…", "Keeps going if you switch apps.", ongoing = true),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        val runner = AppGraph.get(this).runner
        intent?.getStringExtra(EXTRA_MESSAGE)?.let { runner.send(it, intent.getStringArrayListExtra(EXTRA_IMAGES).orEmpty()) }

        scope.launch {
            val done = runner.state.first { !it.busy }
            val plan = done.plan
            val error = done.error
            val text = when {
                error != null -> "Stopped: ${error.take(80)}"
                plan != null -> "Plan ready: ${plan.actions.size} changes. Tap to review."
                else -> "Reply ready. Tap to open."
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            getSystemService(NotificationManager::class.java)
                .notify(DONE_ID, notification("File Agent", text, ongoing = false))
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(title: String, text: String, ongoing: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_logo)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .build()
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Agent runs", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    companion object {
        private const val CHANNEL = "agent_runs"
        private const val RUNNING_ID = 1
        private const val DONE_ID = 2
        private const val EXTRA_MESSAGE = "message"
        private const val EXTRA_IMAGES = "images"

        fun send(context: Context, message: String, images: List<String> = emptyList()) {
            context.startForegroundService(
                Intent(context, AgentService::class.java)
                    .putExtra(EXTRA_MESSAGE, message)
                    .putStringArrayListExtra(EXTRA_IMAGES, ArrayList(images))
            )
        }
    }
}

fun AgentEvent.describe() = when (this) {
    is AgentEvent.Thinking -> text
    is AgentEvent.ToolUsed -> if (name == "remember") "Saved to memory: $detail" else "Looked at $detail"
    is AgentEvent.Proposed -> "Planned: ${action.label()}"
    is AgentEvent.Rejected -> "Skipped: $reason"
    is AgentEvent.Unproposed -> "Removed from plan: $path ($count)"
    is AgentEvent.ShowFiles -> "Showed ${paths.size} files"
}
