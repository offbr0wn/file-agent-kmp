package dev.fileagent.agent

import dev.fileagent.fs.FileSystemGateway
import dev.fileagent.fs.parentOf
import kotlinx.serialization.Serializable

/** Every applied change, so it can be reversed. */
@Serializable
data class UndoBatch(val request: String, val appliedAtMillis: Long, val done: List<PlannedAction>)

data class ExecutionResult(val batch: UndoBatch, val failures: List<Pair<PlannedAction, String>>)

class PlanExecutor(private val fs: FileSystemGateway) {

    /**
     * Applies actions in order. A failed step is skipped and reported, never retried.
     * [onProgress] gets (step, total) before each step, for a progress bar.
     */
    fun apply(plan: Plan, nowMillis: Long, onProgress: (Int, Int) -> Unit = { _, _ -> }): ExecutionResult {
        val done = mutableListOf<PlannedAction>()
        val failures = mutableListOf<Pair<PlannedAction, String>>()
        plan.actions.forEachIndexed { i, action ->
            onProgress(i, plan.actions.size)
            runCatching {
                when (action) {
                    is PlannedAction.Mkdir -> { if (!fs.exists(action.path)) fs.mkdirs(action.path); action }
                    is PlannedAction.Move -> { fs.move(action.from, action.to); action }
                    is PlannedAction.Delete -> {
                        // Deleting = moving into the bin, recorded as a move so Undo brings it back.
                        val bin = binPathFor(action.path, nowMillis)
                        fs.mkdirs(parentOf(bin))
                        fs.move(action.path, bin)
                        PlannedAction.Move(action.path, bin, "deleted")
                    }
                }
            }.onSuccess { done += it }
                .onFailure { failures += action to (it.message ?: "failed") }
        }
        return ExecutionResult(UndoBatch(plan.request, nowMillis, done), failures)
    }

    /** Reverses moves newest first. Created folders are left if not empty. */
    fun undo(batch: UndoBatch): List<String> {
        val problems = mutableListOf<String>()
        batch.done.asReversed().forEach { action ->
            runCatching {
                when (action) {
                    is PlannedAction.Move -> fs.move(action.to, action.from)
                    is PlannedAction.Mkdir -> Unit // Safe default: never delete folders.
                    is PlannedAction.Delete -> Unit // Never stored: applied deletes are recorded as moves.
                }
            }.onFailure { problems += "${action}: ${it.message}" }
        }
        return problems
    }
}
