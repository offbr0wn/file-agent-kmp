package dev.fileagent

import dev.fileagent.agent.FileAgent
import dev.fileagent.agent.PlanExecutor
import dev.fileagent.agent.PlannedAction
import dev.fileagent.fs.FileEntry
import dev.fileagent.fs.FileSystemGateway
import dev.fileagent.fs.parentOf
import dev.fileagent.llm.AssistantTurn
import dev.fileagent.llm.LlmClient
import dev.fileagent.llm.ToolCall
import dev.fileagent.llm.ToolSpec
import dev.fileagent.rules.RuleSorter
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** In-memory file system: set of dirs + map of files. */
class FakeFs(files: List<String>, dirs: List<String> = emptyList()) : FileSystemGateway {
    val fileSet = files.toMutableSet()
    val dirSet = (dirs + files.map(::parentOf)).filter { it.isNotBlank() }.toMutableSet()
    override val root = "/"
    override fun list(path: String): List<FileEntry> {
        val prefix = if (path.isBlank()) "" else "$path/"
        val children = (fileSet + dirSet).filter {
            it.startsWith(prefix) && !it.removePrefix(prefix).contains('/') && it != path
        }
        return children.map { FileEntry(it, it.substringAfterLast('/'), it in dirSet, 10, 0) }
    }
    override fun info(path: String) = list(parentOf(path)).firstOrNull { it.path == path }
    override fun exists(path: String) = path in fileSet || path in dirSet
    override fun mkdirs(path: String) { dirSet += path }
    override fun move(from: String, to: String) {
        check(!exists(to)); check(fileSet.remove(from)); fileSet += to
    }
}

/** Replays a fixed script of tool calls. */
class ScriptedLlm(private val turns: List<List<Pair<String, Map<String, String>>>>) : LlmClient {
    private var i = 0
    override suspend fun complete(system: String, messages: List<JsonObject>, tools: List<ToolSpec>): AssistantTurn {
        val calls = turns.getOrElse(i++) { emptyList() }.mapIndexed { n, (name, args) ->
            ToolCall("t$i-$n", name, JsonObject(args.mapValues { JsonPrimitive(it.value) }))
        }
        return AssistantTurn("", calls, JsonArray(emptyList()), "tool_use")
    }
}

class FileAgentTest {
    @Test
    fun plansThenExecutesThenUndoes() = runTest {
        val fs = FakeFs(listOf("Download/cv.pdf", "Download/cat.jpg"))
        val llm = ScriptedLlm(listOf(
            listOf("list_dir" to mapOf("path" to "Download")),
            listOf(
                "propose_mkdir" to mapOf("path" to "Download/Docs"),
                "propose_move" to mapOf("from" to "Download/cv.pdf", "to" to "Download/Docs/cv.pdf"),
            ),
            listOf("finish" to mapOf("summary" to "Moved CV")),
        ))
        val plan = FileAgent(llm, fs).plan("tidy downloads")
        assertEquals(2, plan.actions.size)
        assertEquals("Moved CV", plan.summary)
        assertTrue(fs.exists("Download/cv.pdf"), "nothing moves before approval")

        val exec = PlanExecutor(fs)
        val result = exec.apply(plan, 0)
        assertTrue(fs.exists("Download/Docs/cv.pdf"))
        exec.undo(result.batch)
        assertTrue(fs.exists("Download/cv.pdf"))
    }

    @Test
    fun plansMissingFolderForAMove() = runTest {
        val fs = FakeFs(listOf("Download/a.pdf"))
        val llm = ScriptedLlm(listOf(
            listOf("propose_move" to mapOf("from" to "Download/a.pdf", "to" to "Download/New/Deep/a.pdf")),
            listOf("finish" to mapOf("summary" to "x")),
        ))
        val actions = FileAgent(llm, fs).plan("x").actions
        assertEquals(2, actions.size)
        assertTrue(actions.first() is dev.fileagent.agent.PlannedAction.Mkdir)
    }

    @Test
    fun rejectsMoveIntoMissingPlace() = runTest {
        val fs = FakeFs(listOf("Download/a.pdf"))
        val llm = ScriptedLlm(listOf(
            listOf("propose_move" to mapOf("from" to "Download/a.pdf", "to" to "Nope/a.pdf")),
            listOf("finish" to mapOf("summary" to "x")),
        ))
        assertTrue(FileAgent(llm, fs).plan("x").actions.isEmpty())
    }

    @Test
    fun ruleSorterAvoidsNameClashes() {
        val fs = FakeFs(listOf("Download/a.jpg", "Download/Sorted/Images/a.jpg", "Download/b.zip"))
        val plan = RuleSorter(fs, minAgeMillis = 0).plan("Download", 1)
        val moves = plan.actions.filterIsInstance<PlannedAction.Move>().associate { it.from to it.to }
        assertEquals("Download/Sorted/Images/a (1).jpg", moves["Download/a.jpg"])
        assertEquals("Download/Sorted/Archives/b.zip", moves["Download/b.zip"])
    }

    @Test
    fun chatSurvivesSaveAndRestore() = runTest {
        val fs = FakeFs(listOf("Download/cv.pdf"))
        val remembered = mutableListOf<String>()
        val llm = ScriptedLlm(listOf(
            listOf(
                "remember" to mapOf("fact" to "CVs go in Docs"),
                "propose_mkdir" to mapOf("path" to "Download/Docs"),
                "propose_move" to mapOf("from" to "Download/cv.pdf", "to" to "Download/Docs/cv.pdf"),
            ),
            listOf("finish" to mapOf("summary" to "Moved CV")),
        ))
        val agent = FileAgent(llm, fs, onRemember = { remembered += it })
        val session = agent.newSession("tidy")
        agent.run(session)
        assertEquals(listOf("CVs go in Docs"), remembered)

        val restored = FileAgent.importSession(FileAgent.exportSession(session))
        assertEquals(FileAgent.exportSession(session), FileAgent.exportSession(restored))

        FileAgent.planDone(restored, "applied")
        assertTrue(FileAgent.exportSession(restored).contains("applied the last plan"))
    }

    @Test
    fun deleteGoesToBinAndUndoBringsItBack() = runTest {
        val fs = FakeFs(listOf("Download/old.apk", "Download/keep.pdf"))
        val llm = ScriptedLlm(listOf(
            listOf("propose_delete" to mapOf("path" to "Download/old.apk", "reason" to "duplicate")),
            listOf("finish" to mapOf("summary" to "Bin one APK")),
        ))
        val plan = FileAgent(llm, fs).plan("delete old apks")
        assertEquals(listOf(PlannedAction.Delete("Download/old.apk", "duplicate")), plan.actions)

        val exec = PlanExecutor(fs)
        val result = exec.apply(plan, 42)
        assertTrue(result.failures.isEmpty())
        assertTrue("Download/FileAgent Bin/42/old.apk" in fs.fileSet)
        assertTrue("Download/old.apk" !in fs.fileSet)

        exec.undo(result.batch)
        assertTrue("Download/old.apk" in fs.fileSet)
    }
}
