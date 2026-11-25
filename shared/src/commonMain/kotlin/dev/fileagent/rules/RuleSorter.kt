package dev.fileagent.rules

import dev.fileagent.agent.Plan
import dev.fileagent.agent.PlannedAction
import dev.fileagent.fs.FileSystemGateway
import dev.fileagent.fs.join

/**
 * No-LLM sorter for background runs: files in [sourceDir] go into
 * [sourceDir]/Sorted/<Category>. Fast, free, predictable.
 */
class RuleSorter(
    private val fs: FileSystemGateway,
    private val categories: Map<String, Set<String>> = DEFAULT_CATEGORIES,
    private val minAgeMillis: Long = 60 * 60 * 1000L, // skip files still downloading
) {
    fun plan(sourceDir: String, nowMillis: Long): Plan {
        val sortedRoot = join(sourceDir, "Sorted")
        val actions = mutableListOf<PlannedAction>()
        val madeDirs = mutableSetOf<String>()
        val taken = mutableSetOf<String>()

        if (!fs.exists(sortedRoot)) {
            actions += PlannedAction.Mkdir(sortedRoot, "Sorted root")
            madeDirs += sortedRoot
        }

        fs.list(sourceDir)
            .filter { !it.isDir && !it.name.startsWith(".") }
            .filter { nowMillis - it.modifiedMillis >= minAgeMillis }
            .forEach { file ->
                val category = categories.entries.firstOrNull { file.extension in it.value }?.key ?: "Other"
                val dir = join(sortedRoot, category)
                if (!fs.exists(dir) && dir !in madeDirs) {
                    actions += PlannedAction.Mkdir(dir, "Category $category")
                    madeDirs += dir
                }
                val target = uniqueName(dir, file.name, taken)
                taken += target
                actions += PlannedAction.Move(file.path, target, "${file.extension.ifBlank { "no ext" }} -> $category")
            }

        return Plan("Auto-sort $sourceDir", "Sorted ${actions.count { it is PlannedAction.Move }} files", actions)
    }

    private fun uniqueName(dir: String, name: String, taken: Set<String>): String {
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "").let { if (it.isEmpty() || it == name) "" else ".$it" }
        var candidate = join(dir, name)
        var n = 1
        while (fs.exists(candidate) || candidate in taken) {
            candidate = join(dir, "$base ($n)$ext"); n++
        }
        return candidate
    }

    companion object {
        val DEFAULT_CATEGORIES = mapOf(
            "Images" to setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "bmp", "svg"),
            "Videos" to setOf("mp4", "mkv", "mov", "webm", "avi", "3gp"),
            "Audio" to setOf("mp3", "m4a", "wav", "ogg", "flac", "aac", "opus"),
            "Documents" to setOf("pdf", "doc", "docx", "txt", "md", "rtf", "odt", "pages"),
            "Spreadsheets" to setOf("xls", "xlsx", "csv", "ods", "numbers"),
            "Slides" to setOf("ppt", "pptx", "odp", "key"),
            "Archives" to setOf("zip", "rar", "7z", "tar", "gz"),
            "APKs" to setOf("apk", "apks", "xapk"),
        )
    }
}
