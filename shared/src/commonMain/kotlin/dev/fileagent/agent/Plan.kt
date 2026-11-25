package dev.fileagent.agent

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A change the agent wants to make. Nothing runs until the user approves it. */
@Serializable
sealed interface PlannedAction {
    val reason: String

    @Serializable @SerialName("mkdir")
    data class Mkdir(val path: String, override val reason: String) : PlannedAction

    @Serializable @SerialName("move")
    data class Move(val from: String, val to: String, override val reason: String) : PlannedAction

    /** Sends a file or folder to the bin (see [binPathFor]), so it can be undone until the bin is emptied. */
    @Serializable @SerialName("delete")
    data class Delete(val path: String, override val reason: String) : PlannedAction
}

/** Name of the bin folder kept at the top of each place. */
const val BIN_FOLDER = "FileAgent Bin"

/** Where a deleted path goes: "<place>/FileAgent Bin/<time>/<rest of path>". */
fun binPathFor(path: String, nowMillis: Long): String {
    val p = path.trim('/')
    val place = p.substringBefore('/', "")
    val rest = p.substringAfter('/', p)
    return if (place.isEmpty()) "$BIN_FOLDER/$nowMillis/$p" else "$place/$BIN_FOLDER/$nowMillis/$rest"
}

@Serializable
data class Plan(
    val request: String,
    val summary: String,
    val actions: List<PlannedAction>,
)

/** What the UI shows while the agent works. */
sealed interface AgentEvent {
    data class Thinking(val text: String) : AgentEvent
    data class ToolUsed(val name: String, val detail: String) : AgentEvent
    data class Proposed(val action: PlannedAction) : AgentEvent
    data class Rejected(val reason: String) : AgentEvent
    data class Unproposed(val path: String, val count: Int) : AgentEvent
    /** The AI wants the user to see these files as pictures, to open them or pick some. */
    data class ShowFiles(val paths: List<String>, val title: String) : AgentEvent
}
