package dev.fileagent.app

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** A newer build waiting on GitHub. */
data class UpdateInfo(val build: Int, val notes: String, val url: String, val bytes: Long)

/**
 * In-app updates from the project's GitHub releases. Each CI build is a release tagged
 * "build-<number>", and that number is also the app's version code.
 */
class Updater(private val context: Context) {

    val installedBuild: Int
        get() = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode.toInt() }.getOrDefault(0)

    val installedName: String
        get() = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"

    /** The newest release if it is newer than this app. Null when up to date. Throws when it can't check. */
    suspend fun check(): UpdateInfo? = withContext(Dispatchers.IO) {
        val conn = (URL(LATEST).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        try {
            if (conn.responseCode == 404) error("Updates are not public yet")
            if (conn.responseCode != 200) error("Can't check right now (${conn.responseCode})")
            val o = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val build = o.optString("tag_name").removePrefix("build-").toIntOrNull() ?: return@withContext null
            if (build <= installedBuild) return@withContext null
            val assets = o.optJSONArray("assets") ?: return@withContext null
            val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
                .firstOrNull { it.optString("name").endsWith(".apk") } ?: return@withContext null
            UpdateInfo(build, o.optString("body").trim().take(600), apk.getString("browser_download_url"), apk.optLong("size"))
        } finally {
            conn.disconnect()
        }
    }

    /** Downloads the APK. [onProgress] gets 0..100. */
    suspend fun download(info: UpdateInfo, onProgress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        // Only ever fetch from this project's GitHub releases.
        require(info.url.startsWith("https://github.com/$REPO/releases/download/")) { "Unexpected download address" }
        val dir = File(context.cacheDir, "update").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "FileAgent-${info.build}.apk")
        val conn = (URL(info.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
        }
        try {
            if (conn.responseCode != 200) error("Download failed (${conn.responseCode})")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: info.bytes
            conn.inputStream.use { input ->
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
                        val pct = if (total > 0) (done * 100 / total).toInt().coerceAtMost(100) else 0
                        if (pct != shown) { shown = pct; onProgress(pct) }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        out
    }

    /**
     * True when the downloaded file is this app, signed by the same key as the one installed.
     * Android checks this too at install time; checking first means a bad file is never offered.
     */
    @Suppress("DEPRECATION")
    private fun trusted(apk: File): Boolean = runCatching {
        val pm = context.packageManager
        val flag = android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
        val file = pm.getPackageArchiveInfo(apk.path, flag) ?: return false
        if (file.packageName != context.packageName) return false
        val theirs = file.signingInfo?.apkContentsSigners?.map { it.toByteArray().contentHashCode() }?.toSet()
            ?: return true // this phone can't read it from a file: Android's own check still applies
        val ours = pm.getPackageInfo(context.packageName, flag).signingInfo?.apkContentsSigners
            ?.map { it.toByteArray().contentHashCode() }?.toSet().orEmpty()
        theirs.isNotEmpty() && theirs == ours
    }.getOrDefault(false)

    /** Hands the APK to Android's installer. Android asks the user before installing. */
    fun install(apk: File) {
        if (!trusted(apk)) { apk.delete(); error("This update isn't signed by File Agent. Not installing.") }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    companion object {
        const val REPO = "offbr0wn/file-agent-kmp"
        private const val LATEST = "https://api.github.com/repos/$REPO/releases/latest"
    }
}
