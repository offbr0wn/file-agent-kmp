package dev.fileagent.app.ui

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfRenderer
import android.media.MediaPlayer
import android.os.ParcelFileDescriptor
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.fileagent.app.AgentViewModel
import dev.fileagent.app.Viewing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The built-in viewer: photos, video, audio, text and PDF open inside the app,
 * from the phone or a NAS. Anything else can be handed to another app.
 */
@Composable
fun ViewerScreen(vm: AgentViewModel, v: Viewing) {
    val c = LocalSui.current
    BackHandler { vm.closeViewer() }
    Column(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundIcon(Icons.Outlined.Close, "Close") { vm.closeViewer() }
            Text(v.name, style = SuiType.label, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
            v.file?.let { f -> RoundIcon(Icons.Outlined.OpenInNew, "Open in another app") { vm.openWith(f) } }
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val file = v.file
            when {
                v.error != null -> Text(v.error, style = SuiType.body, color = Color.White, modifier = Modifier.padding(SuiSpace.section))
                v.loading != null -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(SuiSpace.inner)) {
                    CircularProgressIndicator(color = c.brand)
                    Text(v.loading, style = SuiType.label, color = Color.White)
                }
                v.kind == "video" || v.kind == "audio" -> MediaView(vm, v)
                file == null -> Text("Can't open this file", style = SuiType.body, color = Color.White)
                v.kind == "image" -> PhotoView(file)
                v.kind == "text" -> TextView(file)
                v.kind == "pdf" -> PdfView(file)
                else -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(SuiSpace.pad)) {
                    Icon(iconForExtension(file.extension), null, tint = Color.White, modifier = Modifier.size(56.dp))
                    Text("No preview for this type", style = SuiType.body, color = Color.White)
                    PillButton("Open in another app", { vm.openWith(file) }, icon = Icons.Outlined.OpenInNew, primary = true)
                }
            }
        }
    }
}

@Composable
private fun RoundIcon(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(SuiSpace.tap).clip(CircleShape).clickable(onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(24.dp)) }
}

// ---- photo: pinch to zoom, drag to move ---------------------------------------------------

@Composable
private fun PhotoView(file: File) {
    val bmp by produceState<ImageBitmap?>(null, file) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                    val long = maxOf(info.size.width, info.size.height)
                    val scale = minOf(1f, 2560f / long)
                    if (scale < 1f) decoder.setTargetSize(
                        (info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1),
                    )
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }.asImageBitmap()
            }.getOrNull()
        }
    }
    var zoom by remember(file) { mutableStateOf(1f) }
    var offset by remember(file) { mutableStateOf(Offset.Zero) }
    val img = bmp
    if (img == null) CircularProgressIndicator(color = LocalSui.current.brand)
    else Image(
        img, contentDescription = file.name, contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxSize()
            .pointerInput(file) {
                detectTransformGestures { _, pan, gestureZoom, _ ->
                    zoom = (zoom * gestureZoom).coerceIn(1f, 6f)
                    offset = if (zoom == 1f) Offset.Zero else offset + pan
                }
            }
            .graphicsLayer(scaleX = zoom, scaleY = zoom, translationX = offset.x, translationY = offset.y),
    )
}

// ---- video and audio ------------------------------------------------------------------------

@Composable
private fun MediaView(vm: AgentViewModel, v: Viewing) {
    val c = LocalSui.current
    val player = remember(v.path) { MediaPlayer() }
    var ready by remember(v.path) { mutableStateOf(false) }
    var playing by remember(v.path) { mutableStateOf(false) }
    var position by remember(v.path) { mutableStateOf(0) }
    var duration by remember(v.path) { mutableStateOf(0) }
    var seeking by remember(v.path) { mutableStateOf<Float?>(null) }
    var aspect by remember(v.path) { mutableStateOf(16f / 9f) }
    var problem by remember(v.path) { mutableStateOf<String?>(null) }

    DisposableEffect(v.path) { onDispose { runCatching { player.release() } } }

    LaunchedEffect(v.path) {
        runCatching {
            val local = v.file
            if (local != null) player.setDataSource(local.path)
            else {
                // Plays straight from the NAS: only the parts being watched are read.
                val source = withContext(Dispatchers.IO) { vm.mediaSource(v.path) } ?: error("Can't play this file")
                player.setDataSource(source)
            }
            player.setOnVideoSizeChangedListener { _, w, h -> if (w > 0 && h > 0) aspect = w / h.toFloat() }
            player.setOnPreparedListener { duration = it.duration; ready = true; it.start(); playing = true }
            player.setOnCompletionListener { playing = false }
            player.setOnErrorListener { _, _, _ -> problem = "Can't play this file"; true }
            player.prepareAsync()
        }.onFailure { problem = it.message ?: "Can't play this file" }
    }
    LaunchedEffect(ready, playing) {
        while (ready && playing) {
            position = runCatching { player.currentPosition }.getOrDefault(position)
            delay(300)
        }
    }

    fun toggle() {
        if (!ready) return
        runCatching { if (playing) player.pause() else player.start() }
        playing = !playing
    }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth().clickable(onClickLabel = "Play or pause") { toggle() }, contentAlignment = Alignment.Center) {
            if (v.kind == "video") {
                AndroidView(
                    factory = { ctx ->
                        SurfaceView(ctx).apply {
                            holder.addCallback(object : SurfaceHolder.Callback {
                                override fun surfaceCreated(h: SurfaceHolder) { runCatching { player.setDisplay(h) } }
                                override fun surfaceChanged(h: SurfaceHolder, format: Int, width: Int, height: Int) {}
                                override fun surfaceDestroyed(h: SurfaceHolder) { runCatching { player.setDisplay(null) } }
                            })
                        }
                    },
                    modifier = Modifier.aspectRatio(aspect, matchHeightConstraintsFirst = aspect < 1f),
                )
            } else {
                Icon(Icons.Outlined.MusicNote, null, tint = c.brand, modifier = Modifier.size(96.dp))
            }
            when {
                problem != null -> Text(problem.orEmpty(), style = SuiType.body, color = Color.White,
                    modifier = Modifier.background(Color.Black.copy(alpha = 0.6f)).padding(SuiSpace.pad))
                !ready -> CircularProgressIndicator(color = c.brand)
                !playing -> Box(
                    Modifier.size(72.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Outlined.PlayArrow, "Play", tint = Color.White, modifier = Modifier.size(40.dp)) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = SuiSpace.pad, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundIcon(if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, if (playing) "Pause" else "Play") { toggle() }
            Text(clock(seeking?.let { (it * duration).toInt() } ?: position), style = SuiType.bodySm, color = Color.White)
            Slider(
                value = seeking ?: if (duration > 0) position / duration.toFloat() else 0f,
                onValueChange = { seeking = it },
                onValueChangeFinished = {
                    seeking?.let { f ->
                        val to = (f * duration).toInt()
                        runCatching { player.seekTo(to) }
                        position = to
                    }
                    seeking = null
                },
                enabled = ready && duration > 0,
                colors = SliderDefaults.colors(thumbColor = c.brand, activeTrackColor = c.brand, inactiveTrackColor = Color.White.copy(alpha = 0.25f)),
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            Text(clock(duration), style = SuiType.bodySm, color = Color.White)
            Spacer(Modifier.width(8.dp))
        }
    }
}

private fun clock(ms: Int): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

// ---- text -------------------------------------------------------------------------------------

@Composable
private fun TextView(file: File) {
    val text by produceState<String?>(null, file) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                file.inputStream().use { input ->
                    val buf = ByteArray(200_000)
                    val n = input.read(buf).coerceAtLeast(0)
                    String(buf, 0, n, Charsets.UTF_8) + if (file.length() > n) "\n\n… (first part only)" else ""
                }
            }.getOrDefault("Can't read this file")
        }
    }
    val t = text
    if (t == null) CircularProgressIndicator(color = LocalSui.current.brand)
    else Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(SuiSpace.pad)) {
        Text(t, style = SuiType.mono, color = Color.White)
    }
}

// ---- pdf --------------------------------------------------------------------------------------

@Composable
private fun PdfView(file: File) {
    val renderer = remember(file) {
        runCatching { PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)) }.getOrNull()
    }
    val lock = remember(file) { Mutex() } // PdfRenderer shows one page at a time
    DisposableEffect(file) { onDispose { runCatching { renderer?.close() } } }
    if (renderer == null) {
        Text("Can't open this PDF", style = SuiType.body, color = Color.White)
        return
    }
    val pages = renderer.pageCount
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(pages) { index ->
            val page by produceState<ImageBitmap?>(null, file, index) {
                value = withContext(Dispatchers.IO) {
                    lock.withLock {
                        runCatching {
                            renderer.openPage(index).use { p ->
                                val w = 1080
                                val h = (w.toFloat() * p.height / p.width).toInt().coerceAtLeast(1)
                                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                                bmp.eraseColor(android.graphics.Color.WHITE)
                                p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                bmp.asImageBitmap()
                            }
                        }.getOrNull()
                    }
                }
            }
            val img = page
            if (img != null) Image(img, contentDescription = "Page ${index + 1}", contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxWidth())
            else Box(Modifier.fillMaxWidth().height(420.dp).background(Color.White.copy(alpha = 0.08f)))
        }
    }
}
