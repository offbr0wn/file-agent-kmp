package dev.fileagent.fs

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Shared-storage access via java.io. Needs MANAGE_EXTERNAL_STORAGE
 * ("All files access") on Android 11+.
 */
class AndroidFileSystem(private val rootDir: File) : RootBackend {

    override val root: String = rootDir.absolutePath
    private val rootCanonical = rootDir.canonicalFile

    private fun resolve(path: String): File {
        val f = File(rootDir, path.trim('/')).canonicalFile
        if (f != rootCanonical && !f.path.startsWith(rootCanonical.path + File.separator)) {
            throw SecurityException("Path escapes storage root: $path")
        }
        val rel = f.relativeTo(rootCanonical).invariantSeparatorsPath
        if (rel.split('/').any { it.startsWith(".") } || rel == "Android" || rel.startsWith("Android/")) {
            throw SecurityException("Protected path: $path")
        }
        return f
    }

    private fun File.toEntry() = FileEntry(
        path = relativeTo(rootCanonical).invariantSeparatorsPath,
        name = name,
        isDir = isDirectory,
        sizeBytes = if (isDirectory) 0 else length(),
        modifiedMillis = lastModified(),
    )

    override fun list(path: String): List<FileEntry> =
        resolve(path).listFiles()
            ?.filterNot { it.name.startsWith(".") || (path.isBlank() && it.name == "Android") }
            ?.map { it.canonicalFile.toEntry() }
            .orEmpty()

    override fun info(path: String): FileEntry? = resolve(path).takeIf { it.exists() }?.toEntry()

    override fun exists(path: String): Boolean = resolve(path).exists()

    override fun mkdirs(path: String) {
        val dir = resolve(path)
        if (!dir.exists() && !dir.mkdirs()) throw IOException("Could not create $path")
    }

    override fun move(from: String, to: String) {
        val src = resolve(from)
        val dst = resolve(to)
        if (!src.exists()) throw IOException("Missing $from")
        if (dst.exists()) throw IOException("Already exists: $to")
        if (src.isDirectory && dst.path.startsWith(src.path + File.separator)) {
            throw IOException("Cannot move a folder into itself")
        }
        dst.parentFile?.mkdirs()
        if (!src.renameTo(dst)) {
            // Different volume (e.g. SD card): copy then delete the original.
            if (src.isDirectory) src.copyRecursively(dst) else src.copyTo(dst)
            if (!src.deleteRecursively()) throw IOException("Copied but could not remove $from")
        }
    }

    override fun openIn(path: String): InputStream = resolve(path).inputStream()

    override fun openOut(path: String): OutputStream {
        val f = resolve(path)
        if (f.exists()) throw IOException("Already exists: $path")
        f.parentFile?.mkdirs()
        return f.outputStream()
    }

    override fun delete(path: String) {
        val f = resolve(path)
        if (f == rootCanonical) throw SecurityException("Refusing to delete the place itself")
        if (!f.deleteRecursively()) throw IOException("Could not remove $path")
    }
}
