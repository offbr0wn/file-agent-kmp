package dev.fileagent.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.media.ThumbnailUtils
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material.icons.outlined.VideoFile
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Small in-memory cache so a list does not re-read the same file on every scroll. */
private val thumbCache = ConcurrentHashMap<String, Thumb>()

/** A cached answer. [image] is null when the file has no picture (this map can't hold plain nulls). */
private class Thumb(val image: ImageBitmap?)

/** Only a few thumbnails are made at once, so big videos can't use up the memory. */
private val thumbGate = kotlinx.coroutines.sync.Semaphore(3)

private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp", "gif", "heic", "bmp", "arw", "dng")
private val VIDEO_EXT = setOf("mp4", "mkv", "mov", "webm", "avi", "3gp", "m4v")
private val AUDIO_EXT = setOf("mp3", "m4a", "flac", "wav", "ogg", "aac", "opus")
private val ARCHIVE_EXT = setOf("zip", "rar", "7z", "tar", "gz")
private val DOC_EXT = setOf("doc", "docx", "txt", "md", "rtf", "odt", "pptx", "ppt")
private val SHEET_EXT = setOf("xls", "xlsx", "csv", "ods")

/** Vector icon for a file type (used when there is no real picture to show). */
fun iconForExtension(ext: String): ImageVector = when (ext.lowercase()) {
    "apk" -> Icons.Outlined.Android
    "pdf" -> Icons.Outlined.PictureAsPdf
    in IMAGE_EXT -> Icons.Outlined.Image
    in VIDEO_EXT -> Icons.Outlined.VideoFile
    in AUDIO_EXT -> Icons.Outlined.AudioFile
    in ARCHIVE_EXT -> Icons.Outlined.Archive
    in DOC_EXT -> Icons.Outlined.Description
    in SHEET_EXT -> Icons.Outlined.TableChart
    "" -> Icons.Outlined.Folder
    else -> Icons.Outlined.InsertDriveFile
}

/**
 * The real picture for a file on the phone: the app logo inside an APK,
 * or a thumbnail of a photo or video. Null when there is none (NAS files, documents).
 */
suspend fun loadFileThumb(context: Context, file: File, px: Int): ImageBitmap? = withContext(Dispatchers.IO) {
    val key = "${file.path}:${file.lastModified()}:$px"
    thumbCache[key]?.let { return@withContext it.image }
    val ext = file.extension.lowercase()
    if (ext != "apk" && ext !in IMAGE_EXT && ext !in VIDEO_EXT) return@withContext null
    thumbGate.acquire()
    val img = try {
        when (ext) {
            "apk" -> {
                val pm = context.packageManager
                val info = pm.getPackageArchiveInfo(file.path, 0)
                info?.applicationInfo?.apply { sourceDir = file.path; publicSourceDir = file.path }
                info?.applicationInfo?.loadIcon(pm)?.let { toBitmap(it, px) }
            }
            in IMAGE_EXT -> ThumbnailUtils.createImageThumbnail(file, Size(px, px), null)
            else -> ThumbnailUtils.createVideoThumbnail(file, Size(px, px), null)
        }?.asImageBitmap()
    } catch (t: Throwable) {
        null // unreadable, half-downloaded or too big: fall back to the type icon
    } finally {
        thumbGate.release()
    }
    if (thumbCache.size > 400) thumbCache.clear()
    thumbCache[key] = Thumb(img)
    img
}

private fun toBitmap(d: Drawable, px: Int): Bitmap {
    if (d is BitmapDrawable && d.bitmap != null) return Bitmap.createScaledBitmap(d.bitmap, px, px, true)
    val b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
    val c = Canvas(b)
    d.setBounds(0, 0, px, px)
    d.draw(c)
    return b
}

/**
 * A 40dp tile showing the file's real logo or thumbnail when the phone has one,
 * otherwise a type icon on a soft tile. [localPath] is null for NAS files.
 */
@Composable
fun FileTile(name: String, localPath: File?, size: Dp = 40.dp) {
    val c = LocalSui.current
    val ctx = LocalContext.current
    val ext = name.substringAfterLast('.', "")
    val px = with(androidx.compose.ui.platform.LocalDensity.current) { size.roundToPx() }
    val thumb by produceState<ImageBitmap?>(null, localPath?.path) {
        value = localPath?.let { loadFileThumb(ctx, it, px) }
    }
    Box(
        Modifier.size(size).clip(RoundedCornerShape(12.dp)).background(c.tile),
        contentAlignment = Alignment.Center,
    ) {
        val t = thumb
        if (t != null) {
            Image(t, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Icon(iconForExtension(ext), contentDescription = null, tint = c.textSub, modifier = Modifier.size(size * 0.5f))
        }
    }
}
