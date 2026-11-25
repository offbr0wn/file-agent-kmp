package dev.fileagent.agent

import dev.fileagent.fs.FileSystemGateway
import dev.fileagent.fs.parentOf
import dev.fileagent.llm.AssistantTurn
import dev.fileagent.llm.LlmClient
import dev.fileagent.llm.ToolCall
import dev.fileagent.llm.ToolSpec
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Hermes-style loop: the model calls tools until it is done.
 * Read-only tools run for real. Change tools only add to a [Plan],
 * which the user approves before [PlanExecutor] touches any file.
 */
class FileAgent(
    private val llm: LlmClient,
    private val fs: FileSystemGateway,
    private val maxSteps: Int = 20,
    private val maxListing: Int = 200,
    /** Facts the user asked the AI to remember, added to every chat. */
    private val memory: () -> List<String> = { emptyList() },
    private val onRemember: (String) -> Unit = {},
    /** How the AI should talk: "short" (one or two plain sentences) or "detailed". The user picks. */
    private val replyStyle: () -> String = { "short" },
) {

    /** A picture the user attached. [base64] is the image bytes; [mediaType] e.g. image/jpeg. */
    data class ImageInput(val mediaType: String, val base64: String)

    /** One conversation with the AI. Kept so the user can ask follow-ups about the plan. */
    class Session internal constructor(val request: String) {
        internal val messages = mutableListOf<JsonObject>()
        internal val actions = mutableListOf<PlannedAction>()
        internal var summary = ""
        internal var nudged = false
    }

    fun newSession(request: String, images: List<ImageInput> = emptyList()): Session =
        Session(request).also { it.messages += userContent(request, images) }

    suspend fun plan(request: String, onEvent: (AgentEvent) -> Unit = {}): Plan =
        run(newSession(request), onEvent)

    /**
     * Ask about (or change) the current plan in the same conversation.
     * The AI answers in text and can add or remove planned changes.
     */
    suspend fun followUp(
        session: Session,
        question: String,
        onEvent: (AgentEvent) -> Unit = {},
        images: List<ImageInput> = emptyList(),
    ): Plan {
        appendUser(
            session,
            "New message from the user (the current plan has ${session.actions.size} changes): $question\n" +
                "Reply briefly. If they want file changes, use the tools (unpropose to remove planned " +
                "changes, propose_mkdir / propose_move to add them), then call finish with a one-line summary.",
            images,
        )
        session.nudged = true
        return run(session, onEvent)
    }

    suspend fun run(session: Session, onEvent: (AgentEvent) -> Unit = {}): Plan {
        val messages = session.messages
        val actions = session.actions
        val request = session.request

        repeat(maxSteps) {
            val turn = try {
                completeWithRetry(messages)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Keep what was planned so far instead of losing it all.
                if (actions.isNotEmpty()) {
                    return Plan(
                        request,
                        "Stopped early (${e.message?.take(80)}). Review the ${actions.size} changes found so far.",
                        actions.toList(),
                    )
                }
                throw e
            }
            if (turn.text.isNotBlank()) onEvent(AgentEvent.Thinking(turn.text))
            if (turn.rawContent.isNotEmpty()) {
                messages += buildJsonObject {
                    put("role", "assistant")
                    put("content", turn.rawContent)
                }
            }
            if (turn.toolCalls.isEmpty()) {
                if (turn.text.isBlank()) onEvent(AgentEvent.Thinking("(model returned no text, stop reason: ${turn.stopReason})"))
                // Some models answer in prose without exploring. Nudge once before giving up.
                if (actions.isEmpty() && !session.nudged) {
                    session.nudged = true
                    appendUser(session, NUDGE)
                    return@repeat
                }
                return Plan(request, session.summary.ifBlank { turn.text }, actions.toList())
            }

            var finished = false
            val results = turn.toolCalls.map { call ->
                val (output, isError) = runCatching { runTool(call, actions, onEvent) }
                    .fold({ it to false }, { (it.message ?: "error") to true })
                if (call.name == "finish") {
                    finished = true
                    call.str("summary")?.takeIf { it.isNotBlank() }?.let { session.summary = it }
                }
                if (isError) onEvent(AgentEvent.Rejected("${call.name}: $output"))
                call.id to (output to isError)
            }
            messages += buildJsonObject {
                put("role", "user")
                putJsonArray("content") {
                    results.forEach { (id, res) ->
                        addJsonObject {
                            put("type", "tool_result")
                            put("tool_use_id", id)
                            put("content", res.first)
                            if (res.second) put("is_error", true)
                        }
                    }
                }
            }
            if (finished) return Plan(request, session.summary, actions.toList())
        }
        return Plan(request, "Stopped after $maxSteps steps. Review what was proposed.", actions.toList())
    }

    private fun systemPrompt(): String {
        val facts = memory()
        val style = if (replyStyle() == "detailed") {
            "Reply clearly in a short paragraph. Say briefly why, when that helps the user decide."
        } else {
            "Keep every reply to one or two short sentences with plain, simple words. No jargon."
        }
        val prompt = SYSTEM_PROMPT.replace("{{STYLE}}", style)
        if (facts.isEmpty()) return prompt
        return prompt + "\n\nThings the user asked you to remember:\n" + facts.joinToString("\n") { "- $it" }
    }

    /** One automatic retry for timeouts and flaky connections. */
    private suspend fun completeWithRetry(messages: List<JsonObject>): AssistantTurn {
        try {
            return llm.complete(systemPrompt(), messages, TOOLS)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            delay(2_000)
            return llm.complete(systemPrompt(), messages, TOOLS)
        }
    }

    // ---- tools -------------------------------------------------------------

    private fun runTool(
        call: ToolCall,
        actions: MutableList<PlannedAction>,
        onEvent: (AgentEvent) -> Unit,
    ): String = when (call.name) {
        "list_dir" -> {
            val path = call.str("path").orEmpty()
            onEvent(AgentEvent.ToolUsed("list_dir", path.ifBlank { "/" }))
            val entries = fs.list(path).sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
            val shown = entries.take(maxListing).joinToString("\n") {
                if (it.isDir) "[dir]  ${it.path}"
                else "[file] ${it.path}  ${it.sizeBytes}B  modified=${it.modifiedMillis}"
            }
            val more = entries.size - maxListing
            if (entries.isEmpty()) "(empty)" else if (more > 0) "$shown\n...and $more more" else shown
        }

        "file_info" -> {
            val path = call.req("path")
            onEvent(AgentEvent.ToolUsed("file_info", path))
            fs.info(path)?.toString() ?: error("Not found: $path")
        }

        "propose_mkdir" -> {
            val path = call.req("path").trim('/')
            val planned = plannedDestinations(actions)
            require(!fs.exists(path) && path !in planned) { "Already exists or already planned: $path" }
            PlannedAction.Mkdir(path, call.str("reason").orEmpty()).also {
                actions += it; onEvent(AgentEvent.Proposed(it))
            }
            "Planned folder $path"
        }

        "propose_move" -> {
            val from = call.req("from").trim('/')
            val to = call.req("to").trim('/')
            val planned = plannedDestinations(actions)
            require(fs.exists(from)) { "Source not found: $from" }
            require(actions.none { it is PlannedAction.Move && it.from == from }) { "Already moving $from" }
            require(!fs.exists(to) && to !in planned) { "Destination taken: $to" }
            // NAS drives (and Windows) refuse these characters in names.
            val bad = to.split('/').drop(1).joinToString("").filter { it in "\\:*?\"<>|" }
            require(bad.isEmpty()) { "Names can't contain $bad. Pick a name without \\ : * ? \" < > |" }
            // A missing destination folder is planned for you, so moves never stall on it.
            val parent = parentOf(to)
            val place = to.substringBefore('/')
            require(to.contains('/') && fs.exists(place)) { "No place called $place. Call list_dir with \"\" to see places." }
            if (parent.contains('/') && !fs.exists(parent) && parent !in planned) {
                PlannedAction.Mkdir(parent, "Folder for moved files").also {
                    actions += it; onEvent(AgentEvent.Proposed(it))
                }
            }
            PlannedAction.Move(from, to, call.str("reason").orEmpty()).also {
                actions += it; onEvent(AgentEvent.Proposed(it))
            }
            "Planned move $from -> $to"
        }

        "propose_delete" -> {
            val path = call.req("path").trim('/')
            val p = path.trim('/')
            require(p.contains('/')) { "Cannot delete a whole place" }
            require(fs.exists(path)) { "Not found: $path" }
            require(!p.split('/').contains(BIN_FOLDER)) { "Already in the bin: $path" }
            require(actions.none { (it is PlannedAction.Move && it.from == path) || (it is PlannedAction.Delete && it.path == path) }) {
                "Already planned: $path"
            }
            PlannedAction.Delete(path, call.str("reason").orEmpty()).also {
                actions += it; onEvent(AgentEvent.Proposed(it))
            }
            "Planned delete $path (goes to the bin, can be undone)"
        }

        "unpropose" -> {
            val target = call.req("path")
            val before = actions.size
            actions.removeAll {
                when (it) {
                    is PlannedAction.Move -> it.from == target || it.to == target
                    is PlannedAction.Mkdir -> it.path == target
                    is PlannedAction.Delete -> it.path == target
                }
            }
            val removed = before - actions.size
            require(removed > 0) { "No planned change matches $target" }
            onEvent(AgentEvent.Unproposed(target, removed))
            "Removed $removed planned change(s) for $target"
        }

        "remember" -> {
            val fact = call.req("fact")
            onRemember(fact)
            onEvent(AgentEvent.ToolUsed("remember", fact))
            "Saved to memory"
        }

        "show_files" -> {
            val paths = (call.input["paths"] as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim('/') }
                .filter { it.isNotBlank() && fs.exists(it) }
                .distinct()
                .take(MAX_SHOWN)
            require(paths.isNotEmpty()) { "None of those paths exist. Use list_dir to get real paths." }
            onEvent(AgentEvent.ShowFiles(paths, call.str("title").orEmpty()))
            "Showing ${paths.size} files to the user as pictures. They can tap one to open or play it, and tick " +
                "files to move to the bin. Don't list the names again: say one short line, then call finish."
        }

        "finish" -> "Done"
        else -> error("Unknown tool ${call.name}")
    }

    private fun plannedDestinations(actions: List<PlannedAction>): Set<String> =
        actions.mapNotNull {
            when (it) {
                is PlannedAction.Move -> it.to
                is PlannedAction.Mkdir -> it.path
                is PlannedAction.Delete -> null
            }
        }.toSet()

    private fun ToolCall.str(key: String) = input[key]?.jsonPrimitive?.contentOrNull
    private fun ToolCall.req(key: String) =
        str(key)?.takeIf { it.isNotBlank() } ?: error("Missing '$key'")

    companion object {
        private val snapshotJson = Json { ignoreUnknownKeys = true }

        /** Saves a conversation so it can continue after the app restarts. */
        fun exportSession(s: Session): String = snapshotJson.encodeToString(
            SessionSnapshot.serializer(),
            SessionSnapshot(s.request, s.messages.toList(), s.actions.toList(), s.summary),
        )

        fun importSession(text: String): Session {
            val snap = snapshotJson.decodeFromString(SessionSnapshot.serializer(), text)
            return Session(snap.request).apply {
                messages += snap.messages
                actions += snap.actions
                summary = snap.summary
                nudged = true
            }
        }

        /** Marks the current plan finished (applied or discarded) so the next request starts fresh. */
        fun planDone(session: Session, outcome: String) {
            session.actions.clear()
            session.summary = ""
            appendUser(session, "[The user $outcome the last plan. It is finished. Start a new plan for any new request.]")
        }

        private fun userText(text: String) = buildJsonObject {
            put("role", "user")
            put("content", text)
        }

        /** A user message: plain text, or pictures followed by the text. */
        private fun userContent(text: String, images: List<ImageInput>) =
            if (images.isEmpty()) userText(text) else buildJsonObject {
                put("role", "user")
                put("content", JsonArray(images.map { imageBlock(it) } + textBlock(text)))
            }

        private fun imageBlock(img: ImageInput) = buildJsonObject {
            put("type", "image")
            put("source", buildJsonObject {
                put("type", "base64")
                put("media_type", img.mediaType)
                put("data", img.base64)
            })
        }

        /** Adds user text (and pictures), merging into the last user message so roles keep alternating. */
        internal fun appendUser(session: Session, text: String, images: List<ImageInput> = emptyList()) {
            val msgs = session.messages
            val last = msgs.lastOrNull()
            if (last != null && last["role"]?.jsonPrimitive?.contentOrNull == "user") {
                val blocks = when (val content = last["content"]) {
                    is JsonArray -> content.toMutableList()
                    null -> mutableListOf()
                    else -> mutableListOf<JsonElement>(textBlock(content.jsonPrimitive.content))
                }
                images.forEach { blocks += imageBlock(it) }
                blocks += textBlock(text)
                msgs[msgs.lastIndex] = buildJsonObject {
                    put("role", "user")
                    put("content", JsonArray(blocks))
                }
            } else {
                msgs += userContent(text, images)
            }
        }

        private fun textBlock(text: String) = buildJsonObject {
            put("type", "text")
            put("text", text)
        }


        val SYSTEM_PROMPT = """
            You are a careful file organiser running on an Android phone, used like a chat assistant.
            {{STYLE}}
            The user may attach pictures, like screenshots. Look at them when they help with the request.
            When the user wants to see, look through or pick files, call show_files. Never say you can't show files.
            No lists, no headings, no file paths unless asked. The app draws the plan, so never list the moves.
            You can only use the "places" the user has allowed. Call list_dir with "" to see them.
            Every path starts with a place name, e.g. "Download/cv.pdf" or "NAS/Photos/2024".
            "Downloads" means the "Download" place. Moving between places is allowed when asked:
            the app copies the file, checks it, then removes the original.
            Always start by calling list_dir. Never reply without using the tools first.
            Explore with list_dir and file_info, then build a plan with propose_mkdir
            and propose_move (use propose_move to rename too).
            Plan everything in one go: folders and every move together. Never ask the user to approve a folder first.
            Use propose_delete only when the user asks to delete or clean up (duplicates, old installers, junk).
            Deleted files go to a "FileAgent Bin" folder, so the user can undo; say that in one short line.
            You CAN delete with propose_delete, even if earlier messages in this chat said you could not.
            You cannot touch the bin itself. To free the space for good, tell the user to tap "Delete for good";
            the bin also clears itself after 7 days.
            Rules:
            - Only touch the folder(s) the user asked about. Keep every new folder inside them.
            - Never touch Android/, hidden folders (starting with "."), or app data.
            - Keep names readable. Keep the original extension when renaming.
            - Prefer a few clear folders over many tiny ones.
            - Do not move a folder into itself.
            When done, call finish with a short plain-English summary.
        """.trimIndent()

        const val NUDGE = "Please use the tools now: call list_dir on the relevant folder, " +
            "then propose_mkdir / propose_move for the changes, then finish."

        private fun schema(vararg props: Pair<String, String>, required: List<String>) =
            buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    props.forEach { (name, desc) ->
                        putJsonObject(name) { put("type", "string"); put("description", desc) }
                    }
                }
                putJsonArray("required") { required.forEach { add(JsonPrimitive(it)) } }
            }

        private const val MAX_SHOWN = 60

        val TOOLS = listOf(
            ToolSpec("list_dir", "List files and folders in a directory. Use \"\" for the root.",
                schema("path" to "Relative directory path", required = emptyList())),
            ToolSpec("file_info", "Get size and modified time for one path.",
                schema("path" to "Relative path", required = listOf("path"))),
            ToolSpec("propose_mkdir", "Plan creating a folder. Not executed until the user approves.",
                schema("path" to "Folder to create", "reason" to "Why", required = listOf("path"))),
            ToolSpec("propose_move", "Plan moving or renaming a file or folder, also between places (phone to NAS). Missing destination folders are created for you. Not executed until approved.",
                schema("from" to "Current path", "to" to "New full path", "reason" to "Why",
                    required = listOf("from", "to"))),
            ToolSpec("propose_delete", "Plan deleting a file or folder (moved to the bin, can be undone). Not executed until approved.",
                schema("path" to "Path to delete", "reason" to "Why, a few words", required = listOf("path"))),
            ToolSpec("unpropose", "Remove planned changes for a path (matches a move's from/to or a planned folder).",
                schema("path" to "Path used in the planned change", required = listOf("path"))),
            ToolSpec("remember", "Save a short fact for future chats. Use only when the user asks you to remember something or states a lasting preference.",
                schema("fact" to "The fact, one short sentence", required = listOf("fact"))),
            ToolSpec(
                "show_files",
                "Show files to the user as pictures in the chat (photo and video thumbnails, app icons). They can open " +
                    "or play each one and tick files to move to the bin. Use it whenever the user wants to see, look " +
                    "through, check or pick files. Up to $MAX_SHOWN files.",
                buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("paths") {
                            put("type", "array")
                            putJsonObject("items") { put("type", "string") }
                            put("description", "Full paths from list_dir")
                        }
                        putJsonObject("title") {
                            put("type", "string")
                            put("description", "A few words, e.g. Videos in Downloads")
                        }
                    }
                    putJsonArray("required") { add(JsonPrimitive("paths")) }
                },
            ),
            ToolSpec("finish", "Finish planning.",
                schema("summary" to "Under 8 simple words, e.g. Photos and PDFs into folders", required = listOf("summary"))),
        )
    }
}

@Serializable
private data class SessionSnapshot(
    val request: String,
    val messages: List<JsonObject>,
    val actions: List<PlannedAction>,
    val summary: String,
)
