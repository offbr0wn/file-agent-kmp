package dev.fileagent.fs

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.security.bc.BCSecurityProvider
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.EnumSet

/** One allowed "place" (a phone folder or a NAS share). Paths are relative to the place. */
interface RootBackend : FileSystemGateway {
    fun openIn(path: String): InputStream
    /** Must fail if [path] already exists. */
    fun openOut(path: String): OutputStream
    fun delete(path: String)
}

/** Reads any part of a file, for playing video straight from a NAS. */
interface RandomReader : java.io.Closeable {
    val size: Long
    /** Returns bytes read, or -1 at the end. */
    fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int
}

/**
 * The only file system the AI sees. Every path starts with a place name
 * ("Download/cv.pdf", "NAS/Photos"). Anything outside the places is refused.
 * Moves between places copy, verify the size, then remove the original.
 */
class PlacesFileSystem(private val places: Map<String, RootBackend>) : FileSystemGateway {

    override val root: String = "places"

    /** Opens a file for reading, on the phone or a NAS. */
    fun openIn(path: String): InputStream {
        val (name, rest) = split(path)
        return backend(name).openIn(rest)
    }

    /** Random access to a NAS file. Null for phone files (use the file itself). */
    fun openRandom(path: String): RandomReader? {
        val (name, rest) = split(path)
        return (backend(name) as? SmbFileSystem)?.openRandom(rest)
    }

    val placeNames: Set<String> get() = places.keys

    private fun split(path: String): Pair<String, String> {
        val p = path.trim('/')
        val name = p.substringBefore('/')
        val rest = p.substringAfter('/', "")
        if (name !in places) {
            throw SecurityException("\"$name\" is not an allowed place. Allowed: ${places.keys.joinToString()}")
        }
        return name to rest
    }

    private fun backend(name: String) = places.getValue(name)

    override fun list(path: String): List<FileEntry> {
        if (path.trim('/').isEmpty()) return places.keys.map { FileEntry(it, it, true, 0, 0) }
        val (name, rest) = split(path)
        return backend(name).list(rest).map { it.copy(path = join(name, it.path)) }
    }

    override fun info(path: String): FileEntry? {
        val (name, rest) = split(path)
        if (rest.isEmpty()) return FileEntry(name, name, true, 0, 0)
        return backend(name).info(rest)?.let { it.copy(path = join(name, it.path)) }
    }

    override fun exists(path: String): Boolean {
        if (path.trim('/').isEmpty()) return true
        val (name, rest) = split(path)
        return rest.isEmpty() || backend(name).exists(rest)
    }

    override fun mkdirs(path: String) {
        val (name, rest) = split(path)
        if (rest.isNotEmpty()) backend(name).mkdirs(rest)
    }

    override fun move(from: String, to: String) {
        val (fromName, fromRest) = split(from)
        val (toName, toRest) = split(to)
        if (fromRest.isEmpty() || toRest.isEmpty()) throw SecurityException("Cannot move a whole place")
        if (fromName == toName) {
            backend(fromName).move(fromRest, toRest)
        } else {
            crossMove(backend(fromName), fromRest, backend(toName), toRest)
        }
    }

    private fun crossMove(src: RootBackend, from: String, dst: RootBackend, to: String) {
        val entry = src.info(from) ?: throw IOException("Missing $from")
        if (dst.exists(to)) throw IOException("Already exists: $to")
        if (entry.isDir) {
            dst.mkdirs(to)
            src.list(from).forEach { child -> crossMove(src, child.path, dst, join(to, child.name)) }
            src.delete(from)
        } else {
            val parent = parentOf(to)
            if (parent.isNotEmpty()) dst.mkdirs(parent)
            src.openIn(from).use { input -> dst.openOut(to).use { output -> input.copyTo(output, 256 * 1024) } }
            val copied = dst.info(to)?.sizeBytes
            if (copied != entry.sizeBytes) {
                throw IOException("Copy check failed for $from ($copied of ${entry.sizeBytes} bytes). Original kept.")
            }
            src.delete(from)
        }
    }
}

/**
 * SMB helpers shared by the NAS place and the NAS sign-in screen.
 *
 * Android's built-in crypto has no MD4, which NTLM sign-in needs, so the default
 * smbj setup fails every login. BouncyCastle (bundled with smbj) has it.
 */
object Smb {
    private val config: SmbConfig = SmbConfig.builder()
        .withSecurityProvider(BCSecurityProvider())
        .withTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
        .withSoTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    fun client(): SMBClient = SMBClient(config)

    /**
     * Login details. A blank user signs in as guest.
     * "WORKGROUP\\name" and "name@domain" set the domain.
     */
    fun auth(user: String, password: String, domain: String? = null): AuthenticationContext {
        val u = user.trim()
        if (u.isEmpty()) return AuthenticationContext.guest()
        return when {
            '\\' in u -> AuthenticationContext(u.substringAfter('\\'), password.toCharArray(), u.substringBefore('\\'))
            '@' in u && domain == null -> AuthenticationContext(u.substringBefore('@'), password.toCharArray(), u.substringAfter('@'))
            else -> AuthenticationContext(u, password.toCharArray(), domain)
        }
    }
}

/** A folder on an SMB share (e.g. a UGREEN or Synology NAS). */
class SmbFileSystem(
    private val host: String,
    private val shareName: String,
    private val user: String,
    private val password: String,
    private val basePath: String = "",
    private val domain: String? = null,
) : RootBackend {

    override val root: String = "smb://$host/$shareName/${basePath.trim('/')}"

    private val client = Smb.client()
    private var share: DiskShare? = null

    @Synchronized
    private fun share(): DiskShare {
        // A NAS drops idle connections; only reuse one that is still really open.
        share?.takeIf { runCatching { it.isConnected && it.treeConnect.session.connection.isConnected }.getOrDefault(false) }
            ?.let { return it }
        share = null
        val connection = client.connect(host)
        val session = connection.authenticate(Smb.auth(user, password, domain))
        return (session.connectShare(shareName) as DiskShare).also { share = it }
    }

    /** Runs [block]; if the connection broke (not a "no" from the NAS), reconnects once and tries again. */
    private fun <T> retrying(block: () -> T): T = try {
        block()
    } catch (e: com.hierynomus.mssmb2.SMBApiException) {
        throw e
    } catch (e: Exception) {
        synchronized(this) { share = null }
        block()
    }

    /** Place-relative path to a share path ("a/b" -> "base\a\b"). Refuses "..". */
    private fun p(path: String): String {
        val clean = path.trim('/')
        if (clean.split('/').any { it == ".." }) throw SecurityException("Bad path: $path")
        return listOf(basePath.trim('/'), clean).filter { it.isNotEmpty() }.joinToString("\\") { it.replace('/', '\\') }
    }

    private fun isFile(sp: String) = runCatching { share().fileExists(sp) }.getOrDefault(false)
    private fun isFolder(sp: String) = runCatching { share().folderExists(sp) }.getOrDefault(false)

    override fun list(path: String): List<FileEntry> {
        val base = path.trim('/')
        return share().list(p(path))
            .filter { it.fileName != "." && it.fileName != ".." && !it.fileName.startsWith(".") }
            .map { f ->
                val isDir = (f.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L
                FileEntry(
                    path = join(base, f.fileName),
                    name = f.fileName,
                    isDir = isDir,
                    sizeBytes = if (isDir) 0 else f.endOfFile,
                    modifiedMillis = f.changeTime.toEpochMillis(),
                )
            }
    }

    override fun info(path: String): FileEntry? {
        val sp = p(path)
        if (!isFile(sp) && !isFolder(sp)) return null
        val fi = share().getFileInformation(sp)
        val isDir = fi.standardInformation.isDirectory
        return FileEntry(
            path = path.trim('/'),
            name = path.trim('/').substringAfterLast('/'),
            isDir = isDir,
            sizeBytes = if (isDir) 0 else fi.standardInformation.endOfFile,
            modifiedMillis = fi.basicInformation.changeTime.toEpochMillis(),
        )
    }

    override fun exists(path: String): Boolean {
        val sp = p(path)
        return sp.isEmpty() || isFile(sp) || isFolder(sp)
    }

    override fun mkdirs(path: String) = retrying { mkdirsOnce(path) }

    private fun mkdirsOnce(path: String) {
        var current = ""
        path.trim('/').split('/').filter { it.isNotEmpty() }.forEach { seg ->
            current = join(current, seg)
            val sp = p(current)
            if (!isFolder(sp)) share().mkdir(sp)
        }
    }

    override fun move(from: String, to: String) {
        if (exists(to)) throw IOException("Already exists: $to")
        val parent = parentOf(to)
        if (parent.isNotEmpty()) mkdirs(parent)
        val src = p(from)
        val options = if (isFolder(src)) EnumSet.of(SMB2CreateOptions.FILE_DIRECTORY_FILE)
        else EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE)
        share().open(
            src,
            EnumSet.of(AccessMask.DELETE, AccessMask.GENERIC_READ),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            options,
        ).use { it.rename(p(to)) }
    }

    fun openRandom(path: String): RandomReader {
        val file = retrying {
            share().openFile(
                p(path), EnumSet.of(AccessMask.GENERIC_READ), null,
                SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null,
            )
        }
        val total = file.fileInformation.standardInformation.endOfFile
        return object : RandomReader {
            override val size: Long = total
            override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int =
                file.read(buffer, position, offset, length)
            override fun close() = file.close()
        }
    }

    override fun openIn(path: String): InputStream = retrying {
        val file = share().openFile(
            p(path), EnumSet.of(AccessMask.GENERIC_READ), null,
            SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null,
        )
        object : FilterInputStream(file.inputStream) {
            override fun close() {
                try { super.close() } finally { file.close() }
            }
        }
    }

    override fun openOut(path: String): OutputStream = retrying {
        val file = share().openFile(
            p(path), EnumSet.of(AccessMask.GENERIC_WRITE), null,
            SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_CREATE, null,
        )
        object : FilterOutputStream(file.outputStream) {
            override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
            override fun close() {
                try { super.close() } finally { file.close() }
            }
        }
    }

    override fun delete(path: String) {
        val sp = p(path)
        if (sp.isEmpty() || path.trim('/').isEmpty()) throw SecurityException("Refusing to delete the place itself")
        if (isFolder(sp)) share().rmdir(sp, true) else share().rm(sp)
    }

    /** Quick connection check for the settings screen. */
    fun test(): String = runCatching {
        val n = list("").size
        "Connected. $n items found."
    }.getOrElse { "Failed: ${it.message ?: it::class.simpleName}" }
}
