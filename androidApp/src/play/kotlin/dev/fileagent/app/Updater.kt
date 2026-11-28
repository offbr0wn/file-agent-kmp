package dev.fileagent.app

import android.content.Context

/** Never used in the Play version: Google Play updates the app. */
data class UpdateInfo(val build: Int, val notes: String, val url: String, val bytes: Long)

/**
 * Play version: only reports the installed version. Google Play handles updates, so there is
 * no download or install code here at all.
 */
class Updater(private val context: Context) {
    val installedName: String
        get() = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"

    @Suppress("RedundantSuspendModifier")
    suspend fun check(): UpdateInfo? = null

    @Suppress("RedundantSuspendModifier", "UNUSED_PARAMETER")
    suspend fun download(info: UpdateInfo, onProgress: (Int) -> Unit): java.io.File =
        error("Updates come from Google Play")

    @Suppress("UNUSED_PARAMETER")
    fun install(apk: java.io.File): Unit = error("Updates come from Google Play")
}
