package dev.fileagent.fs

import kotlinx.serialization.Serializable

/** One file or folder. Paths are always relative to the gateway root, using "/". */
@Serializable
data class FileEntry(
    val path: String,
    val name: String,
    val isDir: Boolean,
    val sizeBytes: Long,
    val modifiedMillis: Long,
) {
    val extension: String
        get() = if (isDir) "" else name.substringAfterLast('.', "").lowercase()
}

/**
 * The only way the agent touches storage. Android implements it with java.io
 * over shared storage. Implementations must refuse any path that escapes [root].
 */
interface FileSystemGateway {
    val root: String
    fun list(path: String): List<FileEntry>
    fun info(path: String): FileEntry?
    fun exists(path: String): Boolean
    fun mkdirs(path: String)
    /** Moves or renames. Must fail if [to] already exists. */
    fun move(from: String, to: String)
}

fun parentOf(path: String): String = path.trimEnd('/').substringBeforeLast('/', "")
fun join(dir: String, name: String): String =
    if (dir.isBlank()) name else "${dir.trimEnd('/')}/$name"
