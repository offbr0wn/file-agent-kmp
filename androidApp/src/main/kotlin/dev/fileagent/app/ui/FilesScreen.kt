package dev.fileagent.app.ui

import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.fileagent.app.AgentViewModel
import dev.fileagent.app.UiState
import dev.fileagent.fs.FileEntry

/**
 * A plain file manager over the saved places (phone folders and NAS shares).
 * Tap to open, tick to pick, then move, rename or bin. Every change can be undone.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FilesScreen(vm: AgentViewModel, s: UiState) {
    val c = LocalSui.current
    val atRoot = s.filesPath.isEmpty()
    val picking = s.filesPicked.isNotEmpty()
    val busy = s.filesBusy != null
    var rename by remember { mutableStateOf<String?>(null) }
    var newFolder by remember { mutableStateOf(false) }

    fun back() {
        when {
            picking -> vm.clearFilePicks()
            !vm.filesUp() -> vm.showFiles(false)
        }
    }
    BackHandler { back() }

    Column(Modifier.fillMaxSize().background(c.page).safeDrawingPadding()) {
        // ---- top bar
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            BarIcon(if (picking) Icons.Outlined.Close else Icons.Outlined.ArrowBack, if (picking) "Clear picks" else "Back") { back() }
            Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                Text(
                    when {
                        picking -> "${s.filesPicked.size} picked"
                        atRoot -> "Files"
                        else -> s.filesPath.substringAfterLast('/')
                    },
                    style = SuiType.title, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (!atRoot && !picking && s.filesPath.contains('/')) {
                    Text(s.filesPath.substringBeforeLast('/').replace("/", " › "), style = SuiType.bodySm, color = c.textSoft,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (picking) {
                Text("All", style = SuiType.labelSm, color = c.textSub,
                    modifier = Modifier.clip(SuiShape.pill).clickable { vm.pickAllFiles() }.padding(horizontal = 12.dp, vertical = 8.dp))
            } else if (!atRoot) {
                BarIcon(Icons.Outlined.CreateNewFolder, "New folder", enabled = !busy) { newFolder = true }
            }
        }

        if (busy) {
            Column(Modifier.padding(horizontal = SuiSpace.page, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(s.filesBusy.orEmpty(), style = SuiType.labelSm, color = c.textSub)
                LinearProgressIndicator(Modifier.fillMaxWidth().height(4.dp).clip(SuiShape.pill), color = c.brand, trackColor = c.tile)
            }
        }
        s.filesError?.let {
            Text(it, style = SuiType.bodySm, color = c.errorDark,
                modifier = Modifier.padding(horizontal = SuiSpace.page, vertical = 8.dp).fillMaxWidth()
                    .clip(SuiShape.row).background(c.errorLighter).padding(SuiSpace.inner))
        }
        s.filesNote?.let { note ->
            Row(
                Modifier.padding(horizontal = SuiSpace.page, vertical = 8.dp).fillMaxWidth().clip(SuiShape.row).background(c.tile)
                    .padding(start = SuiSpace.pad, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(note, style = SuiType.labelSm, color = c.text, modifier = Modifier.weight(1f))
                PillButton("Undo", vm::filesUndo, icon = Icons.Outlined.Undo, small = true, enabled = !busy)
            }
        }

        // ---- list
        val entries = s.filesEntries
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                entries == null -> LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp), color = c.textSoft, trackColor = c.tile)
                entries.isEmpty() -> Text(
                    if (atRoot) "No folders yet. Add one in a chat." else "Empty folder",
                    style = SuiType.body, color = c.textSoft, modifier = Modifier.align(Alignment.Center),
                )
                else -> LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                    items(entries, key = { it.path }) { e ->
                        val on = e.path in s.filesPicked
                        fun open() {
                            when {
                                picking && !atRoot -> vm.toggleFilePick(e.path)
                                e.isDir -> vm.browse(e.path)
                                else -> vm.viewFile(e.path)
                            }
                        }
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 64.dp)
                                .combinedClickable(onClick = ::open, onLongClick = { if (!atRoot) vm.toggleFilePick(e.path) })
                                .padding(horizontal = SuiSpace.page, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            when {
                                atRoot -> IconTile(
                                    if (s.places.firstOrNull { it.name == e.name }?.type == "smb") Icons.Outlined.Dns else Icons.Outlined.PhoneAndroid,
                                    size = 44.dp,
                                )
                                e.isDir -> IconTile(Icons.Outlined.Folder, c.brandDark, c.brandLighter, size = 44.dp)
                                else -> FileTile(e.name, vm.localFileFor(e.path), size = 44.dp)
                            }
                            Spacer(Modifier.width(SuiSpace.inner))
                            Column(Modifier.weight(1f)) {
                                Text(e.name, style = SuiType.label, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(metaFor(e, atRoot, s), style = SuiType.bodySm, color = c.textSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (!atRoot) {
                                Spacer(Modifier.width(SuiSpace.inner))
                                Box(
                                    Modifier.size(SuiSpace.tap).clip(CircleShape).clickable(onClickLabel = if (on) "Unpick" else "Pick") { vm.toggleFilePick(e.path) },
                                    contentAlignment = Alignment.Center,
                                ) { RoundTick(on) }
                            }
                        }
                    }
                }
            }
        }

        // ---- bottom bar
        if (s.filesClip.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(SuiSpace.pad), horizontalArrangement = Arrangement.spacedBy(SuiSpace.inner)) {
                PillButton("Cancel", vm::cancelClip)
                PillButton(
                    if (atRoot) "Open a folder" else "Move ${s.filesClip.size} here",
                    vm::pasteHere, Modifier.weight(1f), icon = Icons.Outlined.DriveFileMove, primary = true, enabled = !atRoot && !busy,
                )
            }
        } else if (picking) {
            Row(Modifier.fillMaxWidth().padding(SuiSpace.pad), horizontalArrangement = Arrangement.spacedBy(SuiSpace.inner)) {
                PillButton("Move", vm::cutPicked, Modifier.weight(1f), icon = Icons.Outlined.DriveFileMove, enabled = !busy)
                if (s.filesPicked.size == 1) {
                    PillButton("Rename", { rename = s.filesPicked.first() }, Modifier.weight(1f), icon = Icons.Outlined.Edit, enabled = !busy)
                }
                PillButton("Bin", vm::binPickedFiles, Modifier.weight(1f), icon = Icons.Outlined.DeleteOutline, danger = true, enabled = !busy)
            }
        }
    }

    rename?.let { path ->
        NameDialog("Rename", path.substringAfterLast('/'), "Rename", onClose = { rename = null }) { vm.renameFile(path, it) }
    }
    if (newFolder) NameDialog("New folder", "", "Make", onClose = { newFolder = false }) { vm.newFolder(it) }
}

private fun metaFor(e: FileEntry, atRoot: Boolean, s: UiState): String = when {
    atRoot -> s.places.firstOrNull { it.name == e.name }.let { p ->
        when {
            p == null || p.type != "smb" -> "Phone"
            p.host in s.nasOffline -> "NAS · offline"
            else -> "NAS · ${p.server.ifBlank { p.host }}"
        }
    }
    e.isDir -> "Folder"
    else -> {
        val date = if (e.modifiedMillis > 0) " · " + DateUtils.getRelativeTimeSpanString(
            e.modifiedMillis, System.currentTimeMillis(), DateUtils.DAY_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE,
        ) else ""
        fileSize(e.sizeBytes) + date
    }
}

private fun fileSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.0f MB".format(bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

@Composable
private fun BarIcon(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val c = LocalSui.current
    Box(
        Modifier.size(SuiSpace.tap).clip(CircleShape).clickable(enabled = enabled, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = label, tint = if (enabled) c.textSub else c.textSoft, modifier = Modifier.size(22.dp)) }
}

/** Asks for a name. [onSave] returns a short problem to show, or null when fine. */
@Composable
private fun NameDialog(title: String, start: String, action: String, onClose: () -> Unit, onSave: (String) -> String?) {
    val c = LocalSui.current
    var name by remember { mutableStateOf(start) }
    var problem by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = c.card,
        title = { Text(title, style = SuiType.title, color = c.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it; problem = null }, singleLine = true,
                    shape = SuiShape.control, modifier = Modifier.fillMaxWidth())
                problem?.let { Text(it, style = SuiType.bodySm, color = c.errorDark) }
            }
        },
        confirmButton = {
            PillButton(action, { problem = onSave(name); if (problem == null) onClose() }, primary = true, small = true, enabled = name.isNotBlank())
        },
        dismissButton = { PillButton("Cancel", onClose, small = true) },
    )
}
